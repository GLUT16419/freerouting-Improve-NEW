package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.LayerStructure;
import app.freerouting.board.RoutingBoard;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HybridBatchAutorouter: Implements the three-phase hybrid routing algorithm.
 *
 * Phase 0: Global congestion estimation (optional but recommended)
 * Phase 1: Short-net first routing using real A* via BatchAutorouter
 * Phase 2: Cluster-based routing with layer assignment and negotiation
 * Phase 3: SAT-like exact solving for remaining stubborn nets
 */
public class HybridBatchAutorouter implements Serializable {

  public static final String ALGORITHM_ID = "hybrid-three-phase";
  public static final String ALGORITHM_NAME = "Hybrid Three-Phase Router";
  public static final String ALGORITHM_VERSION = "2.0.0";

  private final RoutingBoard board;
  private final RouterSettings routerSettings;
  private final HybridRouterSettings hybridSettings;
  private final BatchAutorouter batchAutorouter;

  private PowerGndAutoLabeler powerGndLabeler;
  private LayerFunction[] layerFunctions;
  private CongestionMap congestionMap;
  private CostModel costModel;

  private Phase0CongestionEstimator phase0;
  private Phase1ShortRouter phase1;
  private Phase2ClusterRouter phase2;
  private Phase3SatRouter phase3;

  private final Set<Integer> unroutedNets = new HashSet<>();
  private final Set<Integer> routedNets = new HashSet<>();
  private boolean isRunning = false;
  private boolean isCancelled = false;
  private double progress = 0.0;
  private String currentPhase = "Init";

  private long startTime;
  private final Map<String, Long> phaseTimes = new LinkedHashMap<>();

  public HybridBatchAutorouter(RoutingBoard board,
                               RouterSettings routerSettings,
                               HybridRouterSettings hybridSettings,
                               BatchAutorouter batchAutorouter) {
    this.board = board;
    this.routerSettings = routerSettings;
    this.hybridSettings = hybridSettings;
    this.batchAutorouter = batchAutorouter;
  }

  public Map<Integer, String> runAllPhases() {
    isRunning = true;
    isCancelled = false;
    startTime = System.currentTimeMillis();
    Map<Integer, String> resultsMap = new HashMap<>();

    FRLogger.debug("=== HybridBatchAutorouter v" + ALGORITHM_VERSION + " starting ===");
    FRLogger.debug("Quality level: " + hybridSettings.qualityLevel);

    try {
      preProcessing();
      if (hybridSettings.phase0Enabled && !isCancelled) runPhase0();
      if (hybridSettings.phase1Enabled && !isCancelled) {
        List<Integer> phase1Routed = runPhase1();
        for (int netNo : phase1Routed) {
          resultsMap.put(netNo, "Phase1");
          routedNets.add(netNo);
        }
      }
      if (hybridSettings.phase2Enabled && !isCancelled) {
        Set<Integer> phase2Routed = runPhase2();
        for (int netNo : phase2Routed) {
          resultsMap.put(netNo, "Phase2");
          routedNets.add(netNo);
        }
      }
      if (hybridSettings.phase3Enabled && !isCancelled) {
        Set<Integer> phase3Routed = runPhase3();
        for (int netNo : phase3Routed) {
          resultsMap.put(netNo, "Phase3");
          routedNets.add(netNo);
        }
      }
    } catch (Exception e) {
      FRLogger.warn("HybridBatchAutorouter: Error during routing: " + e.getMessage());
    } finally {
      isRunning = false;
      long totalTime = System.currentTimeMillis() - startTime;
      logSummary(totalTime);
    }
    return resultsMap;
  }

  private void preProcessing() {
    currentPhase = "Pre-processing";
    if (hybridSettings.autoAssignLayerFunctions) {
      LayerStructure layerStructure = board.layer_structure;
      layerFunctions = LayerFunctionAutoAssigner.assignFunctions(layerStructure);
    }
    if (hybridSettings.autoLabelPowerGnd) {
      powerGndLabeler = new PowerGndAutoLabeler(board);
      powerGndLabeler.autoLabelAllNets();
    }
    updateProgress(0.05);
  }

  private void runPhase0() {
    currentPhase = "Phase 0: Congestion Estimation";
    long phaseStart = System.currentTimeMillis();
    phase0 = new Phase0CongestionEstimator(board);
    congestionMap = phase0.analyze();
    costModel = new CostModel(board, congestionMap);
    phaseTimes.put("Phase0", System.currentTimeMillis() - phaseStart);
    updateProgress(0.15);
  }

