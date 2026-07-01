package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.util.*;
import java.util.stream.Collectors;

/**
 * V6: Spectral clustering for net grouping.
 *
 * Builds a similarity graph from net spatial proximity + netlist connectivity,
 * computes the Laplacian eigen-decomposition (power iteration), and applies
 * K-means on the dominant eigenvectors to obtain clusters.
 *
 * For small N (< 10) falls back to simple spatial clustering.
 */
public class SpectralClusterer {

  private static final int MAX_EIGENVECTORS = 6;
  private static final int POWER_ITERATIONS = 20;
  private static final double SIMILARITY_SIGMA = 50000; // units

  private final BasicBoard board;

  public SpectralClusterer(BasicBoard board) {
    this.board = board;
  }

  /**
   * Perform spectral clustering on the given set of net numbers.
   * @return list of NetCluster objects
   */
  public List<NetCluster> cluster(Set<Integer> netNumbers) {
    List<Integer> netList = new ArrayList<>(netNumbers);
    if (netList.size() < 4) {
      return fallbackSpatialClustering(netList);
    }
    int n = netList.size();

    // 1. Build similarity matrix (n x n)
    double[][] sim = buildSimilarityMatrix(netList);

    // 2. Degree matrix → normalized Laplacian L = I - D^(-1/2) * S * D^(-1/2)
    double[] degree = new double[n];
    for (int i = 0; i < n; i++)
      for (int j = 0; j < n; j++)
        degree[i] += sim[i][j];
    for (int i = 0; i < n; i++)
      degree[i] = degree[i] > 1e-12 ? 1.0 / Math.sqrt(degree[i]) : 0;

    // 3. Power iteration for eigenvectors of normalized Laplacian
    int k = Math.min(MAX_EIGENVECTORS, Math.max(2, n / 5));
    double[][] eigenvectors = powerIteration(sim, degree, n, k);

    // 4. K-means clustering on eigenvectors (each net has k features)
    int[] assignments = kMeans(eigenvectors, n, k);

    // 5. Build NetCluster objects
    Map<Integer, List<Integer>> clusterMap = new HashMap<>();
    for (int i = 0; i < n; i++) {
      clusterMap.computeIfAbsent(assignments[i], _k -> new ArrayList<>()).add(netList.get(i));
    }

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
        cluster.addNet(netNo, net.name, NetType.fromName(net.name),
            nMinX, nMinY, nMaxX, nMaxY);
      }
      result.add(cluster);
    }

    FRLogger.info("SpectralClusterer: " + netList.size() + " nets → " + result.size()
        + " clusters (eigenvectors=" + k + ")");
    return result;
  }

  /** Build similarity matrix from spatial proximity + netlist sharing. */
  private double[][] buildSimilarityMatrix(List<Integer> netList) {
    int n = netList.size();
    double[][] sim = new double[n][n];

    // Precompute net centers
    FloatPoint[] centers = new FloatPoint[n];
    for (int i = 0; i < n; i++) {
      Net net = board.rules.nets.get(netList.get(i));
      if (net == null) continue;
      Collection<Pin> pins = net.get_pins();
      double cx = 0, cy = 0; int cnt = 0;
      for (Pin pin : pins) {
        FloatPoint p = pin.get_center().to_float();
        cx += p.x; cy += p.y; cnt++;
      }
      if (cnt > 0) centers[i] = new FloatPoint(cx / cnt, cy / cnt);
    }

    for (int i = 0; i < n; i++) {
      sim[i][i] = 1.0;
      for (int j = i + 1; j < n; j++) {
        double dist = centers[i] != null && centers[j] != null
            ? Math.sqrt(Math.pow(centers[i].x - centers[j].x, 2)
                       + Math.pow(centers[i].y - centers[j].y, 2))
            : Double.MAX_VALUE;
        double spatialSim = dist < 1e-12 ? 1.0
            : Math.exp(-dist * dist / (2 * SIMILARITY_SIGMA * SIMILARITY_SIGMA));

        // Netlist connectivity bonus: share a component?
        double netlistSim = 0;
        Net ni = board.rules.nets.get(netList.get(i));
        Net nj = board.rules.nets.get(netList.get(j));
        if (ni != null && nj != null) {
          Set<Integer> compsI = ni.get_pins().stream()
              .map(Pin::get_component_no).collect(Collectors.toSet());
          Set<Integer> compsJ = nj.get_pins().stream()
              .map(Pin::get_component_no).collect(Collectors.toSet());
          compsI.retainAll(compsJ);
          if (!compsI.isEmpty()) netlistSim = 0.3;
        }

        double s = 0.7 * spatialSim + 0.3 * netlistSim;
        sim[i][j] = sim[j][i] = s;
      }
    }
    return sim;
  }

  /**
   * Power iteration to extract top-k eigenvectors of the normalized Laplacian.
   * Returns double[k][n] where each row is an eigenvector of dimension n.
   */
  private double[][] powerIteration(double[][] sim, double[] degree, int n, int k) {
    // Apply D^(-1/2) * S * D^(-1/2) normalization on the fly
    // Initialize random vectors
    double[][] vecs = new double[k][n];
    Random rng = new Random(42);
    for (int i = 0; i < k; i++)
      for (int j = 0; j < n; j++)
        vecs[i][j] = rng.nextDouble() - 0.5;

    for (int iter = 0; iter < POWER_ITERATIONS; iter++) {
      for (int i = 0; i < k; i++) {
        double[] newVec = new double[n];
        for (int u = 0; u < n; u++) {
          double sum = 0;
          for (int v = 0; v < n; v++)
            sum += degree[u] * sim[u][v] * degree[v] * vecs[i][v];
          newVec[u] = sum;
        }
        // Gram-Schmidt orthogonalization
        for (int p = 0; p < i; p++) {
          double dot = 0;
          for (int u = 0; u < n; u++) dot += newVec[u] * vecs[p][u];
          for (int u = 0; u < n; u++) newVec[u] -= dot * vecs[p][u];
        }
        // Normalize
        double norm = 0;
        for (int u = 0; u < n; u++) norm += newVec[u] * newVec[u];
        norm = Math.sqrt(norm);
        if (norm > 1e-12)
          for (int u = 0; u < n; u++) vecs[i][u] = newVec[u] / norm;
      }
    }
    return vecs;
  }

  /**
   * K-means on net feature vectors derived from eigenvectors.
   *
   * eigenvectors = double[k][n] — k eigenvectors, each of dimension n.
   * Net i is represented by feature vector f[i] = { eigenvectors[0][i], eigenvectors[1][i], ... }.
   */
  private int[] kMeans(double[][] eigenvectors, int n, int k) {
    int eigenCount = eigenvectors.length; // k
    int kEff = Math.min(k, Math.max(2, n / 3));
    int[] assignment = new int[n];
    double[][] centroids = new double[kEff][eigenCount];

    // 1. Initialize centroids: pick kEff distinct nets as initial seeds
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
        if (minD > maxDist) {
          maxDist = minD;
          bestNet = i;
        }
      }
      for (int dk = 0; dk < eigenCount; dk++)
        centroids[ci][dk] = eigenvectors[dk][bestNet];
    }

    // 2. Iterate assignment + centroid update
    boolean changed = true;
    for (int iter = 0; iter < 20 && changed; iter++) {
      changed = false;

      // Assign each net to nearest centroid
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
          if (d < bestD) {
            bestD = d;
            bestC = ci;
          }
        }
        if (assignment[i] != bestC) {
          assignment[i] = bestC;
          changed = true;
        }
      }

      // Update centroids
      for (int ci = 0; ci < kEff; ci++) {
        double[] sum = new double[eigenCount];
        int count = 0;
        for (int i = 0; i < n; i++) {
          if (assignment[i] == ci) {
            for (int dk = 0; dk < eigenCount; dk++)
              sum[dk] += eigenvectors[dk][i];
            count++;
          }
        }
        if (count > 0) {
          for (int dk = 0; dk < eigenCount; dk++)
            centroids[ci][dk] = sum[dk] / count;
        }
      }
    }
    return assignment;
  }

  /** Fallback: simple overlap-based spatial clustering for small N. */
  private List<NetCluster> fallbackSpatialClustering(List<Integer> netList) {
    return NetCluster.clusterNets(board, new HashSet<>(netList));
  }
}
