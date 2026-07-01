package app.freerouting.autoroute;

import app.freerouting.board.DrillItem;
import app.freerouting.board.Item;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.board.Via;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * V6 Three-Stage Hybrid Routing Pipeline.
 *
 * Implements the full V6 routing pipeline:
 * <ol>
 *   <li><b>Phase 0</b>: FLUTE global congestion estimation + Power/GND fanout</li>
 *   <li><b>Phase 1</b>: Adaptive region division (FLUTE-enhanced density heatmap)</li>
 *   <li><b>Phase 2</b>: Region-based parallel signal fanout</li>
 *   <li><b>Phase 3</b>: Short net routing with channel-retention A* cost</li>
 *   <li><b>Phase 4a</b>: Spectral clustering + cluster pin fanout + layer-pair allocation + parallel cluster routing (DynamicThaw)</li>
 *   <li><b>Phase 4b</b>: SAT/ILP exact solving via MultiSolverManager for stubborn nets</li>
 *   <li><b>Phase 4c</b>: Sequential fallback cleanup</li>
 *   <li><b>Phase 5</b>: GND net delayed routing + acceptance</li>
 *   <li><b>Phase 6</b>: Final cleanup (last-mile + dynamic constraints + parallel A* engine)</li>
 * </ol>
 * <p>
 * 2-layer optimization: Power nets treated as signal (no priority fanout),
 * only GND gets priority fanout. Layer pairs auto-degrade to single-pair mode.
 */
public class HybridBatchAutorouterWrapper extends BatchAutorouter {

  public HybridBatchAutorouterWrapper(RoutingJob job) {
    super(job);
  }

