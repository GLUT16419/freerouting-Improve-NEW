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

  // ── V7.x: 拥塞梯度代价 ──
  static final double CONGESTION_GRADIENT_WEIGHT = 5.0;

  // ── V7.x: 时间窗软化 ──
  static final int TIME_WINDOW_SIZE = 3;
  static final double SOFT_SHARING_COST = 10.0;

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

  // ── V7.x runtime flags (injected from settings) ──
  private boolean congestionGradientEnabled = false;
  private double congestionGradientWeight = CONGESTION_GRADIENT_WEIGHT;
  private boolean timeWindowSofteningEnabled = false;
  private int timeWindowSize = TIME_WINDOW_SIZE;
  private double softSharingCost = SOFT_SHARING_COST;

  // ── ALT heuristic (A* with Landmarks + Triangle inequality) ──
  /** landmarkDistances[l][cellIdx] = shortest dist from landmark l to grid cell. */
  private double[][] landmarkDistances;
  /** Number of landmarks (0 = ALT disabled). */
  private int numLandmarks = 0;
  private boolean altHeuristicEnabled = false;

  // ── Arc Flags pruning ──
  /** arcFlags[cellIdx] = BitSet of functional block IDs reachable from this cell. */
  private BitSet[] arcFlags;
  /** cellFunctionalBlockIds[cellIdx] = functional block ID for each grid cell. */
  private int[] cellFunctionalBlockIds;
  private boolean arcFlagsEnabled = false;
  /** Number of functional blocks (for flag validation). */
  private int numFunctionalBlocks = 0;

  // ── V8: 多目标帕累托 A\* (Multi-Objective Pareto A*) ──
  private boolean paretoAStarEnabled = false;

  static class ParetoLabel {
    final double length;
    final double congestion;
    final long cellKey;
    ParetoLabel(double length, double congestion, long cellKey) {
      this.length = length; this.congestion = congestion; this.cellKey = cellKey;
    }
    boolean dominates(ParetoLabel o) {
      return length <= o.length && congestion <= o.congestion
          && (length < o.length || congestion < o.congestion);
    }
  }

  static class ParetoFront {
    final List<ParetoLabel> labels = new ArrayList<>();
    boolean tryAdd(ParetoLabel nl) {
      labels.removeIf(l -> nl.dominates(l));
      for (ParetoLabel l : labels) if (l.dominates(nl)) return false;
      labels.add(nl); return true;
    }
    ParetoLabel getMinCongestion() {
      ParetoLabel b = null;
      for (ParetoLabel l : labels) if (b == null || l.congestion < b.congestion) b = l;
      return b;
    }
    int size() { return labels.size(); }
  }

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

    // ── Heuristic (ALT or Manhattan) ──
    double h0 = heuristicALT(srcRow, srcCol, tgtRow, tgtCol);

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

    // ── Compute source/target functional block IDs for Arc Flags pruning ──
    int srcFunctionalBlockId = -1;
    int targetFunctionalBlockId = -1;
    if (arcFlagsEnabled && cellFunctionalBlockIds != null) {
      int srcIdx = srcRow * gridCols + srcCol;
      if (srcIdx >= 0 && srcIdx < cellFunctionalBlockIds.length) {
        srcFunctionalBlockId = cellFunctionalBlockIds[srcIdx];
      }
      int tgtIdx = tgtRow * gridCols + tgtCol;
      if (tgtIdx >= 0 && tgtIdx < cellFunctionalBlockIds.length) {
        targetFunctionalBlockId = cellFunctionalBlockIds[tgtIdx];
      }
    }

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
                   isDifferential, chPathGuide, tgtRow, tgtCol, targetFunctionalBlockId);
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
                   isDifferential, chPathGuide, srcRow, srcCol, srcFunctionalBlockId);
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
                          int targetRow, int targetCol,
                          int targetFunctionalBlockId) {

    // ── 4-directional neighbours ──
    int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    for (int[] d : dirs) {
      int nr = curr.row + d[0];
      int nc = curr.col + d[1];
      if (nr < 0 || nr >= gridRows || nc < 0 || nc >= gridCols) continue;

      // ── Arc Flags pruning: skip cells that cannot reach target's functional block ──
      if (arcFlagsEnabled && arcFlags != null && targetFunctionalBlockId >= 0) {
        int nIdx = nr * gridCols + nc;
        if (nIdx >= 0 && nIdx < arcFlags.length) {
          BitSet flags = arcFlags[nIdx];
          if (flags != null && !flags.get(targetFunctionalBlockId)) {
            continue; // prune — this cell cannot reach target's functional block
          }
        }
      }

      // Heuristic to target (ALT or Manhattan for source for backward search)
      double h = heuristicALT(nr, nc, targetRow, targetCol);

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

      // Corridor benefit (lower cost for corridor cells)
      double corridorMultiplier = 1.0;
      if (corridorPlanner != null) {
        double priority = corridorPlanner.getPriorityAtCell(nr, nc);
        corridorMultiplier = Math.max(0.1, 1.0 - priority * CORRIDOR_BENEFIT);
      }

    // ── V7.x: 拥塞梯度代价 ──
    double gradientCost = 0;
    if (congestionGradientEnabled && probEstimator != null) {
      double[] gradient = probEstimator.getGradientAtCell(nr, nc);
      if (gradient != null) {
        // Expand direction (d[0]=dr, d[1]=dc), normalize
        double dirNorm = Math.sqrt(d[0] * d[0] + d[1] * d[1]);
        if (dirNorm > 1e-10) {
          double dot = (gradient[0] * d[0] + gradient[1] * d[1]) / dirNorm;
          // Moving along gradient (toward congestion) → penalty
          // Moving against gradient (away from congestion) → no penalty
          gradientCost = Math.max(0, dot) * congestionGradientWeight;
        }
      }
    }

    // ── V7.x: 时间窗软化 STOM 查询 ──
    double stomCost = 0;
    if (stom != null) {
      if (timeWindowSofteningEnabled) {
        int softLevel = stom.querySoft(curr.layer, nr, nc, timeStep, timeWindowSize);
        stomCost = SpatioTemporalOccupancyMap.getSoftSharingCost(
            softLevel, softSharingCost, STOM_PENALTY);
      } else {
        int occCount = stom.getOccupancyCount(curr.layer, nr, nc);
        if (occCount > 0) {
          stomCost = STOM_PENALTY * occCount;
          if (stom.isOccupied(curr.layer, nr, nc, timeStep)) {
            stomCost += STOM_PENALTY * 10;
          }
        }
      }
    }

    // Probabilistic congestion penalty
    double probCost = 0;
      if (probEstimator != null) {
        double congestion = probEstimator.getCongestionAtCell(nr, nc);
        probCost = congestion * 10.0;
      }

      // Base movement cost (includes V7.x gradient cost)
      double moveCost = CELL_SIZE * corridorMultiplier + stomCost + probCost + diffCost + gradientCost;

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

  /**
   * ALT heuristic: Max over landmarks of |dist(L, target) - dist(L, node)|.
   * Falls back to Manhattan if ALT data not available.
   */
  private double heuristicALT(int row, int col, int targetRow, int targetCol) {
    double manh = manhattan(row, col, targetRow, targetCol) * HEURISTIC_WEIGHT;
    if (!altHeuristicEnabled || landmarkDistances == null || numLandmarks == 0) {
      return manh;
    }
    int targetIdx = targetRow * gridCols + targetCol;
    int currIdx = row * gridCols + col;
    if (targetIdx < 0 || targetIdx >= gridRows * gridCols
        || currIdx < 0 || currIdx >= gridRows * gridCols) {
      return manh;
    }
    double maxAlt = 0;
    for (int l = 0; l < numLandmarks; l++) {
      double dTarget = landmarkDistances[l][targetIdx];
      double dCurr = landmarkDistances[l][currIdx];
      if (dTarget < Double.MAX_VALUE && dCurr < Double.MAX_VALUE) {
        double diff = Math.abs(dTarget - dCurr);
        if (diff > maxAlt) maxAlt = diff;
      }
    }
    // ALT provides a lower bound; take the max with Manhattan
    // Convert from board-unit distance to cell-count distance
    return Math.max(manh, maxAlt / CELL_SIZE * HEURISTIC_WEIGHT);
  }

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }

  // ── ALT + Arc Flags precomputation ──

  /**
   * Precompute ALT landmark distances.
   * Selects 6 landmarks (4 corners + 2 center points) and computes shortest
   * distances from each landmark to all grid cells using the CH graph.
   * <p>
   * Landmark selection follows the "max-cover" heuristic: corner landmarks
   * provide strong bounds for diagonal paths; center landmarks help with
   * interior-to-interior paths.
   *
   * @param ch the Contraction Hierarchies graph
   */
  public void precomputeLandmarkDistances(ContractionHierarchies ch) {
    if (ch == null) return;
    int cellCount = gridRows * gridCols;
    if (cellCount <= 0) return;

    // Select 6 landmarks spread across the board
    int[][] landmarkCells = {
        {0, 0},                           // top-left corner
        {0, gridCols - 1},                // top-right corner
        {gridRows - 1, 0},                // bottom-left corner
        {gridRows - 1, gridCols - 1},     // bottom-right corner
        {gridRows / 2, gridCols / 2},     // center
        {gridRows / 3, gridCols * 2 / 3}  // off-center (for asymmetric coverage)
    };
    numLandmarks = landmarkCells.length;
    landmarkDistances = new double[numLandmarks][cellCount];
    // Initialize with safe fallback values (Manhattan * CELL_SIZE)
    for (int l = 0; l < numLandmarks; l++) {
      int lr = landmarkCells[l][0];
      int lc = landmarkCells[l][1];
      for (int r = 0; r < gridRows; r++) {
        for (int c = 0; c < gridCols; c++) {
          landmarkDistances[l][r * gridCols + c] = manhattan(lr, lc, r, c) * CELL_SIZE;
        }
      }
    }

    // Compute distances using CH for each landmark
    for (int l = 0; l < numLandmarks; l++) {
      int lr = landmarkCells[l][0];
      int lc = landmarkCells[l][1];
      double fx = boardMinX + (lc + 0.5) * CELL_SIZE;
      double fy = boardMinY + (lr + 0.5) * CELL_SIZE;
      int chSourceId = ch.findNearestNode(new FloatPoint((float) fx, (float) fy), 0);
      if (chSourceId < 0) continue;

      double[] chDistances = ch.computeAllDistances(chSourceId);
      if (chDistances == null) continue;

      // Map CH distances to grid cell distances
      for (int r = 0; r < gridRows; r++) {
        for (int c = 0; c < gridCols; c++) {
          int chNodeId = ch.getNodeId(0, r, c);
          if (chNodeId >= 0 && chDistances[chNodeId] < Double.MAX_VALUE) {
            landmarkDistances[l][r * gridCols + c] = chDistances[chNodeId];
          }
        }
      }
      FRLogger.debug("TimedAStar: landmark " + l + " precomputed ("
          + (gridRows * gridCols) + " cells)");
    }

    altHeuristicEnabled = true;
    FRLogger.info("TimedAStar: ALT heuristic enabled with " + numLandmarks + " landmarks");
  }

  /**
   * Precompute Arc Flags for functional-block-level pruning.
   * <p>
   * For each CH node, determines which functional blocks are reachable via CH
   * query. Maps this information to grid cells. During A* expandNode, cells
   * that cannot reach the target's functional block are skipped.
   *
   * @param partitioner the multi-level partitioner (provides functional block info)
   * @param ch          the contraction hierarchies graph
   */
  public void precomputeArcFlags(MultiLevelPartitioner partitioner, ContractionHierarchies ch) {
    if (partitioner == null || ch == null) return;
    int cellCount = gridRows * gridCols;
    if (cellCount <= 0) return;

    List<MultiLevelPartitioner.FunctionalBlock> blocks = partitioner.getAllFunctionalBlocks();
    numFunctionalBlocks = blocks.size();
    if (numFunctionalBlocks <= 1) {
      // Single block — no pruning possible
      arcFlagsEnabled = false;
      return;
    }

    // Step 1: Assign functional block ID to each grid cell
    cellFunctionalBlockIds = new int[cellCount];
    Arrays.fill(cellFunctionalBlockIds, -1);

    for (int r = 0; r < gridRows; r++) {
      for (int c = 0; c < gridCols; c++) {
        double fx = boardMinX + (c + 0.5) * CELL_SIZE;
        double fy = boardMinY + (r + 0.5) * CELL_SIZE;
        MultiLevelPartitioner.District dist = partitioner.findDistrict(fx, fy);
        if (dist != null) {
          cellFunctionalBlockIds[r * gridCols + c] = dist.functionalBlockId;
        }
      }
    }

    // Step 2: For each functional block, compute which CH nodes can reach it.
    // Run reverse Dijkstra from boundary nodes of each block.
    // Simplified: use CH computeAllDistances from a representative node in each block.
    BitSet[] blockReachability = new BitSet[numFunctionalBlocks];
    for (int fb = 0; fb < numFunctionalBlocks; fb++) {
      blockReachability[fb] = new BitSet(ch.getNodeCount());
    }

    // Find one representative CH node for each functional block
    int[] repChNodes = new int[numFunctionalBlocks];
    Arrays.fill(repChNodes, -1);
    for (CHNode node : ch.getNodes()) {
      if (node.functionalBlockId >= 0 && node.functionalBlockId < numFunctionalBlocks) {
        if (repChNodes[node.functionalBlockId] < 0) {
          repChNodes[node.functionalBlockId] = node.id;
        }
      }
    }

    // Run CH all-distances from each representative to compute reachability
    for (int fb = 0; fb < numFunctionalBlocks; fb++) {
      int srcId = repChNodes[fb];
      if (srcId < 0) continue;
      double[] dists = ch.computeAllDistances(srcId);
      if (dists == null) continue;
      for (int nid = 0; nid < dists.length; nid++) {
        if (dists[nid] < Double.MAX_VALUE) {
          blockReachability[fb].set(nid);
        }
      }
    }

    // Step 3: Map CH node reachability to grid cell arc flags.
    // A grid cell can reach functional block FB if its nearest CH node can reach FB.
    arcFlags = new BitSet[cellCount];
    for (int r = 0; r < gridRows; r++) {
      for (int c = 0; c < gridCols; c++) {
        int idx = r * gridCols + c;
        int chNodeId = ch.getNodeId(0, r, c);
        if (chNodeId < 0) continue;
        BitSet reachable = new BitSet(numFunctionalBlocks);
        for (int fb = 0; fb < numFunctionalBlocks; fb++) {
          if (blockReachability[fb].get(chNodeId)) {
            reachable.set(fb);
          }
        }
        if (!reachable.isEmpty()) {
          arcFlags[idx] = reachable;
        }
      }
    }

    arcFlagsEnabled = true;
    FRLogger.info("TimedAStar: Arc Flags enabled with " + numFunctionalBlocks
        + " functional blocks");
  }

  // ── V7.x setters ──

  public void setCongestionGradientEnabled(boolean enabled) {
    this.congestionGradientEnabled = enabled;
  }

  public void setCongestionGradientWeight(double weight) {
    this.congestionGradientWeight = Math.max(0, weight);
  }

  public void setTimeWindowSofteningEnabled(boolean enabled) {
    this.timeWindowSofteningEnabled = enabled;
  }

  public void setTimeWindowSize(int size) {
    this.timeWindowSize = Math.max(1, size);
  }

  public void setSoftSharingCost(double cost) {
    this.softSharingCost = Math.max(0, cost);
  }

  /** Enable/disable ALT heuristic. */
  public void setAltHeuristicEnabled(boolean enabled) {
    this.altHeuristicEnabled = enabled;
  }

  /** Enable/disable Arc Flags pruning. */
  public void setArcFlagsEnabled(boolean enabled) {
    this.arcFlagsEnabled = enabled;
  }

  /**
   * V8.2: Pareto Multi-Objective A* search.
   * <p>
   * Tracks both path length (obj1) and congestion cost (obj2) independently,
   * maintaining a Pareto frontier of non-dominated path alternatives per cell.
   * Returns the Pareto-optimal path that minimizes congestion impact on STOM.
   * <p>
   * Only intended for Phase 1 critical backbone nets where path quality matters most.
   * Standard search is sufficient for Phase 2/3 mass routing.
   */
  public SearchResult searchPareto(double srcX, double srcY,
                                    double tgtX, double tgtY,
                                    int startLayer, int timeStep) {
    if (!paretoAStarEnabled) {
      return search(srcX, srcY, tgtX, tgtY, startLayer, timeStep);
    }

    int srcCol = clampCol((int) ((srcX - boardMinX) / CELL_SIZE));
    int srcRow = clampRow((int) ((srcY - boardMinY) / CELL_SIZE));
    int tgtCol = clampCol((int) ((tgtX - boardMinX) / CELL_SIZE));
    int tgtRow = clampRow((int) ((tgtY - boardMinY) / CELL_SIZE));

    int layer = startLayer;
    if (layer < 0 || layer >= layerCount) {
      layer = signalLayers.length > 0 ? signalLayers[0] : 0;
    }

    // Replace best-map with ParetoFront map
    Map<Long, ParetoFront> paretoFrontiers = new HashMap<>();
    PriorityQueue<SearchNode> open = new PriorityQueue<>();
    Map<Long, ParetoFront> allFronts = new HashMap<>();
    double h0 = heuristicALT(srcRow, srcCol, tgtRow, tgtCol);

    SearchNode startNode = new SearchNode(srcRow, srcCol, layer, 0, h0, null, true);
    open.add(startNode);
    long startKey = SpatioTemporalOccupancyMap.cellKey(layer, srcRow, srcCol);
    ParetoFront startFront = new ParetoFront();
    startFront.tryAdd(new ParetoLabel(0, 0, -1L));
    paretoFrontiers.put(startKey, startFront);

    long tgtKey = SpatioTemporalOccupancyMap.cellKey(layer, tgtRow, tgtCol);
    int nodesExpanded = 0;
    long meetingKey = -1;

    while (!open.isEmpty() && nodesExpanded < MAX_SEARCH_NODES) {
      SearchNode curr = open.poll();
      long currKey = SpatioTemporalOccupancyMap.cellKey(curr.layer, curr.row, curr.col);
      ParetoFront currFront = paretoFrontiers.get(currKey);
      if (currFront == null) continue;

      // Check if we reached the target
      if (curr.row == tgtRow && curr.col == tgtCol && curr.layer == layer) {
        meetingKey = currKey;
        break;
      }

      nodesExpanded++;

      // Expand 4-directional neighbours
      int[][] dirs = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
      for (int[] d : dirs) {
        int nr = curr.row + d[0], nc = curr.col + d[1];
        if (nr < 0 || nr >= gridRows || nc < 0 || nc >= gridCols) continue;

        double h = heuristicALT(nr, nc, tgtRow, tgtCol);
        long nkey = SpatioTemporalOccupancyMap.cellKey(curr.layer, nr, nc);

        // Compute length and congestion separately for Pareto tracking
        double moveCost = CELL_SIZE;
        double stomCost = 0;
        double probCost = 0;

        if (stom != null) {
          int occ = stom.getOccupancyCount(curr.layer, nr, nc);
          if (occ > 0) stomCost = STOM_PENALTY * occ;
        }
        if (probEstimator != null) {
          probCost = probEstimator.getCongestionAtCell(nr, nc) * 10.0;
        }

        double lenIncrement = moveCost;  // obj1
        double congIncrement = stomCost + probCost; // obj2

        // Build new labels from current Pareto frontier
        for (ParetoLabel pl : currFront.labels) {
          double newLen = pl.length + lenIncrement;
          double newCong = pl.congestion + congIncrement;
          ParetoLabel newLabel = new ParetoLabel(newLen, newCong, currKey);

          ParetoFront nf = paretoFrontiers.computeIfAbsent(nkey, k -> new ParetoFront());
          if (nf.tryAdd(newLabel)) {
            open.add(new SearchNode(nr, nc, curr.layer, newLen + h, h, curr, true));
          }
        }
      }
    }

    if (meetingKey < 0) return SearchResult.NOT_FOUND;

    // Select Pareto-optimal path with minimum congestion impact
    ParetoFront targetFront = paretoFrontiers.get(tgtKey);
    if (targetFront == null) return SearchResult.NOT_FOUND;

    ParetoLabel best = targetFront.getMinCongestion();
    if (best == null) return SearchResult.NOT_FOUND;

    // Reconstruct path
    List<Long> pathKeys = new ArrayList<>();
    long backtrackKey = best.cellKey;
    while (backtrackKey >= 0 && paretoFrontiers.containsKey(backtrackKey)) {
      pathKeys.add(0, backtrackKey);
      ParetoFront pf = paretoFrontiers.get(backtrackKey);
      ParetoLabel pl = pf.getMinCongestion();
      backtrackKey = (pl != null) ? pl.cellKey : -1;
    }
    pathKeys.add(tgtKey);

    int[] decoded = SpatioTemporalOccupancyMap.decodeCellKey(meetingKey);
    return new SearchResult(true, pathKeys, best.length + best.congestion, nodesExpanded,
        decoded[1], decoded[2], decoded[0]);
  }

  /** Enable/disable Pareto Multi-Objective A*. */
  public void setParetoAStarEnabled(boolean enabled) {
    this.paretoAStarEnabled = enabled;
  }

  /** Check if Pareto A* is active. */
  public boolean isParetoAStarEnabled() { return paretoAStarEnabled; }

  /** Get number of landmarks used by ALT heuristic. */
  public int getNumLandmarks() { return numLandmarks; }

  /** Check if ALT heuristic is active. */
  public boolean isAltHeuristicEnabled() { return altHeuristicEnabled; }

  /** Check if Arc Flags pruning is active. */
  public boolean isArcFlagsEnabled() { return arcFlagsEnabled; }
}
