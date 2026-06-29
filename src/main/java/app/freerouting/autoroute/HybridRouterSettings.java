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
      return result;
    }
  }
}
