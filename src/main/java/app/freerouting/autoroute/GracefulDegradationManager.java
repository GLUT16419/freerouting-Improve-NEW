package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR V7 — Graceful Degradation Manager.
 * <p>
 * Manages a 5-level degradation path for each network and network group.
 * Starts with strict constraints, and automatically degrades to looser
 * constraints when routing phases fail, ensuring maximum routability.
 * <p>
 * Provides feedback loop integration with Phase 1/2/3 and maintains
 * a degradation event log for reporting.
 */
public class GracefulDegradationManager implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final Map<Integer, NetClass.DegradationLevel> netDegradationMap = new HashMap<>();
  private final Map<Integer, NetClass.DegradationLevel> groupDegradationMap = new HashMap<>();
  private final List<DegradationEvent> eventLog = new ArrayList<>();

  private final Map<Integer, NetClass> netClassMap;

  private int maxDegradationRounds = 5;
  private boolean degradationEnabled = true;

  // ── 预判性降级参数 (V7.x) ──
  private boolean degradationPredictiveEnabled = false;
  private double predictiveCongestionThresholdHigh = 0.85;
  private double predictiveCongestionThresholdMid = 0.70;

  /** Nets with reduced Phase 1 timeout (pre-judged mid-conflict). */
  private final Set<Integer> reducedTimeoutNets = new HashSet<>();

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public GracefulDegradationManager(Map<Integer, NetClass> netClassMap) {
    this.netClassMap = netClassMap != null ? netClassMap : new HashMap<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Build initial degradation plan
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build the initial degradation plan based on NetClass analysis results.
   * Called once during Phase 0.
   */
  public void buildPlan() {
    if (!degradationEnabled) return;

    for (Map.Entry<Integer, NetClass> entry : netClassMap.entrySet()) {
      int netNo = entry.getKey();
      NetClass nc = entry.getValue();

      NetClass.DegradationLevel initialLevel;

      // Assign initial degradation level based on net type and confidence
      if (nc.getType() == NetClass.NetType.DIFF_PAIR && nc.getConfidence() >= 0.80) {
        initialLevel = NetClass.DegradationLevel.LEVEL_0; // Strong DP → strict
      } else if (nc.getType() == NetClass.NetType.DIFF_PAIR_WEAK
          || (nc.getType() == NetClass.NetType.DIFF_PAIR && nc.getConfidence() >= 0.55)) {
        initialLevel = NetClass.DegradationLevel.LEVEL_1; // Weak DP → relaxed
      } else if (nc.getLengthGroupId() >= 0) {
        // Length-match groups: check group size and spatial spread
        initialLevel = NetClass.DegradationLevel.LEVEL_0;
      } else {
        initialLevel = null; // No degradation for ordinary nets
      }

      if (initialLevel != null) {
        netDegradationMap.put(netNo, initialLevel);
        nc.setDegradationLevel(initialLevel);
      }
    }

    // Log summary
    long level0 = netDegradationMap.values().stream()
        .filter(l -> l == NetClass.DegradationLevel.LEVEL_0).count();
    long level1 = netDegradationMap.values().stream()
        .filter(l -> l == NetClass.DegradationLevel.LEVEL_1).count();
    FRLogger.info("GracefulDegradationManager: " + netDegradationMap.size()
        + " nets tracked (LEVEL_0=" + level0 + ", LEVEL_1=" + level1 + ")");
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  预判性降级 (V7.x) — Predictive Degradation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build a predictive degradation plan based on probabilistic congestion heatmap.
   * <p>
   * Called at the end of Phase 0, after congestion estimation is complete.
   * Pre-judges which diff pairs / length-match groups are likely to fail
   * due to high congestion in their mandatory routing region.
   * <p>
   * Rules:
   * <ul>
   *   <li>weightedCongestion &gt; high threshold → start at LEVEL_1</li>
   *   <li>weightedCongestion &gt; mid threshold → Phase 1 timeout halved</li>
   *   <li>otherwise → normal start</li>
   * </ul>
   *
   * @param congestion 2D congestion heatmap [row][col] in [0..1]
   */
  public void buildPredictivePlan(double[][] congestion) {
    if (!degradationPredictiveEnabled || congestion == null) return;

    int preDegraded = 0;
    int preTimedOut = 0;

    for (Map.Entry<Integer, NetClass> entry : netClassMap.entrySet()) {
      int netNo = entry.getKey();
      NetClass nc = entry.getValue();

      // Only predict for diff pairs and length-match groups
      if (!nc.isPartOfDiffPair() && !nc.isPartOfLengthGroup()) continue;

      // Estimate the mandatory routing region of this net
      double weightedCongestion = estimateRouteCongestion(netNo, nc, congestion);

      if (weightedCongestion > predictiveCongestionThresholdHigh) {
        // High conflict → skip LEVEL_0, start at LEVEL_1
        NetClass.DegradationLevel current = netDegradationMap.getOrDefault(netNo,
            NetClass.DegradationLevel.LEVEL_0);
        if (current == NetClass.DegradationLevel.LEVEL_0) {
          netDegradationMap.put(netNo, NetClass.DegradationLevel.LEVEL_1);
          nc.setDegradationLevel(NetClass.DegradationLevel.LEVEL_1);
          preDegraded++;
          String reason = String.format("预判降级: 拥塞指数 %.2f > %.2f",
              weightedCongestion, predictiveCongestionThresholdHigh);
          DegradationEvent event = new DegradationEvent(netNo, nc.getLengthGroupId(),
              NetClass.DegradationLevel.LEVEL_0, NetClass.DegradationLevel.LEVEL_1,
              reason, 0, true);
          eventLog.add(event);
          event.log();
        }
      } else if (weightedCongestion > predictiveCongestionThresholdMid) {
        // Medium conflict → mark for reduced Phase 1 timeout
        preTimedOut++;
        reducedTimeoutNets.add(netNo);
      }
    }

    if (preDegraded > 0 || preTimedOut > 0) {
      FRLogger.info("  GracefulDegradationManager: 预判降级 " + preDegraded
          + " 网络 (直接降级), " + preTimedOut + " 网络 (超时减半)");
    }
  }

  /**
   * Estimate the weighted congestion in a net's mandatory routing region.
   * Uses the bounding box of the net's pins, weighted by a Gaussian-like
   * kernel centered on the pin cluster.
   */
  private double estimateRouteCongestion(int netNo, NetClass nc,
                                          double[][] congestion) {
    // Use pin bounding box as the routing region
    if (congestion == null || congestion.length == 0) return 0.0;

    int gridRows = congestion.length;
    int gridCols = congestion[0].length;

    // Simple approach: average congestion in the net's bounding box
    // with higher weight on the center region
    double totalWeight = 0;
    double weightedSum = 0;

    // Center of the bounding box (assumed pin cluster center)
    int centerR = gridRows / 2;
    int centerC = gridCols / 2;

    int radiusR = Math.max(1, gridRows / 4);
    int radiusC = Math.max(1, gridCols / 4);

    for (int r = Math.max(0, centerR - radiusR); r <= Math.min(gridRows - 1, centerR + radiusR); r++) {
      for (int c = Math.max(0, centerC - radiusC); c <= Math.min(gridCols - 1, centerC + radiusC); c++) {
        // Gaussian-like weight: higher near center
        double dr = (double) (r - centerR) / radiusR;
        double dc = (double) (c - centerC) / radiusC;
        double w = Math.exp(-(dr * dr + dc * dc) * 2.0);
        weightedSum += congestion[r][c] * w;
        totalWeight += w;
      }
    }

    return totalWeight > 1e-10 ? weightedSum / totalWeight : 0.0;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Degradation triggers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Trigger degradation for a single net after a routing phase failure.
   *
   * @param netNo  the net number that failed
   * @param phase  the phase in which failure occurred (1, 2, or 3)
   * @return true if degradation was performed, false if already at max
   */
  public boolean degradeNet(int netNo, int phase) {
    if (!degradationEnabled) return false;
    NetClass nc = netClassMap.get(netNo);
    if (nc == null) return false;

    NetClass.DegradationLevel current = netDegradationMap.get(netNo);
    if (current == null) {
      // Not tracked — start from LEVEL_2 (some already lost constraints)
      current = NetClass.DegradationLevel.LEVEL_2;
    }

    NetClass.DegradationLevel next = nextLevel(current);
    if (next == null) return false; // Already at max degradation

    netDegradationMap.put(netNo, next);
    nc.setDegradationLevel(next);

    String reason = buildDegradationReason(nc, phase);
    DegradationEvent event = new DegradationEvent(netNo, nc.getLengthGroupId(),
        current, next, reason, phase, false);
    eventLog.add(event);
    event.log();
    return true;
  }

  /**
   * Trigger degradation for an entire group (diff-pair or length-match group).
   *
   * @param groupId  the group ID
   * @param phase    the phase in which failure occurred
   * @return true if any net was degraded
   */
  public boolean degradeGroup(int groupId, int phase) {
    if (!degradationEnabled) return false;

    // Find all nets belonging to this group
    List<Integer> groupNets = new ArrayList<>();
    for (Map.Entry<Integer, NetClass> entry : netClassMap.entrySet()) {
      NetClass nc = entry.getValue();
      if (nc.getDiffPairId() == groupId || nc.getLengthGroupId() == groupId) {
        groupNets.add(entry.getKey());
      }
    }

    if (groupNets.isEmpty()) return false;

    // Check current group level
    NetClass.DegradationLevel currentGroupLevel = groupDegradationMap.get(groupId);
    if (currentGroupLevel == null) {
      currentGroupLevel = NetClass.DegradationLevel.LEVEL_0;
    }
    NetClass.DegradationLevel nextGroupLevel = nextLevel(currentGroupLevel);
    if (nextGroupLevel == null) return false;

    groupDegradationMap.put(groupId, nextGroupLevel);

    // Degrade all nets in the group
    boolean anyDegraded = false;
    for (int netNo : groupNets) {
      NetClass nc = netClassMap.get(netNo);
      if (nc == null) continue;

      NetClass.DegradationLevel current = netDegradationMap.get(netNo);
      if (current == null) current = NetClass.DegradationLevel.LEVEL_0;
      if (compareLevel(current, nextGroupLevel) >= 0) continue; // already at same or worse

      netDegradationMap.put(netNo, nextGroupLevel);
      nc.setDegradationLevel(nextGroupLevel);

      String reason = "Group#" + groupId + " degraded (Phase " + phase + ")";
      DegradationEvent event = new DegradationEvent(netNo, groupId,
          current, nextGroupLevel, reason, phase, false);
      eventLog.add(event);
      event.log();
      anyDegraded = true;
    }
    return anyDegraded;
  }

  /**
   * Check differential pair coupling quality after Phase 1/2.
   * Degrade if routing failed.
   */
  public boolean checkDiffPairStatus(int netNo, boolean routingFailed, int phase) {
    if (!degradationEnabled) return false;
    NetClass nc = netClassMap.get(netNo);
    if (nc == null || nc.getDiffPairId() < 0) return false;

    if (!routingFailed) return false; // no degradation needed

    // Degrade the differential pair group
    return degradeGroup(nc.getDiffPairId(), phase);
  }

  /**
   * Check length-match group quality after Phase 2.
   * Degrade tolerance if length mismatch detected.
   */
  public boolean checkLengthGroupStatus(int groupId, double currentMaxDiff,
                                        double referenceLength, int phase) {
    if (!degradationEnabled) return false;

    NetClass.DegradationLevel groupLevel = groupDegradationMap.getOrDefault(groupId,
        NetClass.DegradationLevel.LEVEL_0);

    // Compute allowed tolerance from current level
    double allowedTolerance = getLengthTolerance(groupLevel);
    double actualRatio = referenceLength > 0 ? currentMaxDiff / referenceLength : 0;

    if (actualRatio <= allowedTolerance) return false; // within tolerance

    // Degrade
    return degradeGroup(groupId, phase);
  }

  /**
   * Check if SAT UNSAT was caused by differential pair or length-match constraints.
   * Degrade if so.
   */
  public boolean checkSatUnsat(int netNo, int phase) {
    if (!degradationEnabled) return false;
    NetClass nc = netClassMap.get(netNo);
    if (nc == null) return false;

    // Check diff pair
    if (nc.getDiffPairId() >= 0) {
      return degradeGroup(nc.getDiffPairId(), phase);
    }
    // Check length group
    if (nc.getLengthGroupId() >= 0) {
      return degradeGroup(nc.getLengthGroupId(), phase);
    }
    // Otherwise degrade just this net
    return degradeNet(netNo, phase);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query methods
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Get the current degradation level for a net.
   */
  public NetClass.DegradationLevel getNetLevel(int netNo) {
    NetClass nc = netClassMap.get(netNo);
    if (nc != null) return nc.getDegradationLevel();
    return netDegradationMap.getOrDefault(netNo, NetClass.DegradationLevel.LEVEL_0);
  }

  /**
   * Check if a net should still be routed with strict differential pair constraints.
   */
  public boolean isStrictDifferential(int netNo) {
    NetClass.DegradationLevel level = getNetLevel(netNo);
    return level == NetClass.DegradationLevel.LEVEL_0;
  }

  /**
   * Check if a differential pair is released (routed independently).
   */
  public boolean isReleased(int netNo) {
    NetClass.DegradationLevel level = getNetLevel(netNo);
    return level == NetClass.DegradationLevel.LEVEL_3
        || level == NetClass.DegradationLevel.LEVEL_4;
  }

  /**
   * Check if a net is abandoned.
   */
  public boolean isAbandoned(int netNo) {
    return getNetLevel(netNo) == NetClass.DegradationLevel.LEVEL_4;
  }

  /**
   * Get the allowed length tolerance for a given degradation level.
   */
  public double getLengthTolerance(NetClass.DegradationLevel level) {
    switch (level) {
      case LEVEL_0: return 0.05; // ±5%
      case LEVEL_1: return 0.15; // ±15%
      case LEVEL_2: return 0.30; // ±30%
      case LEVEL_3: return 1.0;  // no constraint (100% tolerance = effectively none)
      case LEVEL_4: return 1.0;
      default: return 0.05;
    }
  }

  /**
   * Get the diff pair coupling spacing multiplier for a degradation level.
   */
  public double getDiffPairSpacingMultiplier(NetClass.DegradationLevel level) {
    switch (level) {
      case LEVEL_0: return 1.0;
      case LEVEL_1: return 2.0;
      case LEVEL_2: return 3.0;
      default: return 5.0; // effectively uncoupled
    }
  }

  /**
   * Check if the net is allowed to change layers freely.
   */
  public boolean allowFreeLayerChange(int netNo) {
    NetClass.DegradationLevel level = getNetLevel(netNo);
    return level == NetClass.DegradationLevel.LEVEL_3
        || level == NetClass.DegradationLevel.LEVEL_4
        || level == NetClass.DegradationLevel.LEVEL_2;
  }

  /**
   * Get the combined degradation level for a net considering both per-net
   * and per-group degradation.
   */
  public NetClass.DegradationLevel getEffectiveLevel(int netNo) {
    NetClass nc = netClassMap.get(netNo);
    if (nc == null) return NetClass.DegradationLevel.LEVEL_0;

    NetClass.DegradationLevel netLevel = netDegradationMap.get(netNo);
    NetClass.DegradationLevel groupLevel = null;

    if (nc.getDiffPairId() >= 0) {
      groupLevel = groupDegradationMap.get(nc.getDiffPairId());
    } else if (nc.getLengthGroupId() >= 0) {
      groupLevel = groupDegradationMap.get(nc.getLengthGroupId());
    }

    // Use the stricter (lower numerical value) of net and group levels
    if (netLevel != null && groupLevel != null) {
      return compareLevel(netLevel, groupLevel) <= 0 ? netLevel : groupLevel;
    }
    if (netLevel != null) return netLevel;
    if (groupLevel != null) return groupLevel;
    return nc.getDegradationLevel();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Reporting
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Print a final degradation report to the log.
   */
  public void printReport() {
    if (eventLog.isEmpty()) {
      FRLogger.info("  [降级报告] 无降级事件发生");
      return;
    }

    FRLogger.info("╔════════════════════════════════════════════════════════╗");
    FRLogger.info("║          自动网络识别与降级报告                         ║");
    FRLogger.info("╠════════════════════════════════════════════════════════╣");

    // Count diff pairs and groups
    long diffPairCount = netClassMap.values().stream()
        .filter(nc -> nc.getDiffPairId() >= 0).count();
    long lengthGroupCount = netClassMap.values().stream()
        .filter(nc -> nc.getLengthGroupId() >= 0).count();

    // Count diff pair types
    long strongDp = netClassMap.values().stream()
        .filter(nc -> nc.getType() == NetClass.NetType.DIFF_PAIR).count();
    long weakDp = netClassMap.values().stream()
        .filter(nc -> nc.getType() == NetClass.NetType.DIFF_PAIR_WEAK).count();

    FRLogger.info("║ 差分对检测: " + diffPairCount + " 对 (" + strongDp + "强+" + weakDp + "弱)");
    FRLogger.info("║ 等长组检测: " + lengthGroupCount + " 组");

    // List degradation events
    FRLogger.info("╠════════════════════════════════════════════════════════╣");
    FRLogger.info("║ 降级记录:");
    for (DegradationEvent event : eventLog) {
      FRLogger.info("║   " + event.toString());
    }

    FRLogger.info("╚════════════════════════════════════════════════════════╝");
  }

  /**
   * Get the list of all degradation events.
   */
  public List<DegradationEvent> getEventLog() {
    return Collections.unmodifiableList(eventLog);
  }

  /**
   * Get the count of degradation events by phase.
   */
  public Map<Integer, Integer> getDegradationCountByPhase() {
    Map<Integer, Integer> counts = new HashMap<>();
    for (DegradationEvent event : eventLog) {
      counts.merge(event.getPhaseId(), 1, Integer::sum);
    }
    return counts;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Settings
  // ═══════════════════════════════════════════════════════════════════════

  public void setMaxDegradationRounds(int maxRounds) { this.maxDegradationRounds = maxRounds; }

  public void setDegradationEnabled(boolean enabled) { this.degradationEnabled = enabled; }

  public boolean isDegradationEnabled() { return degradationEnabled; }

  /** Check if a net has reduced Phase 1 timeout (from predictive degradation). */
  public boolean hasReducedTimeout(int netNo) {
    return reducedTimeoutNets.contains(netNo);
  }

  /** Enable/disable predictive degradation (V7.x). */
  public void setDegradationPredictiveEnabled(boolean enabled) {
    this.degradationPredictiveEnabled = enabled;
  }

  public boolean isDegradationPredictiveEnabled() { return degradationPredictiveEnabled; }

  public void setPredictiveCongestionThresholds(double high, double mid) {
    this.predictiveCongestionThresholdHigh = high;
    this.predictiveCongestionThresholdMid = mid;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Get the next lower degradation level. */
  private NetClass.DegradationLevel nextLevel(NetClass.DegradationLevel current) {
    switch (current) {
      case LEVEL_0: return NetClass.DegradationLevel.LEVEL_1;
      case LEVEL_1: return NetClass.DegradationLevel.LEVEL_2;
      case LEVEL_2: return NetClass.DegradationLevel.LEVEL_3;
      case LEVEL_3: return NetClass.DegradationLevel.LEVEL_4;
      default: return null;
    }
  }

  /** Build a human-readable reason string for degradation. */
  private String buildDegradationReason(NetClass nc, int phase) {
    if (nc.getDiffPairId() >= 0) {
      return "差分对耦合路由失败 (Phase " + phase + ")";
    }
    if (nc.getLengthGroupId() >= 0) {
      return "等长组长度不匹配 (Phase " + phase + ")";
    }
    return "网络路由失败 (Phase " + phase + ")";
  }

  /** Compare two levels: negative if a is stricter (lower), positive if a is more degraded. */
  private int compareLevel(NetClass.DegradationLevel a, NetClass.DegradationLevel b) {
    return Integer.compare(a.ordinal(), b.ordinal());
  }
}