  private List<Integer> runPhase1() {
    currentPhase = "Phase 1: Short-Net First Routing";
    long phaseStart = System.currentTimeMillis();
    if (congestionMap == null) {
      phase0 = new Phase0CongestionEstimator(board);
      congestionMap = phase0.analyze();
    }
    if (costModel == null) {
      costModel = new CostModel(board, congestionMap);
    }
    phase1 = new Phase1ShortRouter(board, costModel, congestionMap,
        powerGndLabeler, routerSettings, batchAutorouter);
    List<Integer> phase1Routed = phase1.routeAll();
    phaseTimes.put("Phase1", System.currentTimeMillis() - phaseStart);
    updateProgress(0.40);
    return phase1Routed;
  }

  private Set<Integer> runPhase2() {
    currentPhase = "Phase 2: Cluster Routing";
    long phaseStart = System.currentTimeMillis();
    List<Integer> phase1Unrouted = (phase1 != null)
        ? phase1.getUnroutedNetNumbers()
        : collectAllNetNumbers();
    phase2 = new Phase2ClusterRouter(board, congestionMap, costModel,
        powerGndLabeler, layerFunctions, batchAutorouter);
    Set<Integer> phase2Routed = phase2.routeRemaining(phase1Unrouted);
    phaseTimes.put("Phase2", System.currentTimeMillis() - phaseStart);
    updateProgress(0.75);
    return phase2Routed;
  }

  private Set<Integer> runPhase3() {
    currentPhase = "Phase 3: SAT Exact Solving";
    long phaseStart = System.currentTimeMillis();
    Set<Integer> remaining = (phase2 != null)
        ? new HashSet<>(phase2.getUnroutedNets())
        : new HashSet<>();
    // If Phase 2 wasn't run, add all unrouted nets
    if (remaining.isEmpty() && phase1 != null) {
      remaining.addAll(phase1.getUnroutedNetNumbers());
    }
    phase3 = new Phase3SatRouter(board, congestionMap, costModel, batchAutorouter);
    Set<Integer> phase3Routed = phase3.routeRemaining(remaining);
    phaseTimes.put("Phase3", System.currentTimeMillis() - phaseStart);
    updateProgress(1.0);
    return phase3Routed;
  }

  private List<Integer> collectAllNetNumbers() {
    List<Integer> all = new ArrayList<>();
    if (board.rules != null && board.rules.nets != null) {
      int netCount = board.rules.nets.max_net_no();
      for (int i = 1; i <= netCount; i++) {
        Net net = board.rules.nets.get(i);
        if (net != null && !routedNets.contains(net.net_number)) all.add(net.net_number);
      }
    }
    return all;
  }

  private void updateProgress(double fraction) { progress = fraction; }

  private void logSummary(long totalTimeMs) {
    StringBuilder sb = new StringBuilder("\n=== HybridBatchAutorouter Summary ===\n");
    sb.append("Total time: ").append(totalTimeMs).append("ms\n");
    for (Map.Entry<String, Long> entry : phaseTimes.entrySet()) {
      sb.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("ms\n");
    }
    int total = 0;
    if (board.rules != null && board.rules.nets != null) {
      int netCount = board.rules.nets.max_net_no();
      for (int i = 1; i <= netCount; i++) {
        if (board.rules.nets.get(i) != null) total++;
      }
    }
    sb.append("Routed: ").append(routedNets.size()).append("/").append(total).append(" nets\n");
    sb.append("Unrouted: ").append(total - routedNets.size()).append(" nets\n");
    FRLogger.debug(sb.toString());
  }

  public void cancel() { this.isCancelled = true; }
  public boolean isRunning() { return isRunning; }
  public boolean isCancelled() { return isCancelled; }
  public double getProgress() { return progress; }
  public String getCurrentPhase() { return currentPhase; }
  public Set<Integer> getRoutedNets() { return routedNets; }
  public PowerGndAutoLabeler getPowerGndLabeler() { return powerGndLabeler; }
  public LayerFunction[] getLayerFunctions() { return layerFunctions; }
  public CongestionMap getCongestionMap() { return congestionMap; }
  public CostModel getCostModel() { return costModel; }
  public Phase0CongestionEstimator getPhase0() { return phase0; }
  public Phase1ShortRouter getPhase1() { return phase1; }
  public Phase2ClusterRouter getPhase2() { return phase2; }
  public Phase3SatRouter getPhase3() { return phase3; }
}
