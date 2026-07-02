package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;
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

  /** 4-level routing speed preset (V7.x). Default = MEDIUM (第三档). */
  @SerializedName("speed_level")
  public SpeedLevel speedLevel = SpeedLevel.MEDIUM;

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

  public enum SpeedLevel {
    /** 非常快 — Phase 0 only, fast estimation, skip heavy phases. */
    VERY_FAST,
    /** 快 — Phase 0+1, limited Phase 2, skip Phase 3. */
    FAST,
    /** 中 — Full pipeline with moderate iterations (default). */
    MEDIUM,
    /** 慢 — Full pipeline with max iterations for best quality. */
    SLOW
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

  /** V7.x: Max autoroute passes in Phase 2 (set by speed level). */
  @SerializedName("max_phase2_passes")
  public int maxPhase2Passes = 6;

  /** V7.x: Max high-ripup per-net attempts after Phase 2 passes. */
  @SerializedName("max_phase2_ripup_tiers")
  public int maxPhase2RipupTiers = 2;

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

  /** V7.x: Max cleanup passes in Phase 3 (set by speed level). */
  @SerializedName("max_phase3_passes")
  public int maxPhase3Passes = 6;

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
  //  V7.x: 地图导航算法深化参数
  // ═══════════════════════════════════════════════════════════════════════

  // ── CH 层感知拆分 ──
  @SerializedName("ch_layer_aware")
  public boolean chLayerAware = true;

  // ── 预判性降级 ──
  @SerializedName("degradation_predictive")
  public boolean degradationPredictive = true;

  @SerializedName("predictive_congestion_threshold_high")
  public double predictiveCongestionThresholdHigh = 0.85;

  @SerializedName("predictive_congestion_threshold_mid")
  public double predictiveCongestionThresholdMid = 0.70;

  // ── 历史代价冷却衰减 ──
  @SerializedName("dlite_cooling_factor")
  public double dLiteCoolingFactor = 0.5;

  // ── 等长组扩散增强 ──
  @SerializedName("length_group_diffusion_enabled")
  public boolean lengthGroupDiffusionEnabled = true;

  // ── 拥塞梯度代价 ──
  @SerializedName("congestion_gradient_enabled")
  public boolean congestionGradientEnabled = true;

  @SerializedName("congestion_gradient_weight")
  public double congestionGradientWeight = 5.0;

  // ── 时间窗软化 ──
  @SerializedName("time_window_softening_enabled")
  public boolean timeWindowSofteningEnabled = true;

  @SerializedName("time_window_size")
  public int timeWindowSize = 3;

  @SerializedName("soft_sharing_cost")
  public double softSharingCost = 10.0;

  // ── 最大空隙优先 ──
  @SerializedName("max_gap_first_enabled")
  public boolean maxGapFirstEnabled = true;

  // ── Arc Flags 剪枝 (V7.x) ──
  @SerializedName("arc_flags_enabled")
  public boolean arcFlagsEnabled = true;

  // ── ALT 启发式 (V7.x) ──
  @SerializedName("alt_heuristic_enabled")
  public boolean altHeuristicEnabled = true;

  // ── V8: 多目标帕累托 A\* ──
  @SerializedName("pareto_astar_enabled")
  public boolean paretoAStarEnabled = true;

  // ── V8: 用户均衡并行试探 ──
  @SerializedName("user_equilibrium_enabled")
  public boolean userEquilibriumEnabled = true;

  /** Maximum probing rounds for user equilibrium. */
  @SerializedName("max_probing_rounds")
  public int maxProbingRounds = 3;

  // ═══════════════════════════════════════════════════════════════════════
  //  Apply quality level
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Apply speed level presets, auto-configuring parameters based on the
   * board's complexity (net count, layer count).
   * <p>
   * Called during initialization, after the board is loaded and net count
   * is known. Overrides qualityLevel and all phase settings.
   *
   * @param netCount total number of nets on the board
   * @param layerCount number of signal layers
   */
  public void applySpeedLevel(int netCount, int layerCount) {

    // Auto-adjust: complex boards shift parameters conservatively;
    // simple boards can afford more aggressive iterations
    boolean denseBoard = (netCount > 500);
    boolean smallBoard = (netCount < 50);

    // ── All 4 levels run the full pipeline ──
    phase0Enabled = true;
    phase1Enabled = true;
    phase2Enabled = true;
    phase3Enabled = true;

    switch (speedLevel) {
      case VERY_FAST:
        // 非常快: 最小 CH（但≥3保证基本捷径）、少量 Phase 2/3 pass、低迭代
        chLevels             = denseBoard ? 3 : 3;  // 2→3: 保证基本CH层次
        maxPhase2Passes      = 3;  // 2→3: 多1轮多布3-5网
        maxPhase2RipupTiers  = 0;
        maxPhase3Passes      = 1;  // 0→1: 至少1轮cleanup兜底
        fanoutEnabled        = true;
        incrementalRepairEnabled = false;
        incrementalMaxIterations = 1;
        maxCriticalNets      = 50;
        maxCandidatesPerNet   = 4;
        maxRelaxationLevels   = 2;
        stomPenalty          = 80.0;  // 50→80: 提高预约惩罚减少最终冲突
        maxDegradationRounds = 1;
        lowReqTimeoutSeconds = 1;
        solverTimeoutSeconds = 10;
        parallelSolvers      = 1;
        // V7.x: minimal
        chLayerAware             = true;
        degradationPredictive    = false;
        congestionGradientEnabled = false;
        congestionGradientWeight = 3.0;
        timeWindowSofteningEnabled = true;
        timeWindowSize           = 2;
        maxGapFirstEnabled       = false;
        dLiteCoolingFactor       = 0.3;
        arcFlagsEnabled          = true;
        altHeuristicEnabled      = true;
        paretoAStarEnabled       = false;
        userEquilibriumEnabled   = false;
        maxProbingRounds         = 1;
        break;

      case FAST:
        // 快: 中等 CH、适量 Phase 2/3 pass
        maxPhase2Passes      = 4;  // 3→4: 提升骨干网剩余覆盖率
        maxPhase2RipupTiers  = 1;
        maxPhase3Passes      = 2;
        chLevels             = denseBoard ? 5 : 4;  // 3→4: 提升CH查询质量
        fanoutEnabled        = true;
        incrementalRepairEnabled = true;
        incrementalMaxIterations = 4;
        maxCriticalNets      = 100;
        maxCandidatesPerNet   = 6;
        maxRelaxationLevels   = 3;
        stomPenalty          = 80.0;
        maxDegradationRounds = 2;
        lowReqTimeoutSeconds = 2;
        solverTimeoutSeconds = 20;
        parallelSolvers      = Math.min(4, Runtime.getRuntime().availableProcessors());
        // V7.x: partial
        chLayerAware             = true;
        degradationPredictive    = !smallBoard;
        congestionGradientEnabled = true;
        congestionGradientWeight = 4.0;
        timeWindowSofteningEnabled = true;
        timeWindowSize           = 3;
        maxGapFirstEnabled       = true;
        dLiteCoolingFactor       = 0.4;
        arcFlagsEnabled          = true;
        altHeuristicEnabled      = true;
        paretoAStarEnabled       = true;
        userEquilibriumEnabled   = true;
        maxProbingRounds         = 2;
        break;

      case MEDIUM:
        // 中 (DEFAULT): 平衡速度与质量
        maxPhase2Passes      = 4;
        maxPhase2RipupTiers  = 1;
        maxPhase3Passes      = 2;  // 3→2: 减1轮省~60s，完成率几乎不变
        chLevels             = (layerCount <= 4) ? 5 : (layerCount <= 8 ? 6 : 7);
        if (denseBoard) chLevels = Math.max(4, chLevels - 1);
        fanoutEnabled        = true;
        incrementalRepairEnabled = true;
        incrementalMaxIterations = 6;
        maxCriticalNets      = 200;
        maxCandidatesPerNet   = 8;
        maxRelaxationLevels   = 5;
        stomPenalty          = 100.0;
        maxDegradationRounds = 3;
        lowReqTimeoutSeconds = 3;
        solverTimeoutSeconds = 30;
        parallelSolvers      = Math.min(4, Runtime.getRuntime().availableProcessors());
        // V7.x: 全部开启
        chLayerAware             = true;
        degradationPredictive    = true;
        congestionGradientEnabled = true;
        congestionGradientWeight = 5.0;
        timeWindowSofteningEnabled = true;
        timeWindowSize           = 3;
        maxGapFirstEnabled       = true;
        dLiteCoolingFactor       = 0.5;
        arcFlagsEnabled          = true;
        altHeuristicEnabled      = true;
        paretoAStarEnabled       = true;
        userEquilibriumEnabled   = true;
        maxProbingRounds         = 3;
        break;

      case SLOW:
        // 慢: 高质量、充足迭代（但不过度）
        maxPhase2Passes      = 5;  // 6→5: 减1轮省~30s
        maxPhase2RipupTiers  = 2;
        maxPhase3Passes      = 4;  // 6→4: 减2轮省~120s，4轮已足够
        chLevels             = (layerCount <= 4) ? 8 : Math.min(8, layerCount <= 8 ? 9 : 10);  // 上限8
        if (smallBoard) chLevels = Math.min(8, chLevels + 1);
        fanoutEnabled        = true;
        incrementalRepairEnabled = true;
        incrementalMaxIterations = 12;
        maxCriticalNets      = 999;
        maxCandidatesPerNet   = 16;
        maxRelaxationLevels   = 8;
        stomPenalty          = 120.0;
        maxDegradationRounds = 5;
        lowReqTimeoutSeconds = 5;
        solverTimeoutSeconds = 60;
        parallelSolvers      = Math.min(6, Runtime.getRuntime().availableProcessors());
        // V7.x: 全部增强
        chLayerAware             = true;
        degradationPredictive    = true;
        congestionGradientEnabled = true;
        congestionGradientWeight = 8.0;
        timeWindowSofteningEnabled = true;
        timeWindowSize           = 5;
        maxGapFirstEnabled       = true;
        dLiteCoolingFactor       = 0.6;
        arcFlagsEnabled          = true;
        altHeuristicEnabled      = true;
        paretoAStarEnabled       = true;
        userEquilibriumEnabled   = true;
        maxProbingRounds         = 3;
        break;
    }

    FRLogger.info("  SpeedLevel: " + speedLevel + " (nets=" + netCount
        + ", layers=" + layerCount + ", chLevels=" + chLevels
        + ", p2Passes=" + maxPhase2Passes + ", p3Passes=" + maxPhase3Passes + ")");
  }

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
      r.speedLevel = this.speedLevel;
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
      r.maxPhase2Passes = this.maxPhase2Passes;
      r.maxPhase2RipupTiers = this.maxPhase2RipupTiers;
      r.phase3Enabled = this.phase3Enabled;
      r.maxCandidatesPerNet = this.maxCandidatesPerNet;
      r.maxPhase3Passes = this.maxPhase3Passes;
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
      // V7.x fields
      r.chLayerAware = this.chLayerAware;
      r.degradationPredictive = this.degradationPredictive;
      r.predictiveCongestionThresholdHigh = this.predictiveCongestionThresholdHigh;
      r.predictiveCongestionThresholdMid = this.predictiveCongestionThresholdMid;
      r.dLiteCoolingFactor = this.dLiteCoolingFactor;
      r.lengthGroupDiffusionEnabled = this.lengthGroupDiffusionEnabled;
      r.congestionGradientEnabled = this.congestionGradientEnabled;
      r.congestionGradientWeight = this.congestionGradientWeight;
      r.timeWindowSofteningEnabled = this.timeWindowSofteningEnabled;
      r.timeWindowSize = this.timeWindowSize;
      r.softSharingCost = this.softSharingCost;
      r.maxGapFirstEnabled = this.maxGapFirstEnabled;
      r.arcFlagsEnabled = this.arcFlagsEnabled;
      r.altHeuristicEnabled = this.altHeuristicEnabled;
      r.paretoAStarEnabled = this.paretoAStarEnabled;
      r.userEquilibriumEnabled = this.userEquilibriumEnabled;
      r.maxProbingRounds = this.maxProbingRounds;
      return r;
    }
  }
}
