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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * V2 Adaptive Region Parallel Routing Wrapper.
 * <p>
 * Implements the 6-phase adaptive routing pipeline:
 * <ol>
 *   <li><b>Phase 0</b>: Power/GND priority fanout (2-layer: GND only)</li>
 *   <li><b>Phase 1</b>: Adaptive region division (density heatmap + 1D projection)</li>
 *   <li><b>Phase 2</b>: Region-based parallel signal fanout</li>
 *   <li><b>Phase 3</b>: Region-based parallel short net routing</li>
 *   <li><b>Phase 4</b>: Region-based parallel cluster routing + sequential fallback</li>
 *   <li><b>Phase 5</b>: GND net delayed routing + acceptance</li>
 *   <li><b>Phase 6</b>: Final cleanup (last-mile + dynamic constraints)</li>
 * </ol>
 * <p>
 * 2-layer optimization: Power nets are treated as signal nets (no priority fanout,
 * participate in all routing phases). Only GND gets priority fanout and delayed routing.
 */
public class HybridBatchAutorouterWrapper extends BatchAutorouter {

  public HybridBatchAutorouterWrapper(RoutingJob job) {
    super(job);
  }

  @Override
  public boolean runBatchLoop() {
    FRLogger.info("=== HybridBatchAutorouterWrapper V2 Adaptive Region Parallel Routing starting ===");

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
      FRLogger.warn("HybridBatchAutorouterWrapper V2: Board is not a RoutingBoard");
      return false;
    }
    RoutingBoard routingBoard = (RoutingBoard) this.board;
    hybridSettings.applyQualityLevel();

    // Detect layer count and set 2-layer mode
    int layerCount = routingBoard.get_layer_count();
    boolean isTwoLayerBoard = (layerCount <= 2);
    FRLogger.info("HybridBatchAutorouterWrapper V2: Board has " + layerCount + " layers"
        + (isTwoLayerBoard ? " (2-layer mode: power nets treated as signal, GND priority)" : ""));

    // Fire task started event
    this.fireTaskStateChangedEvent(new app.freerouting.autoroute.events.TaskStateChangedEvent(
        this, app.freerouting.autoroute.TaskState.STARTED, 0, this.board.get_hash()));

    // ============================================================
    // Phase 0: Power/GND Priority Fanout
    // 2-layer: only GND nets; multi-layer: power + GND
    // ============================================================
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

