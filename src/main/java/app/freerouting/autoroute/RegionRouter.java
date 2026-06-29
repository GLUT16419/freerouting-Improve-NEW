package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.board.Via;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Phase 2-4: Region-based parallel routing engine for the V2 adaptive pipeline.
 * <p>
 * Each method operates on a list of {@link Region} objects produced by {@link RegionDivider},
 * deep-copies the board per region (or per cluster within a region), routes in parallel,
 * then merges results back to the main board.
 */
public final class RegionRouter {

  private static final int REGION_TIMEOUT_SECONDS = 120;
  private static final int SHORT_NET_THRESHOLD = 5; // max airline length (units) for "short" nets
  private static final double STUB_EXPANSION_FACTOR = 0.10; // 10% for stub boundary checking

  private RegionRouter() {
    // utility class
  }

  // ===================== Phase 2: Region-based parallel signal fanout =====================

  /**
   * Phase 2: fans out signal-net SMD pins in parallel per region.
   * Each region's components are assigned by their SMD pin gravity center falling
   * within the region's core bbox, then fanned out independently on a deep copy.
   */
  public static void regionFanout(
      RoutingBoard board,
      List<Region> regions,
      RouterSettings settings,
      StoppableThread thread,
      Set<Integer> powerGroundNetNumbers) {

    Collection<Pin> smdPins = board.get_smd_pins();
    if (smdPins == null || smdPins.isEmpty()) {
      FRLogger.info("RegionRouter.regionFanout: No SMD pins, skipping.");
      return;
    }

    List<Integer> activeIndices = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      if (regions.get(i).isActive) activeIndices.add(i);
    }
    if (activeIndices.size() <= 1) {
      FRLogger.info("RegionRouter.regionFanout: Only 1 active region, falling back to normal fanout.");
      BatchFanout.parallelFanoutBoard(board, settings, thread, null, powerGroundNetNumbers);
      return;
    }

    FRLogger.info("RegionRouter.regionFanout: " + activeIndices.size()
        + " active regions, " + smdPins.size() + " SMD pins...");

    // Assign component numbers to regions
    List<Set<Integer>> componentFilters = assignComponentsToRegions(board, regions);

    // Collect original item IDs before fanout
    Set<Integer> originalItemIds = collectItemIds(board);

    // Parallel fanout per region
    ExecutorService executor = Executors.newFixedThreadPool(activeIndices.size());
    List<Future<RegionFanoutResult>> futures = new ArrayList<>();

    for (int i : activeIndices) {
      final int rIdx = i;
      final Set<Integer> compFilter = componentFilters.get(i);
      if (compFilter == null || compFilter.isEmpty()) continue;

      futures.add(executor.submit(() -> {
        RoutingRegionContext ctx = new RoutingRegionContext();
        ctx.board = board.deepCopy();
        if (ctx.board == null) return null;
        ctx.regionIndex = rIdx;

        StoppableThread rt = createTrialThread();
        BatchFanout regionFanout = new BatchFanout(ctx.board, settings, rt,
            powerGroundNetNumbers, compFilter);
        long start = System.currentTimeMillis();
        BatchFanout.FanoutRunSummary summary = regionFanout.fanoutBoardRegion(null);
        ctx.duration = System.currentTimeMillis() - start;

        FRLogger.info("RegionRouter.fanout: Region " + rIdx + " — "
            + summary.escapeStatistics().escapedCount() + "/"
            + summary.escapeStatistics().totalSmdPins() + " escaped, "
            + summary.completedPassCount() + " passes, " + ctx.duration + "ms");
        return new RegionFanoutResult(ctx.board, summary, compFilter, rIdx);
      }));
    }

    // Collect results with timeout
    List<RegionFanoutResult> results = collectFutures(futures, executor);

    if (results.isEmpty()) {
      FRLogger.warn("RegionRouter.fanout: All regions failed, falling back to single-thread fanout.");
      BatchFanout.parallelFanoutBoard(board, settings, thread, null, powerGroundNetNumbers);
      return;
    }

    // Merge fanout items back to main board
    int mergedCount = 0;
    int mergeErrors = 0;
    for (RegionFanoutResult result : results) {
      try {
        mergedCount += mergeNewItems(board, result.board, originalItemIds);
      } catch (Exception e) {
        mergeErrors++;
        FRLogger.warn("RegionRouter.fanout: Merge error for region " + result.regionIndex + ": " + e.getMessage());
      }
    }

