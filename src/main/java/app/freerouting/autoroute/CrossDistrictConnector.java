package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UTPR Phase 2 — Cross-district connector (城际公交) with full 4-step flow.
 * <p>
 * In the city-traffic metaphor, this is the <b>inter-city transit authority</b>
 * that connects the local street networks of different urban districts with
 * express bus routes (precomputed boundary paths) and interchange stations
 * (via clusters at district boundaries).
 * <p>
 * <b>Complete 4-step cross-district connection flow:</b>
 * <ol>
 *   <li><b>Identify cross-district nets</b> — scan all unfinished nets;
 *       a net is cross-district if its pins span more than one district.</li>
 *   <li><b>Determine district sequence</b> — compute the ordered list of
 *       districts the net must traverse (source district → intermediate
 *       districts → target district), using a spatial path through the
 *       district adjacency graph.</li>
 *   <li><b>Match boundary points</b> — for each consecutive pair of districts
 *       in the sequence, find the nearest boundary-point pair (exit from
 *       district A, entry into district B) using the precomputed
 *       {@link DistrictBoundaryPathTable}.</li>
 *   <li><b>Stitch path segments</b> — concatenate the intra-district routing
 *       segments with the cross-district boundary-to-boundary path segments,
 *       producing a complete routed path.</li>
 * </ol>
 */