    if (this.settings.isFanoutEnabled() && hasSmdPins && !powerGroundNetNumbers.isEmpty()) {
      FRLogger.info("=== Phase 0: " + (isTwoLayerBoard ? "GND" : "Power/GND")
          + " priority fanout (" + powerGroundNetNumbers.size() + " nets) ===");
      long phaseStart = System.currentTimeMillis();
      try {
        BatchFanout.parallelFanoutBoard(routingBoard, this.settings, this.thread,
            null, powerGroundNetNumbers);
      } catch (Exception e) {
        FRLogger.warn("Phase 0: Fanout error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 0 complete in " + (System.currentTimeMillis() - phaseStart) + "ms");
    } else {
      FRLogger.info("Phase 0: Skipped (no power/GND nets or fanout disabled)");
    }

    // ============================================================
    // Phase 1: Adaptive Region Division
    // Density heatmap + 1D projection, excluding power/GND nets
    // ============================================================
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

    // Count active regions
    long activeRegionCount = regions.stream().filter(r -> r.isActive).count();

    // If only one active region, skip Phase 2-4 and fall through to standard cleanup
    boolean useRegionRouting = (activeRegionCount > 1);

    // ============================================================
    // Phase 2: Region-based Parallel Signal Fanout
    // ============================================================
    if (this.settings.isFanoutEnabled() && hasSmdPins && useRegionRouting) {
      FRLogger.info("=== Phase 2: Region-based signal fanout ===");
      long phaseStart2 = System.currentTimeMillis();
      try {
        // Pass empty powerGroundNetNumbers so all remaining nets are fanned out as signal
        RegionRouter.regionFanout(routingBoard, regions, this.settings, this.thread,
            new HashSet<>());
      } catch (Exception e) {
        FRLogger.warn("Phase 2: Region fanout error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 2 complete in " + (System.currentTimeMillis() - phaseStart2) + "ms");
    } else {
      FRLogger.info("Phase 2: Skipped (no SMD pins, fanout disabled, or single region)");
    }

    // Disable fanout for remaining routing phases
    this.settings.setFanoutEnabled(false);

    // ============================================================
    // Phase 3: Region-based Parallel Short Net Routing
    // ============================================================
    if (useRegionRouting) {
      FRLogger.info("=== Phase 3: Region-based short net routing ===");
      long phaseStart3 = System.currentTimeMillis();
      try {
        Set<Integer> netsToExcludeFromShortRoute = new HashSet<>();
        // For 2-layer, only exclude GND; for multi-layer, exclude power+GND
        if (labeler != null) {
          if (isTwoLayerBoard) {
            netsToExcludeFromShortRoute.addAll(labeler.getGroundNetNumbers());
          } else {
            netsToExcludeFromShortRoute.addAll(labeler.getPowerNetNumbers());
            netsToExcludeFromShortRoute.addAll(labeler.getGroundNetNumbers());
          }
        }
        RegionRouter.regionShortRoute(routingBoard, regions, this.settings, this.thread,
            5.0, netsToExcludeFromShortRoute);
      } catch (Exception e) {
        FRLogger.warn("Phase 3: Short route error (continuing): " + e.getMessage());
      }
      int afterPhase3 = calculateIncompleteCount(routingBoard);
      FRLogger.info("Phase 3 complete in " + (System.currentTimeMillis() - phaseStart3)
          + "ms, " + afterPhase3 + " incompletes remaining");
    }

    // ============================================================
    // Phase 4: Region-based Parallel Cluster Routing
    // ============================================================
    if (useRegionRouting) {
      FRLogger.info("=== Phase 4: Region-based cluster routing ===");
      long phaseStart4 = System.currentTimeMillis();
      try {
        // Phase 4a: Region-based parallel cluster routing (Pass1)
        this.setStopAtPassMinimum(4);
        this.setStagnationPassLimit(5);
        int remaining = RegionRouter.regionClusterRoute(
            routingBoard, regions, this.settings, this.thread);
        FRLogger.info("Phase 4a: " + remaining + " incompletes remaining");
      } catch (Exception e) {
        FRLogger.warn("Phase 4: Cluster route error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 4 complete in " + (System.currentTimeMillis() - phaseStart4) + "ms");
    }

    // ============================================================
    // Phase 4c: Sequential fallback cleanup (always runs)
    // ============================================================
    {
      int remaining = calculateIncompleteCount(routingBoard);
      if (remaining > 0 && !this.thread.is_stop_auto_router_requested()) {
        FRLogger.info("=== Phase 4c: Sequential fallback cleanup ("
            + remaining + " incompletes) ===");
        long phaseStart4c = System.currentTimeMillis();
        try {
          // Pre-populate power/ground skip set before standard cleanup
          populatePowerGndSkipSet(routingBoard);
          super.runBatchLoop();
        } catch (Exception e) {
          FRLogger.warn("Phase 4c: Sequential cleanup error (continuing): " + e.getMessage());
        }
        FRLogger.info("Phase 4c complete in " + (System.currentTimeMillis() - phaseStart4c) + "ms");
      } else {
        FRLogger.info("Phase 4c: Skipped (board fully routed or stopped)");
      }
    }

    // ============================================================
    // Phase 5: GND Net Delayed Routing + Acceptance
    // ============================================================
    {
      FRLogger.info("=== Phase 5: GND delayed routing ===");
      long phaseStart5 = System.currentTimeMillis();

      // Route remaining GND nets with a quick cleanup pass
      // For 2-layer: only GND; for multi-layer: power + GND
      try {
        Set<Integer> gndNetNumbers = (labeler != null)
            ? labeler.getGroundNetNumbers() : new HashSet<>();

        if (!gndNetNumbers.isEmpty()) {
          FRLogger.info("Phase 5: " + gndNetNumbers.size() + " GND nets, routing...");

          // Quick cleanup on main board (will pick up GND nets since they're last in getAutorouteItems)
          // Use a short cleanup pass with strict limits (3 passes max)
          this.setStopAtPassMinimum(2);
          this.setStagnationPassLimit(3);
          this.settings.setFanoutEnabled(false);
          super.runBatchLoop();

          // Accept GND nets with fanout-only state
          acceptFanoutOnlyForGndNets(routingBoard);
        }
      } catch (Exception e) {
        FRLogger.warn("Phase 5: GND routing error (continuing): " + e.getMessage());
      }
      FRLogger.info("Phase 5 complete in " + (System.currentTimeMillis() - phaseStart5) + "ms");
    }

    // ============================================================
    // Phase 6: Final Cleanup (last-mile + dynamic constraints)
    // ============================================================
    FRLogger.info("=== Phase 6: Final cleanup ===");
    long phaseStart6 = System.currentTimeMillis();

    // Last-mile router for stubborn connections
    int remaining = calculateIncompleteCount(routingBoard);
    if (remaining > 0 && remaining <= 3 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("Phase 6: " + remaining
          + " stubborn connections, activating last-mile router...");
      lastMileRouter(routingBoard);
    }

    // Dynamic constraint reduction
    remaining = calculateIncompleteCount(routingBoard);
    if (remaining > 0 && !this.thread.is_stop_auto_router_requested()) {
      FRLogger.info("Phase 6: " + remaining
          + " connections still remaining, trying relaxed constraints...");
      dynamicConstraintRouting(routingBoard);
    }

    if (remaining == 0) {
      FRLogger.info("Phase 6: Board fully routed!");
    }
    FRLogger.info("Phase 6 complete in " + (System.currentTimeMillis() - phaseStart6) + "ms");

    // Fire completion event
    this.fireTaskStateChangedEvent(new app.freerouting.autoroute.events.TaskStateChangedEvent(
        this, app.freerouting.autoroute.TaskState.FINISHED, 1, this.board.get_hash()));

    FRLogger.info("=== HybridBatchAutorouterWrapper V2 complete ===");
    return true;
  }

  // ===================== P1: Last-Mile Aggressive Ripup =====================

  /**
   * P1 — Last-mile aggressive ripup router.
   * <p>
   * When ≤ 3 connections remain and standard cleanup has stagnated, this method:
   * <ol>
   *   <li>Finds the bounding box (AABB) of the remaining unrouted airlines</li>
   *   <li>Removes all traces inside that box (preserving pins and vias)</li>
   *   <li>Attempts routing with higher ripup costs (3x normal)</li>
   *   <li>Tries multiple random seeds for diverse exploration</li>
   * </ol>
   */
  private void lastMileRouter(RoutingBoard routingBoard) {
    DesignRulesChecker drc = new DesignRulesChecker(routingBoard, null);
    drc.calculateAllIncompletes();
    AirLine[] airlines = drc.getAllAirlines();
    if (airlines == null || airlines.length == 0) return;

    // 1. Calculate AABB of all remaining airlines
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

    // Expand AABB by 20%
    double width = maxX - minX;
    double height = maxY - minY;
    double expandX = Math.max(width * 0.2, 500000);
    double expandY = Math.max(height * 0.2, 500000);
    minX -= expandX; maxX += expandX;
    minY -= expandY; maxY += expandY;

    FRLogger.info("lastMileRouter: AABB [" + (long) minX + "," + (long) minY + "] → ["
        + (long) maxX + "," + (long) maxY + "], expanding by "
        + (long) expandX + "x" + (long) expandY);

    // 2. Remove all Traces inside the AABB (keep pins and vias)
    int tracesRemoved = 0;
    List<Item> itemsToRemove = new ArrayList<>();
    Iterator<UndoableObjects.UndoableObjectNode> it
        = routingBoard.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob
          = routingBoard.item_list.read_object(it);
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
    FRLogger.info("lastMileRouter: Removed " + tracesRemoved + " traces inside AABB");

    if (tracesRemoved == 0) {
      FRLogger.info("lastMileRouter: No traces to remove, skipping");
      return;
    }

    // 3. Try routing with multiple random seeds and higher ripup costs
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
        FRLogger.info("lastMileRouter: deepCopy not available, routing in-place (seed=" + seed + ")");
        trialBoard = routingBoard;
        isInPlace = true;
      }
      BatchAutorouter trialRouter = new BatchAutorouter(
          this.thread, trialBoard, this.settings, true, true,
          this.settings.get_start_ripup_costs() * 3,
          this.settings.trace_pull_tight_accuracy);
      trialRouter.random = new Random(seed);
      trialRouter.setStagnationPassLimit(5);

      FRLogger.info("lastMileRouter: Trial seed=" + seed + ", inPlace=" + isInPlace + ", ripup_costs="
          + (this.settings.get_start_ripup_costs() * 3));
      trialRouter.runBatchLoop();

      float trialScore = new BoardStatistics(trialBoard)
          .getNormalizedScore(this.job.routerSettings.scoring);
      int trialIncomplete = new DesignRulesChecker(trialBoard, null).getIncompleteCount();

      FRLogger.info("lastMileRouter: Trial seed=" + seed + " score=" + trialScore
          + " incompletes=" + trialIncomplete);

      if (trialScore > bestScore) {
        bestScore = trialScore;
        bestBoard = trialBoard;
        if (isInPlace) break;
      }
      if (trialIncomplete == 0) break;
    }

    // Apply best result
    if (bestBoard != null) {
      this.board = bestBoard;
      if (this.job != null) {
        this.job.board = bestBoard;
      }
      int remainingAfter = new DesignRulesChecker((RoutingBoard) this.board, null).getIncompleteCount();
      FRLogger.info("lastMileRouter: Done, " + remainingAfter + " incompletes remaining, best score=" + bestScore);
    }
  }

