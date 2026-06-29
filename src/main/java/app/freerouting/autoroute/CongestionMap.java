package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Point;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.BoardRules;

import java.io.Serializable;
import java.util.*;

/**
 * Represents a coarse-grained congestion map of the PCB routing area.
 */
public class CongestionMap implements Serializable {

  public static final double DEFAULT_CELL_SIZE = 200000.0;

  private final int gridCols;
  private final int gridRows;
  private final int layerCount;
  private final double cellSize;
  private final double boardMinX;
  private final double boardMinY;

  private final double[][][] usage;
  private final double[][][] capacity;
  private final double[][][] historicalCost;
  private final int[][][] contentionCount;

  public CongestionMap(BasicBoard board, double cellSize) {
    this.cellSize = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
    this.layerCount = board.get_layer_count();

    double boardWidth = board.bounding_box.width();
    double boardHeight = board.bounding_box.height();
    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;

    this.gridCols = Math.max(1, (int) Math.ceil(boardWidth / cellSize));
    this.gridRows = Math.max(1, (int) Math.ceil(boardHeight / cellSize));

    this.usage = new double[layerCount][gridRows][gridCols];
    this.capacity = new double[layerCount][gridRows][gridCols];
    this.historicalCost = new double[layerCount][gridRows][gridCols];
    this.contentionCount = new int[layerCount][gridRows][gridCols];

    initializeCapacity(board);
    computeInitialUsage(board);
  }

  public CongestionMap(BasicBoard board) {
    this(board, DEFAULT_CELL_SIZE);
  }

  private void initializeCapacity(BasicBoard board) {
    for (int layer = 0; layer < layerCount; layer++) {
      boolean isRoutable = layer < board.layer_structure.arr.length
          && board.layer_structure.arr[layer] != null
          && board.layer_structure.arr[layer].is_signal;

      for (int row = 0; row < gridRows; row++) {
        for (int col = 0; col < gridCols; col++) {
          if (isRoutable) {
            double trackPitch = 3000.0;
            double tracksPerCell = cellSize / trackPitch;
            double obstacleFraction = 0.1;
            capacity[layer][row][col] = Math.max(1.0, tracksPerCell * (1.0 - obstacleFraction));
          } else {
            capacity[layer][row][col] = 0.0;
          }
        }
      }
    }
  }

  private void computeInitialUsage(BasicBoard board) {
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    while (true) {
      Item item = (Item) board.item_list.read_object(it);
      if (item == null) break;

      if (item instanceof Trace trace) {
        int layer = trace.get_layer();
        if (layer >= 0 && layer < layerCount) {
          // Use bounding box center as approximation
          IntBox box = item.bounding_box();
          FloatPoint center = new FloatPoint(
              (box.ll.to_float().x + box.ur.to_float().x) / 2.0,
              (box.ll.to_float().y + box.ur.to_float().y) / 2.0);
          addUsage(center, layer, 1.0);
        }
      } else if (item instanceof Via via) {
        FloatPoint center = via.get_center().to_float();
        int firstLayer = via.first_layer();
        int lastLayer = via.last_layer();
        double viaWeight = 0.5;
        for (int l = firstLayer; l <= lastLayer && l < layerCount; l++) {
          if (l >= 0) {
            addUsage(center, l, viaWeight);
          }
        }
      } else if (item instanceof Pin pin) {
        FloatPoint center = pin.get_center().to_float();
        addUsage(center, pin.first_layer(), 0.2);
      }
    }
  }

  public void addUsage(FloatPoint point, int layer, double amount) {
    if (point == null || layer < 0 || layer >= layerCount) return;
    int col = (int) ((point.x - boardMinX) / cellSize);
    int row = (int) ((point.y - boardMinY) / cellSize);
    if (col >= 0 && col < gridCols && row >= 0 && row < gridRows) {
      usage[layer][row][col] += amount;
    }
  }

  public void removeUsage(FloatPoint point, int layer, double amount) {
    if (point == null || layer < 0 || layer >= layerCount) return;
    int col = (int) ((point.x - boardMinX) / cellSize);
    int row = (int) ((point.y - boardMinY) / cellSize);
    if (col >= 0 && col < gridCols && row >= 0 && row < gridRows) {
      usage[layer][row][col] = Math.max(0.0, usage[layer][row][col] - amount);
    }
  }

