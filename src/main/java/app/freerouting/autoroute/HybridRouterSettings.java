package app.freerouting.autoroute;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * Settings specific to the Hybrid (three-phase) routing algorithm.
 * <p>
 * Controls the behavior of each phase and the overall routing strategy.
 */
public class HybridRouterSettings implements Serializable, Cloneable {

  // Phase enable/disable
  @SerializedName("phase0_enabled")
  public boolean phase0Enabled = true;

  @SerializedName("phase1_enabled")
  public boolean phase1Enabled = true;

  @SerializedName("phase2_enabled")
  public boolean phase2Enabled = true;

  @SerializedName("phase3_enabled")
  public boolean phase3Enabled = true;

  // Phase 0: Congestion estimation
  @SerializedName("congestion_cell_size_um")
  public int congestionCellSizeUm = 2000; // 2mm grid cells

  // Phase 1: Short net routing
  @SerializedName("short_net_threshold_um")
  public int shortNetThresholdUm = 5000; // 5mm threshold

  @SerializedName("channel_reservation_factor")
  public double channelReservationFactor = 0.3;

  @SerializedName("max_short_nets_per_pass")
  public int maxShortNetsPerPass = 50;

  // Phase 2: Cluster routing
  @SerializedName("cluster_overlap_threshold")
  public double clusterOverlapThreshold = 0.3; // 30% overlap

  @SerializedName("max_nets_per_cluster")
  public int maxNetsPerCluster = 20;

  @SerializedName("max_refinement_iterations")
  public int maxRefinementIterations = 5;

  @SerializedName("negotiation_max_iterations")
  public int negotiationMaxIterations = 50;

  @SerializedName("negotiation_stagnation_limit")
  public int negotiationStagnationLimit = 10;

  // Phase 3: SAT routing
  @SerializedName("sat_max_candidates_per_net")
  public int satMaxCandidatesPerNet = 20;

  @SerializedName("sat_max_relaxation_levels")
  public int satMaxRelaxationLevels = 5;

  // Layer function assignment
  @SerializedName("auto_assign_layer_functions")
  public boolean autoAssignLayerFunctions = true;

  // Power/GND auto-labeling
  @SerializedName("auto_label_power_gnd")
  public boolean autoLabelPowerGnd = true;

  // === V6 specific settings ===
  @SerializedName("v6_flute_enabled")
  public boolean v6FluteEnabled = true;

  @SerializedName("v6_spectral_clustering_enabled")
  public boolean v6SpectralClusteringEnabled = true;

  @SerializedName("v6_dynamic_thaw_enabled")
  public boolean v6DynamicThawEnabled = true;

  @SerializedName("v6_sat_solver_enabled")
  public boolean v6SatSolverEnabled = true;

  @SerializedName("v6_parallel_engine_enabled")
  public boolean v6ParallelEngineEnabled = true;

  @SerializedName("v6_flute_cell_size_um")
  public int v6FluteCellSizeUm = 2000000; // 2mm grid

  @SerializedName("v6_spectral_min_nets")
  public int v6SpectralMinNets = 10;

  @SerializedName("v6_max_parallel_clusters")
  public int v6MaxParallelClusters = 6;

  @SerializedName("v6_channel_retention_factor")
  public double v6ChannelRetentionFactor = 0.3;

  // Routing quality vs speed tradeoff
  @SerializedName("quality_level")
  public QualityLevel qualityLevel = QualityLevel.BALANCED;

  public enum QualityLevel {
    /** Fastest: phase 1 only, coarse settings */
    DRAFT,
    /** Balanced: phase 1 + phase 2, default settings */
    BALANCED,
    /** Best quality: all 3 phases, fine settings */
    HIGH
  }

  public HybridRouterSettings() {
    // Default constructor
  }

  /**
   * Returns effective settings based on quality level.
   */
  public void applyQualityLevel() {
    switch (qualityLevel) {
      case DRAFT:
        phase0Enabled = false;
        phase2Enabled = false;
        phase3Enabled = false;
        maxRefinementIterations = 1;
        negotiationMaxIterations = 10;
        break;
      case BALANCED:
        phase0Enabled = true;
        phase1Enabled = true;
        phase2Enabled = true;
        phase3Enabled = false;
        maxRefinementIterations = 3;
        negotiationMaxIterations = 25;
        break;
      case HIGH:
        phase0Enabled = true;
        phase1Enabled = true;
        phase2Enabled = true;
        phase3Enabled = true;
        maxRefinementIterations = 5;
        negotiationMaxIterations = 50;
        satMaxCandidatesPerNet = 30;
        break;
    }
  }

  @Override
  public HybridRouterSettings clone() {
    try {
      return (HybridRouterSettings) super.clone();
    } catch (CloneNotSupportedException e) {
      HybridRouterSettings result = new HybridRouterSettings();
      result.phase0Enabled = this.phase0Enabled;
      result.phase1Enabled = this.phase1Enabled;
      result.phase2Enabled = this.phase2Enabled;
      result.phase3Enabled = this.phase3Enabled;
      result.congestionCellSizeUm = this.congestionCellSizeUm;
      result.shortNetThresholdUm = this.shortNetThresholdUm;
      result.channelReservationFactor = this.channelReservationFactor;
      result.maxShortNetsPerPass = this.maxShortNetsPerPass;
      result.clusterOverlapThreshold = this.clusterOverlapThreshold;
      result.maxNetsPerCluster = this.maxNetsPerCluster;
      result.maxRefinementIterations = this.maxRefinementIterations;
      result.negotiationMaxIterations = this.negotiationMaxIterations;
      result.negotiationStagnationLimit = this.negotiationStagnationLimit;
      result.satMaxCandidatesPerNet = this.satMaxCandidatesPerNet;
      result.satMaxRelaxationLevels = this.satMaxRelaxationLevels;
      result.autoAssignLayerFunctions = this.autoAssignLayerFunctions;
      result.autoLabelPowerGnd = this.autoLabelPowerGnd;
      result.qualityLevel = this.qualityLevel;
      // V6 settings
      result.v6FluteEnabled = this.v6FluteEnabled;
      result.v6SpectralClusteringEnabled = this.v6SpectralClusteringEnabled;
      result.v6DynamicThawEnabled = this.v6DynamicThawEnabled;
      result.v6SatSolverEnabled = this.v6SatSolverEnabled;
      result.v6ParallelEngineEnabled = this.v6ParallelEngineEnabled;
      result.v6FluteCellSizeUm = this.v6FluteCellSizeUm;
      result.v6SpectralMinNets = this.v6SpectralMinNets;
      result.v6MaxParallelClusters = this.v6MaxParallelClusters;
      result.v6ChannelRetentionFactor = this.v6ChannelRetentionFactor;
      return result;
    }
  }
}
