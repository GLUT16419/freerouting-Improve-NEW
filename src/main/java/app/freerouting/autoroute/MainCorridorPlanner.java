package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Phase 0 — Main corridor planning.
 * <p>
 * In the city-traffic metaphor, this is the <b>urban highway authority</b>
 * that decides where the main expressways and arterial roads will be built
 * BEFORE any local streets are laid down. It identifies the high-priority
 * traffic corridors needed by critical signals and reserves them.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Identify critical/high-priority nets (clocks, DDR, PCIe, etc.).</li>
 *   <li>Compute ideal FLUTE corridors for each critical net.</li>
 *   <li>Expand each corridor by a safety margin to create reserved zones.</li>
 *   <li>Mark reserved corridor cells with elevated importance in a
 *       "corridor priority map" that biases the route search cost model.</li>
 * </ol>
 */
public class MainCorridorPlanner implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Corridor half-width in grid cells (on each side of the FLUTE line). */
  static final int CORRIDOR_HALF_WIDTH = 3;

  /** Priority multiplier for corridor cells (higher = more protected). */
  static final double CORRIDOR_PRIORITY_MULTIPLIER = 0.3;

  /** Grid cell size default. */
  static final double DEFAULT_CELL_SIZE = 200_000.0;

  // ═══════════════════════════════════════════════════════════════════════
  //  CorridorCell
  // ═══════════════════════════════════════════════════════════════════════

  /** A cell in the corridor priority map. */
  public static class CorridorCell implements Serializable {
    private static final long serialVersionUID = 1L;
    public final int row, col;
    public final double priority;        // 0..1, higher = more protected
    public final Set<Integer> reservedForNetNumbers;  // which nets reserved this

    public CorridorCell(int row, int col, double priority) {
      this.row = row;
      this.col = col;
      this.priority = priority;
      this.reservedForNetNumbers = new HashSet<>();
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final int gridRows;
  private final int gridCols;
  private final double cellSize;
  private final double boardMinX;
  private final double boardMinY;

  /** Grid of corridor priority values [0..1]. */
  private final double[][] corridorPriority;

  /** List of all corridor cells (non-zero priority). */
  private final List<CorridorCell> corridorCells;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public MainCorridorPlanner(BasicBoard board, double cellSize) {
    this.board = board;
    this.cellSize = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;
    double bw = board.bounding_box.width();
    double bh = board.bounding_box.height();

    this.gridCols = Math.max(4, (int) Math.ceil(bw / this.cellSize));
    this.gridRows = Math.max(4, (int) Math.ceil(bh / this.cellSize));

    this.corridorPriority = new double[gridRows][gridCols];
    this.corridorCells = new ArrayList<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main planning
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Plan main corridors from all nets, prioritising critical signals.
   *
   * @param allNetNumbers all net numbers to consider for corridor planning
   * @param criticalNetNumbers subset of nets that get highest priority
   */
  public void planCorridors(Set<Integer> allNetNumbers,
                            Set<Integer> criticalNetNumbers) {
    long t0 = System.currentTimeMillis();

    // Step 1: Extract corridor from each critical net (full corridor width)
    if (criticalNetNumbers != null) {
      for (int netNo : criticalNetNumbers) {
        planCorridorForNet(netNo, CORRIDOR_HALF_WIDTH, 1.0);
      }
    }

    // Step 2: Extract corridor from each regular net (half width, lower priority)
    if (allNetNumbers != null) {
      for (int netNo : allNetNumbers) {
        if (criticalNetNumbers != null && criticalNetNumbers.contains(netNo)) continue;
        planCorridorForNet(netNo, CORRIDOR_HALF_WIDTH / 2 + 1, 0.5);
      }
    }

    // Step 3: Collect non-zero cells
    for (int r = 0; r < gridRows; r++) {
      for (int c = 0; c < gridCols; c++) {
        if (corridorPriority[r][c] > 0) {
          corridorCells.add(new CorridorCell(r, c, corridorPriority[r][c]));
        }
      }
    }

    long t1 = System.currentTimeMillis();
    FRLogger.info(String.format("MainCorridorPlanner: %d corridor cells "
            + "(critical=%d, all=%d) time=%dms",
        corridorCells.size(),
        criticalNetNumbers != null ? criticalNetNumbers.size() : 0,
        allNetNumbers != null ? allNetNumbers.size() : 0,
        t1 - t0));
  }

  /**
   * Plan a corridor for a single net.
   */
  private void planCorridorForNet(int netNo, int halfWidth, double basePriority) {
    Net net = board.rules.nets.get(netNo);
    if (net == null) return;
    Collection<Pin> pins = net.get_pins();
    if (pins.size() < 2) return;

    // Compute FLUTE MST
    List<FloatPoint> points = new ArrayList<>();
    for (Pin pin : pins) {
      points.add(pin.get_center().to_float());
    }
    List<int[]> mstEdges = computeMST(points);

    // For each edge, mark corridor cells
    for (int[] edge : mstEdges) {
      FloatPoint a = points.get(edge[0]);
      FloatPoint b = points.get(edge[1]);

      int c0 = clampCol((int) ((a.x - boardMinX) / cellSize));
      int r0 = clampRow((int) ((a.y - boardMinY) / cellSize));
      int c1 = clampCol((int) ((b.x - boardMinX) / cellSize));
      int r1 = clampRow((int) ((b.y - boardMinY) / cellSize));

      // Bresenham
      int dc = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
      int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
      int err = dc - dr;
      int r = r0, c = c0;

      while (true) {
        // Mark cells in a square around this cell (halfWidth)
        for (int rr = Math.max(0, r - halfWidth);
             rr <= Math.min(gridRows - 1, r + halfWidth); rr++) {
          for (int cc = Math.max(0, c - halfWidth);
               cc <= Math.min(gridCols - 1, c + halfWidth); cc++) {
            // Priority decays with distance from corridor center
            double dist = Math.sqrt(Math.pow(rr - r, 2) + Math.pow(cc - c, 2));
            double decay = Math.max(0, 1.0 - dist / (halfWidth + 1));
            corridorPriority[rr][cc] = Math.max(
                corridorPriority[rr][cc], basePriority * decay);
          }
        }

        if (r == r1 && c == c1) break;
        int e2 = 2 * err;
        if (e2 > -dr) { err -= dr; c += sc; }
        if (e2 < dc) { err += dc; r += sr; }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  MST helper (same FLUTE-lite algorithm)
  // ═══════════════════════════════════════════════════════════════════════

  private List<int[]> computeMST(List<FloatPoint> points) {
    int n = points.size();
    boolean[] visited = new boolean[n];
    double[] minDist = new double[n];
    int[] parent = new int[n];
    Arrays.fill(minDist, Double.MAX_VALUE);
    minDist[0] = 0;

    List<int[]> edges = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int u = -1;
      double best = Double.MAX_VALUE;
      for (int j = 0; j < n; j++)
        if (!visited[j] && minDist[j] < best) { best = minDist[j]; u = j; }
      if (u == -1) break;
      visited[u] = true;
      if (i > 0) edges.add(new int[]{parent[u], u});
      for (int v = 0; v < n; v++)
        if (!visited[v]) {
          double d = Math.abs(points.get(u).x - points.get(v).x)
                   + Math.abs(points.get(u).y - points.get(v).y);
          if (d < minDist[v]) { minDist[v] = d; parent[v] = u; }
        }
    }
    return edges;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query methods
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Get corridor priority multiplier [0..1] at a position.
   * Lower values = higher priority (multiplier applied to cost → less cost).
   * Used by the cost model to bias routing toward corridor cells.
   */
  public double getCorridorMultiplier(double x, double y) {
    int c = clampCol((int) ((x - boardMinX) / cellSize));
    int r = clampRow((int) ((y - boardMinY) / cellSize));
    double priority = corridorPriority[r][c];
    // High priority → low multiplier (cheaper to route through)
    return Math.max(0.1, 1.0 - priority * (1.0 - CORRIDOR_PRIORITY_MULTIPLIER));
  }

  /** Get the raw corridor priority at a grid cell. */
  public double getPriorityAtCell(int row, int col) {
    if (row < 0 || row >= gridRows || col < 0 || col >= gridCols) return 0;
    return corridorPriority[row][col];
  }

  /** Get all corridor cells. */
  public List<CorridorCell> getCorridorCells() {
    return Collections.unmodifiableList(corridorCells);
  }

  /** Get the corridor priority matrix (for cost model integration). */
  public double[][] getCorridorPriorityMatrix() {
    return corridorPriority;
  }

  public int getGridRows() { return gridRows; }
  public int getGridCols() { return gridCols; }

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }
}