  @Override
  public boolean runBatchLoop() {
    FRLogger.info("=== HybridBatchAutorouterWrapper V6 Three-Stage Hybrid Routing starting ===");

    // Get hybrid settings
    HybridRouterSettings hybridSettings;
    if (this.job != null && this.job.routerSettings != null
        && this.job.routerSettings.hybrid != null) {
      hybridSettings = this.job.routerSettings.hybrid;
    } else {
      hybridSettings = new HybridRouterSettings();
      hybridSettings.applyQualityLevel();
    }

    if (!(this.board instanceof RoutingBoard)) {
      FRLogger.warn("HybridBatchAutorouterWrapper V6: Board is not a RoutingBoard");
      return false;
    }
    RoutingBoard routingBoard = (RoutingBoard) this.board;
    hybridSettings.applyQualityLevel();

    int layerCount = routingBoard.get_layer_count();
    int signalLayerCount = routingBoard.layer_structure.signal_layer_count();
    boolean isTwoLayerBoard = (layerCount <= 2);
    FRLogger.info("HybridBatchAutorouterWrapper V6: Board has " + layerCount + " layers ("
        + signalLayerCount + " signal)"
        + (isTwoLayerBoard ? " (2-layer mode)" : ""));

    this.fireTaskStateChangedEvent(new app.freerouting.autoroute.events.TaskStateChangedEvent(
        this, app.freerouting.autoroute.TaskState.STARTED, 0, this.board.get_hash()));

    // ================================================================
    // Phase 0: FLUTE Global Congestion Estimation + Power/GND Fanout
    // ================================================================
    boolean hasSmdPins = routingBoard.get_smd_pins() != null && !routingBoard.get_smd_pins().isEmpty();
    PowerGndAutoLabeler labeler = null;
    Set<Integer> powerGroundNetNumbers = new HashSet<>();
    if (hasSmdPins) {
      labeler = new PowerGndAutoLabeler(routingBoard);
      labeler.autoLabelAllNets();
      if (isTwoLayerBoard) {
        powerGroundNetNumbers.addAll(labeler.getGroundNetNumbers());
      } else {
        powerGroundNetNumbers.addAll(labeler.getPowerNetNumbers());
        powerGroundNetNumbers.addAll(labeler.getGroundNetNumbers());
      }
    }

    // FLUTE global congestion estimation
    FRLogger.info("=== Phase 0: FLUTE global congestion estimation ===");
    long phase0Start = System.currentTimeMillis();
    FluteTopologyEstimator fluteEstimator = null;
    try {
      Set<Integer> netsToEstimate = collectIncompleteNetNumbers(routingBoard);
      if (netsToEstimate != null && !netsToEstimate.isEmpty()) {
        fluteEstimator = new FluteTopologyEstimator(routingBoard, 2000000); // 2mm grid
        fluteEstimator.estimate(netsToEstimate);
        int hotspotCount = fluteEstimator.getHotSpots().size();
        FRLogger.info("Phase 0: FLUTE done — " + netsToEstimate.size() + " nets estimated, "
            + hotspotCount + " hotspots, max congestion="
            + String.format("%.2f", fluteEstimator.getMaxCongestion()));
      }
    } catch (Exception e) {
      FRLogger.warn("Phase 0: FLUTE estimation error (continuing): " + e.getMessage());
    }
    FRLogger.info("Phase 0 complete in " + (System.currentTimeMillis() - phase0Start) + "ms");

    // Power/GND priority fanout
    if (this.settings.isFanoutEnabled() && hasSmdPins && !powerGroundNetNumbers.isEmpty()) {
      FRLogger.info("=== Phase 0b: " + (isTwoLayerBoard ? "GND" : "Power/GND")
          + " priority fanout (" + powerGroundNetNumbers.size() + " nets) ===");
      long fanoutStart = System.currentTimeMillis();
      try {
        BatchFanout.parallelFanoutBoard(routingBoard, this.settings, this.thread,
            null, powerGroundNetNumbers);
      } catch (Exception e) {
        FRLogger.warn("Phase 0b: Fanout error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 0b complete in " + (System.currentTimeMillis() - fanoutStart) + "ms");
    } else {
      FRLogger.info("Phase 0b: Skipped (no power/GND nets or fanout disabled)");
    }

    // ================================================================
    // Phase 1: Adaptive Region Division (FLUTE-enhanced)
    // ================================================================
    FRLogger.info("=== Phase 1: Adaptive region division ===");
    long phaseStart1 = System.currentTimeMillis();
    Set<Integer> netsToExcludeFromDensity = new HashSet<>();
    if (labeler != null) {
      if (isTwoLayerBoard) {
        netsToExcludeFromDensity.addAll(labeler.getGroundNetNumbers());
      } else {
        netsToExcludeFromDensity.addAll(labeler.getPowerNetNumbers());
        netsToExcludeFromDensity.addAll(labeler.getGroundNetNumbers());
      }
    }
    int coreCount = Runtime.getRuntime().availableProcessors();
    List<Region> regions = RegionDivider.divideBoard(routingBoard, coreCount,
        labeler, netsToExcludeFromDensity);
    FRLogger.info("Phase 1 complete: " + regions.size() + " regions in "
        + (System.currentTimeMillis() - phaseStart1) + "ms");

    long activeRegionCount = regions.stream().filter(r -> r.isActive).count();
    boolean useRegionRouting = (activeRegionCount > 1);

    // ================================================================
    // Phase 2: Region-based Parallel Signal Fanout
    // ================================================================
    if (this.settings.isFanoutEnabled() && hasSmdPins && useRegionRouting) {
      FRLogger.info("=== Phase 2: Region-based signal fanout ===");
      long phaseStart2 = System.currentTimeMillis();
      try {
        RegionRouter.regionFanout(routingBoard, regions, this.settings, this.thread,
            new HashSet<>());
      } catch (Exception e) {
        FRLogger.warn("Phase 2: Region fanout error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 2 complete in " + (System.currentTimeMillis() - phaseStart2) + "ms");
    } else {
      FRLogger.info("Phase 2: Skipped (no SMD pins, fanout disabled, or single region)");
    }
    this.settings.setFanoutEnabled(false);

    // ================================================================
    // Phase 3: Short Net Routing (channel-retention cost)
    // ================================================================
    if (useRegionRouting) {
      FRLogger.info("=== Phase 3: Short net routing (channel retention) ===");
      long phaseStart3 = System.currentTimeMillis();
      try {
        Set<Integer> netsToExcludeFromShortRoute = new HashSet<>();
        if (labeler != null) {
          if (isTwoLayerBoard) {
            netsToExcludeFromShortRoute.addAll(labeler.getGroundNetNumbers());
          } else {
            netsToExcludeFromShortRoute.addAll(labeler.getPowerNetNumbers());
            netsToExcludeFromShortRoute.addAll(labeler.getGroundNetNumbers());
          }
        }
        // Use FLUTE congestion dampened threshold if available
        double shortNetThreshold = 500000.0;
        if (fluteEstimator != null && fluteEstimator.getMaxCongestion() > 2.0) {
          shortNetThreshold *= 0.7; // More aggressive short route in congested boards
          FRLogger.info("Phase 3: Congestion >2.0 detected, reducing short-net threshold to "
              + (long) shortNetThreshold);
        }
        RegionRouter.regionShortRoute(routingBoard, regions, this.settings, this.thread,
            shortNetThreshold, netsToExcludeFromShortRoute);
      } catch (Exception e) {
        FRLogger.warn("Phase 3: Short route error (continuing): " + e.getMessage());
      }
      int afterPhase3 = calculateIncompleteCount(routingBoard);
      FRLogger.info("Phase 3 complete in " + (System.currentTimeMillis() - phaseStart3)
          + "ms, " + afterPhase3 + " incompletes remaining");
    }

    // ================================================================
    // Phase 4a: Spectral Clustering + Pin Fanout + Layer Pair + Dynamic Thaw
    // ================================================================
    if (useRegionRouting) {
      FRLogger.info("=== Phase 4a: Spectral clustering + DynamicThaw routing ===");
      long phaseStart4 = System.currentTimeMillis();
      try {
        this.setStopAtPassMinimum(3);
        this.setStagnationPassLimit(4);
        int remaining = regionClusterRouteV6(routingBoard, regions, this.settings, this.thread);
        FRLogger.info("Phase 4a: " + remaining + " incompletes remaining");
      } catch (Exception e) {
        FRLogger.warn("Phase 4a: Cluster route error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 4a complete in " + (System.currentTimeMillis() - phaseStart4) + "ms");
    }

    // ================================================================
    // Phase 4b: SAT/ILP Exact Solving
    // ================================================================
    int phase4bRemaining = calculateIncompleteCount(routingBoard);
    if (phase4bRemaining > 2 && phase4bRemaining <= 50 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("=== Phase 4b: SAT/ILP exact solving (" + phase4bRemaining + " nets) ===");
      long phaseStart4b = System.currentTimeMillis();
      try {
        Set<Integer> stubbornNets = collectIncompleteNetNumbers(routingBoard);
        if (!stubbornNets.isEmpty()) {
          satExactSolve(routingBoard, stubbornNets);
        }
      } catch (Exception e) {
        FRLogger.warn("Phase 4b: SAT error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 4b complete in " + (System.currentTimeMillis() - phaseStart4b) + "ms");
    } else {
      FRLogger.info("Phase 4b: Skipped (" + phase4bRemaining
          + " incompletes — requires 3-50 for SAT)");
    }

    // ================================================================
    // Phase 4c: Sequential fallback cleanup
    // ================================================================
    {
      int remaining = calculateIncompleteCount(routingBoard);
      if (remaining > 0 && !this.thread.is_stop_auto_router_requested()) {
        FRLogger.info("=== Phase 4c: Sequential fallback cleanup (" + remaining + " incompletes) ===");
        long phaseStart4c = System.currentTimeMillis();
        try {
          populatePowerGndSkipSet(routingBoard);
          this.setStagnationPassLimit(2);
          this.setStopAtPassMinimum(2);
          super.runBatchLoop();
        } catch (Exception e) {
          FRLogger.warn("Phase 4c: Sequential cleanup error (continuing): " + e.getMessage());
        }
        FRLogger.info("Phase 4c complete in " + (System.currentTimeMillis() - phaseStart4c) + "ms");
      } else {
        FRLogger.info("Phase 4c: Skipped (board fully routed or stopped)");
      }
    }

    // ================================================================
    // Phase 5: GND Delayed Routing + Acceptance
    // ================================================================
    {
      FRLogger.info("=== Phase 5: GND delayed routing ===");
      long phaseStart5 = System.currentTimeMillis();
      try {
        Set<Integer> gndNetNumbers = (labeler != null)
            ? labeler.getGroundNetNumbers() : new HashSet<>();
        if (!gndNetNumbers.isEmpty()) {
          FRLogger.info("Phase 5: " + gndNetNumbers.size() + " GND nets, routing...");
          this.setStopAtPassMinimum(2);
          this.setStagnationPassLimit(3);
          this.settings.setFanoutEnabled(false);
          super.runBatchLoop();
          acceptFanoutOnlyForGndNets(routingBoard);
        }
      } catch (Exception e) {
        FRLogger.warn("Phase 5: GND routing error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 5 complete in " + (System.currentTimeMillis() - phaseStart5) + "ms");
    }

    // ================================================================
    // Phase 6: Final Cleanup (last-mile + dynamic constraints + parallel A*)
    // ================================================================
    FRLogger.info("=== Phase 6: Final cleanup ===");
    long phaseStart6 = System.currentTimeMillis();

    // Parallel A* engine for remaining nets
    int remaining = calculateIncompleteCount(routingBoard);
    if (remaining > 0 && remaining <= 30 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("Phase 6a: Parallel A* routing for " + remaining + " nets");
      try {
        DataParallelAStarEngine parallelEngine = new DataParallelAStarEngine(
            routingBoard, this.settings, this.thread);
        Set<Integer> unroutedNets = collectIncompleteNetNumbers(routingBoard);
        Set<Integer> routedNets = parallelEngine.routeNetsParallel(unroutedNets,
            this.settings.get_start_ripup_costs() * 5);
        parallelEngine.shutdown();
        FRLogger.info("Phase 6a: Parallel A* routed " + routedNets.size() + " nets");
      } catch (Exception e) {
        FRLogger.warn("Phase 6a: Parallel A* error (continuing): " + e.getMessage());
      }
    }

    // Last-mile aggressive ripup
    remaining = calculateIncompleteCount(routingBoard);
    if (remaining > 0 && remaining <= 3 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("Phase 6b: Last-mile router...");
      lastMileRouter(routingBoard);
    }

    // Dynamic constraint reduction
    remaining = calculateIncompleteCount(routingBoard);
    if (remaining > 0 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("Phase 6c: Relaxed constraints...");
      dynamicConstraintRouting(routingBoard);
    }

    if (remaining == 0) {
      FRLogger.info("Phase 6: Board fully routed!");
    }
    FRLogger.info("Phase 6 complete in " + (System.currentTimeMillis() - phaseStart6) + "ms");

    this.fireTaskStateChangedEvent(new app.freerouting.autoroute.events.TaskStateChangedEvent(
        this, app.freerouting.autoroute.TaskState.FINISHED, 1, this.board.get_hash()));

    FRLogger.info("=== HybridBatchAutorouterWrapper V6 complete ===");
    return true;
  }

  /**
   * V6 Phase 4a: Spectral clustering + layer-pair allocation + parallel cluster routing + DynamicThaw.
   */
  private int regionClusterRouteV6(
      RoutingBoard board, List<Region> regions, RouterSettings settings, StoppableThread thread) {

    Set<Integer> incompleteNets = collectIncompleteNetNumbers(board);
    if (incompleteNets.isEmpty()) return 0;

    // Step 1: Spectral clustering
    SpectralClusterer spectralClusterer = new SpectralClusterer(board);
    List<NetCluster> clusters = spectralClusterer.cluster(incompleteNets);
    if (clusters.isEmpty()) {
      return incompleteNets.size();
    }

    // Step 2: Layer-pair data model
    List<LayerPair> layerPairs = LayerPair.buildForBoard(
        board.layer_structure.signal_layer_count());

    // Step 3: Cluster pin fanout for BGA/SMD
    ClusterPinFanout fanout = new ClusterPinFanout(board, settings, thread);
    for (NetCluster cluster : clusters) {
      if (thread.is_stop_auto_router_requested()) break;
      try {
        fanout.fanoutCluster(cluster);
      } catch (Exception e) {
        FRLogger.debug("Phase4a: Fanout error for cluster " + cluster.getClusterId() + ": " + e.getMessage());
      }
    }

    // Step 4: Assign clusters to layer pairs (soft cost)
    for (NetCluster cluster : clusters) {
      LayerPair bestPair = null;
      double bestCost = Double.MAX_VALUE;
      for (LayerPair lp : layerPairs) {
        double cost = lp.assignmentCost(cluster.getNetCount(), cluster.getWidth(), cluster.getHeight());
        if (cost < bestCost) { bestCost = cost; bestPair = lp; }
      }
      if (bestPair != null) {
        // Assign primary/secondary layers from pair
        cluster.setPrimaryLayer(bestPair.primaryLayer);
        cluster.setSecondaryLayer(bestPair.secondaryLayer);
        bestPair.currentOccupancy += cluster.getNetCount();
      }
    }

    // Step 5: Sort clusters by priority (net count ascending → small first for quick wins)
    clusters.sort((a, b) -> Integer.compare(a.getNetCount(), b.getNetCount()));

    // Step 6: DynamicThawManager for conflict resolution
    DynamicThawManager thawManager = new DynamicThawManager(board);

    // Step 7: Parallel batch cluster routing with CompletionService + DynamicThaw
    int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), 6);
    int batchSize = Math.max(numThreads * 2, 4);
    Set<Integer> originalItemIds = RegionRouter.collectItemIds(board);
    int totalMerged = 0;
    int completedClusters = 0;

    for (int batchStart = 0; batchStart < clusters.size(); batchStart += batchSize) {
      int batchEnd = Math.min(batchStart + batchSize, clusters.size());

      int remainingNow = new DesignRulesChecker(board, null).getIncompleteCount();
      if (remainingNow == 0) break;

      ExecutorService executor = Executors.newFixedThreadPool(
          Math.min(numThreads, batchEnd - batchStart));
      CompletionService<ClusterRouteTask> completionService = new ExecutorCompletionService<>(executor);
      int submitted = 0;

      for (int ci = batchStart; ci < batchEnd; ci++) {
        final NetCluster cluster = clusters.get(ci);
        final int clusterIdx = ci;
        completionService.submit(() -> {
          long start = System.currentTimeMillis();
          RoutingBoard clusterBoard = board.deepCopy();
          if (clusterBoard == null) return null;

          RouterSettings clusterSettings = settings.clone();
          clusterSettings.netsToRoute = new HashSet<>(cluster.getNetNumbers());
          clusterSettings.setFanoutEnabled(false);

          StoppableThread ct = RegionRouter.createTrialThread();
          BatchAutorouter router = new BatchAutorouter(ct, clusterBoard, clusterSettings,
              true, true, settings.get_start_ripup_costs(),
              settings.trace_pull_tight_accuracy);
          int passLimit = cluster.getNetCount() <= 3 ? 3
              : cluster.getNetCount() <= 8 ? 4 : 5;
          router.setStagnationPassLimit(passLimit);
          router.setStopAtPassMinimum(Math.max(2, passLimit - 1));
          router.setOptimizerAutorouter(true);
          router.runBatchLoop();

          int incompletes = new DesignRulesChecker(clusterBoard, null).getIncompleteCount();
          long duration = System.currentTimeMillis() - start;
          FRLogger.info("Phase4a: Cluster " + clusterIdx + " (" + cluster.getNetCount()
              + " nets) — " + incompletes + " incompletes, " + duration + "ms");
          return new ClusterRouteTask(clusterBoard, clusterIdx);
        });
        submitted++;
      }

      for (int i = 0; i < submitted; i++) {
        try {
          Future<ClusterRouteTask> completed = completionService.take();
          ClusterRouteTask result = completed.get(120, TimeUnit.SECONDS);
          if (result == null) continue;

          int merged = RegionRouter.mergeNewItems(board, result.board, originalItemIds);
          totalMerged += merged;
          completedClusters++;

          Set<Integer> updatedIds = new HashSet<>(originalItemIds);
          Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
          for (;;) {
            UndoableObjects.Storable ob = board.item_list.read_object(it);
            if (ob == null) break;
            if (ob instanceof Item item) updatedIds.add(item.get_id_no());
          }
          originalItemIds = updatedIds;

          // Record routed cluster for DynamicThaw
          thawManager.recordRoutedCluster(clusters.get(result.clusterIndex));
        } catch (TimeoutException e) {
          FRLogger.warn("Phase4a: Cluster timed out after 120s.");
        } catch (Exception e) {
          FRLogger.warn("Phase4a: Cluster execution error: " + e.getMessage());
        }
      }
      executor.shutdownNow();
    }

    // Step 8: DynamicThaw — detect and resolve conflicts
    if (!thawManager.isThawExhausted()) {
      for (DynamicThawManager.ThawRequest request : thawManager.getPendingThawRequests()) {
        if (thread.is_stop_auto_router_requested()) break;
        FRLogger.info("Phase4a: Executing DynamicThaw (urgency=" + request.urgency + ")");
        Set<Integer> thawedNets = thawManager.executeThaw(request);
        if (!thawedNets.isEmpty()) {
          BatchAutorouter thawRouter = new BatchAutorouter(
              thread, board, settings, true, true,
              settings.get_start_ripup_costs() * request.urgency,
              settings.trace_pull_tight_accuracy);
          thawRouter.setStagnationPassLimit(3);
          thawRouter.setOptimizerAutorouter(true);
          thawRouter.runBatchLoop();
        }
      }
      thawManager.clearProcessedThawRequests();
    }

    board.clear_all_item_temporary_autoroute_data();
    board.finish_autoroute();

    int remainingAfter = new DesignRulesChecker(board, null).getIncompleteCount();
    FRLogger.info("Phase4a: Complete — " + completedClusters + "/" + clusters.size()
        + " clusters, " + totalMerged + " items merged, " + remainingAfter + " incompletes, "
        + "thawCount=" + thawManager.getPendingThawRequests().size());
    return remainingAfter;
  }

  /**
   * V6 Phase 4b: SAT/ILP exact solving for stubborn nets.
   */
  private void satExactSolve(RoutingBoard routingBoard, Set<Integer> stubbornNets) {
    Sat4jSolver solver = new Sat4jSolver(routingBoard);
    solver.detectDifferentialPairs(stubbornNets);

    // Generate candidate paths for each net via A* routing
    for (int netNo : stubbornNets) {
      List<Sat4jSolver.CandidatePath> paths = new ArrayList<>();
      for (int pass = 1; pass <= 3; pass++) {
        try {
          StoppableThread ct = RegionRouter.createTrialThread();
          RoutingBoard netBoard = routingBoard.deepCopy();
          RouterSettings netSettings = settings.clone();
          netSettings.netsToRoute = new HashSet<>(Collections.singletonList(netNo));
          netSettings.setFanoutEnabled(false);

          BatchAutorouter router = new BatchAutorouter(ct, netBoard, netSettings,
              true, true, settings.get_start_ripup_costs() * (1 + pass),
              settings.trace_pull_tight_accuracy);
          router.setStagnationPassLimit(2);
          router.setOptimizerAutorouter(true);
          router.runBatchLoop();

          DesignRulesChecker drc = new DesignRulesChecker(netBoard, null);
          drc.calculateAllIncompletes();
          boolean hasIncompletes = drc.getIncompleteCount() > 0;

          if (!hasIncompletes) {
            double estLength = 100000 * pass;
            paths.add(new Sat4jSolver.CandidatePath(netNo, estLength,
                estLength * (1 + pass * 0.1), 0, pass, new ArrayList<>()));
          }
        } catch (Exception e) {
          FRLogger.debug("Sat4jSolver: Candidate gen failed for net " + netNo + ": " + e.getMessage());
        }
      }
      if (!paths.isEmpty()) solver.addCandidates(netNo, paths);
    }

    // Build conflict graph
    solver.buildConflictGraph();

    // Multi-solver parallel exploration
    MultiSolverManager multiSolver = new MultiSolverManager(solver);

    for (int relaxation = 0; relaxation <= 3; relaxation++) {
      MultiSolverManager.SolveResult result = multiSolver.parallelSolve(stubbornNets, relaxation);

      // Route the solved nets on the actual board
      for (int netNo : result.solved) {
        StoppableThread ct = RegionRouter.createTrialThread();
        this.settings.set_start_ripup_costs(
            this.settings.get_start_ripup_costs() * (1 + relaxation));
        BatchAutorouter.NetRouteResult rr = routeNet(netNo,
            5 + relaxation * 2);
      }

      stubbornNets.removeAll(result.solved);
      if (stubbornNets.isEmpty()) break;

      // UNSAT core analysis
      Set<Integer> unsatCore = multiSolver.minimizeUnsatCore(stubbornNets, result.solved);
      if (unsatCore.size() < stubbornNets.size()) {
        FRLogger.info("Phase 4b: UNSAT core reduced from " + stubbornNets.size()
            + " to " + unsatCore.size() + " nets");
      }
    }

    multiSolver.shutdown();

    int satRouted = stubbornNets.size()
        - new DesignRulesChecker(routingBoard, null).getIncompleteCount();
    FRLogger.info("Phase 4b: SAT/ILP solved " + satRouted + " nets");
  }

  // ===================== Last-Mile Aggressive Ripup =====================

  private void lastMileRouter(RoutingBoard routingBoard) {
    DesignRulesChecker drc = new DesignRulesChecker(routingBoard, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null || airlines.length == 0) return;

    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
    for (AirLine al : airlines) {
      if (al.from_item instanceof Pin fPin) {
        FloatPoint fp = fPin.get_center().to_float();
        minX = Math.min(minX, fp.x); maxX = Math.max(maxX, fp.x);
        minY = Math.min(minY, fp.y); maxY = Math.max(maxY, fp.y);
      }
      if (al.to_item instanceof Pin tPin) {
        FloatPoint tp = tPin.get_center().to_float();
        minX = Math.min(minX, tp.x); maxX = Math.max(maxX, tp.x);
        minY = Math.min(minY, tp.y); maxY = Math.max(maxY, tp.y);
      }
    }

    double width = maxX - minX;
    double height = maxY - minY;
    double expandX = Math.max(width * 0.2, 500000);
    double expandY = Math.max(height * 0.2, 500000);
    minX -= expandX; maxX += expandX;
    minY -= expandY; maxY += expandY;

    int tracesRemoved = 0;
    List<Item> itemsToRemove = new ArrayList<>();
    Iterator<UndoableObjects.UndoableObjectNode> it
        = routingBoard.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = routingBoard.item_list.read_object(it);
      if (ob == null) break;
      if (ob instanceof Trace trace && !(ob instanceof Pin) && !(ob instanceof Via)) {
        FloatPoint a = trace.first_corner().to_float();
        FloatPoint b = trace.last_corner().to_float();
        double midX = (a.x + b.x) / 2;
        double midY = (a.y + b.y) / 2;
        if (midX >= minX && midX <= maxX && midY >= minY && midY <= maxY) {
          itemsToRemove.add(trace);
        }
      }
    }
    routingBoard.start_marking_changed_area();
    routingBoard.remove_items(itemsToRemove);
    tracesRemoved = itemsToRemove.size();

    if (tracesRemoved == 0) return;

    int maxSeeds = 3;
    RoutingBoard bestBoard = null;
    float bestScore = Float.NEGATIVE_INFINITY;

    for (int seed = 0; seed < maxSeeds; seed++) {
      if (this.thread.is_stop_auto_router_requested()) break;

      RoutingBoard trialBoard;
      boolean isInPlace = false;
      try {
        trialBoard = routingBoard.deepCopy();
      } catch (Exception e) {
        trialBoard = routingBoard;
        isInPlace = true;
      }
      BatchAutorouter trialRouter = new BatchAutorouter(
          this.thread, trialBoard, this.settings, true, true,
          this.settings.get_start_ripup_costs() * 3,
          this.settings.trace_pull_tight_accuracy);
      trialRouter.random = new Random(seed);
      trialRouter.setStagnationPassLimit(5);
      trialRouter.runBatchLoop();

      float trialScore = new BoardStatistics(trialBoard)
          .getNormalizedScore(this.job.routerSettings.scoring);
      int trialIncomplete = new DesignRulesChecker(trialBoard, null).getIncompleteCount();

      if (trialScore > bestScore) {
        bestScore = trialScore;
        bestBoard = trialBoard;
        if (isInPlace) break;
      }
      if (trialIncomplete == 0) break;
    }

    if (bestBoard != null) {
      this.board = bestBoard;
      if (this.job != null) this.job.board = bestBoard;
    }
  }

  // ===================== GND: Fanout-Only Acceptance =====================

  private void acceptFanoutOnlyForGndNets(RoutingBoard routingBoard) {
    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(routingBoard);
    labeler.autoLabelAllNets();
    boolean isTwoLayer = (routingBoard.get_layer_count() <= 2);

    DesignRulesChecker drc = new DesignRulesChecker(routingBoard, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null || airlines.length == 0) return;

    int gndConnectionsAccepted = 0;
    for (AirLine al : airlines) {
      if (al.net == null) continue;
      int netNo = al.net.net_number;
      if (isTwoLayer) {
        if (!labeler.isGroundNet(netNo)) continue;
      } else {
        if (!labeler.isGroundNet(netNo) && !labeler.isPowerNet(netNo)) continue;
      }

      for (int i = 0; i < 3; i++) {
        board.failureLog.recordFailure(al.from_item, 99,
            app.freerouting.autoroute.AutorouteAttemptState.FAILED,
            "GND: accepted fanout-only");
        board.failureLog.recordFailure(al.to_item, 99,
            app.freerouting.autoroute.AutorouteAttemptState.FAILED,
            "GND: accepted fanout-only");
      }
      gndConnectionsAccepted++;
    }

    if (gndConnectionsAccepted > 0) {
      FRLogger.info("GND acceptance: " + gndConnectionsAccepted
          + " GND connections accepted with fanout-only state");
    }
  }

  private void populatePowerGndSkipSet(RoutingBoard routingBoard) {
    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(routingBoard);
    labeler.autoLabelAllNets();
    boolean isTwoLayer = (routingBoard.get_layer_count() <= 2);

    DesignRulesChecker drc = new DesignRulesChecker(routingBoard, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null || airlines.length == 0) return;

    Set<Integer> skipNets = new HashSet<>();
    for (AirLine al : airlines) {
      if (al.net == null) continue;
      int netNo = al.net.net_number;
      if (isTwoLayer) {
        if (!labeler.isGroundNet(netNo)) continue;
      } else {
        if (!labeler.isGroundNet(netNo) && !labeler.isPowerNet(netNo)) continue;
      }

      if (hasFanoutVia(al.from_item, netNo) || hasFanoutVia(al.to_item, netNo)) {
        skipNets.add(netNo);
      }
    }

    if (!skipNets.isEmpty()) {
      this.addPowerGndNetsToSkip(skipNets);
      FRLogger.info("GND skip set populated with " + skipNets.size()
          + " net(s) that already have fanout via coverage.");
    }
  }

  private boolean hasFanoutVia(Item item, int netNo) {
    if (item == null) return false;
    Set<Item> connected = item.get_connected_set(netNo);
    for (Item ci : connected) {
      if (ci instanceof Via) return true;
    }
    return false;
  }

  // ===================== Dynamic Constraint Reduction =====================

  private void dynamicConstraintRouting(RoutingBoard routingBoard) {
    if (this.thread.is_stop_auto_router_requested()) return;

    int originalViaCosts = this.settings.get_via_costs();
    int originalPlaneViaCosts = this.settings.get_plane_via_costs();
    int originalRipupCosts = this.settings.get_start_ripup_costs();

    int reducedViaCosts = Math.max(1, (int) (originalViaCosts * 0.7));
    int reducedPlaneViaCosts = Math.max(1, (int) (originalPlaneViaCosts * 0.7));
    int increasedRipupCosts = originalRipupCosts * 5;

    this.settings.set_via_costs(reducedViaCosts);
    this.settings.set_plane_via_costs(reducedPlaneViaCosts);
    this.settings.set_start_ripup_costs(increasedRipupCosts);

    BatchAutorouter relaxedRouter = new BatchAutorouter(
        this.thread, routingBoard, this.settings, true, true,
        increasedRipupCosts, this.settings.trace_pull_tight_accuracy);
    relaxedRouter.setStagnationPassLimit(3);
    relaxedRouter.runBatchLoop();

    this.settings.set_via_costs(originalViaCosts);
    this.settings.set_plane_via_costs(originalPlaneViaCosts);
    this.settings.set_start_ripup_costs(originalRipupCosts);

    if (relaxedRouter.board != null) {
      int finalIncomplete = new DesignRulesChecker(
          (RoutingBoard) relaxedRouter.board, null).getIncompleteCount();
      int currentIncomplete = new DesignRulesChecker(routingBoard, null).getIncompleteCount();
      if (finalIncomplete <= currentIncomplete) {
        this.board = relaxedRouter.board;
        if (this.job != null) this.job.board = relaxedRouter.board;
        FRLogger.info("P4 dynamic constraints: applied, " + finalIncomplete + " incompletes remaining");
      }
    }
  }

  // ===================== Utility =====================

  private int calculateIncompleteCount(RoutingBoard board) {
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    return drc.getIncompleteCount();
  }

  private Set<Integer> collectIncompleteNetNumbers(RoutingBoard board) {
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null) return Collections.emptySet();
    Set<Integer> netNos = new HashSet<>();
    for (AirLine al : airlines) {
      if (al.net != null) netNos.add(al.net.net_number);
    }
    return netNos;
  }

  // Inner class for cluster routing tasks (used by CompletionService)
  private static class ClusterRouteTask {
    final RoutingBoard board;
    final int clusterIndex;
    ClusterRouteTask(RoutingBoard board, int clusterIndex) {
      this.board = board;
      this.clusterIndex = clusterIndex;
    }
  }

  // ===================== Identity =====================

  @Override
  public String getId() {
    return "hybrid-three-stage-v6";
  }

  @Override
  public String getName() {
    return "V6 Three-Stage Hybrid Parallel Router";
  }

  @Override
  public String getVersion() {
    return "6.0.0";
  }

  @Override
  public String getDescription() {
    return "V6 Three-stage hybrid routing: FLUTE estimation + spectral clustering + "
        + "SAT/ILP + multi-core parallelism (2-layer optimized)";
  }

  @Override
  public NamedAlgorithmType getType() {
    return NamedAlgorithmType.ROUTER;
  }
}
