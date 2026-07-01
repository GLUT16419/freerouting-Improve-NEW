package app.freerouting.autoroute;

import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * UTPR V7 — Urban Traffic Router Settings.
 * <p>
 * Configuration parameters for the 4-phase city-traffic-inspired routing
 * pipeline. Extends the existing HybridRouterSettings with UTPR-specific
 * parameters for CH contraction levels, STOM capacity, SAT relaxation, etc.
 */
public class UrbanTrafficRouterSettings implements Serializable, Cloneable {

  private static final long serialVersionUID = 1L;

  // ── Quality level ──────────────────────────────────────────────────────

  @SerializedName("quality_level")
  public QualityLevel qualityLevel = QualityLevel.BALANCED;

  public enum QualityLevel {
    /** Draft mode: Phase 0 only (rough estimation). */
    DRAFT,
    /** Balanced: Phases 0-2 (planning + backbone + district routing). */
    BALANCED,
    /** High quality: Phases 0-3 (includes SAT bottleneck solving). */
    HIGH,
    /** Full: all phases with maximum iterations. */
    FULL
  }

  // ── Phase 0: Urban planning ────────────────────────────────────────────

  @SerializedName("phase0_enabled")
  public boolean phase0Enabled = true;

  @SerializedName("ch_cell_size_um")
  public int chCellSizeUm = 2000; // 2mm grid for CH graph

  @SerializedName("ch_levels")
  public int chLevels = 5; // number of hierarchy levels

  @SerializedName("congestion_cell_size_um")
  public int congestionCellSizeUm = 2000; // 2mm for congestion estimation

  @SerializedName("gaussian_sigma_cells")
  public double gaussianSigmaCells = 2.0; // Gaussian blur σ in cells

  @SerializedName("corridor_half_width")
  public int corridorHalfWidth = 3; // corridor margin in cells

  // ── Phase 1: Backbone routing ──────────────────────────────────────────

  @SerializedName("phase1_enabled")
  public boolean phase1Enabled = true;

  @SerializedName("max_critical_nets")
  public int maxCriticalNets = 200; // max nets treated as critical backbone

  @SerializedName("stom_time_steps")
  public int stomTimeSteps = 1000; // max logical time steps in STOM

  @SerializedName("stom_penalty")
  public double stomPenalty = 100.0; // STOM occupancy cost penalty

  @SerializedName("fanout_enabled")
  public boolean fanoutEnabled = true;

  // ── Phase 2: District routing ──────────────────────────────────────────

  @SerializedName("phase2_enabled")
  public boolean phase2Enabled = true;

  @SerializedName("max_district_threads")
  public int maxDistrictThreads = 8; // parallel districts

  @SerializedName("district_ripup_cost")
  public int districtRipupCost = 5;

  @SerializedName("incremental_repair_enabled")
  public boolean incrementalRepairEnabled = true;

  @SerializedName("incremental_max_iterations")
  public int incrementalMaxIterations = 20;

  @SerializedName("convergence_threshold")
  public int convergenceThreshold = 10; // conflicts → Phase 3

  // ── Phase 3: Bottleneck SAT ────────────────────────────────────────────

  @SerializedName("phase3_enabled")
  public boolean phase3Enabled = true;

  @SerializedName("max_candidates_per_net")
  public int maxCandidatesPerNet = 8;

  @SerializedName("max_relaxation_levels")
  public int maxRelaxationLevels = 5;

  @SerializedName("parallel_solvers")
  public int parallelSolvers = 4;

  @SerializedName("solver_timeout_seconds")
  public int solverTimeoutSeconds = 30;

  @SerializedName("max_thaw_nets")
  public int maxThawNets = 4; // max nets to thaw during UNSAT recovery

  // ── Automatic network analysis ────────────────────────────────────────

  @SerializedName("auto_net_analysis")
  public boolean autoNetAnalysis = true; // enable automatic network analysis

  @SerializedName("diff_pair_confidence_high")
  public double diffPairConfidenceHigh = 0.80; // strong DP threshold

  @SerializedName("diff_pair_confidence_low")
  public double diffPairConfidenceLow = 0.55; // weak DP threshold

  @SerializedName("max_match_radius_um")
  public int maxMatchRadiusUm = 2000; // 2mm spatial matching radius

  @SerializedName("group_spatial_radius_um")
  public int groupSpatialRadiusUm = 10000; // 10mm group aggregation radius

  // ── Graceful degradation ──────────────────────────────────────────────

  @SerializedName("degradation_enabled")
  public boolean degradationEnabled = true; // enable smart degradation

  @SerializedName("max_degradation_rounds")
  public int maxDegradationRounds = 5; // max degradation rounds per net

  // ── Low-requirement relaxation ─────────────────────────────────────────

  @SerializedName("low_req_relaxation_enabled")
  public boolean lowReqRelaxationEnabled = true; // enable low-req relaxation

