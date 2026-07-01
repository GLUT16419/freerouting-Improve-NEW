package app.freerouting.autoroute;

import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.util.*;
import java.util.stream.Collectors;

/**
 * V6+UTPR: FLUTE-inspired fast RSMT topology estimator with Gaussian
 * probability diffusion for smooth congestion heatmaps.
 * <p>
 * Provides coarse-grained grid congestion estimates without A* by computing
 * rectilinear Steiner minimal tree (RSMT) approximations for each net and
 * projecting the estimated wire demand onto a uniform grid.
 * <p>
 * <b>UTPR enhancement:</b> adds Gaussian probability diffusion to produce
 * a smooth, probabilistic occupancy heatmap (zero-training dependency,
 * replacing ML-based methods).
 */
public class FluteTopologyEstimator {

  private static final long serialVersionUID = 1L;

  // ── UTPR Gaussian diffusion parameters ─────────────────────────────────

  /** Gaussian sigma in grid cells (spread of the probability kernel). */
  static final double GAUSSIAN_SIGMA = 2.0;

  /** Kernel radius in cells (truncated Gaussian support: ±3σ). */
  static final int KERNEL_RADIUS = (int) Math.ceil(3.0 * GAUSSIAN_SIGMA);

  // ── Fields ─────────────────────────────────────────────────────────────

  private final RoutingBoard board;
  private final int gridRows, gridCols;
  private final double cellWidth, cellHeight;
  private final double[][] demand;          // [row][col] accumulated wire demand
  private final double[][] capacity;        // [row][col] available routing capacity
  private final double[][] congestion;      // [row][col] demand / capacity
  private final double[][] probabilityDemand; // [row][col] Gaussian-diffused demand
  private final double[][] probCongestion;    // [row][col] diffused demand / capacity
  private final double xMin, yMin, xMax, yMax;

  /** Pre-computed 1D Gaussian kernel. */
  private final double[] gaussianKernel1D;

  /** Maximum probability demand value (for normalisation). */
  private double maxProbability;

  /** Grid cell containing congestion heat-map data. */
  public static class GridCell {
    public final int row, col;
    public final double demand, capacity, congestion;
    public boolean isHotSpot;
    GridCell(int row, int col, double demand, double capacity, double congestion) {
      this.row = row; this.col = col;
      this.demand = demand; this.capacity = capacity;
      this.congestion = congestion;
    }
  }

