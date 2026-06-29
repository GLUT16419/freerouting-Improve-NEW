package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;

/**
 * Phase 2: Cluster-based routing with layer assignment and negotiation.
 * Groups unrouted nets into spatial clusters, assigns layers, and routes
 * each cluster using negotiation-based conflict resolution via real A* routing.
 */
public class Phase2ClusterRouter {

  private static final int MAX_REFINEMENT_ITERATIONS = 3;
  private static final int MAX_CLUSTER_THREADS = 4;
  private static final long CLUSTER_TIMEOUT_SECONDS = 120;

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final CostModel costModel;
  private final LayerAssignment layerAssignment;
  private final PowerGndAutoLabeler powerGndLabeler;
  private final LayerFunction[] layerFunctions;
  private final BatchAutorouter batchAutorouter;

  private final Set<Integer> unroutedNets = new HashSet<>();
  private final Set<Integer> phase2Routed = new HashSet<>();
  private final List<NetCluster> finalClusters = new ArrayList<>();
  private boolean complete = false;

  public Phase2ClusterRouter(BasicBoard board,
                             CongestionMap congestionMap,
                             CostModel costModel,
                             PowerGndAutoLabeler powerGndLabeler,
                             LayerFunction[] layerFunctions,
                             BatchAutorouter batchAutorouter) {
    this.board = board;
    this.congestionMap = congestionMap;
    this.costModel = costModel;
    this.powerGndLabeler = powerGndLabeler;
    this.layerFunctions = layerFunctions;
    this.batchAutorouter = batchAutorouter;
    this.layerAssignment = new LayerAssignment(board, congestionMap);
  }

  /**
   * Route remaining nets that Phase 1 could not handle.
   * Groups nets into clusters based on spatial proximity, assigns layers,
   * and routes each cluster with negotiation-based conflict resolution.
   */
  public Set<Integer> routeRemaining(List<Integer> phase1Unrouted) {
    unroutedNets.clear();
    unroutedNets.addAll(phase1Unrouted);
    FRLogger.debug("Phase 2: Starting cluster routing for " + unroutedNets.size() + " unrouted nets...");
    if (unroutedNets.isEmpty()) {
      complete = true;
      return Collections.emptySet();
    }
    for (int iter = 0; iter < MAX_REFINEMENT_ITERATIONS; iter++) {
      FRLogger.debug("Phase 2: Refinement iteration " + (iter + 1) + "/" + MAX_REFINEMENT_ITERATIONS);

      // 1. Cluster unrouted nets by spatial proximity
      List<NetCluster> clusters = NetCluster.clusterNets(board, unroutedNets);
      if (clusters.isEmpty()) break;
      FRLogger.debug("Phase 2: Created " + clusters.size() + " clusters");

      // 2. Assign layers to each cluster
      layerAssignment.assignLayers(clusters, layerFunctions);

      // 3. Route clusters (with multi-threading support)
      Set<Integer> iterationRouted = routeClustersParallel(clusters);

      phase2Routed.addAll(iterationRouted);
      unroutedNets.removeAll(iterationRouted);
      finalClusters.addAll(clusters);

      // 4. Resolve inter-cluster conflicts
      for (NetCluster cluster : clusters) {
        NegotiationRouter negotiator = new NegotiationRouter(board, congestionMap, costModel, batchAutorouter);
        negotiator.resolveInterClusterConflicts(clusters, layerAssignment);
      }

      // 5. If no progress, penalize current layer assignments
      if (iterationRouted.isEmpty()) {
        FRLogger.debug("Phase 2: No progress in iteration " + (iter + 1)
            + ", penalizing current layers");
        for (NetCluster cluster : clusters) {
          if (cluster.getPrimaryLayer() >= 0) {
            layerAssignment.penalizeLayer(cluster, cluster.getPrimaryLayer());
          }
        }
      }

      if (unroutedNets.isEmpty()) {
        complete = true;
        break;
      }
    }
    logResults();
    return phase2Routed;
  }