  @SerializedName("low_req_via_multiplier")
  public double lowReqViaMultiplier = 0.3; // relaxed via cost multiplier

  @SerializedName("low_req_stom_multiplier")
  public double lowReqStomMultiplier = 0.2; // relaxed STOM multiplier

  @SerializedName("low_req_congestion_multiplier")
  public double lowReqCongestionMultiplier = 0.1; // relaxed congestion multiplier

  @SerializedName("low_req_bend_multiplier")
  public double lowReqBendMultiplier = 0.0; // relaxed bend multiplier

  @SerializedName("low_req_ripup_multiplier")
  public double lowReqRipupCostMultiplier = 0.1; // relaxed ripup multiplier

  @SerializedName("low_req_timeout_seconds")
  public int lowReqTimeoutSeconds = 3; // low-req search timeout

  // ═══════════════════════════════════════════════════════════════════════
  //  Apply quality level
  // ═══════════════════════════════════════════════════════════════════════

  public void applyQualityLevel() {
    switch (qualityLevel) {
      case DRAFT:
        phase1Enabled = false;
        phase2Enabled = false;
        phase3Enabled = false;
        chLevels = 2;
        incrementalRepairEnabled = false;
        break;
      case BALANCED:
        phase1Enabled = true;
        phase2Enabled = true;
        phase3Enabled = true;
        chLevels = 5;
        incrementalRepairEnabled = false;
        incrementalMaxIterations = 5;
        break;
      case HIGH:
        phase1Enabled = true;
        phase2Enabled = true;
        phase3Enabled = true;
        chLevels = 8;
        incrementalRepairEnabled = true;
        incrementalMaxIterations = 20;
        maxRelaxationLevels = 5;
        maxCandidatesPerNet = 12;
        break;
      case FULL:
        phase1Enabled = true;
        phase2Enabled = true;
        phase3Enabled = true;
        chLevels = 10;
        incrementalRepairEnabled = true;
        incrementalMaxIterations = 30;
        maxRelaxationLevels = 8;
        maxCandidatesPerNet = 16;
        parallelSolvers = 6;
        break;
    }
  }

  @Override
  public UrbanTrafficRouterSettings clone() {
    try {
      return (UrbanTrafficRouterSettings) super.clone();
    } catch (CloneNotSupportedException e) {
      UrbanTrafficRouterSettings r = new UrbanTrafficRouterSettings();
      r.qualityLevel = this.qualityLevel;
      r.phase0Enabled = this.phase0Enabled;
      r.phase1Enabled = this.phase1Enabled;
      r.phase2Enabled = this.phase2Enabled;
      r.phase3Enabled = this.phase3Enabled;
      r.chCellSizeUm = this.chCellSizeUm;
      r.chLevels = this.chLevels;
      r.congestionCellSizeUm = this.congestionCellSizeUm;
      r.gaussianSigmaCells = this.gaussianSigmaCells;
      r.corridorHalfWidth = this.corridorHalfWidth;
      r.maxCriticalNets = this.maxCriticalNets;
      r.stomTimeSteps = this.stomTimeSteps;
      r.stomPenalty = this.stomPenalty;
      r.fanoutEnabled = this.fanoutEnabled;
      r.maxDistrictThreads = this.maxDistrictThreads;
      r.districtRipupCost = this.districtRipupCost;
      r.incrementalRepairEnabled = this.incrementalRepairEnabled;
      r.incrementalMaxIterations = this.incrementalMaxIterations;
      r.convergenceThreshold = this.convergenceThreshold;
      r.phase3Enabled = this.phase3Enabled;
      r.maxCandidatesPerNet = this.maxCandidatesPerNet;
      r.maxRelaxationLevels = this.maxRelaxationLevels;
      r.parallelSolvers = this.parallelSolvers;
      r.solverTimeoutSeconds = this.solverTimeoutSeconds;
      r.maxThawNets = this.maxThawNets;
      r.autoNetAnalysis = this.autoNetAnalysis;
      r.diffPairConfidenceHigh = this.diffPairConfidenceHigh;
      r.diffPairConfidenceLow = this.diffPairConfidenceLow;
      r.maxMatchRadiusUm = this.maxMatchRadiusUm;
      r.groupSpatialRadiusUm = this.groupSpatialRadiusUm;
      r.degradationEnabled = this.degradationEnabled;
      r.maxDegradationRounds = this.maxDegradationRounds;
      r.lowReqRelaxationEnabled = this.lowReqRelaxationEnabled;
      r.lowReqViaMultiplier = this.lowReqViaMultiplier;
      r.lowReqStomMultiplier = this.lowReqStomMultiplier;
      r.lowReqCongestionMultiplier = this.lowReqCongestionMultiplier;
      r.lowReqBendMultiplier = this.lowReqBendMultiplier;
      r.lowReqRipupCostMultiplier = this.lowReqRipupCostMultiplier;
      r.lowReqTimeoutSeconds = this.lowReqTimeoutSeconds;
      return r;
    }
  }
}
