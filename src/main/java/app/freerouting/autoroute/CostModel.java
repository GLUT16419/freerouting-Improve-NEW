package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;

/**
 * Congestion-aware cost model for A* routing.
 */
public class CostModel implements Serializable {

  private static final double BASE_LENGTH_WEIGHT = 1.0;
  private static final double DIRECTION_WEIGHT = 0.3;
  private static final double BEND_WEIGHT = 0.5;
  private static final double VIA_WEIGHT = 2.0;
  private static final double CONGESTION_WEIGHT = 1.5;
  private static final double HISTORICAL_WEIGHT = 0.8;
  private static final double RESERVATION_WEIGHT = 0.6;
  private static final double POWER_GND_VIA_DISCOUNT = 0.3;

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

  public double computeSegmentCost(FloatPoint from, FloatPoint to, int layer,
                                   boolean isPowerOrGround, double netPriority) {
    if (!isLayerRoutable[layer]) return Double.POSITIVE_INFINITY;
    double dx = Math.abs(to.x - from.x);
    double dy = Math.abs(to.y - from.y);
    double length = Math.sqrt(dx * dx + dy * dy);
    double cost = BASE_LENGTH_WEIGHT * length;
    double directionCost = DIRECTION_WEIGHT * length * (dx > dy ? horizontalCost[layer] : verticalCost[layer]);
    cost += directionCost;
    FloatPoint mid = new FloatPoint((from.x + to.x) / 2.0, (from.y + to.y) / 2.0);
    double congestion = congestionMap.getCongestion(mid, layer);
    cost += CONGESTION_WEIGHT * length * congestion * congestion;
    double historical = congestionMap.getHistoricalCost(mid, layer);
    cost += HISTORICAL_WEIGHT * historical;
    return cost;
  }

  public double computeViaCost(int fromLayer, int toLayer, boolean isPowerOrGround) {
    if (fromLayer == toLayer) return 0.0;
    if (!isLayerRoutable[fromLayer] || !isLayerRoutable[toLayer]) return Double.POSITIVE_INFINITY;
    double cost = viaCosts[fromLayer][toLayer];
    if (isPowerOrGround) cost *= (1.0 - POWER_GND_VIA_DISCOUNT);
    return cost;
  }

  public double computeBendCost(int layer) {
    if (!isLayerRoutable[layer]) return Double.POSITIVE_INFINITY;
    return BEND_WEIGHT * bendCost[layer];
  }

  public double computeHeuristic(FloatPoint from, FloatPoint to, int layer) {
    double dx = Math.abs(to.x - from.x);
    double dy = Math.abs(to.y - from.y);
    double manhattanDist = dx + dy;
    double congestion = congestionMap.getAverageCongestion(
        Math.min(from.x, to.x), Math.min(from.y, to.y),
        Math.max(from.x, to.x), Math.max(from.y, to.y));
    return BASE_LENGTH_WEIGHT * manhattanDist * (1.0 + CONGESTION_WEIGHT * congestion);
  }

  public void reserveChannel(int layer, FloatPoint point, double amount) {
    if (layer < 0 || layer >= layerCount) return;
    int col = (int) ((point.x - bbMinX) / CongestionMap.DEFAULT_CELL_SIZE);
    int row = (int) ((point.y - bbMinY) / CongestionMap.DEFAULT_CELL_SIZE);
    if (reservationMap[layer] == null) {
      reservationMap[layer] = new double[congestionMap.getGridRows() * congestionMap.getGridCols()];
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
}
