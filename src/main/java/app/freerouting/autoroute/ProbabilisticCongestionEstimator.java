package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Phase 0 — Probabilistic congestion estimator using Gaussian-diffused
 * FLUTE topology (no ML training required).
 * <p>
 * In the city-traffic metaphor, this is the <b>traffic flow forecaster</b>
 * that predicts which city blocks will be congested during rush hour BEFORE
 * any actual routing begins.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Compute a FLUTE-like RSMT for each net (reusing the existing
 *       {@link FluteTopologyEstimator} MST engine).</li>
 *   <li>For each RSMT edge, apply a <b>Gaussian blur</b> (σ ≈ 2 grid cells)
 *       to produce a smooth, probabilistic occupancy distribution.</li>
 *   <li>Sum all per-net contributions across the board → probabilistic
 *       occupancy heatmap.</li>
 *   <li>Divide by cell capacity → congestion probability map.</li>
 *   <li>Export congestion gradient vectors for channel guidance.</li>
 * </ol>
 */
public class ProbabilisticCongestionEstimator implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Gaussian sigma in grid cells (spread of the probability kernel). */
  static final double GAUSSIAN_SIGMA = 2.0;

  /** Kernel radius in cells (truncated Gaussian support: ±3σ). */
  static final int KERNEL_RADIUS = (int) Math.ceil(3.0 * GAUSSIAN_SIGMA);

  /** Default grid cell size (internal board units, ~2 mm). */
  static final double DEFAULT_CELL_SIZE = 200_000.0;

  /** Weight multiplier for parallel segments (differential pairs etc.). */
  static final double PARALLEL_SEGMENT_WEIGHT = 1.8;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final int gridRows;
  private final int gridCols;
  private final double cellSize;
  private final double boardMinX;
  private final double boardMinY;

  /** Per-cell probability demand (accumulated, unnormalized). */
  private final double[][] probabilityDemand;

  /** Per-cell capacity (derived from layer count and track pitch). */
  private final double[][] capacity;

  /** Final congestion probability [0..1] for each cell. */
  private final double[][] congestion;

  /** Pre-computed Gaussian kernel (symmetric, 2D separable). */
  private final double[] gaussianKernel1D;

  /** Signal layer count for capacity estimation. */
  private final int signalLayerCount;

  /** Maximum probability value (for normalisation). */
  private double maxProbability;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public ProbabilisticCongestionEstimator(BasicBoard board, double cellSize) {
    this.board = board;
    this.cellSize = cellSize > 0 ? cellSize : DEFAULT_CELL_SIZE;
    this.boardMinX = board.bounding_box.ll.to_float().x;
    this.boardMinY = board.bounding_box.ll.to_float().y;
    double boardWidth = board.bounding_box.width();
    double boardHeight = board.bounding_box.height();

    this.gridCols = Math.max(4, (int) Math.ceil(boardWidth / this.cellSize));
    this.gridRows = Math.max(4, (int) Math.ceil(boardHeight / this.cellSize));

    this.probabilityDemand = new double[gridRows][gridCols];
    this.capacity = new double[gridRows][gridCols];
    this.congestion = new double[gridRows][gridCols];

    this.signalLayerCount = board.layer_structure.signal_layer_count();

    // Pre-compute 1D Gaussian kernel
    this.gaussianKernel1D = new double[2 * KERNEL_RADIUS + 1];
    double sigma2 = GAUSSIAN_SIGMA * GAUSSIAN_SIGMA;
    double total = 0;
    for (int i = -KERNEL_RADIUS; i <= KERNEL_RADIUS; i++) {
      double v = Math.exp(-(i * i) / (2.0 * sigma2));
      gaussianKernel1D[i + KERNEL_RADIUS] = v;
      total += v;
    }
    // Normalise kernel
    for (int i = 0; i < gaussianKernel1D.length; i++) {
      gaussianKernel1D[i] /= total;
    }

    this.maxProbability = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main estimation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Estimate probabilistic congestion for all incomplete nets.
   *
   * @param incompleteNets set of net numbers to include in estimation
   */
  public void estimate(Set<Integer> incompleteNets) {
    long t0 = System.currentTimeMillis();

    if (incompleteNets == null || incompleteNets.isEmpty()) {
      FRLogger.info("ProbCongestionEstimator: no nets to estimate");
      return;
    }

    // 1. Initialize capacity per cell
    initCapacity();

    // 2. Compute FLUTE-MST and apply Gaussian diffusion for each net
    for (int netNo : incompleteNets) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) continue;

      // 2a. Build MST via FLUTE lite
      List<FloatPoint> points = new ArrayList<>();
      for (Pin pin : pins) {
        points.add(pin.get_center().to_float());
      }
      List<int[]> mstEdges = computeMST(points);

      // 2b. Apply Gaussian diffusion along each edge
      double netWeight = isDifferentialLike(net) ? PARALLEL_SEGMENT_WEIGHT : 1.0;
      for (int[] edge : mstEdges) {
        FloatPoint a = points.get(edge[0]);
        FloatPoint b = points.get(edge[1]);
        diffuseLine(a, b, netWeight);
      }
    }

    // 3. Compute congestion probability = demand / capacity
    for (int r = 0; r < gridRows; r++) {
      for (int c = 0; c < gridCols; c++) {
        congestion[r][c] = capacity[r][c] > 1e-10
            ? Math.min(1.0, probabilityDemand[r][c] / capacity[r][c])
            : 0.0;
      }
    }

    long t1 = System.currentTimeMillis();
    FRLogger.info(String.format("ProbCongestionEstimator: %d nets, %dx%d grid, "
            + "maxProb=%.4f, time=%dms",
        incompleteNets.size(), gridRows, gridCols, maxProbability, t1 - t0));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Capacity
  // ═══════════════════════════════════════════════════════════════════════

  private void initCapacity() {
    double tracksPerCell = (cellSize / 3000.0) * signalLayerCount; // ~3000 per track
    double obstacleFraction = 0.15;
    double baseCapacity = Math.max(1.0, tracksPerCell * (1.0 - obstacleFraction));
    for (int r = 0; r < gridRows; r++) {
      Arrays.fill(capacity[r], baseCapacity);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  MST (FLUTE lite)
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
      for (int j = 0; j < n; j++) {
        if (!visited[j] && minDist[j] < best) {
          best = minDist[j];
          u = j;
        }
      }
      if (u == -1) break;
      visited[u] = true;
      if (i > 0) edges.add(new int[]{parent[u], u});
      for (int v = 0; v < n; v++) {
        if (!visited[v]) {
          double d = manhattan(points.get(u), points.get(v));
          if (d < minDist[v]) {
            minDist[v] = d;
            parent[v] = u;
          }
        }
      }
    }
    return edges;
  }

  private double manhattan(FloatPoint a, FloatPoint b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Gaussian diffusion
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Diffuse a Gaussian-weighted probability contribution along the line
   * from {@code a} to {@code b}.
   */
  private void diffuseLine(FloatPoint a, FloatPoint b, double weight) {
    // Rasterize line onto grid
    int c0 = clampCol((int) ((a.x - boardMinX) / cellSize));
    int r0 = clampRow((int) ((a.y - boardMinY) / cellSize));
    int c1 = clampCol((int) ((b.x - boardMinX) / cellSize));
    int r1 = clampRow((int) ((b.y - boardMinY) / cellSize));

    // Bresenham traversal
    int dc = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
    int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
    int err = dc - dr;

    int r = r0, c = c0;
    Set<Long> visited = new HashSet<>();

    while (true) {
      if (r >= 0 && r < gridRows && c >= 0 && c < gridCols) {
        long key = ((long) r << 32) | (c & 0xFFFFFFFFL);
        if (!visited.contains(key)) {
          visited.add(key);
          applyGaussianBlur(r, c, weight);
        }
      }
      if (r == r1 && c == c1) break;
      int e2 = 2 * err;
      if (e2 > -dr) { err -= dr; c += sc; }
      if (e2 < dc) { err += dc; r += sr; }
    }
  }

  /**
   * Apply the Gaussian kernel centered at (row, col).
   * This is what creates the "smooth probabilistic heatmap" effect.
   */
  private void applyGaussianBlur(int centerR, int centerC, double weight) {
    int rMin = Math.max(0, centerR - KERNEL_RADIUS);
    int rMax = Math.min(gridRows - 1, centerR + KERNEL_RADIUS);
    int cMin = Math.max(0, centerC - KERNEL_RADIUS);
    int cMax = Math.min(gridCols - 1, centerC + KERNEL_RADIUS);

    for (int r = rMin; r <= rMax; r++) {
      int dr = r - centerR;
      double wRow = gaussianKernel1D[dr + KERNEL_RADIUS];
      if (wRow < 1e-6) continue;
      for (int c = cMin; c <= cMax; c++) {
        int dc = c - centerC;
        double wCol = gaussianKernel1D[dc + KERNEL_RADIUS];
        double contribution = weight * wRow * wCol;
        probabilityDemand[r][c] += contribution;
        if (probabilityDemand[r][c] > maxProbability) {
          maxProbability = probabilityDemand[r][c];
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query methods
  // ═══════════════════════════════════════════════════════════════════════

  /** Get congestion probability [0..1] at a board position. */
  public double getCongestionAt(double x, double y) {
    int c = clampCol((int) ((x - boardMinX) / cellSize));
    int r = clampRow((int) ((y - boardMinY) / cellSize));
    return congestion[r][c];
  }

  /** Get congestion probability at a specific grid cell. */
  public double getCongestionAtCell(int row, int col) {
    if (row < 0 || row >= gridRows || col < 0 || col >= gridCols) return 0;
    return congestion[row][col];
  }

  /** Get max congestion across all cells. */
  public double getMaxCongestion() {
    double max = 0;
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        max = Math.max(max, congestion[r][c]);
    return max;
  }

  /** Get the probability demand matrix (for debugging / visualisation). */
  public double[][] getProbabilityDemand() {
    return probabilityDemand;
  }

  /** Get the final congestion matrix. */
  public double[][] getCongestionMatrix() {
    return congestion;
  }

  /**
   * Get congestion gradient at a position (direction of increasing congestion).
   * Used by the route planner for channel guidance (avoid hotspots).
   */
  public double[] getGradientAt(double x, double y) {
    int c = clampCol((int) ((x - boardMinX) / cellSize));
    int r = clampRow((int) ((y - boardMinY) / cellSize));
    double gx = 0, gy = 0;
    if (c > 0) gx = congestion[r][c] - congestion[r][c - 1];
    if (c < gridCols - 1) gx += congestion[r][c + 1] - congestion[r][c];
    if (r > 0) gy = congestion[r][c] - congestion[r - 1][c];
    if (r < gridRows - 1) gy += congestion[r + 1][c] - congestion[r][c];
    return new double[]{gx, gy};
  }

  /** Generate detour guide points: midpoints of cells with congestion > threshold. */
  public List<FloatPoint> getDetourGuidePoints(double threshold) {
    List<FloatPoint> guides = new ArrayList<>();
    for (int r = 0; r < gridRows; r++) {
      for (int c = 0; c < gridCols; c++) {
        if (congestion[r][c] > threshold) {
          guides.add(new FloatPoint(
              (float) (boardMinX + (c + 0.5) * cellSize),
              (float) (boardMinY + (r + 0.5) * cellSize)));
        }
      }
    }
    return guides;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Net classification helpers
  // ═══════════════════════════════════════════════════════════════════════

  private boolean isDifferentialLike(Net net) {
    String name = net.name != null ? net.name.toUpperCase() : "";
    return name.endsWith("_P") || name.endsWith("_N")
        || name.contains("_N_") || name.contains("_P_");
  }

  // ═══════════════════════════════════════════════════════════════════════

  public int getGridRows() { return gridRows; }
  public int getGridCols() { return gridCols; }
  public double getCellSize() { return cellSize; }

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }
}
