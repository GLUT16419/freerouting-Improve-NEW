package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;

import java.io.Serializable;

/**
 * UTPR V7.x — Max Gap Finder for low-requirement net routing guidance.
 * <p>
 * Uses 2D prefix sum and greedy scan to locate the largest continuous free
 * rectangular area between source and target, guiding the A* search to
 * preferentially route through spacious regions rather than fragmented gaps.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Build a binary grid: 0 = obstacle/occupied, 1 = free</li>
 *   <li>Compute 2D prefix sum for O(1) free-region queries</li>
 *   <li>Greedy scan from source-to-target direction to find largest
 *       free rectangle along the path axis</li>
 * </ol>
 */
public class MaxGapFinder implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * Result of a max-gap search.
   */
  public static class GapResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Top-left row of the max gap rectangle. */
    public final int rMin;
    /** Top-left col of the max gap rectangle. */
    public final int cMin;
    /** Bottom-right row of the max gap rectangle. */
    public final int rMax;
    /** Bottom-right col of the max gap rectangle. */
    public final int cMax;
    /** Area of the gap in cells. */
    public final int area;
    /** True if a gap was found (area > 0). */
    public final boolean found;

    public GapResult(int rMin, int cMin, int rMax, int cMax, int area) {
      this.rMin = rMin;
      this.cMin = cMin;
      this.rMax = rMax;
      this.cMax = cMax;
      this.area = area;
      this.found = area > 0;
    }

    public static final GapResult NOT_FOUND = new GapResult(-1, -1, -1, -1, 0);
  }

  /**
   * Find the maximum continuous free rectangle between source and target.
   * <p>
   * The algorithm uses a greedy approach:
   * <ol>
   *   <li>Build a binary free/obstacle grid from the obstacle matrix</li>
   *   <li>Compute 2D prefix sum for O(1) free-region queries</li>
   *   <li>Scan rows between srcR and tgtR, expanding columns outward</li>
   *   <li>Track the largest rectangular region that is entirely free</li>
   * </ol>
   *
   * @param obstacleGrid 2D grid: 0 = free, > 0 = obstacle/occupied
   * @param srcR         source row
   * @param srcC         source col
   * @param tgtR         target row
   * @param tgtC         target col
   * @return the largest free gap rectangle found
   */
  public static GapResult findMaxGap(int[][] obstacleGrid,
                                      int srcR, int srcC,
                                      int tgtR, int tgtC) {
    if (obstacleGrid == null || obstacleGrid.length == 0) {
      return GapResult.NOT_FOUND;
    }

    int rows = obstacleGrid.length;
    int cols = obstacleGrid[0].length;

    // 1. Build binary free matrix: 1 = free, 0 = obstacle
    int[][] free = new int[rows][cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        free[r][c] = (obstacleGrid[r][c] == 0) ? 1 : 0;
      }
    }

    // 2. 2D prefix sum
    int[][] prefix = new int[rows + 1][cols + 1];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        prefix[r + 1][c + 1] = free[r][c]
            + prefix[r][c + 1]
            + prefix[r + 1][c]
            - prefix[r][c];
      }
    }

    // 3. Greedy scan between src and tgt
    int rMin = Math.min(srcR, tgtR);
    int rMax = Math.max(srcR, tgtR);
    int cMin = Math.min(srcC, tgtC);
    int cMax = Math.max(srcC, tgtC);

    // Expand the search region by 25% to capture nearby gaps
    int marginR = Math.max(1, (rMax - rMin) / 4);
    int marginC = Math.max(1, (cMax - cMin) / 4);
    int scanRMin = Math.max(0, rMin - marginR);
    int scanRMax = Math.min(rows - 1, rMax + marginR);
    int scanCMin = Math.max(0, cMin - marginC);
    int scanCMax = Math.min(cols - 1, cMax + marginC);

    int bestArea = 0;
    int bestRMin = -1, bestCMin = -1, bestRMax = -1, bestCMax = -1;

    // Try each row as top boundary
    for (int top = scanRMin; top <= scanRMax; top++) {
      // Expand downward
      for (int bottom = top; bottom <= scanRMax; bottom++) {
        // Count free cells in this [top..bottom] band
        // Use histogram method: for each column, check if column is fully free
        int colStart = -1;
        for (int c = scanCMin; c <= scanCMax + 1; c++) {
          boolean isFreeCol;
          if (c > scanCMax) {
            isFreeCol = false; // force closure at end
          } else {
            int freeCount = sumRegion(prefix, top, c, bottom, c);
            int expected = bottom - top + 1;
            isFreeCol = (freeCount == expected);
          }

          if (isFreeCol) {
            if (colStart < 0) colStart = c;
          } else {
            if (colStart >= 0) {
              int width = c - colStart;
              int height = bottom - top + 1;
              int area = width * height;

              // Enforce aspect ratio between [0.3, 3.0] for practical routing
              double aspect = (double) Math.max(width, height) / Math.max(1, Math.min(width, height));
              if (aspect <= 3.0 && area > bestArea) {
                bestArea = area;
                bestRMin = top;
                bestCMin = colStart;
                bestRMax = bottom;
                bestCMax = c - 1;
              }
            }
            colStart = -1;
          }
        }
      }
    }

    if (bestArea > 0) {
      FRLogger.debug("MaxGapFinder: found gap " + bestArea + " cells ["
          + bestRMin + "," + bestCMin + " -> " + bestRMax + "," + bestCMax + "]");
      return new GapResult(bestRMin, bestCMin, bestRMax, bestCMax, bestArea);
    }
    return GapResult.NOT_FOUND;
  }

  /**
   * Query the 2D prefix sum for sum of free cells in [r1..r2, c1..c2].
   */
  private static int sumRegion(int[][] prefix, int r1, int c1, int r2, int c2) {
    return prefix[r2 + 1][c2 + 1]
        - prefix[r1][c2 + 1]
        - prefix[r2 + 1][c1]
        + prefix[r1][c1];
  }
}