  public FluteTopologyEstimator(RoutingBoard board, int cellSizeUm) {
    this.board = board;
    FloatPoint bbMin = board.bounding_box.ll.to_float();
    FloatPoint bbMax = board.bounding_box.ur.to_float();
    this.xMin = bbMin.x; this.yMin = bbMin.y;
    this.xMax = bbMax.x; this.yMax = bbMax.y;
    double bw = xMax - xMin, bh = yMax - yMin;
    this.gridCols = Math.max(4, (int) Math.ceil(bw / cellSizeUm));
    this.gridRows = Math.max(4, (int) Math.ceil(bh / cellSizeUm));
    this.cellWidth = bw / gridCols;
    this.cellHeight = bh / gridRows;
    this.demand = new double[gridRows][gridCols];
    this.capacity = new double[gridRows][gridCols];
    this.congestion = new double[gridRows][gridCols];
    this.probabilityDemand = new double[gridRows][gridCols];
    this.probCongestion = new double[gridRows][gridCols];
    this.maxProbability = 0;

    // Pre-compute 1D Gaussian kernel
    this.gaussianKernel1D = new double[2 * KERNEL_RADIUS + 1];
    double sigma2 = GAUSSIAN_SIGMA * GAUSSIAN_SIGMA;
    double total = 0;
    for (int i = -KERNEL_RADIUS; i <= KERNEL_RADIUS; i++) {
      double v = Math.exp(-(i * i) / (2.0 * sigma2));
      gaussianKernel1D[i + KERNEL_RADIUS] = v;
      total += v;
    }
    for (int i = 0; i < gaussianKernel1D.length; i++) {
      gaussianKernel1D[i] /= total;
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Original V6 estimate() + UTPR probability diffusion
  // ═══════════════════════════════════════════════════════════════════════

  /** Estimate global topology demand from all incomplete nets. */
  public void estimate(Set<Integer> incompleteNets) {
    if (incompleteNets == null || incompleteNets.isEmpty()) return;

    // 1. Initialize capacity per cell
    int signalLayerCount = board.layer_structure.signal_layer_count();
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        capacity[r][c] = Math.max(1, signalLayerCount * 3);

    // 2. Compute RSMT-like demand + Gaussian diffusion for each net
    for (int netNo : incompleteNets) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      if (pins.size() < 2) continue;

      List<FloatPoint> points = new ArrayList<>();
      for (Pin pin : pins)
        points.add(pin.get_center().to_float());

      // 2a. MST edges
      List<int[]> mstEdges = approximateMST(points);

      // 2b. Rasterize + apply Gaussian diffusion for each edge
      double netWeight = isDifferentialLike(net) ? 1.8 : 1.0;
      for (int[] edge : mstEdges) {
        FloatPoint a = points.get(edge[0]);
        FloatPoint b = points.get(edge[1]);
        rasterizeLine(a, b, signalLayerCount);
        diffuseLine(a, b, netWeight); // UTPR: Gaussian diffusion
      }
    }

    // 3. Compute standard congestion ratio
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        congestion[r][c] = capacity[r][c] > 0 ? demand[r][c] / capacity[r][c] : 1.0;

    // 4. Compute probabilistic congestion (UTPR enhancement)
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        probCongestion[r][c] = capacity[r][c] > 1e-10
            ? Math.min(1.0, probabilityDemand[r][c] / capacity[r][c])
            : 0.0;

    FRLogger.info("FLUTE: " + gridRows + "x" + gridCols + " grid, "
        + incompleteNets.size() + " nets, max congestion="
        + String.format("%.2f", getMaxCongestion())
        + ", max prob=" + String.format("%.4f", maxProbability));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Original V6 methods (unchanged)
  // ═══════════════════════════════════════════════════════════════════════

  /** Simple MST approximation (Prim's) as FLUTE-like RSMT proxy. */
  private List<int[]> approximateMST(List<FloatPoint> points) {
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
      for (int v = 0; v < n; v++) {
        if (!visited[v]) {
          double d = manhattan(points.get(u), points.get(v));
          if (d < minDist[v]) { minDist[v] = d; parent[v] = u; }
        }
      }
    }
    return edges;
  }

  private double manhattan(FloatPoint a, FloatPoint b) {
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y);
  }

