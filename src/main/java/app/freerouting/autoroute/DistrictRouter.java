package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
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
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point: route all districts in parallel
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Route all districts sequentially (thread-safe: AutorouteEngine is not
   * safe for concurrent access).  Parallel routing can be re-enabled once
   * the engine supports per-thread expansion rooms.
   *
   * @return aggregated results across all districts
   */
  public List<DistrictRoutingResult> routeAllDistricts() {
    List<MultiLevelPartitioner.District> districts = partitioner.getAllDistricts();
    if (districts.isEmpty()) {
      FRLogger.info("DistrictRouter: no districts to route");
      return Collections.emptyList();
    }

    FRLogger.info("DistrictRouter: routing " + districts.size()
        + " districts sequentially (serial mode for thread safety)");

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

    // Log summary
    int totalRouted = results.stream().mapToInt(r -> r.netsRouted).sum();
    int totalFailed = results.stream().mapToInt(r -> r.netsFailed).sum();
    FRLogger.info("DistrictRouter: complete — " + totalRouted + " routed, "
        + totalFailed + " failed across " + results.size() + " districts");

    return results;
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

    // Route each net (skip those already routed by Phase 1)
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

  private List<Long> extractNetCellKeys(Net net) {
    Set<Long> keys = new HashSet<>();
    double cellSize = stom != null ? stom.getCellSize()
        : SpatioTemporalOccupancyMap.DEFAULT_CELL_SIZE;
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;

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
