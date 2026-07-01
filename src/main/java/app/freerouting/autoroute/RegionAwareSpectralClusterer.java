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
 * UTPR V7 — Region-Aware Spectral Clusterer.
 * <p>
 * Enhances the existing {@link SpectralClusterer} by incorporating net type
 * affinity and grouping information (differential pairs, length-match groups)
 * into the similarity distance metric, so that nets of the same type or group
 * are more likely to fall into the same district (城区).
 * <p>
 * The enhanced distance: <pre>
 *   dist_enhanced(u,v) = α × euclidean + β × typeDissimilarity(u,v) + γ × groupAffinity(u,v)
 * </pre>
 * After clustering, post-processing forces differential pair co-location and
 * handles length-match group cohesion/splitting.
 */
public class RegionAwareSpectralClusterer implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Distance weights ─────────────────────────────────────────────────
  private static final double ALPHA = 0.50; // spatial weight
  private static final double BETA = 0.30;  // type dissimilarity weight
  private static final double GAMMA = 0.20; // group affinity weight

  // ── Type dissimilarity constants ─────────────────────────────────────
  private static final double SAME_TYPE = 0.0;
  private static final double SAME_MAJOR = 0.3;
  private static final double COMPATIBLE = 0.6;
  private static final double CONFLICT = 1.0;
  private static final double DEFAULT_DISSIM = 0.5;

  // ── Group affinity constants ─────────────────────────────────────────
  private static final double SAME_DIFF_PAIR_AFFINITY = -0.5;
  private static final double SAME_LENGTH_GROUP_AFFINITY = -0.3;
  private static final double SAME_BUS_AFFINITY = -0.2;
  private static final double CROSS_FUNCTION_AFFINITY = 0.1;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final AutomaticNetworkAnalyzer networkAnalyzer;

  // Cached data for distance computation
  private Map<Integer, NetClass> netClassMap;
  private Map<Integer, int[]> diffPairs;
  private Map<Integer, AutomaticNetworkAnalyzer.LengthMatchGroup> lengthGroups;
  private Map<Integer, FloatPoint> netCenters;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public RegionAwareSpectralClusterer(BasicBoard board,
                                      AutomaticNetworkAnalyzer networkAnalyzer) {
    this.board = board;
    this.networkAnalyzer = networkAnalyzer;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main entry
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Run region-aware spectral clustering on the given set of net numbers.
   * Returns a list of NetCluster objects, with differential pair co-location
   * enforcement and length-match group cohesion/splitting applied.
   */
  public List<NetCluster> cluster(Set<Integer> netNumbers) {
    long t0 = System.currentTimeMillis();
    List<Integer> netList = new ArrayList<>(netNumbers);
    if (netList.size() < 4) {
      return fallbackClustering(netList);
    }

    // Load analysis data
    this.netClassMap = networkAnalyzer.getNetClassMap();
    this.diffPairs = networkAnalyzer.getDiffPairs();
    this.lengthGroups = networkAnalyzer.getLengthGroups();

    // Precompute net centers
    precomputeNetCenters(netList);

    int n = netList.size();

    // 1. Build enhanced similarity matrix
    double[][] sim = buildEnhancedSimilarityMatrix(netList);

    // 2. Degree matrix → normalized Laplacian
    double[] degree = new double[n];
    for (int i = 0; i < n; i++)
      for (int j = 0; j < n; j++)
        degree[i] += sim[i][j];
    for (int i = 0; i < n; i++)
      degree[i] = degree[i] > 1e-12 ? 1.0 / Math.sqrt(degree[i]) : 0;

    // 3. Power iteration for eigenvectors
    int k = Math.min(6, Math.max(2, n / 5));
    double[][] eigenvectors = powerIteration(sim, degree, n, k);

    // 4. K-means clustering
    int[] assignments = kMeans(eigenvectors, n, k);

    // 5. Build NetCluster objects
    Map<Integer, List<Integer>> clusterMap = new HashMap<>();
    for (int i = 0; i < n; i++) {
      clusterMap.computeIfAbsent(assignments[i], _k -> new ArrayList<>()).add(netList.get(i));
    }
    List<NetCluster> clusters = buildClusters(clusterMap);

    // 6. Post-processing: differential pair forced co-location
    clusters = enforceDiffPairCoLocation(clusters, netList);

    // 7. Post-processing: length-match group cohesion / split
    clusters = processLengthGroupCohesion(clusters);

    FRLogger.info("RegionAwareSpectralClusterer: " + netList.size()
        + " nets → " + clusters.size() + " clusters (" + k + " eigenvectors) in "
        + (System.currentTimeMillis() - t0) + "ms");
    return clusters;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Enhanced similarity matrix
  // ═══════════════════════════════════════════════════════════════════════

  private double[][] buildEnhancedSimilarityMatrix(List<Integer> netList) {
    int n = netList.size();
    double[][] sim = new double[n][n];

    for (int i = 0; i < n; i++) {
      sim[i][i] = 1.0;
      for (int j = i + 1; j < n; j++) {
        int netU = netList.get(i);
        int netV = netList.get(j);

        // Spatial distance
        double euclidean = computeEuclideanDistance(netU, netV);

        // Type dissimilarity
        double typeDissim = computeTypeDissimilarity(netU, netV);

        // Group affinity
        double groupAff = computeGroupAffinity(netU, netV);

        // Enhanced distance
        double enhancedDist = ALPHA * euclidean + BETA * typeDissim + GAMMA * groupAff;

        // Convert distance to similarity: exp(-dist)
        double s = Math.exp(-enhancedDist);
        sim[i][j] = sim[j][i] = s;
      }
    }
    return sim;
  }

  /** Compute Euclidean distance between two net centers. */
  private double computeEuclideanDistance(int netU, int netV) {
    FloatPoint cU = netCenters.get(netU);
    FloatPoint cV = netCenters.get(netV);
    if (cU == null || cV == null) return Double.MAX_VALUE;
    return Math.sqrt(Math.pow(cU.x - cV.x, 2) + Math.pow(cU.y - cV.y, 2));
  }

  /** Compute type dissimilarity between two nets on a 4-level scale. */
  private double computeTypeDissimilarity(int netU, int netV) {
    NetClass ncU = netClassMap.get(netU);
    NetClass ncV = netClassMap.get(netV);
    if (ncU == null || ncV == null) return DEFAULT_DISSIM;

    NetClass.NetType typeU = ncU.getType();
    NetClass.NetType typeV = ncV.getType();

    if (typeU == typeV) return SAME_TYPE;

    // Same major type groups
    if (isSameMajorType(typeU, typeV)) return SAME_MAJOR;

    // Compatible types
    if (isCompatibleType(typeU, typeV)) return COMPATIBLE;

    // Conflicting types
    if (isTypeConflict(typeU, typeV)) return CONFLICT;

    return DEFAULT_DISSIM;
  }

  private boolean isSameMajorType(NetClass.NetType a, NetClass.NetType b) {
    // DDR data & DDR command are same major
    if (a == NetClass.NetType.DDR_DATA && b == NetClass.NetType.DDR_CMD) return true;
    if (a == NetClass.NetType.DDR_CMD && b == NetClass.NetType.DDR_DATA) return true;
    // DIFF_PAIR and DIFF_PAIR_WEAK
    if (a == NetClass.NetType.DIFF_PAIR && b == NetClass.NetType.DIFF_PAIR_WEAK) return true;
    if (a == NetClass.NetType.DIFF_PAIR_WEAK && b == NetClass.NetType.DIFF_PAIR) return true;
    return false;
  }

  private boolean isCompatibleType(NetClass.NetType a, NetClass.NetType b) {
    // CLOCK is compatible with DIFF_PAIR (often differential clock)
    if (a == NetClass.NetType.CLOCK && (b == NetClass.NetType.DIFF_PAIR
        || b == NetClass.NetType.DIFF_PAIR_WEAK)) return true;
    if (b == NetClass.NetType.CLOCK && (a == NetClass.NetType.DIFF_PAIR
        || a == NetClass.NetType.DIFF_PAIR_WEAK)) return true;
    // HIGH_SPEED_SERIAL compatible with ETHERNET, USB, HDMI
    if (a == NetClass.NetType.HIGH_SPEED_SERIAL && (b == NetClass.NetType.ETHERNET
        || b == NetClass.NetType.USB || b == NetClass.NetType.HDMI)) return true;
    if (b == NetClass.NetType.HIGH_SPEED_SERIAL && (a == NetClass.NetType.ETHERNET
        || a == NetClass.NetType.USB || a == NetClass.NetType.HDMI)) return true;
    // BUS_GROUPED with DDR
    if (a == NetClass.NetType.BUS_GROUPED && (b == NetClass.NetType.DDR_DATA
        || b == NetClass.NetType.DDR_CMD)) return true;
    if (b == NetClass.NetType.BUS_GROUPED && (a == NetClass.NetType.DDR_DATA
        || a == NetClass.NetType.DDR_CMD)) return true;
    // NORMAL compatible with I2C_SPI
    if (a == NetClass.NetType.NORMAL && b == NetClass.NetType.I2C_SPI) return true;
    if (a == NetClass.NetType.I2C_SPI && b == NetClass.NetType.NORMAL) return true;
    return false;
  }

  private boolean isTypeConflict(NetClass.NetType a, NetClass.NetType b) {
    // ANALOG and POWER_GND conflict
    if (a == NetClass.NetType.ANALOG && b == NetClass.NetType.POWER_GND) return true;
    if (a == NetClass.NetType.POWER_GND && b == NetClass.NetType.ANALOG) return true;
    // ANALOG and HIGH_SPEED_SERIAL
    if (a == NetClass.NetType.ANALOG && b == NetClass.NetType.HIGH_SPEED_SERIAL) return true;
    if (a == NetClass.NetType.HIGH_SPEED_SERIAL && b == NetClass.NetType.ANALOG) return true;
    // CLOCK and POWER_GND
    if (a == NetClass.NetType.CLOCK && b == NetClass.NetType.POWER_GND) return true;
    if (a == NetClass.NetType.POWER_GND && b == NetClass.NetType.CLOCK) return true;
    return false;
  }

  /** Compute group affinity (negative = attraction, positive = repulsion). */
  private double computeGroupAffinity(int netU, int netV) {
    NetClass ncU = netClassMap.get(netU);
    NetClass ncV = netClassMap.get(netV);
    if (ncU == null || ncV == null) return 0.0;

    // Same differential pair → strong attraction
    if (ncU.getDiffPairId() >= 0 && ncU.getDiffPairId() == ncV.getDiffPairId()) {
      return SAME_DIFF_PAIR_AFFINITY;
    }

    // Same length-match group → moderate attraction
    if (ncU.getLengthGroupId() >= 0 && ncU.getLengthGroupId() == ncV.getLengthGroupId()) {
      return SAME_LENGTH_GROUP_AFFINITY;
    }

    // Same bus prefix → mild attraction
    if (ncU.getGroupPrefix() != null && ncU.getGroupPrefix().equals(ncV.getGroupPrefix())) {
      return SAME_BUS_AFFINITY;
    }

    return 0.0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Post-processing: differential pair forced co-location
  // ═══════════════════════════════════════════════════════════════════════

  private List<NetCluster> enforceDiffPairCoLocation(List<NetCluster> clusters,
                                                     List<Integer> netList) {
    // No diff pairs → nothing to do
    if (diffPairs.isEmpty()) return clusters;

    // Build net → cluster mapping
    Map<Integer, NetCluster> netToCluster = new HashMap<>();
    for (NetCluster cluster : clusters) {
      for (int netNo : cluster.getNetNumbers()) {
        netToCluster.put(netNo, cluster);
      }
    }

    boolean changed = true;
    int maxIter = 5;
    while (changed && maxIter-- > 0) {
      changed = false;
      for (Map.Entry<Integer, int[]> entry : diffPairs.entrySet()) {
        int[] pair = entry.getValue();
        NetCluster cP = netToCluster.get(pair[0]);
        NetCluster cN = netToCluster.get(pair[1]);
        if (cP == null || cN == null) continue;
        if (cP.getClusterId() == cN.getClusterId()) continue; // already same cluster

        // Merge P's cluster into N's cluster (or vice versa)
        changed = true;
        NetCluster source = cP;
        NetCluster target = cN;

        // Move all nets from source to target
        for (int netNo : new ArrayList<>(source.getNetNumbers())) {
          Net net = board.rules.nets.get(netNo);
          if (net == null) continue;
          Collection<Pin> pins = net.get_pins();
          double nMinX = Double.MAX_VALUE, nMinY = Double.MAX_VALUE;
          double nMaxX = -Double.MAX_VALUE, nMaxY = -Double.MAX_VALUE;
          for (Pin pin : pins) {
            FloatPoint p = pin.get_center().to_float();
            nMinX = Math.min(nMinX, p.x); nMaxX = Math.max(nMaxX, p.x);
            nMinY = Math.min(nMinY, p.y); nMaxY = Math.max(nMaxY, p.y);
          }
          NetClass nc = netClassMap.get(netNo);
          target.addNet(netNo, net.name,
              nc != null ? toLegacyNetType(nc.getType()) : NetType.SIGNAL,
              nMinX, nMinY, nMaxX, nMaxY);
          netToCluster.put(netNo, target);
        }

        clusters.remove(source);
        break; // restart iteration after modification
      }
    }

    return clusters;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Post-processing: length-match group cohesion / split
  // ═══════════════════════════════════════════════════════════════════════

  private List<NetCluster> processLengthGroupCohesion(List<NetCluster> clusters) {
    if (lengthGroups.isEmpty()) return clusters;

    // For each length-match group, check if members are spread across clusters
    for (AutomaticNetworkAnalyzer.LengthMatchGroup lg : lengthGroups.values()) {
      if (lg.netNumbers.isEmpty()) continue;

      // Group members by cluster
      Map<Integer, List<Integer>> clusterMembers = new HashMap<>();
      for (int netNo : lg.netNumbers) {
        for (NetCluster cl : clusters) {
          if (cl.getNetNumbers().contains(netNo)) {
            clusterMembers.computeIfAbsent(cl.getClusterId(), _k -> new ArrayList<>()).add(netNo);
            break;
          }
        }
      }

      if (clusterMembers.size() <= 1) continue; // already cohesive

      // Large groups (>8 nets) can be split along spatial axis
      if (lg.isLargeGroup()) {
        // Allow split — members stay in their current clusters
        FRLogger.debug("RegionAwareSpectralClusterer: LengthMatchGroup#" + lg.groupId
            + " split across " + clusterMembers.size() + " clusters (group size > 8)");
        continue;
      }

      // Small/medium groups: merge into the cluster with the most members
      Map.Entry<Integer, List<Integer>> best = Collections.max(
          clusterMembers.entrySet(), Map.Entry.comparingByValue(
              (a, b) -> Integer.compare(a.size(), b.size())));
      int targetClusterId = best.getKey();
      NetCluster targetCluster = findClusterById(clusters, targetClusterId);

      // Move stray members to the target cluster
      for (Map.Entry<Integer, List<Integer>> entry : clusterMembers.entrySet()) {
        if (entry.getKey() == targetClusterId) continue;
        for (int netNo : entry.getValue()) {
          Net net = board.rules.nets.get(netNo);
          if (net == null) continue;
          Collection<Pin> pins = net.get_pins();
          double nMinX = Double.MAX_VALUE, nMinY = Double.MAX_VALUE;
          double nMaxX = -Double.MAX_VALUE, nMaxY = -Double.MAX_VALUE;
          for (Pin pin : pins) {
            FloatPoint p = pin.get_center().to_float();
            nMinX = Math.min(nMinX, p.x); nMaxX = Math.max(nMaxX, p.x);
            nMinY = Math.min(nMinY, p.y); nMaxY = Math.max(nMaxY, p.y);
          }
          NetClass nc = netClassMap.get(netNo);
          targetCluster.addNet(netNo, net.name,
              nc != null ? toLegacyNetType(nc.getType()) : NetType.SIGNAL,
              nMinX, nMinY, nMaxX, nMaxY);
        }
      }
    }

    return clusters;
  }

  private NetCluster findClusterById(List<NetCluster> clusters, int id) {
    for (NetCluster cl : clusters) {
      if (cl.getClusterId() == id) return cl;
    }
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Spectral clustering internals (adapted from SpectralClusterer)
  // ═══════════════════════════════════════════════════════════════════════

  private double[][] powerIteration(double[][] sim, double[] degree, int n, int k) {
    double[][] vecs = new double[k][n];
    Random rng = new Random(42);
    for (int i = 0; i < k; i++)
      for (int j = 0; j < n; j++)
        vecs[i][j] = rng.nextDouble() - 0.5;

    final int POWER_ITERS = 20;
    for (int iter = 0; iter < POWER_ITERS; iter++) {
      for (int i = 0; i < k; i++) {
        double[] newVec = new double[n];
        for (int u = 0; u < n; u++) {
          double sum = 0;
          for (int v = 0; v < n; v++)
            sum += degree[u] * sim[u][v] * degree[v] * vecs[i][v];
          newVec[u] = sum;
        }
        // Gram-Schmidt
        for (int p = 0; p < i; p++) {
          double dot = 0;
          for (int u = 0; u < n; u++) dot += newVec[u] * vecs[p][u];
          for (int u = 0; u < n; u++) newVec[u] -= dot * vecs[p][u];
        }
        double norm = 0;
        for (int u = 0; u < n; u++) norm += newVec[u] * newVec[u];
        norm = Math.sqrt(norm);
        if (norm > 1e-12)
          for (int u = 0; u < n; u++) vecs[i][u] = newVec[u] / norm;
      }
    }
    return vecs;
  }

  private int[] kMeans(double[][] eigenvectors, int n, int k) {
    int eigenCount = eigenvectors.length;
    int kEff = Math.min(k, Math.max(2, n / 3));
    int[] assignment = new int[n];
    double[][] centroids = new double[kEff][eigenCount];

    Random rng = new Random(7);
    int firstNet = rng.nextInt(n);
    for (int dk = 0; dk < eigenCount; dk++)
      centroids[0][dk] = eigenvectors[dk][firstNet];

    for (int ci = 1; ci < kEff; ci++) {
      double maxDist = -1;
      int bestNet = 0;
      for (int i = 0; i < n; i++) {
        double minD = Double.MAX_VALUE;
        for (int cj = 0; cj < ci; cj++) {
          double d = 0;
          for (int dk = 0; dk < eigenCount; dk++) {
            double diff = eigenvectors[dk][i] - centroids[cj][dk];
            d += diff * diff;
          }
          minD = Math.min(minD, Math.sqrt(d));
        }
        if (minD > maxDist) { maxDist = minD; bestNet = i; }
      }
      for (int dk = 0; dk < eigenCount; dk++)
        centroids[ci][dk] = eigenvectors[dk][bestNet];
    }

    boolean changed = true;
    for (int iter = 0; iter < 20 && changed; iter++) {
      changed = false;
      for (int i = 0; i < n; i++) {
        double bestD = Double.MAX_VALUE;
        int bestC = 0;
        for (int ci = 0; ci < kEff; ci++) {
          double d = 0;
          for (int dk = 0; dk < eigenCount; dk++) {
            double diff = eigenvectors[dk][i] - centroids[ci][dk];
            d += diff * diff;
          }
          d = Math.sqrt(d);
          if (d < bestD) { bestD = d; bestC = ci; }
        }
        if (assignment[i] != bestC) { assignment[i] = bestC; changed = true; }
      }
      for (int ci = 0; ci < kEff; ci++) {
        double[] sum = new double[eigenCount];
        int count = 0;
        for (int i = 0; i < n; i++) {
          if (assignment[i] == ci) {
            for (int dk = 0; dk < eigenCount; dk++) sum[dk] += eigenvectors[dk][i];
            count++;
          }
        }
        if (count > 0)
          for (int dk = 0; dk < eigenCount; dk++) centroids[ci][dk] = sum[dk] / count;
      }
    }
    return assignment;
  }

  private List<NetCluster> buildClusters(Map<Integer, List<Integer>> clusterMap) {
    List<NetCluster> result = new ArrayList<>();
    int cid = 0;
    for (Map.Entry<Integer, List<Integer>> entry : clusterMap.entrySet()) {
      NetCluster cluster = new NetCluster(cid++);
      for (int netNo : entry.getValue()) {
        Net net = board.rules.nets.get(netNo);
        if (net == null) continue;
        Collection<Pin> pins = net.get_pins();
        double nMinX = Double.MAX_VALUE, nMinY = Double.MAX_VALUE;
        double nMaxX = -Double.MAX_VALUE, nMaxY = -Double.MAX_VALUE;
        for (Pin pin : pins) {
          FloatPoint c = pin.get_center().to_float();
          nMinX = Math.min(nMinX, c.x); nMaxX = Math.max(nMaxX, c.x);
          nMinY = Math.min(nMinY, c.y); nMaxY = Math.max(nMaxY, c.y);
        }
        NetClass nc = netClassMap.get(netNo);
        cluster.addNet(netNo, net.name,
            nc != null ? toLegacyNetType(nc.getType()) : NetType.SIGNAL,
            nMinX, nMinY, nMaxX, nMaxY);
      }
      result.add(cluster);
    }
    return result;
  }

  private List<NetCluster> fallbackClustering(List<Integer> netList) {
    return NetCluster.clusterNets(board, new HashSet<>(netList));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Convert NetClass.NetType to the old NetType enum for NetCluster compatibility. */
  private static NetType toLegacyNetType(NetClass.NetType type) {
    if (type == NetClass.NetType.POWER_GND) {
      return NetType.POWER; // POWER_GND maps to POWER for cluster purposes
    }
    return NetType.SIGNAL;
  }

  private void precomputeNetCenters(List<Integer> netList) {
    netCenters = new HashMap<>();
    for (int netNo : netList) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      double cx = 0, cy = 0;
      int cnt = 0;
      for (Pin pin : net.get_pins()) {
        FloatPoint p = pin.get_center().to_float();
        cx += p.x; cy += p.y; cnt++;
      }
      if (cnt > 0) netCenters.put(netNo, new FloatPoint(cx / cnt, cy / cnt));
    }
  }
}
