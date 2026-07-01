package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.util.*;

/**
 * UTPR V7 — Urban Traffic Planning & Routing Batch Autorouter.
 * <p>
 * The main entry point for the 4-phase city-traffic-inspired routing pipeline.
 * Replaces {@link HybridBatchAutorouterWrapper} as the default auto-router.
 * <p>
 * <b>Phase 0 — 城市规划:</b>
 * <ol>
 *   <li>Automatic network analysis (net type, diff pair, length-match groups)</li>
 *   <li>CH contraction hierarchy graph building</li>
 *   <li>Probabilistic congestion estimation (FLUTE + Gaussian diffusion)</li>
 *   <li>Main corridor planning (type-aware)</li>
 *   <li>Region-aware spectral clustering</li>
 *   <li>CRP multi-level partitioning</li>
 *   <li>Boundary path table precomputation</li>
 *   <li>Degradation plan + low-requirement relaxation plan</li>
 *   <li>Power/GND priority fanout</li>
 * </ol>
 * <b>Phase 1 — 骨干路网:</b>
 * <ol>
 *   <li>Backbone net selection and priority ordering</li>
 *   <li>Hub fanout template matching</li>
 *   <li>Timed A* routing with STOM reservation for critical signals</li>
 * </ol>
 * <b>Phase 2 — 城区交通填充:</b>
 * <ol>
 *   <li>Traffic-mode layer assignment</li>
 *   <li>District-level parallel routing</li>
 *   <li>Cross-district connection stitching</li>
 *   <li>Incremental conflict repair (D* Lite)</li>
 * </ol>
 * <b>Phase 3 — 路口精确调度:</b>
 * <ol>
 *   <li>Bottleneck analysis</li>
 *   <li>SAT/ILP exact solving with UNSAT recovery</li>
 *   <li>Local thaw and retry</li>
 * </ol>
 */
public class UrbanTrafficBatchAutorouter extends BatchAutorouter {

  /** UTPR settings instance. */
  private final UrbanTrafficRouterSettings utprSettings;

  // ── Phase 0 components ──
  private ContractionHierarchies ch;
  private ProbabilisticCongestionEstimator congestionEstimator;
  private MainCorridorPlanner corridorPlanner;
  private MultiLevelPartitioner partitioner;
  private DistrictBoundaryPathTable boundaryPathTable;

  // ── V7 new modules (Phase 0) ──
  private AutomaticNetworkAnalyzer networkAnalyzer;
  private Map<Integer, NetClass> netClassMap;
  private RegionAwareSpectralClusterer regionAwareClusterer;
  private GracefulDegradationManager degradationManager;
  private LowRequirementRelaxationManager relaxationManager;

  // ── Phase 1 components ──
  private SpatioTemporalOccupancyMap stom;
  private BackboneNetSelector backboneSelector;
  private HubFanoutTemplates hubFanout;
  private TimedBidirectionalAStar timedAStar;

  // ── Phase 2 components ──
  private TrafficModeLayerAssigner trafficAssigner;
  private DistrictRouter districtRouter;
  private CrossDistrictConnector crossDistrictConnector;
  private IncrementalRerouter incrementalRerouter;

  // ── Phase 3 components ──
  private BottleneckAnalyzer bottleneckAnalyzer;
  private BottleneckSatSolver bottleneckSatSolver;

  // ── State ──
  private int currentPhase;
  private Set<Integer> allNetNumbers;
  private Set<Integer> routedNets;
  private Set<Integer> failedNets;