  // ===================== GND: Fanout-Only Acceptance =====================

  /**
   * GND strategy: Accept fanout-only state for GND nets by pre-populating their
   * failure logs so that getAutorouteItems() skips them in future passes.
   */
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

  /**
   * Q1 — Populate the power/ground net skip set before the standard cleanup phase.
   */
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
    } else {
      FRLogger.debug("populatePowerGndSkipSet: no nets eligible for skipping.");
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

  // ===================== P4: Dynamic Constraint Reduction =====================

  /**
   * P4 — Dynamic constraint reduction for stubborn nets.
   */
  private void dynamicConstraintRouting(RoutingBoard routingBoard) {
    if (this.thread.is_stop_auto_router_requested()) return;

    int originalViaCosts = this.settings.get_via_costs();
    int originalPlaneViaCosts = this.settings.get_plane_via_costs();
    int originalRipupCosts = this.settings.get_start_ripup_costs();

    int reducedViaCosts = Math.max(1, (int) (originalViaCosts * 0.7));
    int reducedPlaneViaCosts = Math.max(1, (int) (originalPlaneViaCosts * 0.7));
    int increasedRipupCosts = originalRipupCosts * 5;

    FRLogger.info("P4 dynamic constraints: via_cost " + originalViaCosts + "→" + reducedViaCosts
        + ", ripup " + originalRipupCosts + "→" + increasedRipupCosts);

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
      } else {
        FRLogger.debug("P4 dynamic constraints: relaxed routing made things worse, keeping original");
      }
    }
  }

  // ===================== Utility =====================

  private int calculateIncompleteCount(RoutingBoard board) {
    DesignRulesChecker drc = new DesignRulesChecker(board, null);
    drc.calculateAllIncompletes();
    return drc.getIncompleteCount();
  }

  // ===================== Identity =====================

  @Override
  public String getId() {
    return "hybrid-adaptive-region-parallel-v2";
  }

  @Override
  public String getName() {
    return "Adaptive Region Parallel Router V2";
  }

  @Override
  public String getVersion() {
    return "2.0.0";
  }

  @Override
  public String getDescription() {
    return "V2 Adaptive region-based parallel routing with 2-layer optimization";
  }

  @Override
  public NamedAlgorithmType getType() {
    return NamedAlgorithmType.ROUTER;
  }
}
