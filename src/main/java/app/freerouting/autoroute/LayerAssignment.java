package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Layer;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;

/**
 * Soft-cost Layer Assignment Algorithm for Phase 2.
 * <p>
 * Assigns each cluster to one or more routing layers using soft costs
 * rather than hard constraints. This allows:
 * <ul>
 *   <li>Multiple clusters to share a layer if congestion allows</li>
 *   <li>Clusters to span multiple layers when beneficial</li>
 *   <li>Reassignment during negotiation iterations</li>
 * </ul>
 * <p>
 * The assignment considers:
 * <ul>
 *   <li>Layer function (SIGNAL vs MIXED vs PLANE)</li>
 *   <li>Current layer utilization/congestion</li>
 *   <li>Cluster size and aspect ratio</li>
 *   <li>Preferred direction per layer (horizontal/vertical alternating)</li>
 *   <li>Net type (power/ground prefer plane layers)</li>
 * </ul>
 */
public class LayerAssignment implements Serializable {

  private final BasicBoard board;
  private final CongestionMap congestionMap;
  private final int layerCount;

  // Per-layer load tracking for soft balancing
  private final double[] layerLoad;

  public LayerAssignment(BasicBoard board, CongestionMap congestionMap) {
    this.board = board;
    this.congestionMap = congestionMap;
    this.layerCount = board.get_layer_count();
    this.layerLoad = new double[layerCount];
  }

  /**
   * Assigns layers to a list of clusters.
   * Each cluster gets:
   * <ul>
   *   <li>A primary layer (for most routing)</li>
   *   <li>An optional secondary layer (for overflow / crossing)</li>
   * </ul>
   *
   * @param clusters The clusters to assign
   * @param layerFunctions Pre-assigned layer functions
   */
  public void assignLayers(List<NetCluster> clusters, LayerFunction[] layerFunctions) {
    FRLogger.debug("Phase 2: Starting layer assignment for " + clusters.size() + " clusters...");

    // Reset layer load
    Arrays.fill(layerLoad, 0.0);
    initializeLayerLoad(layerFunctions);

    // Sort clusters by size (largest first) for better load balancing
    clusters.sort((a, b) -> Integer.compare(b.getNetCount(), a.getNetCount()));

    for (NetCluster cluster : clusters) {
      assignClusterLayer(cluster, layerFunctions);
    }

    logAssignment(clusters, layerFunctions);
  }

  /**
   * Initializes layer loads based on current congestion.
   */
  private void initializeLayerLoad(LayerFunction[] layerFunctions) {
    for (int i = 0; i < layerCount; i++) {
      if (layerFunctions != null && i < layerFunctions.length) {
        LayerFunction func = layerFunctions[i];
        if (func == LayerFunction.POWER_PLANE || func == LayerFunction.GROUND_PLANE) {
          // Planes have zero routing load (but can accommodate power nets)
          layerLoad[i] = 0.0;
        } else if (func == LayerFunction.MECHANICAL || func == LayerFunction.OTHER) {
          // Non-routable layers
          layerLoad[i] = Double.MAX_VALUE;
        }
      }

      // Add base load from congestion
      double avgCong = 0.0;
      int count = 0;
      for (int r = 0; r < congestionMap.getGridRows(); r++) {
        for (int c = 0; c < congestionMap.getGridCols(); c++) {
          avgCong += congestionMap.getCellCongestion(i, r, c);
          count++;
        }
      }
      layerLoad[i] += count > 0 ? avgCong / count * 10.0 : 0.0;
    }
  }

  /**
   * Assigns the best layer(s) to a single cluster.
   */
  private void assignClusterLayer(NetCluster cluster, LayerFunction[] layerFunctions) {
    double bestScore = Double.MAX_VALUE;
    int bestLayer = -1;
    double secondBestScore = Double.MAX_VALUE;
    int secondBestLayer = -1;

    for (int i = 0; i < layerCount; i++) {
      if (!isLayerUsable(i, layerFunctions)) continue;

      double score = computeLayerScore(cluster, i, layerFunctions);

      if (score < bestScore) {
        secondBestScore = bestScore;
        secondBestLayer = bestLayer;
        bestScore = score;
        bestLayer = i;
      } else if (score < secondBestScore) {
        secondBestScore = score;
        secondBestLayer = i;
      }
    }

    if (bestLayer >= 0) {
      cluster.setPrimaryLayer(bestLayer);
      layerLoad[bestLayer] += cluster.getNetCount() * 1.0;

      // Assign secondary layer if available and significantly different
      if (secondBestLayer >= 0 && secondBestLayer != bestLayer) {
        cluster.setSecondaryLayer(secondBestLayer);
        layerLoad[secondBestLayer] += cluster.getNetCount() * 0.3;
      }
    }
  }

