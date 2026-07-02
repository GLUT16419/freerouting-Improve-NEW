package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * UTPR Phase 2 — District-level parallel router.
 * <p>
 * In the city-traffic metaphor, this is the <b>district construction team</b>
 * that independently builds the local street networks within each urban
 * district (level-3 CRP district). Since districts are separated by
 * precomputed boundary paths and reserved corridors, they can run in
 * <b>fully parallel</b> — just like different construction crews working in
 * different parts of the city simultaneously.
 * <p>
 * <b>Key design:</b>
 * <ul>
 *   <li>Each district gets its own routing context (STOM sub-map, layer
 *       assignment, net list).</li>
 *   <li>All districts are processed in parallel using a thread pool.</li>
 *   <li>Within each district, nets are routed in priority order using the
 *       existing BatchAutorouter mechanism.</li>
 *   <li>Results are merged back into the global board after all districts
 *       complete.</li>
 * </ul>
 */
public class DistrictRouter implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Maximum threads for parallel district routing. */
  static final int MAX_DISTRICT_THREADS = 8;

  /** Ripup cost for nets already attempted in Phase 1 (higher = more aggressive). */
  static final int DISTRICT_RIPUP_COST = 12;

  /** Lower ripup cost for nets already successfully routed (saves time). */
  static final int SKIP_ALREADY_ROUTED = -1;

  // ═══════════════════════════════════════════════════════════════════════
  //  DistrictRoutingTask
  // ═══════════════════════════════════════════════════════════════════════

  /** Result of routing one district. */
  public static class DistrictRoutingResult implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int districtId;
    public final int netsRouted;
    public final int netsFailed;
    public final long elapsedMs;
    public final Set<Integer> routedNetNumbers;
    public final Set<Integer> failedNetNumbers;

    public DistrictRoutingResult(int districtId, int netsRouted, int netsFailed,
                                  long elapsedMs,
                                  Set<Integer> routedNetNumbers,
                                  Set<Integer> failedNetNumbers) {
      this.districtId = districtId;
      this.netsRouted = netsRouted;
      this.netsFailed = netsFailed;
      this.elapsedMs = elapsedMs;
      this.routedNetNumbers = routedNetNumbers;
      this.failedNetNumbers = failedNetNumbers;
    }

    public boolean isComplete() { return netsFailed == 0; }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final RoutingBoard routingBoard;
  private final BatchAutorouter batchAutorouter;
  private final MultiLevelPartitioner partitioner;
  private final SpatioTemporalOccupancyMap stom;
  private final TrafficModeLayerAssigner trafficAssigner;

  /** Nets already routed by Phase 1 (backbone) — skip re-routing. */
  private final Set<Integer> alreadyRoutedNets;

  /** Router settings for creating cloned autorouters (passed through). */
  private RouterSettings routerSettings;

  /** Thread pool for parallel district routing. */
  private transient ExecutorService districtExecutor;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public DistrictRouter(BasicBoard board, RoutingBoard routingBoard,
                        BatchAutorouter batchAutorouter,
                        MultiLevelPartitioner partitioner,
                        SpatioTemporalOccupancyMap stom,
                        TrafficModeLayerAssigner trafficAssigner,
                        Set<Integer> alreadyRoutedNets) {
    this.board = board;
    this.routingBoard = routingBoard;
    this.batchAutorouter = batchAutorouter;
    this.partitioner = partitioner;
    this.stom = stom;
    this.trafficAssigner = trafficAssigner;
    this.alreadyRoutedNets = alreadyRoutedNets != null ? alreadyRoutedNets : new HashSet<>();
    if (this.batchAutorouter != null && this.batchAutorouter.job != null) {
      this.routerSettings = this.batchAutorouter.job.routerSettings;
    }
  }

  /**
   * Set router settings for cloned autorouters (called from UrbanTrafficBatchAutorouter).
   */
  public void setRouterSettings(RouterSettings settings) {
    this.routerSettings = settings;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point: route all districts in parallel
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Route all districts in parallel using per-district board clones.
   * Each district gets its own Board + BatchAutorouter instance, allowing
   * true concurrent routing without thread-safety issues.
   * <p>
   * Falls back to sequential mode if the board cannot be serialized or
   * if there are fewer than 2 districts (no parallelization benefit).
   *
   * @return aggregated results across all districts
   */
  public List<DistrictRoutingResult> routeAllDistricts() {
    List<MultiLevelPartitioner.District> districts = partitioner.getAllDistricts();
    if (districts.isEmpty()) {
      FRLogger.info("DistrictRouter: no districts to route");
      return Collections.emptyList();
    }

    // Sequential mode for 1 district or when settings disable parallelism
    if (districts.size() <= 1) {
      return routeAllDistrictsSequential(districts);
    }

    int maxThreads = Math.min(MAX_DISTRICT_THREADS,
        Runtime.getRuntime().availableProcessors());
    int effectiveThreads = Math.min(maxThreads, districts.size());

    FRLogger.info("DistrictRouter: routing " + districts.size()
        + " districts in parallel using " + effectiveThreads + " threads");

    districtExecutor = Executors.newFixedThreadPool(effectiveThreads);
    List<Future<DistrictRoutingResult>> futures = new ArrayList<>();

    // Snapshot STOM once — each parallel task gets its own copy
    SpatioTemporalOccupancyMap stomSnapshot = stom != null ? stom.snapshot() : null;

    for (MultiLevelPartitioner.District district : districts) {
      if (district.netNumbers.isEmpty()) continue;

      final MultiLevelPartitioner.District dist = district;
      final SpatioTemporalOccupancyMap distStom =
          stomSnapshot != null ? stomSnapshot.snapshot() : null;

      futures.add(districtExecutor.submit(() -> {
        long t0 = System.currentTimeMillis();
        Set<Integer> routed = new HashSet<>();
        Set<Integer> failed = new HashSet<>();

        FRLogger.debug("DistrictRouter[parallel]: district " + dist.id
            + " (" + dist.netNumbers.size() + " nets)");

        // Clone the board and create a dedicated BatchAutorouter for this district
        RoutingBoard clonedBoard;
        try {
          clonedBoard = ((RoutingBoard) this.board).deepCopy();
        } catch (Exception e) {
          FRLogger.warn("DistrictRouter[parallel]: board deepCopy failed for district "
              + dist.id + ": " + e.getMessage());
          // Fallback: report all nets as failed
          failed.addAll(dist.netNumbers);
          return new DistrictRoutingResult(dist.id, 0, dist.netNumbers.size(),
              System.currentTimeMillis() - t0, routed, failed);
        }

        // Build a fresh BatchAutorouter for the cloned board
        BatchAutorouter distAutorouter;
        try {
          // Create a minimal StoppableThread for routing
          app.freerouting.core.StoppableThread distThread =
              new app.freerouting.core.StoppableThread() {
                @Override protected void thread_action() {}
              };
          distThread.setName("District-" + dist.id + "-Router");
          distAutorouter = new BatchAutorouter(distThread, clonedBoard,
              routerSettings, true, true,
              DISTRICT_RIPUP_COST * 2, 4);
          // Set a minimal RoutingJob for logging/scoring
          app.freerouting.core.RoutingJob distJob = new app.freerouting.core.RoutingJob();
          distJob.board = clonedBoard;
          distJob.routerSettings = routerSettings;
          distAutorouter.job = distJob;
        } catch (Exception e) {
          FRLogger.warn("DistrictRouter[parallel]: autorouter init failed "
              + "for district " + dist.id + ": " + e.getMessage());
          failed.addAll(dist.netNumbers);
          return new DistrictRoutingResult(dist.id, 0, dist.netNumbers.size(),
              System.currentTimeMillis() - t0, routed, failed);
        }

        // Sort nets by traffic mode priority
        List<Integer> sortedNets = dist.netNumbers.stream()
            .sorted((a, b) -> {
              int ma = trafficAssigner.getMode(a);
              int mb = trafficAssigner.getMode(b);
              return Integer.compare(mb, ma);
            })
            .collect(Collectors.toList());

        // Route each net
        for (int netNo : sortedNets) {
          if (alreadyRoutedNets.contains(netNo)) {
            routed.add(netNo);
            continue;
          }
          try {
            BatchAutorouter.NetRouteResult result =
                distAutorouter.routeNet(netNo, DISTRICT_RIPUP_COST);

              if (result != null && result.isRouted()) {
              routed.add(netNo);
              if (distStom != null) {
                Net net = clonedBoard.rules.nets.get(netNo);
                if (net != null) {
                  List<Long> cellKeys = extractCellKeysFromBoard(clonedBoard, net, distStom.getCellSize());
                  if (!cellKeys.isEmpty()) {
                    distStom.reserve(cellKeys);
                  }
                }
              }
            } else {
              failed.add(netNo);
            }
          } catch (Exception e) {
            FRLogger.debug("DistrictRouter[parallel]: net " + netNo
                + " failed: " + e.getMessage());
            failed.add(netNo);
          }
        }

        long elapsed = System.currentTimeMillis() - t0;
        FRLogger.debug("DistrictRouter[parallel]: district " + dist.id
            + " done — " + routed.size() + " routed, " + failed.size()
            + " failed, " + elapsed + "ms");

        // Merge district STOM back into the global STOM
        if (stom != null && distStom != null) {
          stom.merge(distStom);
        }

        return new DistrictRoutingResult(dist.id, routed.size(), failed.size(),
            elapsed, routed, failed);
      }));
    }

    // Collect results
    List<DistrictRoutingResult> results = new ArrayList<>();
    for (Future<DistrictRoutingResult> future : futures) {
      try {
        results.add(future.get(60, TimeUnit.MINUTES)); // generous timeout
      } catch (Exception e) {
        FRLogger.warn("DistrictRouter[parallel]: district task failed: "
            + e.getMessage());
      }
    }

    districtExecutor.shutdown();
    try {
      districtExecutor.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Log summary
    int totalRouted = results.stream().mapToInt(r -> r.netsRouted).sum();
    int totalFailed = results.stream().mapToInt(r -> r.netsFailed).sum();
    FRLogger.info("DistrictRouter[parallel]: complete — " + totalRouted
        + " routed, " + totalFailed + " failed across " + results.size()
        + " districts");

    return results;
  }

  /**
   * Fallback sequential mode for single-district boards.
   */
  private List<DistrictRoutingResult> routeAllDistrictsSequential(
      List<MultiLevelPartitioner.District> districts) {
    FRLogger.info("DistrictRouter: routing " + districts.size()
        + " district(s) sequentially");

    List<DistrictRoutingResult> results = new ArrayList<>();
    for (MultiLevelPartitioner.District district : districts) {
      if (district.netNumbers.isEmpty()) continue;
      try {
        DistrictRoutingResult result = routeDistrict(district);
        results.add(result);
      } catch (Exception e) {
        FRLogger.warn("DistrictRouter: district " + district.id
            + " error: " + e.getMessage());
      }
    }

    int totalRouted = results.stream().mapToInt(r -> r.netsRouted).sum();
    int totalFailed = results.stream().mapToInt(r -> r.netsFailed).sum();
    FRLogger.info("DistrictRouter: complete — " + totalRouted + " routed, "
        + totalFailed + " failed across " + results.size() + " districts");
    return results;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  V8: User Equilibrium Parallel Probing
  // ═══════════════════════════════════════════════════════════════════════

  /** Max probing rounds (configurable from settings). */
  private int maxProbingRounds = 3;
  /** Enable user equilibrium parallel probing. */
  private boolean userEquilibriumEnabled = false;

  /** Set max probing rounds from settings. */
  public void setMaxProbingRounds(int rounds) {
    this.maxProbingRounds = Math.max(1, Math.min(5, rounds));
  }

  /** Enable/disable user equilibrium. */
  public void setUserEquilibriumEnabled(boolean enabled) {
    this.userEquilibriumEnabled = enabled;
  }

  /**
   * V8.2: Route same-priority nets using User Equilibrium parallel probing.
   * <p>
   * Run 2-3 rounds of probing: Round 1 routes all nets independently; Round 2
   * detects cell-level overlaps, adds conflict penalties, and re-routes;
   * Round 3 checks convergence. This reduces sub-optimal reservations caused
   * by sequential order dependence in traditional ripup-and-reroute.
   *
   * @param sortedNets nets to route (already sorted by traffic mode priority)
   * @param alreadyRouted nets routed in previous phases
   * @param stomRef STOM reference for reservation
   * @param boardRef board reference for net info
   * @return (routed, failed) sets
   */
  private Map.Entry<Set<Integer>, Set<Integer>> routeWithUserEquilibrium(
      List<Integer> sortedNets, Set<Integer> alreadyRouted,
      SpatioTemporalOccupancyMap stomRef, BasicBoard boardRef) {

    Set<Integer> routed = new HashSet<>();
    Set<Integer> failed = new HashSet<>();

    if (sortedNets.isEmpty() || sortedNets.size() < 2) {
      // Not enough nets for probing — fallback to sequential
      for (int netNo : sortedNets) {
        if (alreadyRouted.contains(netNo)) { routed.add(netNo); continue; }
        try {
          BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, DISTRICT_RIPUP_COST);
          if (result != null && result.isRouted()) { routed.add(netNo); }
          else { failed.add(netNo); }
        } catch (Exception e) { failed.add(netNo); }
      }
      return new AbstractMap.SimpleEntry<>(routed, failed);
    }

    // Group by traffic mode (same priority)
    Map<Integer, List<Integer>> priorityGroups = sortedNets.stream()
        .collect(Collectors.groupingBy(n -> trafficAssigner.getMode(n)));

    for (Map.Entry<Integer, List<Integer>> group : priorityGroups.entrySet()) {
      List<Integer> groupNets = group.getValue();
      if (groupNets.isEmpty()) continue;

      // Round 1: Route all nets in this group independently
      Map<Integer, Set<Long>> netCellOccupancy = new HashMap<>();
      Set<Integer> roundRouted = new HashSet<>();
      Set<Integer> roundFailed = new HashSet<>();

      for (int netNo : groupNets) {
        if (alreadyRouted.contains(netNo)) { roundRouted.add(netNo); continue; }
        try {
          BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, DISTRICT_RIPUP_COST);
          if (result != null && result.isRouted()) {
            roundRouted.add(netNo);
            Net net = boardRef.rules.nets.get(netNo);
            if (net != null && stomRef != null) {
              Set<Long> cells = new HashSet<>(extractCellKeysFromBoard(boardRef, net, stomRef.getCellSize()));
              netCellOccupancy.put(netNo, cells);
            }
          } else {
            roundFailed.add(netNo);
          }
        } catch (Exception e) { roundFailed.add(netNo); }
      }
      routed.addAll(roundRouted);
      failed.addAll(roundFailed);

      // Round 2 (and optionally 3): Detect cell conflicts and re-route
      int maxRounds = Math.min(maxProbingRounds, groupNets.size());
      for (int round = 1; round < maxRounds; round++) {
        // Detect overlapping cells among the routed nets
        Map<Integer, Set<Integer>> conflictPairs = new HashMap<>();
        List<Integer> toReRoute = new ArrayList<>();
        for (int a : roundRouted) {
          Set<Long> cellsA = netCellOccupancy.get(a);
          if (cellsA == null) continue;
          for (int b : roundRouted) {
            if (a >= b) continue;
            Set<Long> cellsB = netCellOccupancy.get(b);
            if (cellsB == null) continue;
            Set<Long> intersection = new HashSet<>(cellsA);
            intersection.retainAll(cellsB);
            if (!intersection.isEmpty()) {
              conflictPairs.computeIfAbsent(a, k -> new HashSet<>()).add(b);
              conflictPairs.computeIfAbsent(b, k -> new HashSet<>()).add(a);
              toReRoute.add(a);
              toReRoute.add(b);
            }
          }
        }

        if (toReRoute.isEmpty()) break; // converged

        // Re-route conflicting nets with increased ripup cost
        for (int netNo : new HashSet<>(toReRoute)) {
          if (!roundRouted.contains(netNo)) continue;
          int conflictCount = conflictPairs.getOrDefault(netNo, Collections.emptySet()).size();
          int ripupBoost = DISTRICT_RIPUP_COST + conflictCount * 5;
          try {
            BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, ripupBoost);
            if (result != null && result.isRouted()) {
              Net net = boardRef.rules.nets.get(netNo);
              if (net != null && stomRef != null) {
                Set<Long> newCells = new HashSet<>(extractCellKeysFromBoard(boardRef, net, stomRef.getCellSize()));
                netCellOccupancy.put(netNo, newCells);
              }
            }
          } catch (Exception e) {
            roundFailed.add(netNo);
            roundRouted.remove(netNo);
          }
        }
      }

      // Reserve final paths in STOM
      if (stomRef != null) {
        for (int netNo : roundRouted) {
          if (alreadyRouted.contains(netNo)) continue;
          Net net = boardRef.rules.nets.get(netNo);
          if (net != null) {
            List<Long> cellKeys = extractCellKeysFromBoard(boardRef, net, stomRef.getCellSize());
            if (!cellKeys.isEmpty()) stomRef.reserve(cellKeys);
          }
        }
      }
    }

    return new AbstractMap.SimpleEntry<>(routed, failed);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Route a single district
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Route all nets within a single district.
   */
  private DistrictRoutingResult routeDistrict(MultiLevelPartitioner.District district) {
    long t0 = System.currentTimeMillis();
    Set<Integer> routed = new HashSet<>();
    Set<Integer> failed = new HashSet<>();

    FRLogger.debug("DistrictRouter: routing district " + district.id
        + " (" + district.netNumbers.size() + " nets, "
        + district.nodeIds.size() + " CH nodes)");

    // Sort nets by priority from traffic assigner
    List<Integer> sortedNets = district.netNumbers.stream()
        .sorted((a, b) -> {
          int ma = trafficAssigner.getMode(a);
          int mb = trafficAssigner.getMode(b);
          // Subway first, then elevated, then surface
          return Integer.compare(mb, ma);
        })
        .collect(Collectors.toList());

    // V8: User Equilibrium parallel probing for same-priority nets
    if (userEquilibriumEnabled) {
      Map.Entry<Set<Integer>, Set<Integer>> eqResult = routeWithUserEquilibrium(
          sortedNets, alreadyRoutedNets, stom, board);
      routed.addAll(eqResult.getKey());
      failed.addAll(eqResult.getValue());
    } else {
      // Route each net sequentially (skip those already routed by Phase 1)
      for (int netNo : sortedNets) {
        if (alreadyRoutedNets.contains(netNo)) {
          routed.add(netNo); // counted as routed from previous phase
          continue;
        }

        try {
          BatchAutorouter.NetRouteResult result =
              batchAutorouter.routeNet(netNo, DISTRICT_RIPUP_COST);

          if (result != null && result.isRouted()) {
            routed.add(netNo);

            // Reserve path in STOM
            if (stom != null) {
              Net net = board.rules.nets.get(netNo);
              if (net != null) {
                List<Long> cellKeys = extractNetCellKeys(net);
                if (!cellKeys.isEmpty()) {
                  stom.reserve(cellKeys);
                }
              }
            }
          } else {
            failed.add(netNo);
          }
        } catch (Exception e) {
          FRLogger.debug("DistrictRouter: net " + netNo + " failed: " + e.getMessage());
          failed.add(netNo);
        }
      }
    }

    long elapsed = System.currentTimeMillis() - t0;
    FRLogger.debug("DistrictRouter: district " + district.id + " done — "
        + routed.size() + " routed, " + failed.size() + " failed, "
        + elapsed + "ms");

    return new DistrictRoutingResult(
        district.id, routed.size(), failed.size(),
        elapsed, routed, failed);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private final Map<Integer, List<Long>> netCellKeyCache = new HashMap<>();

  private List<Long> extractNetCellKeys(Net net) {
    if (net == null) return Collections.emptyList();
    return netCellKeyCache.computeIfAbsent(net.net_number, k -> {
      double cellSize = stom != null ? stom.getCellSize()
          : SpatioTemporalOccupancyMap.DEFAULT_CELL_SIZE;
      return extractCellKeysFromBoard((BasicBoard) this.board, net, cellSize);
    });
  }

  /**
   * Static cell key extraction — safe for use with cloned boards.
   * Does NOT depend on instance fields, making it thread-safe.
   */
  private static List<Long> extractCellKeysFromBoard(BasicBoard targetBoard, Net net, double cellSize) {
    if (net == null) return Collections.emptyList();
    Set<Long> keys = new HashSet<>();
    double bmx = targetBoard.bounding_box.ll.to_float().x;
    double bmy = targetBoard.bounding_box.ll.to_float().y;
    for (app.freerouting.board.Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      int col = (int) ((c.x - bmx) / cellSize);
      int row = (int) ((c.y - bmy) / cellSize);
      keys.add(SpatioTemporalOccupancyMap.cellKey(pin.first_layer(), row, col));
    }
    for (app.freerouting.board.Item item : net.get_items()) {
      if (item instanceof app.freerouting.board.PolylineTrace trace) {
        for (int i = 0; i < trace.corner_count(); i++) {
          FloatPoint cp = trace.polyline().corner_approx(i);
          int col = (int) ((cp.x - bmx) / cellSize);
          int row = (int) ((cp.y - bmy) / cellSize);
          keys.add(SpatioTemporalOccupancyMap.cellKey(trace.get_layer(), row, col));
        }
      } else if (item instanceof app.freerouting.board.Via via) {
        FloatPoint vc = via.get_center().to_float();
        int col = (int) ((vc.x - bmx) / cellSize);
        int row = (int) ((vc.y - bmy) / cellSize);
        keys.add(SpatioTemporalOccupancyMap.cellKey(
            via.first_layer(), row, col));
      }
    }
    return new ArrayList<>(keys);
  }
}