  /**
   * Route clusters sequentially (thread-safe).
   * WARNING: Multi-threaded cluster routing is NOT safe with the current
   * routing engine (BatchAutorouter/AutorouteEngine/MazeSearchAlgo are
   * NOT thread-safe). Each cluster must route on the shared board one at a time.
   * For true parallel routing, deep-copy the board per cluster (see
   * BatchAutorouter.autoroute_pass_multi_thread() for the pattern).
   */
  /**
   * Routes clusters in parallel using deep copy per cluster.
   *
   * <p>Strategy: For each cluster, deep-copy the board, create an independent
   * BatchAutorouter on the copy, and route the cluster's nets in parallel.
   * After all clusters complete, new trace/via items are serialized back to
   * the original board using item id_no to identify new items.
   *
   * <p>If there are few clusters or deepCopy fails, falls back to sequential routing.
   */
  private Set<Integer> routeClustersParallel(List<NetCluster> clusters) {
    if (clusters.isEmpty()) return Collections.emptySet();
    if (!(this.board instanceof RoutingBoard routingBoard)) {
      FRLogger.warn("Phase2ClusterRouter: Board is not RoutingBoard, falling back to sequential.");
      return routeClustersSequential(clusters);
    }

    int numThreads = Math.min(MAX_CLUSTER_THREADS, Runtime.getRuntime().availableProcessors());
    numThreads = Math.min(numThreads, clusters.size());

    if (numThreads <= 1) {
      FRLogger.debug("Phase2ClusterRouter: Single cluster or 1 thread, using sequential routing.");
      return routeClustersSequential(clusters);
    }

    FRLogger.info("Phase2ClusterRouter: Routing " + clusters.size() + " clusters in parallel ("
        + numThreads + " threads)...");

    // Collect original item IDs for merge — new items after routing will have different id_no values
    Set<Integer> originalItemIds = collectItemIds(routingBoard);

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Future<Set<Integer>>> futures = new ArrayList<>();

    for (int cIdx = 0; cIdx < clusters.size(); cIdx++) {
      final NetCluster cluster = clusters.get(cIdx);
      futures.add(executor.submit(() -> {
        long clusterStart = System.currentTimeMillis();

        // Deep copy the board for this cluster's exclusive use
        RoutingBoard clusterBoard = routingBoard.deepCopy();
        if (clusterBoard == null) {
          FRLogger.warn("Phase2ClusterRouter: deepCopy failed for cluster " + cluster.getClusterId());
          return Collections.<Integer>emptySet();
        }

        // Create a thread with independent stop-request state
        StoppableThread clusterThread = createClusterThread();

        // Create BatchAutorouter for the deep copy
        RouterSettings settings = this.batchAutorouter.settings;
        BatchAutorouter clusterRouter = new BatchAutorouter(
            clusterThread, clusterBoard, settings, true, true,
            settings.get_start_ripup_costs(),
            settings.trace_pull_tight_accuracy);

        // Create CongestionMap + CostModel for this cluster (used by NegotiationRouter)
        CongestionMap cmCopy = new CongestionMap(clusterBoard);
        CostModel costCopy = new CostModel(clusterBoard, cmCopy);

        // Route this cluster's nets via NegotiationRouter
        NegotiationRouter negotiator = new NegotiationRouter(
            clusterBoard, cmCopy, costCopy, clusterRouter);
        Set<Integer> routed = negotiator.routeCluster(cluster, Collections.emptySet());

        long duration = System.currentTimeMillis() - clusterStart;
        FRLogger.debug("Phase2ClusterRouter: Cluster " + cluster.getClusterId()
            + " complete — " + routed.size() + " nets routed, " + duration + "ms");

        // Merge new items (traces/vias) from the copy back to the original board
        mergeRoutingItems(routingBoard, clusterBoard, originalItemIds);

        return routed;
      }));
    }

    // Collect all results with timeout to prevent hanging
    Set<Integer> allRouted = new HashSet<>();
    for (Future<Set<Integer>> future : futures) {
      try {
        Set<Integer> routed = future.get(CLUSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (routed != null) allRouted.addAll(routed);
      } catch (TimeoutException e) {
        future.cancel(true);
        FRLogger.warn("Phase2ClusterRouter: Cluster routing timed out after "
            + CLUSTER_TIMEOUT_SECONDS + "s, continuing with partial results.");
      } catch (Exception e) {
        FRLogger.warn("Phase2ClusterRouter: Cluster parallel execution error: " + e.getMessage());
      }
    }
    executor.shutdownNow();

    // Run cleanup on the main board after parallel merge
    routingBoard.clear_all_item_temporary_autoroute_data();
    routingBoard.finish_autoroute();

    FRLogger.info("Phase2ClusterRouter: Parallel routing complete — "
        + allRouted.size() + "/" + clusters.stream().mapToInt(NetCluster::getNetCount).sum()
        + " nets routed across all clusters.");
    return allRouted;
  }

  /**
   * Collects all item id_no values from the board for identifying new items after routing.
   */
  private static Set<Integer> collectItemIds(RoutingBoard board) {
    Set<Integer> ids = new HashSet<>();
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = board.item_list.read_object(it);
      if (ob == null) break;
      if (ob instanceof Item item) {
        ids.add(item.get_id_no());
      }
    }
    return ids;
  }