  /**
   * Checks if a layer is usable for routing.
   */
  private boolean isLayerUsable(int layerIndex, LayerFunction[] layerFunctions) {
    if (layerFunctions != null && layerIndex < layerFunctions.length) {
      LayerFunction func = layerFunctions[layerIndex];
      if (func == LayerFunction.SIGNAL || func == LayerFunction.MIXED) {
        return true;
      }
      // Even planes can be used if there's no signal layer available
      if (func == LayerFunction.POWER_PLANE || func == LayerFunction.GROUND_PLANE) {
        return true; // Allow fallback to planes
      }
      return false;
    }
    // If no function info, use the is_signal flag
    Layer layer = layerIndex < board.layer_structure.arr.length
        ? board.layer_structure.arr[layerIndex] : null;
    return layer != null && layer.is_signal;
  }

  /**
   * Computes a score for assigning a cluster to a specific layer.
   * Lower is better.
   */
  private double computeLayerScore(NetCluster cluster, int layerIndex,
                                   LayerFunction[] layerFunctions) {
    double score = 0.0;

    // 1. Current layer load (congestion + existing assignments)
    score += layerLoad[layerIndex];

    // 2. Preferred direction match
    boolean preferredHorizontal = (layerIndex % 2 == 1); // Alternate
    double clusterAspectRatio = cluster.getWidth() > 0
        ? cluster.getHeight() / cluster.getWidth() : 1.0;

    if (preferredHorizontal) {
      // Horizontal prefers wide clusters
      score += Math.max(0, clusterAspectRatio - 1.0) * 5.0;
    } else {
      // Vertical prefers tall clusters
      score += Math.max(0, 1.0 - clusterAspectRatio) * 5.0;
    }

    // 3. Layer function match
    if (layerFunctions != null && layerIndex < layerFunctions.length) {
      LayerFunction func = layerFunctions[layerIndex];

      // Power/Ground nets prefer plane layers
      boolean hasPowerOrGround = cluster.getNetNumbers().stream()
          .anyMatch(n -> { return true; }); // Simplified - real check uses labeler

      if (hasPowerOrGround && (func == LayerFunction.POWER_PLANE || func == LayerFunction.GROUND_PLANE)) {
        score -= 20.0; // Strong bonus for power nets on plane layers
      }

      if (func == LayerFunction.MIXED) {
        score += 5.0; // Slight penalty for mixed layers
      }
    }

    // 4. Cluster size-dependent scaling
    score *= (1.0 + cluster.getNetCount() * 0.1);

    // 5. Outer layer penalty (to prefer inner layers for dense clusters)
    int signalLayerCount = board.layer_structure.signal_layer_count();
    if (signalLayerCount > 2) {
      if (layerIndex == 0 || layerIndex == layerCount - 1) {
        score *= 1.2; // 20% penalty for outer layers
      }
    }

    return score;
  }

  /**
   * Reassigns a cluster when negotiation routing detects excessive contention.
   * Increases the penalty for the current layer to encourage moving.
   */
  public void penalizeLayer(NetCluster cluster, int layer) {
    if (layer >= 0 && layer < layerCount) {
      layerLoad[layer] += 10.0;
    }
  }

  /**
   * Checks if a layer has capacity for additional routing.
   */
  public boolean hasLayerCapacity(int layerIndex) {
    if (layerIndex < 0 || layerIndex >= layerCount) return false;
    return layerLoad[layerIndex] < 100.0; // Soft limit
  }

  private void logAssignment(List<NetCluster> clusters, LayerFunction[] layerFunctions) {
    StringBuilder sb = new StringBuilder("Layer Assignment Results:\n");
    for (NetCluster cluster : clusters) {
      String primaryName = cluster.getPrimaryLayer() >= 0
          ? board.layer_structure.arr[cluster.getPrimaryLayer()].name : "none";
      String secondaryName = cluster.getSecondaryLayer() >= 0
          ? board.layer_structure.arr[cluster.getSecondaryLayer()].name : "none";
      sb.append("  ").append(cluster)
          .append(" -> primary=").append(primaryName)
          .append(", secondary=").append(secondaryName)
          .append("\n");
    }

    sb.append("  Layer loads: ");
    for (int i = 0; i < layerCount; i++) {
      String lName = board.layer_structure.arr[i].name;
      sb.append("[").append(lName).append(":").append(String.format("%.1f", layerLoad[i])).append("] ");
    }
    FRLogger.debug(sb.toString());
  }
}
