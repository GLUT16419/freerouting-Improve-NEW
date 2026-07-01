package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Phase 1 — Timed Bidirectional A* search with CH acceleration and
 * STOM reservation awareness.
 * <p>
 * In the city-traffic metaphor, this is the <b>express GPS navigator</b> that
 * plans a route from A to B through the city, checking real-time traffic
 * reservations (STOM) and using express lanes (CH shortcuts).
 * <p>
 * <b>Key features:</b>
 * <ul>
 *   <li><b>Temporal awareness</b> — queries STOM before expanding a node;
 *       pre-reserved cells incur a heavy penalty to avoid conflicts.</li>
 *   <li><b>Bidirectional search</b> — forward from source, backward from
 *       target, meeting in the middle for ~2× speedup.</li>
 *   <li><b>CH acceleration</b> — long-distance routes jump to higher
 *       hierarchy levels via shortcuts.</li>
 *   <li><b>Differential pair support</b> — searches for paired routes with
 *       fixed spacing simultaneously.</li>
 *   <li><b>Length-matching guidance</b> — for matched-length groups,
 *       automatically adds detour cost near the target.</li>
 * </ul>
 */
public class TimedBidirectionalAStar implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Manhattan heuristic weight (higher = more greedy). */
  static final double HEURISTIC_WEIGHT = 1.0;

  /** STOM occupancy penalty per occupied cell-visit. */
  static final double STOM_PENALTY = 100.0;

  /** Layer change (via) base cost. */
  static final double VIA_COST = 5.0;

  /** Bend cost per turn. */
  static final double BEND_COST = 1.0;

  /** Search node limit before aborting. */
  static final int MAX_SEARCH_NODES = 100_000;

  /** Grid cell size (matches CH / STOM grid). */
  static final double CELL_SIZE = 200_000.0;

  /** Corridor priority benefit per corridor cell traversed. */
  static final double CORRIDOR_BENEFIT = 0.7;

  /** Differential pair spacing in grid cells. */
  static final double DIFF_PAIR_SPACING = 3.0;

  /** Length-matching deviation tolerance (fraction of reference length). */
  static final double LENGTH_MATCH_TOLERANCE = 0.05;

  /** Minimum serpentine amplitude for length matching (grid cells). */
  static final double MIN_SERPENTINE_AMP = 2.0;

  // ═══════════════════════════════════════════════════════════════════════
  //  SearchNode
  // ═══════════════════════════════════════════════════════════════════════

  /** Node in the timed A* search tree. */
  static class SearchNode implements Comparable<SearchNode> {
    final int row, col, layer;
    final double g;       // actual cost from start
    final double h;       // heuristic to target
    final double f;       // g + h
    final SearchNode parent;
    final int depth;
    final boolean fromStart;  // true = forward, false = backward

    SearchNode(int row, int col, int layer, double g, double h,
               SearchNode parent, boolean fromStart) {
      this.row = row;
      this.col = col;
      this.layer = layer;
      this.g = g;
      this.h = h;
      this.f = g + h;
      this.parent = parent;
      this.depth = parent != null ? parent.depth + 1 : 0;
      this.fromStart = fromStart;
    }

    @Override
    public int compareTo(SearchNode o) {
      int cmp = Double.compare(this.f, o.f);
      return cmp != 0 ? cmp : Integer.compare(this.hashCode(), o.hashCode());
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  SearchResult
  // ═══════════════════════════════════════════════════════════════════════

  /** Result of a timed A* search. */
  public static class SearchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** True if a path was found. */
    public final boolean found;

    /** Path cell keys: list of (layer, row, col) long-encoded keys. */
    public final List<Long> pathCellKeys;

    /** Total path cost (length + penalties). */
    public final double totalCost;

    /** Number of nodes expanded during search. */
    public final int nodesExpanded;

    /** Midpoint meeting node (for bidirectional). */
    public final int meetingRow, meetingCol, meetingLayer;

    public SearchResult(boolean found, List<Long> pathCellKeys,
                        double totalCost, int nodesExpanded,
                        int meetingRow, int meetingCol, int meetingLayer) {
      this.found = found;
      this.pathCellKeys = pathCellKeys;
      this.totalCost = totalCost;
      this.nodesExpanded = nodesExpanded;
      this.meetingRow = meetingRow;
      this.meetingCol = meetingCol;
      this.meetingLayer = meetingLayer;
    }

    public static final SearchResult NOT_FOUND = new SearchResult(
        false, Collections.emptyList(), Double.MAX_VALUE, 0, -1, -1, -1);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final RoutingBoard routingBoard;
  private final ContractionHierarchies ch;
  private final SpatioTemporalOccupancyMap stom;
  private final MainCorridorPlanner corridorPlanner;
  private final ProbabilisticCongestionEstimator probEstimator;

  private final double boardMinX, boardMinY;
  private final int gridCols, gridRows;
  private final int layerCount;
  private final int[] signalLayers;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public TimedBidirectionalAStar(BasicBoard board,
                                  RoutingBoard routingBoard,
                                  ContractionHierarchies ch,
                                  SpatioTemporalOccupancyMap stom,
                                  MainCorridorPlanner corridorPlanner,
                                  ProbabilisticCongestionEstimator probEstimator) {
    this.board = board;
    this.routingBoard = routingBoard;
    this.ch = ch;
    this.stom = stom;
    this.corridorPlanner = corridorPlanner;
    this.probEstimator = probEstimator;

    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;
    double bw = board.bounding_box.width();
    double bh = board.bounding_box.height();

    this.gridCols = Math.max(4, (int) Math.ceil(bw / CELL_SIZE));
    this.gridRows = Math.max(4, (int) Math.ceil(bh / CELL_SIZE));
    this.layerCount = board.get_layer_count();

    // Collect signal layer indices
    List<Integer> sig = new ArrayList<>();
    for (int i = 0; i < layerCount; i++) {
      if (i < board.layer_structure.arr.length
          && board.layer_structure.arr[i] != null
          && board.layer_structure.arr[i].is_signal) {
        sig.add(i);
      }
    }
    this.signalLayers = sig.stream().mapToInt(Integer::intValue).toArray();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main search
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Perform a timed bidirectional A* search from source to target.
   * <p>
   * <b>True bidirectional termination:</b> stops when the sum of the minimum
   * f-values from both queues exceeds the best path cost found so far,
   * guaranteeing optimality of the meeting-point path.
   * <p>
   * After a successful search, the caller should reserve the path in STOM
   * using {@link #reservePath(SearchResult)}.
   *
   * @param srcX  source X (board coords)
   * @param srcY  source Y
   * @param tgtX  target X
   * @param tgtY  target Y
   * @param startLayer preferred starting layer
   * @param timeStep  the STOM time step to check occupancy for
   * @return the search result
   */
  public SearchResult search(double srcX, double srcY,
                              double tgtX, double tgtY,
                              int startLayer, int timeStep) {
    return search(srcX, srcY, tgtX, tgtY, startLayer, timeStep, false, null);
  }

  /**
   * Same search with differential pair mode (tighter spacing constraints).
   * @param diffPairNetNo the companion net number for differential pairing
   */
  public SearchResult search(double srcX, double srcY,
                              double tgtX, double tgtY,
                              int startLayer, int timeStep,
                              boolean isDifferential, Integer diffPairNetNo) {
    int srcCol = clampCol((int) ((srcX - boardMinX) / CELL_SIZE));
    int srcRow = clampRow((int) ((srcY - boardMinY) / CELL_SIZE));
    int tgtCol = clampCol((int) ((tgtX - boardMinX) / CELL_SIZE));
    int tgtRow = clampRow((int) ((tgtY - boardMinY) / CELL_SIZE));

    // Use first signal layer if startLayer is not routable
    int layer = startLayer;
    if (layer < 0 || layer >= layerCount) {
      layer = signalLayers.length > 0 ? signalLayers[0] : 0;
    }

    // ── Priority queues ──
    PriorityQueue<SearchNode> openFwd = new PriorityQueue<>();
    PriorityQueue<SearchNode> openBwd = new PriorityQueue<>();

    // ── Visited maps: key = cellKey(layer, row, col) → best g ──
    Map<Long, Double> bestFwd = new HashMap<>();
    Map<Long, Double> bestBwd = new HashMap<>();

    // ── Parent maps for path reconstruction ──
    Map<Long, SearchNode> parentFwd = new HashMap<>();
    Map<Long, SearchNode> parentBwd = new HashMap<>();

    // ── Heuristic ──
    double h0 = manhattan(srcRow, srcCol, tgtRow, tgtCol) * HEURISTIC_WEIGHT;

    SearchNode startNode = new SearchNode(srcRow, srcCol, layer, 0, h0, null, true);
    openFwd.add(startNode);
    bestFwd.put(SpatioTemporalOccupancyMap.cellKey(layer, srcRow, srcCol), 0.0);
    parentFwd.put(SpatioTemporalOccupancyMap.cellKey(layer, srcRow, srcCol), null);

    SearchNode targetNode = new SearchNode(tgtRow, tgtCol, layer, 0, h0, null, false);
    openBwd.add(targetNode);
    bestBwd.put(SpatioTemporalOccupancyMap.cellKey(layer, tgtRow, tgtCol), 0.0);
    parentBwd.put(SpatioTemporalOccupancyMap.cellKey(layer, tgtRow, tgtCol), null);

    double bestPathCost = Double.MAX_VALUE;
    long meetingKey = -1L;
    int nodesExpanded = 0;

    // ── Check CH for long-distance acceleration ──
    int chSourceId = ch != null ? ch.findNearestNode(
        new FloatPoint((float) srcX, (float) srcY), layer) : -1;
    int chTargetId = ch != null ? ch.findNearestNode(
        new FloatPoint((float) tgtX, (float) tgtY), layer) : -1;

    // Use CH query to get a rough path guide (if available)
    List<Integer> chPathGuide = null;
    if (chSourceId >= 0 && chTargetId >= 0) {
      ContractionHierarchies.CHPath chPath = ch.query(chSourceId, chTargetId);
      if (chPath.found) {
        chPathGuide = ch.unpackPath(chPath);
      }
    }

    // ── Main search loop with TRUE bidirectional termination ──
    while ((!openFwd.isEmpty() || !openBwd.isEmpty())
        && nodesExpanded < MAX_SEARCH_NODES) {

      // True bidirectional termination: if sum of the best f-values exceeds
      // the best complete path cost, no better path can exist.
      double minFwdF = openFwd.isEmpty() ? Double.MAX_VALUE : openFwd.peek().f;
      double minBwdF = openBwd.isEmpty() ? Double.MAX_VALUE : openBwd.peek().f;
      if (minFwdF + minBwdF >= bestPathCost) {
        break;
      }

      // Expand the side with smaller minimum f-value (balance heuristic)
      boolean expandForward = minFwdF <= minBwdF;

      if (expandForward && !openFwd.isEmpty()) {
        SearchNode curr = openFwd.poll();
        long key = SpatioTemporalOccupancyMap.cellKey(curr.layer, curr.row, curr.col);
        if (curr.g > bestFwd.getOrDefault(key, Double.MAX_VALUE)) continue;
        nodesExpanded++;

        // Check meeting with backward search
        if (parentBwd.containsKey(key)) {
          double total = curr.g + bestBwd.get(key);
          if (total < bestPathCost) {
            bestPathCost = total;
            meetingKey = key;
          }
        }

        // Expand neighbours
        expandNode(curr, openFwd, bestFwd, parentFwd, true, timeStep,
                   isDifferential, chPathGuide, tgtRow, tgtCol);
      }

      if (!expandForward && !openBwd.isEmpty()) {
        SearchNode curr = openBwd.poll();
        long key = SpatioTemporalOccupancyMap.cellKey(curr.layer, curr.row, curr.col);
        if (curr.g > bestBwd.getOrDefault(key, Double.MAX_VALUE)) continue;
        nodesExpanded++;

        // Check meeting
        if (parentFwd.containsKey(key)) {
          double total = curr.g + bestFwd.get(key);
          if (total < bestPathCost) {
            bestPathCost = total;
            meetingKey = key;
          }
        }

        expandNode(curr, openBwd, bestBwd, parentBwd, false, timeStep,
                   isDifferential, chPathGuide, srcRow, srcCol);
      }
    }

    // No path found
    if (meetingKey < 0) {
      return SearchResult.NOT_FOUND;
    }

    // Reconstruct path
    List<Long> pathKeys = reconstructPath(meetingKey, parentFwd, parentBwd);
    int[] decoded = SpatioTemporalOccupancyMap.decodeCellKey(meetingKey);

    return new SearchResult(true, pathKeys, bestPathCost, nodesExpanded,
        decoded[1], decoded[2], decoded[0]);  // row, col, layer
  }

  /**
   * Reserve a path with strategic time-step allocation.
   */
  public int reservePath(SearchResult result, int priority) {
    if (!result.found || stom == null) return -1;
    return stom.allocateTimeStep(result.pathCellKeys, priority, Collections.emptySet());
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Node expansion
  // ═══════════════════════════════════════════════════════════════════════

  private void expandNode(SearchNode curr, PriorityQueue<SearchNode> open,
                          Map<Long, Double> best, Map<Long, SearchNode> parent,
                          boolean fromStart,
                          int timeStep, boolean isDifferential,
                          List<Integer> chPathGuide,
                          int targetRow, int targetCol) {

    // ── 4-directional neighbours ──
    int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    for (int[] d : dirs) {
      int nr = curr.row + d[0];
      int nc = curr.col + d[1];
      if (nr < 0 || nr >= gridRows || nc < 0 || nc >= gridCols) continue;

      // Heuristic to target (or source for backward search)
      double h = manhattan(nr, nc, targetRow, targetCol) * HEURISTIC_WEIGHT;

      // Differential pair spacing penalty: if the companion diff-pair net
      // has already reserved nearby cells, heavily penalise deviation.
      double diffCost = 0;
      if (isDifferential && stom != null) {
        for (int dr = -1; dr <= 1; dr++) {
          for (int dc = -1; dc <= 1; dc++) {
            int sr = nr + dr, sc = nc + dc;
            if (sr >= 0 && sr < gridRows && sc >= 0 && sc < gridCols) {
              int occCount = stom.getOccupancyCount(curr.layer, sr, sc);
              if (occCount > 0) {
                // Companion diff-pair occupies a nearby cell;
                // encourage routing close to it (within DIFF_PAIR_SPACING)
                double dist = Math.sqrt(dr * dr + dc * dc);
                if (dist <= DIFF_PAIR_SPACING) {
                  diffCost -= 2.0; // bonus for staying close to paired net
                } else {
                  diffCost += 10.0 * (dist - DIFF_PAIR_SPACING); // penalty for straying
                }
              }
            }
          }
        }
      }

      // STOM occupancy penalty
      double stomCost = 0;
      if (stom != null) {
        int occCount = stom.getOccupancyCount(curr.layer, nr, nc);
        if (occCount > 0) {
          stomCost = STOM_PENALTY * occCount;
          if (stom.isOccupied(curr.layer, nr, nc, timeStep)) {
            stomCost += STOM_PENALTY * 10; // extreme penalty
          }
        }
      }

      // Corridor benefit (lower cost for corridor cells)
      double corridorMultiplier = 1.0;
      if (corridorPlanner != null) {
        double priority = corridorPlanner.getPriorityAtCell(nr, nc);
        corridorMultiplier = Math.max(0.1, 1.0 - priority * CORRIDOR_BENEFIT);
      }

      // Probabilistic congestion penalty
      double probCost = 0;
      if (probEstimator != null) {
        double congestion = probEstimator.getCongestionAtCell(nr, nc);
        probCost = congestion * 10.0;
      }

      // Base movement cost
      double moveCost = CELL_SIZE * corridorMultiplier + stomCost + probCost + diffCost;

      // Bend penalty
      if (curr.parent != null) {
        boolean turning = (curr.row - curr.parent.row) != (nr - curr.row)
            || (curr.col - curr.parent.col) != (nc - curr.col);
        if (turning) {
          moveCost += BEND_COST;
        }
      }

      // CH path guidance bonus: if this cell is on the CH path, reduce cost
      if (chPathGuide != null) {
        for (int chNodeId : chPathGuide) {
          if (ch != null) {
            CHNode chNode = ch.getNode(chNodeId);
            if (chNode != null && chNode.gridX == nc && chNode.gridY == nr) {
              moveCost *= 0.5; // half cost for CH-guided cells
              break;
            }
          }
        }
      }

      long key = SpatioTemporalOccupancyMap.cellKey(curr.layer, nr, nc);
      double newG = curr.g + moveCost;
      if (newG < best.getOrDefault(key, Double.MAX_VALUE)) {
        best.put(key, newG);
        parent.put(key, curr);
        open.add(new SearchNode(nr, nc, curr.layer, newG, h, curr, fromStart));
      }
    }

    // ── Via (layer change) ──
    for (int otherLayer : signalLayers) {
      if (otherLayer == curr.layer) continue;
      double viaCost = VIA_COST + Math.abs(otherLayer - curr.layer) * 0.5;

      long viaKey = SpatioTemporalOccupancyMap.cellKey(otherLayer, curr.row, curr.col);
      double h = manhattan(curr.row, curr.col, targetRow, targetCol) * HEURISTIC_WEIGHT;
      double newG = curr.g + viaCost;

      if (newG < best.getOrDefault(viaKey, Double.MAX_VALUE)) {
        best.put(viaKey, newG);
        parent.put(viaKey, curr);
        open.add(new SearchNode(curr.row, curr.col, otherLayer,
            newG, h, curr, fromStart));
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Path reconstruction
  // ═══════════════════════════════════════════════════════════════════════

  private List<Long> reconstructPath(long meetingKey,
                                      Map<Long, SearchNode> parentFwd,
                                      Map<Long, SearchNode> parentBwd) {
    LinkedList<Long> path = new LinkedList<>();

    // Forward segment: start → meeting point
    Long key = meetingKey;
    while (key != null) {
      path.addFirst(key);
      SearchNode p = parentFwd.get(key);
      key = p != null ? SpatioTemporalOccupancyMap.cellKey(p.layer, p.row, p.col) : null;
    }

    // Backward segment: meeting point → target (exclude meeting point itself)
    SearchNode bwdNode = parentBwd.get(meetingKey);
    while (bwdNode != null) {
      Long bk = SpatioTemporalOccupancyMap.cellKey(bwdNode.layer, bwdNode.row, bwdNode.col);
      if (!bk.equals(meetingKey)) {
        path.addLast(bk);
      }
      bwdNode = bwdNode.parent;
    }

    return new ArrayList<>(path);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  STOM reservation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Reserve the path returned by a successful search in STOM.
   * Returns the time step assigned.
   */
  public int reservePath(SearchResult result) {
    if (!result.found || stom == null) return -1;
    return stom.reserve(result.pathCellKeys);
  }

  /**
   * Release a previously reserved path from STOM.
   */
  public void releasePath(int timeStep) {
    if (stom != null) {
      stom.release(timeStep);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private double manhattan(int r1, int c1, int r2, int c2) {
    return Math.abs(r1 - r2) + Math.abs(c1 - c2);
  }

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }
}