    // Finalize merged board
    board.clear_all_item_temporary_autoroute_data();
    board.finish_autoroute();

    FRLogger.info("RegionRouter.fanout: Complete — " + results.size() + " regions, "
        + mergedCount + " items merged (" + mergeErrors + " errors)");
  }

  // ===================== Phase 3: Region-based parallel short net routing =====================

  /**
   * Phase 3: routes short nets in parallel per region.
   * Short nets are those whose airline length is below SHORT_NET_THRESHOLD.
   * Each region deep-copies the board, restricts to its assigned nets, and routes.
   *
   * @return set of net numbers that were successfully routed (had zero incompletes after routing)
   */
  public static Set<Integer> regionShortRoute(
      RoutingBoard board,
      List<Region> regions,
      RouterSettings settings,
      StoppableThread thread,
      double shortNetThreshold,
      Set<Integer> excludedNetNumbers) {

    // Collect incomplete nets per region
    List<Set<Integer>> regionNets = assignNetsToRegions(board, regions, excludedNetNumbers);
    List<Integer> activeIndices = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      if (regions.get(i).isActive && !regionNets.get(i).isEmpty()) activeIndices.add(i);
    }

    if (activeIndices.isEmpty()) {
      FRLogger.info("RegionRouter.shortRoute: No incomplete nets to route.");
      return Collections.emptySet();
    }

    FRLogger.info("RegionRouter.shortRoute: " + activeIndices.size()
        + " regions with incomplete nets, threshold=" + shortNetThreshold + " units...");

    Set<Integer> originalItemIds = collectItemIds(board);
    ExecutorService executor = Executors.newFixedThreadPool(activeIndices.size());
    List<Future<RegionRouteResult>> futures = new ArrayList<>();

    for (int i : activeIndices) {
      final int rIdx = i;
      final Set<Integer> nets = regionNets.get(i);
      if (nets.isEmpty()) continue;

      futures.add(executor.submit(() -> {
        long start = System.currentTimeMillis();
        RoutingRegionContext ctx = new RoutingRegionContext();
        ctx.board = board.deepCopy();
        if (ctx.board == null) return null;
        ctx.regionIndex = rIdx;

        // Route only this region's nets
        RouterSettings regionSettings = settings.clone();
        regionSettings.netsToRoute = new HashSet<>(nets);
        regionSettings.setFanoutEnabled(false);

        StoppableThread rt = createTrialThread();
        BatchAutorouter router = new BatchAutorouter(rt, ctx.board, regionSettings,
            true, true, settings.get_start_ripup_costs(),
            settings.trace_pull_tight_accuracy);
        router.setStagnationPassLimit(5);
        router.setStopAtPassMinimum(3);
        router.runBatchLoop();

        ctx.duration = System.currentTimeMillis() - start;
        int incompletes = new DesignRulesChecker(ctx.board, null).getIncompleteCount();
        FRLogger.info("RegionRouter.shortRoute: Region " + rIdx + " — "
            + nets.size() + " nets, " + incompletes + " incompletes, "
            + ctx.duration + "ms");
        return new RegionRouteResult(ctx.board, nets, rIdx, incompletes);
      }));
    }

    List<RegionRouteResult> results = collectFutures(futures, executor);
    if (results.isEmpty()) {
      FRLogger.warn("RegionRouter.shortRoute: All regions failed.");
      return Collections.emptySet();
    }

    // Merge
    int mergedCount = 0;
    Set<Integer> fullyRoutedNets = new HashSet<>();
    for (RegionRouteResult result : results) {
      try {
        mergedCount += mergeNewItems(board, result.board, originalItemIds);
        if (result.incompleteCount == 0) {
          fullyRoutedNets.addAll(result.nets);
        }
      } catch (Exception e) {
        FRLogger.warn("RegionRouter.shortRoute: Merge error for region " + result.regionIndex + ": " + e.getMessage());
      }
    }

    board.clear_all_item_temporary_autoroute_data();
    board.finish_autoroute();

    FRLogger.info("RegionRouter.shortRoute: Complete — " + results.size() + " regions, "
        + mergedCount + " items merged, " + fullyRoutedNets.size() + " fully routed nets");
    return fullyRoutedNets;
  }

  // ===================== Phase 4 Pass1: Region-based parallel cluster routing =====================

  /**
   * Phase 4 Pass1: clusters incomplete nets spatially using {@link NetCluster#clusterNets},
   * then routes each cluster independently on a deep copy of the board in parallel.
   * Results are merged back to the main board sequentially.
   * <p>
   * After all clusters are routed and merged, the main board state is finalized.
   * The sequential fallback (Phase 4c) in the caller handles any remaining boundary issues.
   *
   * @param board        the main routing board
   * @param regions      the list of adaptive regions (used for assignment/load-balancing only)
   * @param settings     router settings
   * @param thread       the stoppable thread
   * @return number of incompletes remaining after Phase 4
   */
  public static int regionClusterRoute(
      RoutingBoard board,
      List<Region> regions,
      RouterSettings settings,
      StoppableThread thread) {

    // Collect incomplete nets across all regions
    Set<Integer> incompleteNets = collectIncompleteNetNumbers(board);
    if (incompleteNets.isEmpty()) {
      FRLogger.info("RegionRouter.clusterRoute: No incomplete nets, skipping.");
      return 0;
    }

    // Cluster all incomplete nets
    List<NetCluster> clusters = NetCluster.clusterNets(board, incompleteNets);
    if (clusters.isEmpty()) {
      FRLogger.warn("RegionRouter.clusterRoute: No clusters formed.");
      return incompleteNets.size();
    }

    FRLogger.info("RegionRouter.clusterRoute: " + incompleteNets.size()
        + " nets → " + clusters.size() + " clusters");

    // Collect original item IDs before routing
    Set<Integer> originalItemIds = collectItemIds(board);

    // Route each cluster independently in parallel
    int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), 6);
    numThreads = Math.min(numThreads, clusters.size());
    if (numThreads <= 0) numThreads = 1;

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    List<Future<ClusterRouteTask>> futures = new ArrayList<>();

    for (int ci = 0; ci < clusters.size(); ci++) {
      final NetCluster cluster = clusters.get(ci);
      final int clusterIdx = ci;

      futures.add(executor.submit(() -> {
        long start = System.currentTimeMillis();

        // Deep copy the board for this cluster
        RoutingBoard clusterBoard = board.deepCopy();
        if (clusterBoard == null) {
          FRLogger.warn("RegionRouter.clusterRoute: deepCopy failed for cluster " + clusterIdx);
          return null;
        }

        // Restrict routing to this cluster's nets only
        RouterSettings clusterSettings = settings.clone();
        clusterSettings.netsToRoute = new HashSet<>(cluster.getNetNumbers());
        clusterSettings.setFanoutEnabled(false);

        StoppableThread ct = createTrialThread();
        BatchAutorouter router = new BatchAutorouter(ct, clusterBoard, clusterSettings,
            true, true, settings.get_start_ripup_costs(),
            settings.trace_pull_tight_accuracy);
        router.setStagnationPassLimit(5);
        router.setStopAtPassMinimum(3);
        router.setOptimizerAutorouter(true);
        router.runBatchLoop();

        int incompletes = new DesignRulesChecker(clusterBoard, null).getIncompleteCount();
        long duration = System.currentTimeMillis() - start;

        FRLogger.info("RegionRouter.clusterRoute: Cluster " + clusterIdx + " ("
            + cluster.getNetCount() + " nets) — " + incompletes + " incompletes, "
            + duration + "ms");

        return new ClusterRouteTask(clusterBoard, clusterIdx);
      }));
    }

    // Collect results and merge back to main board
    int totalMerged = 0;
    int completedClusters = 0;

    for (Future<ClusterRouteTask> future : futures) {
      try {
        ClusterRouteTask result = future.get(120, TimeUnit.SECONDS);
        if (result == null) continue;

        int merged = mergeNewItems(board, result.board, originalItemIds);
        totalMerged += merged;
        completedClusters++;

        // Update original item IDs to prevent re-merging in subsequent clusters
        Set<Integer> updatedIds = new HashSet<>(originalItemIds);
        Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
        for (;;) {
          UndoableObjects.Storable ob = board.item_list.read_object(it);
          if (ob == null) break;
          if (ob instanceof Item item) {
            updatedIds.add(item.get_id_no());
          }
        }
        originalItemIds = updatedIds;
      } catch (TimeoutException e) {
        future.cancel(true);
        FRLogger.warn("RegionRouter.clusterRoute: Cluster timed out after 120s.");
      } catch (Exception e) {
        FRLogger.warn("RegionRouter.clusterRoute: Cluster execution error: " + e.getMessage());
      }
    }
    executor.shutdownNow();

    // Finalize the board after all merges
    board.clear_all_item_temporary_autoroute_data();
    board.finish_autoroute();

    int remainingAfter = new DesignRulesChecker(board, null).getIncompleteCount();
    FRLogger.info("RegionRouter.clusterRoute: Complete — " + completedClusters
        + "/" + clusters.size() + " clusters, " + totalMerged
        + " items merged, " + remainingAfter + " incompletes remaining.");

    return remainingAfter;
  }

  // ===================== Utility methods =====================

  /**
   * Assigns component numbers to regions based on SMD pin gravity center.
   */
  private static List<Set<Integer>> assignComponentsToRegions(
      RoutingBoard board, List<Region> regions) {
    List<Set<Integer>> result = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      result.add(new HashSet<>());
    }

    Collection<Pin> smdPins = board.get_smd_pins();
    if (smdPins == null || smdPins.isEmpty()) return result;

    // For each component, find its SMD pin gravity center
    for (int compNo = 1; compNo <= board.components.count(); compNo++) {
      app.freerouting.board.Component comp = board.components.get(compNo);
      if (comp == null) continue;

      double cx = 0, cy = 0;
      int cnt = 0;
      for (Pin pin : smdPins) {
        if (pin.get_component_no() == compNo) {
          FloatPoint c = pin.get_center().to_float();
          cx += c.x;
          cy += c.y;
          cnt++;
        }
      }
      if (cnt == 0) continue;
      cx /= cnt;
      cy /= cnt;

      // Assign to the region containing the gravity center
      for (int i = 0; i < regions.size(); i++) {
        Region r = regions.get(i);
        if (r.containsPoint(cx, cy)) {
          result.get(i).add(compNo);
          break;
        }
      }

      // Fallback: if no region contains the center, assign to the nearest region
      if (result.stream().allMatch(Set::isEmpty)) {
        double bestDist = Double.MAX_VALUE;
        int bestRegion = 0;
        for (int i = 0; i < regions.size(); i++) {
          Region r = regions.get(i);
          double dx = cx - r.getCenterX();
          double dy = cy - r.getCenterY();
          double dist = dx * dx + dy * dy;
          if (dist < bestDist) {
            bestDist = dist;
            bestRegion = i;
          }
        }
        result.get(bestRegion).add(compNo);
      }
    }
    return result;
  }

  /**
   * Assigns incomplete net numbers to regions based on pin location bounding boxes.
   */
  private static List<Set<Integer>> assignNetsToRegions(
      RoutingBoard board, List<Region> regions, Set<Integer> excludedNets) {

    List<Set<Integer>> result = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      result.add(new HashSet<>());
    }

    Set<Integer> incompleteNets = collectIncompleteNetNumbers(board);

    for (int netNo : incompleteNets) {
      if (excludedNets != null && excludedNets.contains(netNo)) continue;

      // Get pin bbox for this net
      Collection<Pin> allPins = board.get_pins();
      double nMinX = Double.MAX_VALUE, nMinY = Double.MAX_VALUE;
      double nMaxX = -Double.MAX_VALUE, nMaxY = -Double.MAX_VALUE;
      boolean hasPins = false;
      for (Pin pin : allPins) {
        if (pin.contains_net(netNo)) {
          FloatPoint c = pin.get_center().to_float();
          nMinX = Math.min(nMinX, c.x);
          nMinY = Math.min(nMinY, c.y);
          nMaxX = Math.max(nMaxX, c.x);
          nMaxY = Math.max(nMaxY, c.y);
          hasPins = true;
        }
      }
      if (!hasPins) continue;

      double netCenterX = (nMinX + nMaxX) / 2;
      double netCenterY = (nMinY + nMaxY) / 2;

      // Assign to the region containing the net's center
      boolean assigned = false;
      for (int i = 0; i < regions.size(); i++) {
        if (regions.get(i).containsPoint(netCenterX, netCenterY)) {
          result.get(i).add(netNo);
          assigned = true;
          break;
        }
      }

      // Fallback: assign to region with most overlap
      if (!assigned) {
        double bestOverlap = -1;
        int bestRegion = 0;
        for (int i = 0; i < regions.size(); i++) {
          double overlap = regions.get(i).overlapRatio(nMinX, nMinY, nMaxX, nMaxY);
          if (overlap > bestOverlap) {
            bestOverlap = overlap;
            bestRegion = i;
          }
        }
        result.get(bestRegion).add(netNo);
      }
    }

    return result;
  }

  /**
   * Assigns NetCluster objects to regions based on their bbox center.
   */
  private static List<List<NetCluster>> assignClustersToRegions(
      List<NetCluster> clusters, List<Region> regions) {

    List<List<NetCluster>> result = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      result.add(new ArrayList<>());
    }

    for (NetCluster cluster : clusters) {
      double cx = cluster.getCenterX();
      double cy = cluster.getCenterY();
      boolean assigned = false;
      for (Region region : regions) {
        if (region.containsPoint(cx, cy)) {
          result.get(region.id).add(cluster);
          assigned = true;
          break;
        }
      }
      // Fallback: assign to nearest region
      if (!assigned) {
        double bestDist = Double.MAX_VALUE;
        int bestRegion = 0;
        for (Region region : regions) {
          double dx = cx - region.getCenterX();
          double dy = cy - region.getCenterY();
          double dist = dx * dx + dy * dy;
          if (dist < bestDist) {
            bestDist = dist;
            bestRegion = region.id;
          }
        }
        result.get(bestRegion).add(cluster);
      }
    }

    return result;
  }

  /**
   * Collects all item id_no values from the board.
   */
  static Set<Integer> collectItemIds(RoutingBoard board) {
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
   * Merges new items (traces, vias) from a region/cluster board copy back to the main board.
   * Items with id_no in originalItemIds or items that are Pin instances are skipped.
   */
  static int mergeNewItems(RoutingBoard targetBoard, RoutingBoard sourceBoard,
      Set<Integer> originalItemIds) {
    int count = 0;
    Iterator<UndoableObjects.UndoableObjectNode> it = sourceBoard.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = sourceBoard.item_list.read_object(it);
      if (ob == null) break;
      if (!(ob instanceof Item item)) continue;
      if (originalItemIds.contains(item.get_id_no())) continue;
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
        FRLogger.warn("mergeNewItems: Failed to copy item id=" + item.get_id_no()
            + ": " + e.getMessage());
      }
    }
    return count;
  }

  static StoppableThread createTrialThread() {
    return new StoppableThread() {
      @Override
      protected void thread_action() {
        // no-op: never started, used only for stop-state management
      }
    };
  }

  private static Set<Integer> collectIncompleteNetNumbers(RoutingBoard board) {
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null) return Collections.emptySet();
    Set<Integer> netNos = new HashSet<>();
    for (AirLine al : airlines) {
      if (al.net != null) netNos.add(al.net.net_number);
    }
    return netNos;
  }

  /**
   * Collects Future results with a timeout to prevent hanging.
   */
  private static <T> List<T> collectFutures(List<Future<T>> futures, ExecutorService executor) {
    List<T> results = new ArrayList<>();
    for (Future<T> future : futures) {
      try {
        T result = future.get(REGION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (result != null) results.add(result);
      } catch (TimeoutException e) {
        future.cancel(true);
        FRLogger.warn("RegionRouter: Task timed out after " + REGION_TIMEOUT_SECONDS + "s");
      } catch (Exception e) {
        FRLogger.warn("RegionRouter: Task execution error: " + e.getMessage());
      }
    }
    executor.shutdownNow();
    return results;
  }

  // ===================== Internal data holders =====================

  static class RoutingRegionContext {
    RoutingBoard board;
    int regionIndex;
    long duration;
  }

  static class RegionFanoutResult {
    final RoutingBoard board;
    final BatchFanout.FanoutRunSummary summary;
    final Set<Integer> componentNos;
    final int regionIndex;

    RegionFanoutResult(RoutingBoard board, BatchFanout.FanoutRunSummary summary,
        Set<Integer> componentNos, int regionIndex) {
      this.board = board;
      this.summary = summary;
      this.componentNos = componentNos;
      this.regionIndex = regionIndex;
    }
  }

  static class RegionRouteResult {
    final RoutingBoard board;
    final Set<Integer> nets;
    final int regionIndex;
    final int incompleteCount;

    RegionRouteResult(RoutingBoard board, Set<Integer> nets,
        int regionIndex, int incompleteCount) {
      this.board = board;
      this.nets = nets;
      this.regionIndex = regionIndex;
      this.incompleteCount = incompleteCount;
    }
  }

  static class ClusterRouteTask {
    final RoutingBoard board;
    final int clusterIndex;

    ClusterRouteTask(RoutingBoard board, int clusterIndex) {
      this.board = board;
      this.clusterIndex = clusterIndex;
    }
  }
}
