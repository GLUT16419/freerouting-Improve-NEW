package app.freerouting.autoroute;

import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Phase 1: Adaptive board region divider using density heatmap and 1D projection.
 * <p>
 * Algorithm:
 * <ol>
 *   <li>Compute pin-density grid (50x50) over the board, excluding specified nets</li>
 *   <li>Vertical 1D projection: sum column densities, split into balanced column groups</li>
 *   <li>Horizontal 1D projection per column group: sum row densities, split into balanced row groups</li>
 *   <li>Create a {@link Region} for each resulting cell</li>
 * </ol>
 * <p>
 * This produces N regions with roughly equal pin-density load, bounded by available processors (max 6).
 */
public final class RegionDivider {

  private static final int GRID_SIZE = 50;
  private static final int MAX_REGIONS = 6;

  private RegionDivider() {
    // utility class
  }

  /**
   * Divides the board into adaptive spatial regions based on pin-density heatmap.
   *
   * @param board          the routing board
   * @param coreCount      desired number of regions (will be bounded by available processors and MAX_REGIONS)
   * @param labeler        power/ground auto-labeler for net classification
   * @param netsToExclude  set of net numbers to exclude from density calculation (e.g. GND for 2-layer)
   * @return list of regions covering the board, sorted by id
   */
  public static List<Region> divideBoard(
      RoutingBoard board,
      int coreCount,
      PowerGndAutoLabeler labeler,
      Set<Integer> netsToExclude) {

    // 1. Determine board bounding box from all pin locations
    Collection<Pin> allPins = board.get_pins();
    if (allPins == null || allPins.isEmpty()) {
      FRLogger.warn("RegionDivider: No pins found, creating single default region.");
      Region defaultRegion = new Region(0, 0, 0, 1000000, 1000000);
      defaultRegion.estimatedLoad = 1.0;
      defaultRegion.isActive = true;
      return List.of(defaultRegion);
    }

    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Pin pin : allPins) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x);
      minY = Math.min(minY, c.y);
      maxX = Math.max(maxX, c.x);
      maxY = Math.max(maxY, c.y);
    }

    // Add margin to prevent pins on edges from being cut
    double marginX = Math.max((maxX - minX) * 0.05, 100000);
    double marginY = Math.max((maxY - minY) * 0.05, 100000);
    minX -= marginX;
    minY -= marginY;
    maxX += marginX;
    maxY += marginY;

    double boardWidth = maxX - minX;
    double boardHeight = maxY - minY;
    if (boardWidth < 1 || boardHeight < 1) {
      FRLogger.warn("RegionDivider: Board bounding box too small, creating single region.");
      Region defaultRegion = new Region(0, minX, minY, maxX, maxY);
      defaultRegion.estimatedLoad = 1.0;
      return List.of(defaultRegion);
    }

    // 2. Build density grid
    double cellWidth = boardWidth / GRID_SIZE;
    double cellHeight = boardHeight / GRID_SIZE;
    int[][] density = new int[GRID_SIZE][GRID_SIZE];

    for (Pin pin : allPins) {
      if (pin.net_count() <= 0) continue;
      int netNo = pin.get_net_no(0);
      if (netsToExclude != null && netsToExclude.contains(netNo)) continue;
      FloatPoint c = pin.get_center().to_float();
      int gx = clamp((int) ((c.x - minX) / cellWidth), 0, GRID_SIZE - 1);
      int gy = clamp((int) ((c.y - minY) / cellHeight), 0, GRID_SIZE - 1);
      density[gy][gx]++;
    }

    // 3. Determine partition counts
    int numCores = Math.max(1, Math.min(coreCount, Runtime.getRuntime().availableProcessors()));
    numCores = Math.min(numCores, MAX_REGIONS);

    int numCols, numRows;
    if (numCores <= 2) {
      numCols = numCores;
      numRows = 1;
    } else if (numCores <= 4) {
      numCols = 2;
      numRows = (numCores + numCols - 1) / numCols;
    } else {
      numCols = 3;
      numRows = (numCores + numCols - 1) / numCols;
    }

    FRLogger.info("RegionDivider: Dividing board into " + numCols + "x" + numRows
        + " regions (" + (numCols * numRows) + " total), board size "
        + String.format("%.0f x %.0f", boardWidth, boardHeight) + " units");

    // 4. 1D vertical projection — split columns
    double[] colLoad = new double[GRID_SIZE];
    for (int gx = 0; gx < GRID_SIZE; gx++) {
      for (int gy = 0; gy < GRID_SIZE; gy++) {
        colLoad[gx] += density[gy][gx];
      }
    }
    int[] colBounds = split1D(colLoad, numCols);

    // 5. For each column group, 1D horizontal projection — split rows
    List<Region> regions = new ArrayList<>();
    int regionId = 0;
    for (int ci = 0; ci < colBounds.length - 1; ci++) {
      int colStart = colBounds[ci];
      int colEnd = colBounds[ci + 1];

      double[] rowLoad = new double[GRID_SIZE];
      for (int gx = colStart; gx < colEnd; gx++) {
        for (int gy = 0; gy < GRID_SIZE; gy++) {
          rowLoad[gy] += density[gy][gx];
        }
      }
      int[] rowBounds = split1D(rowLoad, numRows);

      for (int ri = 0; ri < rowBounds.length - 1; ri++) {
        int rowStart = rowBounds[ri];
        int rowEnd = rowBounds[ri + 1];

        // Convert grid bounds to board coordinates
        double rX0 = minX + colStart * cellWidth;
        double rX1 = minX + colEnd * cellWidth;
        double rY0 = minY + rowStart * cellHeight;
        double rY1 = minY + rowEnd * cellHeight;

        Region region = new Region(regionId++, rX0, rY0, rX1, rY1);

        // Calculate load for this region
        double load = 0;
        for (int gx = colStart; gx < colEnd; gx++) {
          for (int gy = rowStart; gy < rowEnd; gy++) {
            load += density[gy][gx];
          }
        }
        region.estimatedLoad = load;
        region.isActive = (load > 0);
        regions.add(region);
      }
    }

    // Log load distribution statistics
    double minLoad = Double.MAX_VALUE, maxLoad = -Double.MAX_VALUE, totalLoad = 0;
    for (Region r : regions) {
      minLoad = Math.min(minLoad, r.estimatedLoad);
      maxLoad = Math.max(maxLoad, r.estimatedLoad);
      totalLoad += r.estimatedLoad;
    }
    double avgLoad = regions.isEmpty() ? 0 : totalLoad / regions.size();
    int activeCount = (int) regions.stream().filter(r -> r.isActive).count();
    FRLogger.info("RegionDivider: " + regions.size() + " regions (" + activeCount
        + " active), load min=" + String.format("%.1f", minLoad)
        + " avg=" + String.format("%.1f", avgLoad)
        + " max=" + String.format("%.1f", maxLoad));

    return regions;
  }

  /**
   * Splits a 1D load array into {@code parts} balanced groups using equal-sum partitioning.
   * Returns an array of indices representing the start of each group, plus one extra for the end.
   */
  static int[] split1D(double[] data, int parts) {
    if (parts <= 1 || data.length == 0) {
      return new int[]{0, data.length};
    }
    double total = 0;
    for (double v : data) total += v;
    double targetPerPart = total / parts;

    // Allocate exact (parts+1) bounds, then trim
    int[] bounds = new int[parts + 1];
    bounds[0] = 0;
    int idx = 1;
    double acc = 0;
    for (int i = 0; i < data.length && idx < parts; i++) {
      acc += data[i];
      if (acc >= targetPerPart) {
        bounds[idx++] = i + 1;
        acc = 0;
      }
    }
    // Fill remaining bounds
    while (idx < parts) {
      bounds[idx] = data.length;
      idx++;
    }
    bounds[parts] = data.length;

    // Trim trailing empty partitions
    int actual = parts;
    while (actual > 1 && bounds[actual - 1] >= data.length) {
      actual--;
    }
    int[] result = new int[actual + 1];
    System.arraycopy(bounds, 0, result, 0, actual + 1);
    return result;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