  /** Rasterize a line segment onto the grid, incrementing demand. */
  private void rasterizeLine(FloatPoint a, FloatPoint b, int layerWeight) {
    int c0 = clampCol((int) ((a.x - xMin) / cellWidth));
    int r0 = clampRow((int) ((a.y - yMin) / cellHeight));
    int c1 = clampCol((int) ((b.x - xMin) / cellWidth));
    int r1 = clampRow((int) ((b.y - yMin) / cellHeight));

    int dc = Math.abs(c1 - c0), dr = Math.abs(r1 - r0);
    int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
    int err = dc - dr;
    int r = r0, c = c0;

    while (true) {
      if (r >= 0 && r < gridRows && c >= 0 && c < gridCols)
        demand[r][c] += layerWeight;
      if (r == r1 && c == c1) break;
      int e2 = 2 * err;
      if (e2 > -dr) { err -= dr; c += sc; }
      if (e2 < dc) { err += dc; r += sr; }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  UTPR: Gaussian probability diffusion
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Diffuse a Gaussian-weighted probability contribution along a line segment.
   * This creates a smooth probabilistic heatmap that estimates how likely
   * each grid cell is to be used for routing — without running actual A*.
   */
  private void diffuseLine(FloatPoint a, FloatPoint b, double weight) {
    int c0 = clampCol((int) ((a.x - xMin) / cellWidth));
    int r0 = clampRow((int) ((a.y - yMin) / cellHeight));
    int c1 = clampCol((int) ((b.x - xMin) / cellWidth));
    int r1 = clampRow((int) ((b.y - yMin) / cellHeight));

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
   * Smooths the line segment's probability contribution to neighbouring cells.
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
  //  Query methods (original + UTPR probability)
  // ═══════════════════════════════════════════════════════════════════════

  /** Get standard congestion at a board position. */
  public double getCongestionAt(double x, double y) {
    int c = clampCol((int) ((x - xMin) / cellWidth));
    int r = clampRow((int) ((y - yMin) / cellHeight));
    return congestion[r][c];
  }

  /** Get probabilistic congestion [0..1] at a board position. */
  public double getProbabilisticCongestionAt(double x, double y) {
    int c = clampCol((int) ((x - xMin) / cellWidth));
    int r = clampRow((int) ((y - yMin) / cellHeight));
    return probCongestion[r][c];
  }

  public double getMaxCongestion() {
    double max = 0;
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        max = Math.max(max, congestion[r][c]);
    return max;
  }

  /** Get max probabilistic congestion. */
  public double getMaxProbabilisticCongestion() {
    double max = 0;
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        max = Math.max(max, probCongestion[r][c]);
    return max;
  }

  /** Identify hotspot cells (congestion > 1.5). */
  public List<GridCell> getHotSpots() {
    List<GridCell> spots = new ArrayList<>();
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        if (congestion[r][c] > 1.5)
          spots.add(new GridCell(r, c, demand[r][c], capacity[r][c], congestion[r][c]));
    return spots;
  }

  /** Identify probabilistic hotspot cells (probCongestion > 0.7). */
  public List<GridCell> getProbabilisticHotSpots() {
    List<GridCell> spots = new ArrayList<>();
    for (int r = 0; r < gridRows; r++)
      for (int c = 0; c < gridCols; c++)
        if (probCongestion[r][c] > 0.7)
          spots.add(new GridCell(r, c, probabilityDemand[r][c], capacity[r][c], probCongestion[r][c]));
    return spots;
  }

  /** Generate detour guide points from standard hotspots. */
  public List<FloatPoint> getDetourGuidePoints() {
    return getHotSpots().stream()
        .map(h -> new FloatPoint(
            (float) (xMin + (h.col + 0.5) * cellWidth),
            (float) (yMin + (h.row + 0.5) * cellHeight)))
        .collect(Collectors.toList());
  }

  /** Generate detour guide points from probabilistic hotspots. */
  public List<FloatPoint> getProbabilisticDetourGuidePoints() {
    return getProbabilisticHotSpots().stream()
        .map(h -> new FloatPoint(
            (float) (xMin + (h.col + 0.5) * cellWidth),
            (float) (yMin + (h.row + 0.5) * cellHeight)))
        .collect(Collectors.toList());
  }

  /** Channel guidance: congestion gradient direction (dx, dy) at (x,y). */
  public double[] getGradientAt(double x, double y) {
    int c = clampCol((int) ((x - xMin) / cellWidth));
    int r = clampRow((int) ((y - yMin) / cellHeight));
    double gx = 0, gy = 0;
    if (c > 0) gx = congestion[r][c] - congestion[r][c-1];
    if (c < gridCols-1) gx += congestion[r][c+1] - congestion[r][c];
    if (r > 0) gy = congestion[r][c] - congestion[r-1][c];
    if (r < gridRows-1) gy += congestion[r+1][c] - congestion[r][c];
    return new double[]{gx, gy};
  }

  /** Get the probability demand matrix (for CostModel integration). */
  public double[][] getProbabilityDemand() {
    return probabilityDemand;
  }

  /** Get the probabilistic congestion matrix. */
  public double[][] getProbCongestionMatrix() {
    return probCongestion;
  }

  // ═══════════════════════════════════════════════════════════════════════

  private int clampCol(int c) { return Math.max(0, Math.min(gridCols - 1, c)); }
  private int clampRow(int r) { return Math.max(0, Math.min(gridRows - 1, r)); }

  private boolean isDifferentialLike(Net net) {
    String name = net.name != null ? net.name.toUpperCase() : "";
    return name.endsWith("_P") || name.endsWith("_N")
        || name.contains("_N_") || name.contains("_P_");
  }
}