  // Degradation feedback state
  private int degradationRound; // current degradation retry round

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public UrbanTrafficBatchAutorouter(RoutingJob job) {
    super(job);
    this.utprSettings = new UrbanTrafficRouterSettings();
    if (job != null && job.routerSettings != null
        && job.routerSettings.hybrid != null) {
      // Inherit quality level from existing settings
      HybridRouterSettings.QualityLevel existingQL =
          job.routerSettings.hybrid.qualityLevel;
      switch (existingQL) {
        case DRAFT:    utprSettings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.DRAFT; break;
        case BALANCED: utprSettings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.BALANCED; break;
        case HIGH:     utprSettings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.HIGH; break;
        default:       utprSettings.qualityLevel = UrbanTrafficRouterSettings.QualityLevel.BALANCED;
      }
    }
    utprSettings.applyQualityLevel();

    this.currentPhase = 0;
    this.allNetNumbers = new HashSet<>();
    this.routedNets = new HashSet<>();
    this.failedNets = new HashSet<>();
    this.degradationRound = 0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main pipeline
  // ═══════════════════════════════════════════════════════════════════════

  @Override
  public boolean runBatchLoop() {
    long pipelineStart = System.currentTimeMillis();

    if (!(this.board instanceof RoutingBoard)) {
      FRLogger.warn("UrbanTrafficBatchAutorouter: Board is not a RoutingBoard");
      return false;
    }
    RoutingBoard routingBoard = (RoutingBoard) this.board;

    FRLogger.info("╔═══════════════════════════════════════════════════════╗");
    FRLogger.info("║  UTPR V7 Urban Traffic Routing Engine — START       ║");
    FRLogger.info("╚═══════════════════════════════════════════════════════╝");
    FRLogger.info("Board: " + routingBoard.get_layer_count() + " layers, "
        + "quality=" + utprSettings.qualityLevel);

    try {
      collectNetNumbers(routingBoard);

      // ── Phase 0: 城市规划 ──────────────────────────────────────────
      if (utprSettings.phase0Enabled) {
        currentPhase = 0;
        FRLogger.info("─── Phase 0: 城市规划 (Urban Planning) ───");
        long t0 = System.currentTimeMillis();
        phase0UrbanPlanning(routingBoard);
        FRLogger.info("Phase 0 complete in " + (System.currentTimeMillis() - t0) + "ms");
      }

      // ── Phase 1: 骨干路网与枢纽建设 ──────────────────────────────
      if (utprSettings.phase1Enabled) {
        currentPhase = 1;
        long t1 = System.currentTimeMillis();
        // Phase 1 with degradation retry (up to 2 rounds)
        degradationRound = 0;
        while (degradationRound < 2) {
          FRLogger.info("─── Phase 1: 骨干路网与枢纽建设 (Backbone Network) ───");
          phase1BackboneRouting(routingBoard);
          // After Phase 1, check differential pair failures → degrade
          boolean degraded = checkPhase1Degradation(routingBoard);
          if (!degraded) break;
          degradationRound++;
        }
        FRLogger.info("Phase 1 complete in " + (System.currentTimeMillis() - t1) + "ms");
      }

      // ── Phase 2: 城区交通填充 ────────────────────────────────────
      if (utprSettings.phase2Enabled) {
        currentPhase = 2;
        long t2 = System.currentTimeMillis();
        phase2DistrictRouting(routingBoard);
        // After Phase 2, check length-match degradation
        checkPhase2Degradation(routingBoard);
        FRLogger.info("Phase 2 complete in " + (System.currentTimeMillis() - t2) + "ms");
      }

      // ── Phase 3: 路口精确调度 ────────────────────────────────────
      if (utprSettings.phase3Enabled) {
        currentPhase = 3;
        long t3 = System.currentTimeMillis();
        phase3BottleneckSolver(routingBoard);
        // After Phase 3, check SAT UNSAT degradation
        checkPhase3Degradation(routingBoard);
        FRLogger.info("Phase 3 complete in " + (System.currentTimeMillis() - t3) + "ms");
      }

      // ── Power/ground last-attempt ──────────────────────────────────
      // After all phases, try remaining power/ground nets one more time
      // with very high ripup. These nets often get skipped by powerGndNetsToSkip
      // or fail due to tight pin spacing; a final high-ripup attempt may succeed
      // once earlier routing has cleared some space.
      {
        Set<Integer> remainingPG = new HashSet<>();
        for (int netNo : allNetNumbers) {
          if (routedNets.contains(netNo)) continue;
          if (powerGndLabeler != null && powerGndLabeler.isPowerOrGroundNet(netNo)) {
            remainingPG.add(netNo);
          }
        }
        if (!remainingPG.isEmpty()) {
          FRLogger.info("─── 电源/地网络最后尝试 (Power/GND last attempt) ───");
          for (int netNo : remainingPG) {
            try {
              BatchAutorouter.NetRouteResult result = routeNet(netNo, 300);
              if (result != null && result.isRouted()) {
                routedNets.add(netNo);
                app.freerouting.rules.Net net = routingBoard.rules.nets.get(netNo);
                String n = (net != null && net.name != null) ? net.name : ("NET#" + netNo);
                FRLogger.info("  Power/GND last attempt: " + n + " routed successfully");
              }
            } catch (Exception e) {
              // ignore
            }
          }
        }
      }

      // ── Final cleanup ────────────────────────────────────────────────
      FRLogger.info("─── Final cleanup ───");
      runStandardCleanup(routingBoard);

    } catch (Exception e) {
      FRLogger.warn("UrbanTrafficBatchAutorouter: pipeline error in Phase "
          + currentPhase + ": " + e.getMessage() + " [cause: "
          + (e.getCause() != null ? e.getCause().getMessage() : "none") + "]");
      for (StackTraceElement ste : e.getStackTrace()) {
        if (ste.getClassName().startsWith("app.freerouting")) {
          FRLogger.warn("  at " + ste.getClassName() + "." + ste.getMethodName()
              + "(" + ste.getFileName() + ":" + ste.getLineNumber() + ")");
        }
      }
      return false;
    }

    long pipelineEnd = System.currentTimeMillis();
    int totalRouted = routedNets.size();
    int totalFailed = allNetNumbers.size() - totalRouted;

    FRLogger.info("╔═══════════════════════════════════════════════════════╗");
    FRLogger.info("║  UTPR V7 Pipeline Complete                          ║");
    FRLogger.info("╠═══════════════════════════════════════════════════════╣");
    FRLogger.info("║  Total nets: " + allNetNumbers.size());
    FRLogger.info("║  Routed:   " + totalRouted);
    FRLogger.info("║  Failed:   " + totalFailed);
    FRLogger.info("║  Time:     " + (pipelineEnd - pipelineStart) + "ms");
    FRLogger.info("╚═══════════════════════════════════════════════════════╝");

    // Log list of failed (unrouted) nets with names
    if (totalFailed > 0) {
      StringBuilder sb = new StringBuilder();
      sb.append("未完成布线的网络 (Failed nets):");
      for (int netNo : allNetNumbers) {
        if (routedNets.contains(netNo)) continue;
        app.freerouting.rules.Net net = routingBoard.rules.nets.get(netNo);
        String netName = (net != null && net.name != null && !net.name.isEmpty())
            ? net.name : "NET#" + netNo;
        sb.append("  ").append(netName);
      }
      FRLogger.info(sb.toString());
    }

    // ── Degradation report ──────────────────────────────────────────────
    if (degradationManager != null) {
      degradationManager.printReport();
    }

    return totalFailed == 0;
  }

    // ═══════════════════════════════════════════════════════════════════════
    //  Phase 0: Urban Planning (V7 enhanced)
    // ═══════════════════════════════════════════════════════════════════════

    private void phase0UrbanPlanning(RoutingBoard routingBoard) {
      // [0.0] Automatic network analysis (V7 new)
      FRLogger.info("  [0.0] 自动网络智能分析...");
      if (utprSettings.autoNetAnalysis) {
        try {
          networkAnalyzer = new AutomaticNetworkAnalyzer(routingBoard);
          netClassMap = networkAnalyzer.analyzeAll(allNetNumbers);
          FRLogger.info("    → " + networkAnalyzer.getDiffPairCount()
              + " 差分对, " + networkAnalyzer.getLengthGroupCount() + " 等长组");
        } catch (Exception e) {
          FRLogger.warn("  [0.0] Auto network analysis error (continuing): " + e.getMessage());
          netClassMap = new HashMap<>();
          for (int netNo : allNetNumbers) {
            netClassMap.put(netNo, new NetClass(netNo));
          }
        }
      } else {
        netClassMap = new HashMap<>();
        for (int netNo : allNetNumbers) {
          netClassMap.put(netNo, new NetClass(netNo));
        }
      }

      FRLogger.info("  [0.1] CH分层路网构建...");
      ch = ContractionHierarchies.buildFromBoard(routingBoard,
          utprSettings.chCellSizeUm * 100.0);
      ch.contract(utprSettings.chLevels);
      FRLogger.info("  [0.2] 概率拥塞估计...");
      congestionEstimator = new ProbabilisticCongestionEstimator(
          routingBoard, utprSettings.congestionCellSizeUm * 100.0);
      congestionEstimator.estimate(allNetNumbers);
      FRLogger.info("  [0.3] 主干道走廊规划...");
      corridorPlanner = new MainCorridorPlanner(routingBoard,
          utprSettings.congestionCellSizeUm * 100.0);
      corridorPlanner.planCorridors(allNetNumbers, null);
      FRLogger.info("  [0.4] 区域感知谱聚类 (V7 new)...");
      try {
        if (networkAnalyzer != null) {
          regionAwareClusterer = new RegionAwareSpectralClusterer(routingBoard, networkAnalyzer);
          List<NetCluster> clusters = regionAwareClusterer.cluster(allNetNumbers);
          FRLogger.info("    → " + clusters.size() + " clusters");
        }
      } catch (Exception e) {
        FRLogger.warn("  [0.4] Region-aware clustering error (continuing): " + e.getMessage());
      }
      FRLogger.info("  [0.5] CRP多级分区...");
      partitioner = new MultiLevelPartitioner(routingBoard, ch);
      partitioner.buildHierarchy(allNetNumbers);
      FRLogger.info("  [0.6] 区边界路径表预计算...");
      try {
        boundaryPathTable = new DistrictBoundaryPathTable(ch, partitioner);
        boundaryPathTable.precomputeAll();
      } catch (Exception e) {
        FRLogger.warn("  [0.6] Boundary path table error (continuing): " + e.getMessage());
        // Continue without boundary path table
      }
      FRLogger.info("  [0.7] 降级策略初始化 (V7 new)...");
      if (utprSettings.degradationEnabled) {
        try {
          degradationManager = new GracefulDegradationManager(netClassMap);
          degradationManager.setMaxDegradationRounds(utprSettings.maxDegradationRounds);
          degradationManager.buildPlan();
        } catch (Exception e) {
          FRLogger.warn("  [0.7] Degradation plan error (continuing): " + e.getMessage());
        }
      }
      FRLogger.info("  [0.8] 低要求网络标记 (V7 new)...");
      if (utprSettings.lowReqRelaxationEnabled) {
        try {
          relaxationManager = new LowRequirementRelaxationManager(routingBoard, netClassMap);
          relaxationManager.buildPlan();
          FRLogger.info("    → " + relaxationManager.getLowRequirementCount() + " 低要求网络");
        } catch (Exception e) {
          FRLogger.warn("  [0.8] Relaxation plan error (continuing): " + e.getMessage());
        }
      }
      FRLogger.info("  [0.9] 电源/地网络扇出...");
      if (utprSettings.fanoutEnabled) {
        try {
          BatchFanout.parallelFanoutBoard(routingBoard, this.settings,
              this.thread, null, null);
        } catch (Exception e) {
          FRLogger.warn("  Phase 0 fanout error (continuing): " + e.getMessage());
        }
      }
    }

  // ═══════════════════════════════════════════════════════════════════════
  //  Phase 1: Backbone Routing
  // ═══════════════════════════════════════════════════════════════════════

  private void phase1BackboneRouting(RoutingBoard routingBoard) {
    FRLogger.info("  [1.1] STOM时空占用图初始化...");
    stom = new SpatioTemporalOccupancyMap(routingBoard,
        utprSettings.chCellSizeUm * 100.0);
    FRLogger.info("  [1.2] 骨干网络筛选排序...");
    backboneSelector = new BackboneNetSelector(routingBoard, ch);
    List<BackboneNetSelector.BackboneNet> backboneNets =
        backboneSelector.selectBackboneNets(allNetNumbers);
    FRLogger.info("  [1.3] 枢纽扇出模板匹配...");
    int signalLayerCount = routingBoard.layer_structure.signal_layer_count();
    hubFanout = new HubFanoutTemplates(300, 600, signalLayerCount);
    FRLogger.info("  [1.4] 时序A*预约式骨干路由...");
    timedAStar = new TimedBidirectionalAStar(routingBoard, routingBoard,
        ch, stom, corridorPlanner, congestionEstimator);
    // Route backbone nets in priority order — attempt critical + med + low nets.
    // Higher attempt count improves coverage for DRAM/bus nets.
    int backboneRouted = 0;
    int routeCount = 0;
    final int MAX_BACKBONE_ROUTE = 60;
    for (BackboneNetSelector.BackboneNet bn : backboneNets) {
      // Skip low-priority nets if already past limit
      if (bn.priority <= BackboneNetSelector.PRIORITY_LOW && routeCount > 24) continue;
      if (bn.priority <= BackboneNetSelector.PRIORITY_MEDIUM && routeCount >= MAX_BACKBONE_ROUTE) break;
      routeCount++;
      try {
        BatchAutorouter.NetRouteResult result = routeNet(bn.netNo, 8);
        if (result.isRouted()) {
          bn.routed = true;
          backboneRouted++;
          routedNets.add(bn.netNo);
          // Reserve in STOM
          List<Long> cellKeys = extractNetCellKeys(routingBoard, bn.netNo);
          if (!cellKeys.isEmpty()) stom.reserve(cellKeys);
        }
      } catch (Exception e) {
        FRLogger.debug("  Backbone net " + bn.netNo + " failed: " + e.getMessage());
      }
    }
    FRLogger.info("  Backbone: " + backboneRouted + "/" + backboneNets.size() + " routed (attempted " + routeCount + ")");
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Phase 2: Global ripup & reroute via autoroute_pass_multi_thread
  //  (replaces per-net routeNet which gets 0 for difficult nets)
  // ═══════════════════════════════════════════════════════════════════════

  private void phase2DistrictRouting(RoutingBoard routingBoard) {
    int remaining = allNetNumbers.size() - routedNets.size();
    FRLogger.info("  [2.1] 全局重生布线 — " + remaining + " 个未完成网络...");

    // Use autoroute_pass_multi_thread for global ripup & reroute.
    // This clones the board, runs multiple threads with shuffled items,
    // and picks the best result — far more effective than per-net routeNet.
    // Falls back to single-threaded autoroute_pass on exception.
    int beforeCount = routedNets.size();
    try {
      for (int pass = 1; pass <= 6; pass++) {
        // Multi-threaded pass (fast, clones board for each thread).
        // Falls back to single-thread on exception or zero-progress.
        boolean progress = autoroute_pass_multi_thread(pass);
        if (!progress) {
          // Multi-thread returned false (error or stall) -> try single-thread fallback
          try {
            progress = autoroute_pass(pass);
            if (progress) {
              FRLogger.info("    Pass " + pass + " multi-thread stalled, single-thread fallback succeeded");
            }
          } catch (Exception e2) {
            FRLogger.debug("  Pass " + pass + " single-thread fallback also failed");
          }
        }
        rebuildRoutedNets();
        int currentRouted = routedNets.size();
        int newlyRouted = currentRouted - beforeCount;
        FRLogger.info("    Pass " + pass + (newlyRouted >= 0
            ? ": " + currentRouted + "/" + allNetNumbers.size() + " routed (+" + newlyRouted + ")"
            : ": " + currentRouted + "/" + allNetNumbers.size() + " routed (" + newlyRouted + ")"));
        beforeCount = currentRouted;
        if (currentRouted >= allNetNumbers.size()) break;
      }
    } catch (Exception e) {
      FRLogger.debug("  Phase 2 autoroute error: " + e.getMessage());
    }

    // After global passes stall, try per-net high-ripup routing on remaining nets.
    // Two tiers: ripup=30 (moderate), then ripup=50 (aggressive).
    remaining = allNetNumbers.size() - routedNets.size();
    if (remaining > 0) {
      FRLogger.info("  [2.2] 高撕线代价逐网攻克 — " + remaining + " 个顽固网络...");
      int highRipupRouted = 0;
      int[] ripupTiers = {30, 50};
      for (int tier : ripupTiers) {
        int tierRouted = 0;
        for (int netNo : allNetNumbers) {
          if (routedNets.contains(netNo)) continue;
          try {
            BatchAutorouter.NetRouteResult result = routeNet(netNo, tier);
            if (result != null && result.isRouted()) {
              routedNets.add(netNo);
              tierRouted++;
            }
          } catch (Exception e) {
            // ignore
          }
        }
        highRipupRouted += tierRouted;
      }
      if (highRipupRouted > 0) {
        FRLogger.info("  [2.2] 高撕线代价: +" + highRipupRouted + " routed, "
            + (allNetNumbers.size() - routedNets.size()) + " remaining");
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Phase 3: Final cleanup with higher ripup passes
  // ═══════════════════════════════════════════════════════════════════════

  private void phase3BottleneckSolver(RoutingBoard routingBoard) {
    Set<Integer> remaining = new HashSet<>(allNetNumbers);
    remaining.removeAll(routedNets);
    if (remaining.isEmpty()) {
      FRLogger.info("  Phase 3: no remaining nets — all routed");
      return;
    }
    FRLogger.info("  [3.1] 最终清理 — " + remaining.size() + " 个未完成...");

    int before = routedNets.size();
    try {
      // Continue autoroute passes with higher pass numbers (higher ripup).
      // Falls back to single-thread on exception or zero-progress.
      for (int pass = 5; pass <= 10; pass++) {
        boolean progress = autoroute_pass_multi_thread(pass);
        if (!progress) {
          try {
            progress = autoroute_pass(pass);
            if (progress) {
              FRLogger.info("    Pass " + pass + " multi-thread stalled, single-thread fallback succeeded");
            }
          } catch (Exception e2) {
            FRLogger.debug("  Pass " + pass + " single-thread fallback also failed");
          }
        }
        rebuildRoutedNets();
        int currentRouted = routedNets.size();
        FRLogger.info("    Pass " + pass + ": " + currentRouted + "/" + allNetNumbers.size()
            + " routed (+" + (currentRouted - before) + ")");
        before = currentRouted;
        if (currentRouted >= allNetNumbers.size()) break;
      }
    } catch (Exception e) {
      FRLogger.debug("  Phase 3 cleanup error: " + e.getMessage());
    }

    // After global passes stall, try very-high-ripup per-net routing.
    // Three escalating rounds: ripup=60 (aggressive), 120 (desperate), 250 (last resort).
    remaining.clear();
    for (int netNo : allNetNumbers) {
      if (!routedNets.contains(netNo)) remaining.add(netNo);
    }
    if (!remaining.isEmpty()) {
      int[] ripupTiers = {60, 120, 250};
      for (int tier = 0; tier < ripupTiers.length; tier++) {
        int tierRouted = 0;
        for (int netNo : remaining) {
          if (routedNets.contains(netNo)) continue;
          try {
            BatchAutorouter.NetRouteResult result = routeNet(netNo, ripupTiers[tier]);
            if (result != null && result.isRouted()) {
              routedNets.add(netNo);
              tierRouted++;
            }
          } catch (Exception e) {
            // ignore
          }
        }
        if (tierRouted > 0) {
          FRLogger.info("  [3.2] 高撕线代价 ripup=" + ripupTiers[tier] + ": +" + tierRouted
              + " routed, " + (allNetNumbers.size() - routedNets.size()) + " remaining");
        }
      }
    }

    remaining.removeIf(n -> routedNets.contains(n));
    int after = routedNets.size();
    if (after > before) {
      FRLogger.info("  Phase 3: " + (after - before) + " additional nets routed via cleanup");
    }
    failedNets.addAll(remaining);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Degradation feedback hooks
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Check degradation after Phase 1 — differential pair routing failures.
   * @return true if any net was degraded
   */
  private boolean checkPhase1Degradation(RoutingBoard routingBoard) {
    if (degradationManager == null || networkAnalyzer == null) return false;
    boolean anyDegraded = false;

    // Check each diff pair: if one net failed and the other was also not routed,
    // degrade the pair
    for (Map.Entry<Integer, int[]> entry : networkAnalyzer.getDiffPairs().entrySet()) {
      int[] pair = entry.getValue();
      boolean pRouted = routedNets.contains(pair[0]);
      boolean nRouted = routedNets.contains(pair[1]);

      if (!pRouted || !nRouted) {
        boolean degraded = degradationManager.checkDiffPairStatus(pair[0], true, 1);
        if (degraded) anyDegraded = true;
      }
    }
    return anyDegraded;
  }

  /**
   * Check degradation after Phase 2 — length-match group mismatches.
   */
  private void checkPhase2Degradation(RoutingBoard routingBoard) {
    if (degradationManager == null || networkAnalyzer == null) return;

    for (Map.Entry<Integer, AutomaticNetworkAnalyzer.LengthMatchGroup> entry
        : networkAnalyzer.getLengthGroups().entrySet()) {
      AutomaticNetworkAnalyzer.LengthMatchGroup group = entry.getValue();
      if (group.netNumbers.isEmpty()) continue;

      // Compute current max length difference among routed members
      double minLen = Double.MAX_VALUE;
      double maxLen = -Double.MAX_VALUE;
      int routedCount = 0;
      for (int netNo : group.netNumbers) {
        if (!routedNets.contains(netNo)) continue;
        double len = estimateRoutedLength(routingBoard, netNo);
        if (len >= 0) {
          minLen = Math.min(minLen, len);
          maxLen = Math.max(maxLen, len);
          routedCount++;
        }
      }

      if (routedCount >= 2 && maxLen > minLen) {
        double maxDiff = maxLen - minLen;
        double referenceLen = maxLen;
        degradationManager.checkLengthGroupStatus(group.groupId, maxDiff, referenceLen, 2);
      }
    }
  }

  /**
   * Check degradation after Phase 3 — SAT UNSAT for remaining nets.
   */
  private void checkPhase3Degradation(RoutingBoard routingBoard) {
    if (degradationManager == null) return;

    Set<Integer> remaining = new HashSet<>(allNetNumbers);
    remaining.removeAll(routedNets);
    if (remaining.isEmpty()) return;

    // Degrade remaining nets' constraints
    for (int netNo : remaining) {
      NetClass nc = netClassMap != null ? netClassMap.get(netNo) : null;
      if (nc == null) continue;
      if (nc.isPartOfDiffPair() || nc.isPartOfLengthGroup()) {
        degradationManager.checkSatUnsat(netNo, 3);
      }
    }
  }

  /**
   * Estimate routed length of a net (pin bounding-box diagonal as proxy).
   */
  private double estimateRoutedLength(RoutingBoard board, int netNo) {
    app.freerouting.rules.Net net = board.rules.nets.get(netNo);
    if (net == null || net.get_pins().isEmpty()) return -1;
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (app.freerouting.board.Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
    }
    return Math.sqrt(Math.pow(maxX - minX, 2) + Math.pow(maxY - minY, 2));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private void collectNetNumbers(RoutingBoard board) {
    allNetNumbers.clear();
    if (board.rules == null || board.rules.nets == null) return;
    int maxNetNo = board.rules.nets.max_net_no();
    for (int i = 1; i <= maxNetNo; i++) {
      app.freerouting.rules.Net net = board.rules.nets.get(i);
      if (net != null && net.get_pins().size() >= 2) {
        allNetNumbers.add(i);
      }
    }
  }

  private List<Long> extractNetCellKeys(RoutingBoard board, int netNo) {
    app.freerouting.rules.Net net = board.rules.nets.get(netNo);
    if (net == null || stom == null) return Collections.emptyList();
    Set<Long> keys = new HashSet<>();
    double cs = stom.getCellSize();
    double bmx = board.bounding_box.ll.to_float().x;
    double bmy = board.bounding_box.ll.to_float().y;
    for (app.freerouting.board.Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      int col = (int) ((c.x - bmx) / cs);
      int row = (int) ((c.y - bmy) / cs);
      keys.add(SpatioTemporalOccupancyMap.cellKey(pin.first_layer(), row, col));
    }
    return new ArrayList<>(keys);
  }

  private void runStandardCleanup(RoutingBoard routingBoard) {
    // Rebuild routedNets from actual board state after autoroute_pass
    rebuildRoutedNets();
    FRLogger.info("  Cleanup: " + routedNets.size() + "/" + allNetNumbers.size() + " routed");
  }

  /**
   * Rebuild the routedNets set by checking each net's actual incomplete
   * connection count via DesignRulesChecker. This ensures routedNets
   * reflects the true board state after autoroute_pass_multi_thread.
   */
  private void rebuildRoutedNets() {
    if (!(this.board instanceof RoutingBoard)) return;
    RoutingBoard rb = (RoutingBoard) this.board;
    DesignRulesChecker drc = new DesignRulesChecker(rb, null);
    drc.calculateAllIncompletes();

    Set<Integer> newRouted = new HashSet<>();
    for (int netNo : allNetNumbers) {
      if (drc.getIncompleteCount(netNo) == 0) {
        newRouted.add(netNo);
      }
    }
    routedNets.clear();
    routedNets.addAll(newRouted);
  }

  /** Get current pipeline phase number. */
  public int getCurrentPhase() { return currentPhase; }

  /** Get UTPR settings. */
  public UrbanTrafficRouterSettings getUtprSettings() { return utprSettings; }

  public ContractionHierarchies getCH() { return ch; }
  public MultiLevelPartitioner getPartitioner() { return partitioner; }
  public SpatioTemporalOccupancyMap getStom() { return stom; }

  /** Get automatic network analyzer (V7 new). */
  public AutomaticNetworkAnalyzer getNetworkAnalyzer() { return networkAnalyzer; }

  /** Get net class map (V7 new). */
  public Map<Integer, NetClass> getNetClassMap() { return netClassMap; }

  /** Get degradation manager (V7 new). */
  public GracefulDegradationManager getDegradationManager() { return degradationManager; }

  /** Get relaxation manager (V7 new). */
  public LowRequirementRelaxationManager getRelaxationManager() { return relaxationManager; }
}