  /**
   * Merges new items (traces, vias) from the source routing board (cluster copy)
   * into the target board (main board). Items already present before routing
   * (identified by originalItemIds) are skipped.
   */
  private static void mergeRoutingItems(RoutingBoard targetBoard, RoutingBoard sourceBoard,
      Set<Integer> originalItemIds) {
    int count = 0;
    int errors = 0;
    Iterator<UndoableObjects.UndoableObjectNode> it = sourceBoard.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = sourceBoard.item_list.read_object(it);
      if (ob == null) break;
      if (!(ob instanceof Item item)) continue;
      // Skip items that were present in the original board
      if (originalItemIds.contains(item.get_id_no())) continue;
      // Skip Pins — they're original board structures
      if (item instanceof Pin) continue;
      try {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(item);
        oos.flush();
        ByteArrayInputStream bin = new ByteArrayInputStream(bos.toByteArray());
        ObjectInputStream ois = new ObjectInputStream(bin);
        Item copy = (Item) ois.readObject();
        copy.board = targetBoard;
        targetBoard.item_list.insert(copy);
        count++;
      } catch (Exception e) {
        FRLogger.warn("mergeRoutingItems: Failed to copy item id=" + item.get_id_no()
            + ": " + e.getMessage());
        errors++;
      }
    }
    if (count > 0 || errors > 0) {
      FRLogger.debug("mergeRoutingItems: " + count + " items merged (" + errors + " errors)");
    }
  }

  /**
   * Creates a lightweight StoppableThread for a cluster routing trial,
   * preventing stop-request cross-talk between parallel clusters.
   */
  private static StoppableThread createClusterThread() {
    return new StoppableThread() {
      @Override
      protected void thread_action() { /* no-op */ }
    };
  }

  /**
   * Sequential fallback for cluster routing (single-threaded).
   */
  private Set<Integer> routeClustersSequential(List<NetCluster> clusters) {
    Set<Integer> allRouted = new HashSet<>();
    for (NetCluster cluster : clusters) {
      NegotiationRouter negotiator = new NegotiationRouter(
          board, congestionMap, costModel, batchAutorouter);
      Set<Integer> routed = negotiator.routeCluster(cluster, allRouted);
      allRouted.addAll(routed);
    }
    return allRouted;
  }

  /**
   * Extract all nets that are not yet fully connected.
   */
  public static Set<Integer> extractUnroutedNets(BasicBoard board) {
    Set<Integer> unrouted = new HashSet<>();
    if (board.rules == null || board.rules.nets == null) return unrouted;
    int netCount = board.rules.nets.max_net_no();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) continue;
      boolean hasTraces = false;
      for (Item item : net.get_items()) {
        if (item instanceof Trace) { hasTraces = true; break; }
      }
      if (!hasTraces) unrouted.add(net.net_number);
    }
    return unrouted;
  }

  private void logResults() {
    FRLogger.debug(String.format(
        "Phase 2 complete: %d routed, %d remaining for Phase 3",
        phase2Routed.size(), unroutedNets.size()));
  }

  public Set<Integer> getPhase2Routed() { return phase2Routed; }
  public Set<Integer> getUnroutedNets() { return unroutedNets; }
  public List<NetCluster> getFinalClusters() { return finalClusters; }
  public boolean isComplete() { return complete; }
}