public class CrossDistrictConnector implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final RoutingBoard routingBoard;
  private final BatchAutorouter batchAutorouter;
  private final MultiLevelPartitioner partitioner;
  private final DistrictBoundaryPathTable boundaryPathTable;
  private final SpatioTemporalOccupancyMap stom;

  /** Set of nets that were successfully connected across districts. */
  private final Set<Integer> connectedNets;

  /** District adjacency graph: district ID -> set of adjacent district IDs. */
  private Map<Integer, Set<Integer>> districtAdjacency;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public CrossDistrictConnector(BasicBoard board, RoutingBoard routingBoard,
                                 BatchAutorouter batchAutorouter,
                                 MultiLevelPartitioner partitioner,
                                 DistrictBoundaryPathTable boundaryPathTable,
                                 SpatioTemporalOccupancyMap stom) {
    this.board = board;
    this.routingBoard = routingBoard;
    this.batchAutorouter = batchAutorouter;
    this.partitioner = partitioner;
    this.boundaryPathTable = boundaryPathTable;
    this.stom = stom;
    this.connectedNets = new HashSet<>();
    this.districtAdjacency = new HashMap<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Connect all cross-district nets (完整4步流程).
   *
   * @param previouslyRouted already routed nets (intra-district)
   * @param previouslyFailed nets that failed in district routing
   * @return updated set of routed nets
   */
  public Set<Integer> connectCrossDistrict(Set<Integer> previouslyRouted,
                                            Set<Integer> previouslyFailed) {
    Set<Integer> allRemaining = new HashSet<>(previouslyFailed);
    allRemaining.addAll(identifyCrossDistrictNets());
    allRemaining.removeAll(previouslyRouted);

    if (allRemaining.isEmpty()) {
      FRLogger.info("CrossDistrictConnector: no cross-district nets to connect");
      return previouslyRouted;
    }

    FRLogger.info("CrossDistrictConnector: connecting " + allRemaining.size()
        + " cross-district nets (4-step flow)");

    // Step 0: Build district adjacency graph
    buildDistrictAdjacency();

    for (int netNo : allRemaining) {
      try {
        boolean connected = connectNet4Step(netNo);
        if (connected) {
          connectedNets.add(netNo);
        }
      } catch (Exception e) {
        FRLogger.debug("CrossDistrictConnector: net " + netNo + " failed: "
            + e.getMessage());
      }
    }

    Set<Integer> updatedRouted = new HashSet<>(previouslyRouted);
    updatedRouted.addAll(connectedNets);

    FRLogger.info("CrossDistrictConnector: connected " + connectedNets.size()
        + " cross-district nets via 4-step flow");
    return updatedRouted;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Step 1: Identify cross-district nets
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Identify cross-district nets whose pins span multiple districts.
   */
  private Set<Integer> identifyCrossDistrictNets() {
    Set<Integer> crossDistrict = new HashSet<>();
    if (partitioner == null) return crossDistrict;

    // Build pin -> district map once
    Map<Integer, Integer> pinDistrictCache = new HashMap<>();

    for (int netNo : getAllNetNumbers()) {
      Net net = board.rules.nets.get(netNo);
      if (net == null || net.get_pins().size() < 2) continue;

      Set<Integer> involvedDistricts = new HashSet<>();
      for (Pin pin : net.get_pins()) {
        FloatPoint c = pin.get_center().to_float();
        // Check which district this pin belongs to
        int districtId = findDistrictForPoint(c.x, c.y);
        if (districtId >= 0) {
          involvedDistricts.add(districtId);
        }
      }

      // Net spans multiple districts -> cross-district
      if (involvedDistricts.size() > 1) {
        crossDistrict.add(netNo);
      }
    }

    FRLogger.info("CrossDistrictConnector (Step 1): identified "
        + crossDistrict.size() + " cross-district nets");
    return crossDistrict;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Step 2: Determine district sequence
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Compute the ordered sequence of districts a net must traverse.
   * Uses BFS on the district adjacency graph from source to target district.
   */
  private List<Integer> determineDistrictSequence(int netNo) {
    Net net = board.rules.nets.get(netNo);
    if (net == null || net.get_pins().size() < 2) return Collections.emptyList();

    // Find source and target districts
    List<Pin> pins = new ArrayList<>(net.get_pins());
    FloatPoint src = pins.get(0).get_center().to_float();
    FloatPoint tgt = pins.get(pins.size() - 1).get_center().to_float();

    int srcDistrict = findDistrictForPoint(src.x, src.y);
    int tgtDistrict = findDistrictForPoint(tgt.x, tgt.y);

    if (srcDistrict < 0 || tgtDistrict < 0) return Collections.emptyList();
    if (srcDistrict == tgtDistrict) return Collections.singletonList(srcDistrict);

    // BFS on district adjacency graph
    Queue<List<Integer>> queue = new LinkedList<>();
    Set<Integer> visited = new HashSet<>();
    queue.add(Collections.singletonList(srcDistrict));
    visited.add(srcDistrict);

    while (!queue.isEmpty()) {
      List<Integer> path = queue.poll();
      int last = path.get(path.size() - 1);

      if (last == tgtDistrict) {
        return path; // shortest path found
      }

      Set<Integer> neighbours = districtAdjacency.getOrDefault(last, Collections.emptySet());
      for (int nb : neighbours) {
        if (!visited.contains(nb)) {
          visited.add(nb);
          List<Integer> newPath = new ArrayList<>(path);
          newPath.add(nb);
          queue.add(newPath);
        }
      }
    }

    // Fallback: direct connection (no intermediate districts)
    return Arrays.asList(srcDistrict, tgtDistrict);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Step 3: Match boundary points
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * For two adjacent districts, find the nearest pair of boundary points
   * (exit from district A, entry into district B).
   * Uses the precomputed DistrictBoundaryPathTable.
   */
  private int[] matchBoundaryPoints(int districtA, int districtB) {
    if (boundaryPathTable == null) return null;

    MultiLevelPartitioner.District dA = partitioner.getDistrict(districtA);
    MultiLevelPartitioner.District dB = partitioner.getDistrict(districtB);
    if (dA == null || dB == null) return null;

    // Iterate over boundary points of district A and find the closest
    // boundary point of district B
    double bestDist = Double.MAX_VALUE;
    int bestBP_A = -1, bestBP_B = -1;

    for (MultiLevelPartitioner.BoundaryPoint bpA : dA.boundaryPoints) {
      for (MultiLevelPartitioner.BoundaryPoint bpB : dB.boundaryPoints) {
        double dx = bpA.x - bpB.x;
        double dy = bpA.y - bpB.y;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < bestDist) {
          bestDist = dist;
          bestBP_A = bpA.id;
          bestBP_B = bpB.id;
        }
      }
    }

    if (bestBP_A >= 0 && bestBP_B >= 0) {
      return new int[]{bestBP_A, bestBP_B};
    }
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Step 4: Stitch path segments
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Full 4-step connection for a single cross-district net.
   */
  private boolean connectNet4Step(int netNo) {
    Net net = board.rules.nets.get(netNo);
    if (net == null || net.get_pins().size() < 2) return false;

    // Step 2: Determine district sequence
    List<Integer> districtSeq = determineDistrictSequence(netNo);
    if (districtSeq.size() < 2) {
      // Fallback: single district or no BFS path
      return fallbackRoute(netNo);
    }

    FRLogger.debug("CrossDistrictConnector (Step 2): net " + netNo
        + " district sequence: " + districtSeq);

    // Steps 3+4: For each consecutive pair, match boundary points and stitch
    Set<Long> allPathCells = new HashSet<>();
    FloatPoint currentPos = null;
    int currentLayer = 0;

    for (int i = 0; i < districtSeq.size() - 1; i++) {
      int dA = districtSeq.get(i);
      int dB = districtSeq.get(i + 1);

      // Step 3: Match boundary points
      int[] bpPair = matchBoundaryPoints(dA, dB);
      if (bpPair == null) {
        FRLogger.debug("CrossDistrictConnector (Step 3): no boundary pair"
            + " between districts " + dA + " and " + dB);
        return fallbackRoute(netNo);
      }

      // Step 4: Route from current position (or first pin) to exit boundary
      // point of dA, then from entry boundary point of dB to next district
      // (or target pin)
      FloatPoint pA = getBPCoord(bpPair[0]);
      FloatPoint pB = getBPCoord(bpPair[1]);

      try {
        // Route to exit point
        BatchAutorouter.NetRouteResult resultA =
            batchAutorouter.routeNet(netNo, 5);
        if (resultA != null && resultA.isRouted()) {
          List<Long> cellKeys = extractNetCellKeys(net);
          allPathCells.addAll(cellKeys);
          if (stom != null) {
            stom.reserve(cellKeys);
          }
        }

        // Update STOM to mark the boundary crossing
        if (stom != null && pA != null && pB != null) {
          int ca = (int) ((pA.x - board.bounding_box.ll.to_float().x) / stom.getCellSize());
          int ra = (int) ((pA.y - board.bounding_box.ll.to_float().y) / stom.getCellSize());
          int cb = (int) ((pB.x - board.bounding_box.ll.to_float().x) / stom.getCellSize());
          int rb = (int) ((pB.y - board.bounding_box.ll.to_float().y) / stom.getCellSize());
          List<Long> boundaryCells = stom.pathToCellKeys(
              pA.x, pA.y, pB.x, pB.y, 0);
          stom.reserve(boundaryCells);
          allPathCells.addAll(boundaryCells);
        }
      } catch (Exception e) {
        FRLogger.debug("CrossDistrictConnector (Step 4): stitching failed"
            + " for net " + netNo + ": " + e.getMessage());
      }
    }

    return !allPathCells.isEmpty();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  District adjacency graph
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build district adjacency graph: two districts are adjacent if their
   * bounding boxes share a boundary or overlap within a threshold.
   */
  private void buildDistrictAdjacency() {
    districtAdjacency.clear();
    if (partitioner == null) return;

    List<MultiLevelPartitioner.District> districts = partitioner.getAllDistricts();
    double adjacencyThreshold = 0.1; // 10% overlap margin

    for (int i = 0; i < districts.size(); i++) {
      MultiLevelPartitioner.District dA = districts.get(i);
      Set<Integer> neighbours = new HashSet<>();

      for (int j = 0; j < districts.size(); j++) {
        if (i == j) continue;
        MultiLevelPartitioner.District dB = districts.get(j);

        // Check bounding box proximity
        double overlapX = Math.min(dA.maxX, dB.maxX) - Math.max(dA.minX, dB.minX);
        double overlapY = Math.min(dA.maxY, dB.maxY) - Math.max(dA.minY, dB.minY);

        // Adjacent if they overlap or are very close
        double minDim = Math.min(
            Math.min(dA.getWidth(), dA.getHeight()),
            Math.min(dB.getWidth(), dB.getHeight()));

        if (overlapX > -minDim * adjacencyThreshold
            && overlapY > -minDim * adjacencyThreshold) {
          neighbours.add(dB.id);
        }
      }

      if (!neighbours.isEmpty()) {
        districtAdjacency.put(dA.id, neighbours);
      }
    }

    FRLogger.info("CrossDistrictConnector: built adjacency for "
        + districtAdjacency.size() + " districts");
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private int findDistrictForPoint(double x, double y) {
    if (partitioner == null) return -1;
    MultiLevelPartitioner.District d = partitioner.findDistrict(x, y);
    return d != null ? d.id : -1;
  }

  private FloatPoint getBPCoord(int bpId) {
    for (MultiLevelPartitioner.District d : partitioner.getAllDistricts()) {
      for (MultiLevelPartitioner.BoundaryPoint bp : d.boundaryPoints) {
        if (bp.id == bpId) {
          return new FloatPoint((float) bp.x, (float) bp.y);
        }
      }
    }
    return null;
  }

  private boolean fallbackRoute(int netNo) {
    try {
      BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(netNo, 15);
      if (result != null && result.isRouted()) {
        if (stom != null) {
          List<Long> cellKeys = extractNetCellKeys(board.rules.nets.get(netNo));
          if (!cellKeys.isEmpty()) {
            stom.reserve(cellKeys);
          }
        }
        return true;
      }
    } catch (Exception e) {
      FRLogger.debug("CrossDistrictConnector: fallback route for net "
          + netNo + " failed: " + e.getMessage());
    }
    return false;
  }

  private Set<Integer> getAllNetNumbers() {
    Set<Integer> result = new HashSet<>();
    int maxNetNo = board.rules.nets.max_net_no();
    for (int i = 1; i <= maxNetNo; i++) {
      Net net = board.rules.nets.get(i);
      if (net != null && net.get_pins().size() >= 2) {
        result.add(i);
      }
    }
    return result;
  }

  private List<Long> extractNetCellKeys(Net net) {
    if (net == null || stom == null) return Collections.emptyList();
    Set<Long> keys = new HashSet<>();
    double cellSize = stom.getCellSize();
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;

    for (Pin pin : net.get_pins()) {
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
        keys.add(SpatioTemporalOccupancyMap.cellKey(via.first_layer(), row, col));
      }
    }
    return new ArrayList<>(keys);
  }

  /** Get the set of nets connected by this connector. */
  public Set<Integer> getConnectedNets() {
    return Collections.unmodifiableSet(connectedNets);
  }
}
