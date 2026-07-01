package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;

/**
 * UTPR Enhanced: Congestion-aware cost model with STOM occupancy,
 * CH hierarchy acceleration, probabilistic congestion estimation,
 * and corridor-priority integration.
 * <p>
 * This serves as the unified cost function for all UTPR phases:
 * <ul>
 *   <li>Base length + direction + bend costs (from V6)</li>
 *   <li>STOM pre-reservation occupancy penalty</li>
 *   <li>CH hierarchy-level-aware shortcut bonus</li>
 *   <li>Probabilistic congestion estimate from Gaussian diffusion</li>
 *   <li>Main corridor priority multiplier (cheaper on main roads)</li>
 *   <li>Historical conflict cost from incremental rerouting</li>
 * </ul>
 */
public class CostModel implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Weight constants ──────────────────────────────────────────────────

  private static final double BASE_LENGTH_WEIGHT = 1.0;
  private static final double DIRECTION_WEIGHT = 0.3;
  private static final double BEND_WEIGHT = 0.5;
  private static final double VIA_WEIGHT = 2.0;
  private static final double CONGESTION_WEIGHT = 1.5;
  private static final double HISTORICAL_WEIGHT = 0.8;
  private static final double RESERVATION_WEIGHT = 0.6;
  private static final double POWER_GND_VIA_DISCOUNT = 0.3;

  // ── UTPR weight constants ─────────────────────────────────────────────

  /** STOM occupancy penalty weight (pre-reserved cells). */
  private static final double STOM_OCCUPANCY_WEIGHT = 100.0;

  /** CH hierarchy bonus (higher-level nodes cheaper to traverse). */
  private static final double CH_LEVEL_BONUS = 0.8;

  /** Corridor priority cost multiplier (lower = cheaper on corridors). */
  private static final double CORRIDOR_MIN_MULTIPLIER = 0.3;

  // ── Fields ────────────────────────────────────────────────────────────

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final int layerCount;

  private final double[] horizontalCost;
  private final double[] verticalCost;
  private final double[] bendCost;
  private final double[][] viaCosts;
  private final boolean[] isLayerRoutable;
  private final double[][] reservationMap;

  // Board bounding box cached
  private final double bbMinX;
  private final double bbMinY;

  // ── UTPR integration fields (nullable — optional) ─────────────────────

  /** STOM for pre-reservation conflict avoidance. */
  private transient SpatioTemporalOccupancyMap stom;

  /** CH for layer-aware routing cost modification. */
  private transient ContractionHierarchies ch;

  /** Probabilistic congestion estimator for future-looking cost. */
  private transient ProbabilisticCongestionEstimator probEstimator;

  /** Corridor planner for priority-based cost reduction. */
  private transient MainCorridorPlanner corridorPlanner;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public CostModel(BasicBoard board, CongestionMap congestionMap) {
    this.board = board;
    this.congestionMap = congestionMap;
    this.layerCount = board.get_layer_count();
    this.bbMinX = board.bounding_box.ll.to_float().x;
    this.bbMinY = board.bounding_box.ll.to_float().y;

    this.horizontalCost = new double[layerCount];
    this.verticalCost = new double[layerCount];
    this.bendCost = new double[layerCount];
    this.isLayerRoutable = new boolean[layerCount];
    this.viaCosts = new double[layerCount][layerCount];
    this.reservationMap = new double[layerCount][];

    initializeCosts();
  }

  private void initializeCosts() {
    for (int i = 0; i < layerCount; i++) {
      boolean isSignal = i < board.layer_structure.arr.length
          && board.layer_structure.arr[i] != null
          && board.layer_structure.arr[i].is_signal;
      isLayerRoutable[i] = isSignal;
      if (isSignal) {
        boolean preferredHorizontal = (i % 2 == 1);
        horizontalCost[i] = preferredHorizontal ? 1.0 : 1.3;
        verticalCost[i] = preferredHorizontal ? 1.3 : 1.0;
        bendCost[i] = 0.5;
      } else {
        horizontalCost[i] = 10.0;
        verticalCost[i] = 10.0;
        bendCost[i] = 10.0;
      }
      for (int j = 0; j < layerCount; j++) {
        viaCosts[i][j] = (i == j) ? 0.0 : VIA_WEIGHT * Math.abs(i - j);
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  UTPR integration setters
  // ═══════════════════════════════════════════════════════════════════════

  /** Attach a STOM instance for pre-reservation conflict avoidance. */
  public void setStom(SpatioTemporalOccupancyMap stom) {
    this.stom = stom;
  }

  /** Attach a CH instance for hierarchy-aware cost modification. */
  public void setCH(ContractionHierarchies ch) {
    this.ch = ch;
  }

  /** Attach a probabilistic congestion estimator. */
  public void setProbEstimator(ProbabilisticCongestionEstimator probEstimator) {
    this.probEstimator = probEstimator;
  }

  /** Attach a main corridor planner. */
  public void setCorridorPlanner(MainCorridorPlanner corridorPlanner) {
    this.corridorPlanner = corridorPlanner;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Core cost computation (original V6 + UTPR enhancements)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Compute the cost of a single routing segment, including UTPR enhancements
   * (STOM occupancy, corridor priority, probabilistic congestion).
   */
  public double computeSegmentCost(FloatPoint from, FloatPoint to, int layer,
                                   boolean isPowerOrGround, double netPriority) {
    if (!isLayerRoutable[layer]) return Double.POSITIVE_INFINITY;

    double dx = Math.abs(to.x - from.x);
    double dy = Math.abs(to.y - from.y);
    double length = Math.sqrt(dx * dx + dy * dy);
    if (length < 1e-6) return 0.0;

    // ── Base cost (length + direction) ──
    double cost = BASE_LENGTH_WEIGHT * length;
    double directionCost = DIRECTION_WEIGHT * length
        * (dx > dy ? horizontalCost[layer] : verticalCost[layer]);
    cost += directionCost;

    // ── V6 congestion cost from CongestionMap ──
    FloatPoint mid = new FloatPoint((from.x + to.x) / 2.0f,
                                    (from.y + to.y) / 2.0f);
    double congestion = congestionMap.getCongestion(mid, layer);
    cost += CONGESTION_WEIGHT * length * congestion * congestion;

    // ── V6 historical cost ──
    double historical = congestionMap.getHistoricalCost(mid, layer);
    cost += HISTORICAL_WEIGHT * historical;

    // ═══════════════════════════════════════════════════════════════════
    //  UTPR enhancements
    // ═══════════════════════════════════════════════════════════════════

    // ── STOM occupancy cost ──
    if (stom != null) {
      int occCount = stom.getOccupancyCount(mid.x, mid.y, layer);
      if (occCount > 0) {
        cost += STOM_OCCUPANCY_WEIGHT * occCount * length;
      }
    }

    // ── Probabilistic congestion cost ──
    if (probEstimator != null) {
      double probCong = probEstimator.getCongestionAt(mid.x, mid.y);
      if (probCong > 0.1) {
        cost += CONGESTION_WEIGHT * length * probCong * 3.0;
      }
    }

    // ── Corridor priority benefit ──
    if (corridorPlanner != null) {
      double multiplier = corridorPlanner.getCorridorMultiplier(mid.x, mid.y);
      // multiplier < 1.0 means corridor benefit → reduce cost
      cost *= multiplier;
    }

    // ── CH hierarchy bonus (higher level = cheaper traversals) ──
    if (ch != null) {
      int chNodeId = ch.findNearestNode(mid, layer);
      if (chNodeId >= 0) {
        CHNode chNode = ch.getNode(chNodeId);
        if (chNode != null && chNode.level > 0) {
          // Higher-level nodes get a discount (express lane)
          double bonus = 1.0 - CH_LEVEL_BONUS
              * ((double) chNode.level / Math.max(1, ch.getNodeCount()));
          cost *= Math.max(CORRIDOR_MIN_MULTIPLIER, bonus);
        }
      }
    }

    return Math.max(1.0, cost);
  }

  /**
   * Compute via cost between two layers (original V6 + UTPR layer-grade cost).
   */
  public double computeViaCost(int fromLayer, int toLayer, boolean isPowerOrGround) {
    if (fromLayer == toLayer) return 0.0;
    if (!isLayerRoutable[fromLayer] || !isLayerRoutable[toLayer])
      return Double.POSITIVE_INFINITY;

    double cost = viaCosts[fromLayer][toLayer];
    if (isPowerOrGround) cost *= (1.0 - POWER_GND_VIA_DISCOUNT);

    // UTPR: penalise crossing to a different traffic grade
    int fromGrade = layerToGrade(fromLayer);
    int toGrade = layerToGrade(toLayer);
    if (fromGrade != toGrade) {
      cost += Math.abs(fromGrade - toGrade) * 0.5;
    }

    return cost;
  }

  /**
   * Compute bend cost (unchanged from V6).
   */
  public double computeBendCost(int layer) {
    if (!isLayerRoutable[layer]) return Double.POSITIVE_INFINITY;
    return BEND_WEIGHT * bendCost[layer];
  }

  /**
   * Compute heuristic for A* (Manhattan distance + congestion + STOM).
   */
  public double computeHeuristic(FloatPoint from, FloatPoint to, int layer) {
    double dx = Math.abs(to.x - from.x);
    double dy = Math.abs(to.y - from.y);
    double manhattanDist = dx + dy;

    double congestion = congestionMap.getAverageCongestion(
        Math.min(from.x, to.x), Math.min(from.y, to.y),
        Math.max(from.x, to.x), Math.max(from.y, to.y));

    double heuristic = BASE_LENGTH_WEIGHT * manhattanDist
        * (1.0 + CONGESTION_WEIGHT * congestion);

    // STOM-aware heuristic: add penalty if midpoint is heavily occupied
    if (stom != null) {
      FloatPoint mid = new FloatPoint((from.x + to.x) / 2.0f,
                                       (from.y + to.y) / 2.0f);
      int occCount = stom.getOccupancyCount(mid.x, mid.y, layer);
      if (occCount > 0) {
        heuristic += RESERVATION_WEIGHT * occCount * manhattanDist;
      }
    }

    return heuristic;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Reservation map (from V6)
  // ═══════════════════════════════════════════════════════════════════════

  public void reserveChannel(int layer, FloatPoint point, double amount) {
    if (layer < 0 || layer >= layerCount) return;
    int col = (int) ((point.x - bbMinX) / CongestionMap.DEFAULT_CELL_SIZE);
    int row = (int) ((point.y - bbMinY) / CongestionMap.DEFAULT_CELL_SIZE);
    if (reservationMap[layer] == null) {
      reservationMap[layer] = new double[
          congestionMap.getGridRows() * congestionMap.getGridCols()];
    }
    int idx = row * congestionMap.getGridCols() + col;
    if (idx >= 0 && idx < reservationMap[layer].length) {
      reservationMap[layer][idx] += amount;
    }
  }

  public void clearReservations() {
    for (int i = 0; i < layerCount; i++) reservationMap[i] = null;
  }

  public boolean isLayerRoutable(int layer) {
    return layer >= 0 && layer < layerCount && isLayerRoutable[layer];
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helper: approximate layer → traffic grade
  // ═══════════════════════════════════════════════════════════════════════

  private int layerToGrade(int layer) {
    int sigCount = layerCount;
    if (layer <= 0 || layer >= sigCount - 1) {
      return LayerPair.GRADE_SURFACE; // top/bottom = surface
    }
    // Estimate: first 1/3 of inner layers = elevated, rest = subway
    int innerStart = 1;
    int innerEnd = sigCount - 2;
    int innerCount = Math.max(1, innerEnd - innerStart + 1);
    int elevatedSplit = innerStart + innerCount / 3;
    if (layer <= elevatedSplit) {
      return LayerPair.GRADE_ELEVATED;
    }
    return LayerPair.GRADE_SUBWAY;
  }
}
