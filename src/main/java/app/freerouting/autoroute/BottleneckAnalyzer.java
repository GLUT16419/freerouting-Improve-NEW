package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 3 bottleneck identifier for the UTPR routing engine.
 * <p>
 * In the city-traffic metaphor, this is the <b>traffic jam analyst</b> that
 * examines the remaining gridlock after Phases 0–2 and identifies the most
 * critically congested intersections (bottlenecks) for SAT/ILP resolution.
 * <p>
 * <b>Algorithm:</b>
 * <ol>
 *   <li>Collect all remaining unrouted nets and their pin bounding boxes.</li>
 *   <li>Cluster nets by spatial proximity (shared pin zones).</li>
 *   <li>For each cluster compute a <i>density score</i> = net_count / area.</li>
 *   <li>Rank bottlenecks by density, extract the tightest (smallest-area,
 *       most-conflicted) regions for SAT solving.</li>
 * </ol>
 * Only bottlenecks with 2–8 nets are forwarded to the SAT solver; larger
 * clusters are recursively subdivided.
 */
public class BottleneckAnalyzer implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Parameters ─────────────────────────────────────────────────────────

  /** Spatial proximity threshold for net clustering (internal board units). */
  static final double CLUSTER_RADIUS = 500_000.0; // ~5mm

  /** Minimum nets in a bottleneck for SAT consideration. */
  static final int MIN_BOTTLENECK_NETS = 2;

  /** Maximum nets in a bottleneck forwarded to SAT (larger = subdivided). */
  static final int MAX_BOTTLENECK_NETS = 8;

  /** Minimum bounding box area (sq units) to qualify as a "tight" bottleneck. */
  static final double MIN_BOTTLENECK_AREA = 1000.0;

  // ═══════════════════════════════════════════════════════════════════════
  //  BottleneckRegion
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A spatially tight cluster of conflicting nets representing a bottleneck
   * intersection that needs exact SAT/ILP resolution.
   */
  public static class BottleneckRegion implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int id;
    public final double minX, minY, maxX, maxY;
    public final Set<Integer> involvedNetNumbers;
    public final int conflictCount;
    public final double densityScore;

    /** Whether this bottleneck has already been processed by the SAT solver. */
    public boolean resolved;

    /** Number of nets successfully routed after SAT processing. */
    public int resolvedCount;

    public BottleneckRegion(int id, double minX, double minY,
                            double maxX, double maxY,
                            Set<Integer> involvedNetNumbers,
                            int conflictCount) {
      this.id = id;
      this.minX = minX;
      this.minY = minY;
      this.maxX = maxX;
      this.maxY = maxY;
      this.involvedNetNumbers = involvedNetNumbers;
      this.conflictCount = conflictCount;
      this.resolved = false;
      this.resolvedCount = 0;

      double area = getArea();
      this.densityScore = area > 0 ? (double) conflictCount / area : 0;
    }

    public double getWidth()  { return maxX - minX; }
    public double getHeight() { return maxY - minY; }
    public double getArea()   { return Math.max(MIN_BOTTLENECK_AREA, getWidth() * getHeight()); }
    public double getCenterX() { return (minX + maxX) / 2.0; }
    public double getCenterY() { return (minY + maxY) / 2.0; }
    public int getNetCount()  { return involvedNetNumbers.size(); }

    public boolean isEmpty()  { return involvedNetNumbers.isEmpty(); }

    @Override
    public String toString() {
      return String.format("Bottleneck#%d nets=%d conflicts=%d area=%.0f density=%.6f [%.0f,%.0f-%.0f,%.0f]%s",
          id, involvedNetNumbers.size(), conflictCount, getArea(), densityScore,
          minX, minY, maxX, maxY, resolved ? " [RESOLVED]" : "");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final List<BottleneckRegion> bottlenecks;
  private int nextRegionId;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public BottleneckAnalyzer(BasicBoard board) {
    this.board = board;
    this.bottlenecks = new ArrayList<>();
    this.nextRegionId = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry point
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Identify bottleneck regions from a set of remaining unrouted net numbers.
   * <p>
   * Returns bottlenecks sorted by density (highest first — worst gridlock
   * gets priority for SAT resolution).
   */
  public List<BottleneckRegion> identifyBottlenecks(Set<Integer> unroutedNetNumbers) {
    bottlenecks.clear();
    nextRegionId = 0;

    if (unroutedNetNumbers == null || unroutedNetNumbers.size() < MIN_BOTTLENECK_NETS) {
      FRLogger.info("BottleneckAnalyzer: too few unrouted nets ("
          + (unroutedNetNumbers == null ? 0 : unroutedNetNumbers.size())
          + ") to form a bottleneck");
      return Collections.emptyList();
    }

    FRLogger.info("BottleneckAnalyzer: analyzing " + unroutedNetNumbers.size()
        + " unrouted nets for bottlenecks");

    // 1. Collect net bounding boxes / pin centers
    Map<Integer, List<FloatPoint>> netPinCenters = collectNetPinCenters(unroutedNetNumbers);

    // 2. Cluster by spatial proximity using DBSCAN-like approach
    List<Set<Integer>> clusters = clusterNetsByProximity(netPinCenters);

    // 3. Create BottleneckRegion for each cluster
    for (Set<Integer> cluster : clusters) {
      BottleneckRegion region = buildRegion(cluster, netPinCenters);
      if (region != null && !region.isEmpty()) {
        bottlenecks.add(region);
      }
    }

    // 4. Sort by density (descending)
    bottlenecks.sort((a, b) -> Double.compare(b.densityScore, a.densityScore));

    // 5. Subdivide oversized clusters
    subdivideOversizedBottlenecks(netPinCenters);

    FRLogger.info("BottleneckAnalyzer: identified " + bottlenecks.size()
        + " bottleneck regions for " + unroutedNetNumbers.size() + " nets");
    for (BottleneckRegion bn : bottlenecks) {
      FRLogger.info("  " + bn);
    }

    return Collections.unmodifiableList(bottlenecks);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Spatial clustering
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build a mapping from each unrouted net number to the centers of its pins.
   */
  private Map<Integer, List<FloatPoint>> collectNetPinCenters(Set<Integer> netNumbers) {
    Map<Integer, List<FloatPoint>> result = new HashMap<>();
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      List<FloatPoint> centers = new ArrayList<>();
      for (Pin pin : net.get_pins()) {
        centers.add(pin.get_center().to_float());
      }
      if (!centers.isEmpty()) {
        result.put(netNo, centers);
      }
    }
    return result;
  }

  /**
   * Cluster nets by spatial proximity using average pin center positions.
   * Uses a simple greedy approach:
   * <ol>
   *   <li>Compute each net's average pin center.</li>
   *   <li>Group nets where pin centers are within CLUSTER_RADIUS.</li>
   * </ol>
   */
  private List<Set<Integer>> clusterNetsByProximity(Map<Integer, List<FloatPoint>> netPinCenters) {
    // Compute net → average center
    Map<Integer, FloatPoint> netCentroid = new HashMap<>();
    for (Map.Entry<Integer, List<FloatPoint>> e : netPinCenters.entrySet()) {
      double cx = 0, cy = 0;
      for (FloatPoint fp : e.getValue()) {
        cx += fp.x;
        cy += fp.y;
      }
      int n = e.getValue().size();
      netCentroid.put(e.getKey(), new FloatPoint((float) (cx / n), (float) (cy / n)));
    }

    List<Integer> netList = new ArrayList<>(netCentroid.keySet());
    List<Set<Integer>> clusters = new ArrayList<>();
    boolean[] assigned = new boolean[netList.size()];

    for (int i = 0; i < netList.size(); i++) {
      if (assigned[i]) continue;
      Set<Integer> cluster = new HashSet<>();
      cluster.add(netList.get(i));
      assigned[i] = true;

      FloatPoint ci = netCentroid.get(netList.get(i));
      for (int j = i + 1; j < netList.size(); j++) {
        if (assigned[j]) continue;
        FloatPoint cj = netCentroid.get(netList.get(j));
        double dist = Math.sqrt(Math.pow(ci.x - cj.x, 2) + Math.pow(ci.y - cj.y, 2));
        if (dist <= CLUSTER_RADIUS) {
          cluster.add(netList.get(j));
          assigned[j] = true;
        }
      }
      clusters.add(cluster);
    }

    return clusters;
  }

  /**
   * Build a BottleneckRegion from a cluster of nets.
   */
  private BottleneckRegion buildRegion(Set<Integer> cluster,
                                        Map<Integer, List<FloatPoint>> netPinCenters) {
    if (cluster.size() < MIN_BOTTLENECK_NETS) return null;

    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

    for (int netNo : cluster) {
      List<FloatPoint> centers = netPinCenters.get(netNo);
      if (centers == null) continue;
      for (FloatPoint fp : centers) {
        minX = Math.min(minX, fp.x);
        minY = Math.min(minY, fp.y);
        maxX = Math.max(maxX, fp.x);
        maxY = Math.max(maxY, fp.y);
      }
    }

    // Add margin
    double mx = (maxX - minX) * 0.1;
    double my = (maxY - minY) * 0.1;

    return new BottleneckRegion(
        nextRegionId++,
        minX - mx, minY - my, maxX + mx, maxY + my,
        cluster, cluster.size());
  }

  /**
   * Subdivide any bottleneck with more than MAX_BOTTLENECK_NETS.
   * Uses simple 2-means on net centroids, splitting along the longer axis.
   */
  private void subdivideOversizedBottlenecks(Map<Integer, List<FloatPoint>> netPinCenters) {
    List<BottleneckRegion> toAdd = new ArrayList<>();
    Iterator<BottleneckRegion> it = bottlenecks.iterator();

    while (it.hasNext()) {
      BottleneckRegion bn = it.next();
      if (bn.getNetCount() <= MAX_BOTTLENECK_NETS) continue;

      it.remove();
      FRLogger.debug("BottleneckAnalyzer: subdividing bottleneck " + bn.id
          + " (" + bn.getNetCount() + " nets)");

      // Split along the longer axis using median
      List<Integer> netList = new ArrayList<>(bn.involvedNetNumbers);
      boolean splitHorizontal = bn.getWidth() >= bn.getHeight();

      netList.sort(splitHorizontal
          ? Comparator.comparingDouble(n -> getNetCenterX(n, netPinCenters))
          : Comparator.comparingDouble(n -> getNetCenterY(n, netPinCenters)));

      int mid = netList.size() / 2;

      Set<Integer> leftCluster = new HashSet<>(netList.subList(0, mid));
      Set<Integer> rightCluster = new HashSet<>(netList.subList(mid, netList.size()));

      for (Set<Integer> subCluster : Arrays.asList(leftCluster, rightCluster)) {
        BottleneckRegion sub = buildRegion(subCluster, netPinCenters);
        if (sub != null) {
          toAdd.add(sub);
        }
      }
    }

    bottlenecks.addAll(toAdd);
    // Re-sort by density
    bottlenecks.sort((a, b) -> Double.compare(b.densityScore, a.densityScore));
  }

  private double getNetCenterX(int netNo, Map<Integer, List<FloatPoint>> netPinCenters) {
    List<FloatPoint> centers = netPinCenters.get(netNo);
    if (centers == null || centers.isEmpty()) return 0;
    double sum = 0;
    for (FloatPoint fp : centers) sum += fp.x;
    return sum / centers.size();
  }

  private double getNetCenterY(int netNo, Map<Integer, List<FloatPoint>> netPinCenters) {
    List<FloatPoint> centers = netPinCenters.get(netNo);
    if (centers == null || centers.isEmpty()) return 0;
    double sum = 0;
    for (FloatPoint fp : centers) sum += fp.y;
    return sum / centers.size();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query
  // ═══════════════════════════════════════════════════════════════════════

  /** Get all identified bottlenecks (sorted by density descending). */
  public List<BottleneckRegion> getBottlenecks() {
    return Collections.unmodifiableList(bottlenecks);
  }

  /** Get the number of identified bottlenecks. */
  public int getBottleneckCount() {
    return bottlenecks.size();
  }

  /** Get total nets across all bottlenecks. */
  public int getTotalNetsInBottlenecks() {
    return bottlenecks.stream().mapToInt(BottleneckRegion::getNetCount).sum();
  }

  /** Mark a bottleneck as resolved. */
  public void markResolved(int regionId, int resolvedCount) {
    for (BottleneckRegion bn : bottlenecks) {
      if (bn.id == regionId) {
        bn.resolved = true;
        bn.resolvedCount = resolvedCount;
        break;
      }
    }
  }

  /** Get unresolved bottlenecks only. */
  public List<BottleneckRegion> getUnresolved() {
    return bottlenecks.stream()
        .filter(bn -> !bn.resolved)
        .collect(Collectors.toList());
  }

  /** Reset all bottlenecks to unresolved state. */
  public void reset() {
    for (BottleneckRegion bn : bottlenecks) {
      bn.resolved = false;
      bn.resolvedCount = 0;
    }
  }
}