  public double getCongestion(FloatPoint point, int layer) {
    if (point == null || layer < 0 || layer >= layerCount) return 1.0;
    int col = (int) ((point.x - boardMinX) / cellSize);
    int row = (int) ((point.y - boardMinY) / cellSize);
    if (col < 0 || col >= gridCols || row < 0 || row >= gridRows) return 1.0;
    double cap = capacity[layer][row][col];
    if (cap <= 0) return 10.0;
    return usage[layer][row][col] / cap;
  }

  public double getCellCongestion(int layer, int row, int col) {
    if (layer < 0 || layer >= layerCount || row < 0 || row >= gridRows || col < 0 || col >= gridCols) return 1.0;
    double cap = capacity[layer][row][col];
    if (cap <= 0) return 10.0;
    return usage[layer][row][col] / cap;
  }

  public double getPathCongestionCost(List<FloatPoint> path, int layer) {
    if (path == null || path.isEmpty()) return 0.0;
    double totalCost = 0.0;
    for (FloatPoint point : path) {
      double congestion = getCongestion(point, layer);
      totalCost += congestion * congestion;
    }
    return totalCost;
  }

  public double getHistoricalCost(FloatPoint point, int layer) {
    if (point == null || layer < 0 || layer >= layerCount) return 0.0;
    int col = (int) ((point.x - boardMinX) / cellSize);
    int row = (int) ((point.y - boardMinY) / cellSize);
    if (col < 0 || col >= gridCols || row < 0 || row >= gridRows) return 0.0;
    return historicalCost[layer][row][col];
  }

  public void incrementHistoricalCost(FloatPoint point, int layer) {
    if (point == null || layer < 0 || layer >= layerCount) return;
    int col = (int) ((point.x - boardMinX) / cellSize);
    int row = (int) ((point.y - boardMinY) / cellSize);
    if (col >= 0 && col < gridCols && row >= 0 && row < gridRows) {
      historicalCost[layer][row][col] += 1.0;
      contentionCount[layer][row][col]++;
    }
  }

  public void resetHistoricalCosts() {
    for (int l = 0; l < layerCount; l++) {
      for (int r = 0; r < gridRows; r++) {
        java.util.Arrays.fill(historicalCost[l][r], 0.0);
      }
    }
  }

  public double getAverageCongestion(double minX, double minY, double maxX, double maxY) {
    int minCol = Math.max(0, (int) ((minX - boardMinX) / cellSize));
    int minRow = Math.max(0, (int) ((minY - boardMinY) / cellSize));
    int maxCol = Math.min(gridCols - 1, (int) ((maxX - boardMinX) / cellSize));
    int maxRow = Math.min(gridRows - 1, (int) ((maxY - boardMinY) / cellSize));

    double totalCongestion = 0.0;
    int cellCount = 0;
    for (int l = 0; l < layerCount; l++) {
      for (int r = minRow; r <= maxRow; r++) {
        for (int c = minCol; c <= maxCol; c++) {
          totalCongestion += getCellCongestion(l, r, c);
          cellCount++;
        }
      }
    }
    return cellCount > 0 ? totalCongestion / cellCount : 0.0;
  }

  public void logSummary() {
    double maxCongestion = 0.0;
    double totalCongestion = 0.0;
    int routableCellCount = 0;
    int overCongestedCells = 0;
    for (int l = 0; l < layerCount; l++) {
      for (int r = 0; r < gridRows; r++) {
        for (int c = 0; c < gridCols; c++) {
          if (capacity[l][r][c] > 0) {
            double cong = usage[l][r][c] / capacity[l][r][c];
            totalCongestion += cong;
            routableCellCount++;
            maxCongestion = Math.max(maxCongestion, cong);
            if (cong > 1.0) overCongestedCells++;
          }
        }
      }
    }
    double avgCongestion = routableCellCount > 0 ? totalCongestion / routableCellCount : 0.0;
    FRLogger.debug(String.format(
        "CongestionMap: grid=%dx%dx%d, avg=%.2f, max=%.2f, over_congested=%d/%d",
        gridCols, gridRows, layerCount, avgCongestion, maxCongestion,
        overCongestedCells, routableCellCount));
  }

  public int getGridCols() { return gridCols; }
  public int getGridRows() { return gridRows; }
  public int getLayerCount() { return layerCount; }
  public double getCellSize() { return cellSize; }
}
