package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Phase 2 — Traffic-mode-based layer assignment (交通分流).
 * <p>
 * In the city-traffic metaphor, this is the <b>urban mobility authority</b>
 * that decides whether each vehicle (net) should travel via surface streets,
 * elevated expressways, or subway tunnels — and assigns them accordingly.
 * <p>
 * <b>Three traffic modes:</b>
 * <ul>
 *   <li><b>Surface (地面)</b> — outer microstrip layers, for short local connections</li>
 *   <li><b>Elevated (高架)</b> — inner layers, for medium-distance routes</li>
 *   <li><b>Subway (地铁)</b> — deep stripline layers, for long-distance and high-speed</li>
 * </ul>
 * <p>
 * Assignment is <b>soft</b>: the layer pair's base cost coefficient is adjusted
 * rather than creating a hard lock. If a pair becomes congested, its coefficient
 * rises and traffic naturally diverts to other pairs.
 */
public class TrafficModeLayerAssigner implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Mode constants ─────────────────────────────────────────────────────

  public static final int MODE_SURFACE = 0;
  public static final int MODE_ELEVATED = 1;
  public static final int MODE_SUBWAY = 2;

  /** Capacity warning threshold (fraction of total mode capacity). */
  static final double CAPACITY_WARNING_THRESHOLD = 0.8;

  /** Capacity critical threshold (fraction of total mode capacity). */
  static final double CAPACITY_CRITICAL_THRESHOLD = 0.95;

  /** Auto-diversion enabled flag. */
  static final boolean AUTO_DIVERSION_ENABLED = true;

  /** Diversion cost multiplier when mode is near capacity. */
  static final double DIVERSION_COST_MULTIPLIER = 5.0;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final List<LayerPair> layerPairs;

  /** Net → assigned mode (MODE_SURFACE/ELEVATED/SUBWAY). */
  private final Map<Integer, Integer> netModeAssignment;

  /** Net → preferred layer pair ID. */
  private final Map<Integer, Integer> netLayerPairAssignment;

  /** Per-mode cost multiplier adjustments. */
  private final double[] modeCostMultiplier;

  /** Remaining capacity per mode. */
  private final double[] modeRemainingCapacity;

  /** Maximum capacity per mode (for % calculation). */
  private final double[] modeMaxCapacity;

  /** Per-layer utilization tracker: layer → used tracks / total available. */
  private final double[] layerUtilization;

  /** Per-layer track capacity (estimated). */
  private final double[] layerTrackCapacity;

  /** Capacity monitoring log throttle. */
  private int capacityLogCounter;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public TrafficModeLayerAssigner(BasicBoard board, List<LayerPair> layerPairs) {
    this.board = board;
    this.layerPairs = layerPairs;
    this.netModeAssignment = new HashMap<>();
    this.netLayerPairAssignment = new HashMap<>();
    this.modeCostMultiplier = new double[]{1.0, 1.0, 1.0};
    this.modeRemainingCapacity = new double[3];
    this.modeMaxCapacity = new double[3];
    this.layerUtilization = new double[board.get_layer_count()];
    this.layerTrackCapacity = new double[board.get_layer_count()];

    // Initialize capacity from layer pairs
    double trackPitch = 3000.0; // ~3um per track
    for (LayerPair lp : layerPairs) {
      int mode = gradeToMode(lp.grade);
      if (mode >= 0 && mode < 3) {
        modeRemainingCapacity[mode] += lp.maxUsableArea;
        modeMaxCapacity[mode] += lp.maxUsableArea;
      }
    }

    // Initialize per-layer track capacity (tracks per cell width)
    double boardWidth = board.bounding_box.width();
    for (int i = 0; i < board.get_layer_count(); i++) {
      if (i < board.layer_structure.arr.length
          && board.layer_structure.arr[i] != null
          && board.layer_structure.arr[i].is_signal) {
        layerTrackCapacity[i] = boardWidth / trackPitch;
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Assignment
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Assign each net to a traffic mode and layer pair.
   * Uses a greedy cost-based assignment that respects capacity limits.
   */
  public void assignAll(Set<Integer> netNumbers,
                        Map<Integer, Integer> backbonePriorityMap) {
    netModeAssignment.clear();
    netLayerPairAssignment.clear();

    // Classify nets
    List<ModeCandidate> candidates = new ArrayList<>();
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      if (net.get_pins().size() < 2) continue;

      double length = estimateNetLength(net);
      String name = net.name != null ? net.name.toUpperCase() : "";
      boolean isHighSpeed = name.contains("CLK") || name.contains("DDR")
          || name.contains("PCIE") || name.contains("SATA")
          || name.contains("MIPI") || name.contains("HDMI");
      boolean isAnalog = name.contains("ADC") || name.contains("DAC")
          || name.contains("SENSE");
      boolean isDifferential = name.endsWith("_P") || name.endsWith("_N");

      int preferredMode;
      if (isHighSpeed || isDifferential || length > 2_000_000) {
        preferredMode = MODE_SUBWAY;
      } else if (isAnalog || length > 800_000) {
        preferredMode = MODE_ELEVATED;
      } else {
        preferredMode = MODE_SURFACE;
      }

      // If backbone priority is critical, force subway
      int priority = backbonePriorityMap != null
          ? backbonePriorityMap.getOrDefault(netNo, 0) : 0;
      if (priority >= BackboneNetSelector.PRIORITY_HIGH) {
        preferredMode = MODE_SUBWAY;
      }

      candidates.add(new ModeCandidate(netNo, length, preferredMode));
    }

    // Sort: longest nets first (they benefit most from proper assignment)
    candidates.sort((a, b) -> Double.compare(b.length, a.length));

    // Greedy assignment with capacity check
    for (ModeCandidate mc : candidates) {
      int bestMode = mc.preferredMode;
      double bestCost = Double.MAX_VALUE;
      int bestPairId = -1;

      for (int attempt = 0; attempt < 3; attempt++) {
        int mode = (mc.preferredMode + attempt) % 3;

        for (LayerPair lp : layerPairs) {
          int lpMode = gradeToMode(lp.grade);
          if (lpMode != mode) continue;

          double cost = lp.assignmentCost(1, lp.primaryLayer, lp.secondaryLayer);
          cost *= modeCostMultiplier[mode];

          // Capacity check
          if (lp.remainingCapacity < mc.length * 0.1) {
            cost += 1000; // heavy penalty if near full
          }

          if (cost < bestCost) {
            bestCost = cost;
            bestMode = mode;
            bestPairId = lp.id;
          }
        }
      }

      if (bestPairId >= 0) {
        netModeAssignment.put(mc.netNo, bestMode);
        netLayerPairAssignment.put(mc.netNo, bestPairId);
        modeRemainingCapacity[bestMode] -= mc.length * 0.1;

        // Update layer pair occupancy
        for (LayerPair lp : layerPairs) {
          if (lp.id == bestPairId) {
            lp.updateRemainingCapacity(mc.length * 0.1);
            break;
          }
        }
      }
    }

    logAssignmentSummary(netNumbers.size());
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query methods
  // ═══════════════════════════════════════════════════════════════════════

  /** Get assigned mode for a net. */
  public int getMode(int netNo) {
    return netModeAssignment.getOrDefault(netNo, MODE_ELEVATED);
  }

  /** Get assigned layer pair ID for a net. */
  public int getLayerPairId(int netNo) {
    return netLayerPairAssignment.getOrDefault(netNo, -1);
  }

  /** Get the cost multiplier for a given mode (for STOM-aware routing). */
  public double getModeCostMultiplier(int mode) {
    if (mode < 0 || mode >= modeCostMultiplier.length) return 1.0;
    return modeCostMultiplier[mode];
  }

  /** Update mode cost multiplier (called after capacity change). */
  public void updateModeCostMultiplier(int mode, double newMultiplier) {
    if (mode >= 0 && mode < modeCostMultiplier.length) {
      modeCostMultiplier[mode] = newMultiplier;
    }
  }

  /** Get remaining capacity for a mode. */
  public double getModeRemainingCapacity(int mode) {
    if (mode < 0 || mode >= modeRemainingCapacity.length) return 0;
    return modeRemainingCapacity[mode];
  }

  // ═══════════════════════════════════════════════════════════════════════

  private double estimateNetLength(Net net) {
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
    }
    return (maxX - minX) + (maxY - minY);
  }

  private int gradeToMode(int grade) {
    switch (grade) {
      case LayerPair.GRADE_SURFACE:  return MODE_SURFACE;
      case LayerPair.GRADE_ELEVATED: return MODE_ELEVATED;
      case LayerPair.GRADE_SUBWAY:   return MODE_SUBWAY;
      default: return MODE_ELEVATED;
    }
  }

  private void logAssignmentSummary(int totalNets) {
    int surface = 0, elevated = 0, subway = 0;
    for (int mode : netModeAssignment.values()) {
      switch (mode) {
        case MODE_SURFACE:  surface++;  break;
        case MODE_ELEVATED: elevated++; break;
        case MODE_SUBWAY:   subway++;   break;
      }
    }
    FRLogger.info(String.format("TrafficModeAssign: %d nets → surface=%d elevated=%d subway=%d",
        totalNets, surface, elevated, subway));
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  层容量动态监控 — Dynamic layer capacity monitoring
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Update capacity utilisation based on current routing state.
   * Called periodically during Phase 2 to divert traffic from congested modes.
   */
  public void updateCapacityMonitoring() {
    if (layerPairs == null) return;

    // Reset per-layer utilisation
    Arrays.fill(layerUtilization, 0.0);

    // Recompute remaining capacity from layer pair state
    for (int mode = 0; mode < 3; mode++) {
      modeRemainingCapacity[mode] = 0;
    }

    for (LayerPair lp : layerPairs) {
      int mode = gradeToMode(lp.grade);
      if (mode >= 0 && mode < 3) {
        modeRemainingCapacity[mode] += lp.remainingCapacity;
        // Update per-layer utilisation
        for (int l : new int[]{lp.primaryLayer, lp.secondaryLayer}) {
          if (l >= 0 && l < layerUtilization.length) {
            layerUtilization[l] = 1.0 - (lp.remainingCapacity / Math.max(1.0, lp.maxUsableArea));
          }
        }
      }
    }

    // Check for capacity warnings and auto-divert
    for (int mode = 0; mode < 3; mode++) {
      double usedFraction = 1.0 - (modeRemainingCapacity[mode]
          / Math.max(1.0, modeMaxCapacity[mode]));

      if (usedFraction >= CAPACITY_CRITICAL_THRESHOLD) {
        FRLogger.warn("TrafficModeLayerAssigner: mode " + modeName(mode)
            + " CRITICAL (" + String.format("%.0f%%", usedFraction * 100)
            + " used) — forcing diversion");
        // Increase cost multiplier for this mode significantly
        modeCostMultiplier[mode] = Math.min(100.0,
            modeCostMultiplier[mode] * DIVERSION_COST_MULTIPLIER);

      } else if (usedFraction >= CAPACITY_WARNING_THRESHOLD) {
        FRLogger.info("TrafficModeLayerAssigner: mode " + modeName(mode)
            + " WARNING (" + String.format("%.0f%%", usedFraction * 100)
            + " used) — raising diversion cost");
        modeCostMultiplier[mode] = Math.min(20.0,
            modeCostMultiplier[mode] * 1.5);
      } else {
        // Gradually reduce cost multiplier if capacity is available
        modeCostMultiplier[mode] = Math.max(1.0,
            modeCostMultiplier[mode] * 0.95);
      }
    }

    // Log per-layer utilisation (at most once every 10 calls)
    if (capacityLogCounter++ % 10 == 0) {
      StringBuilder sb = new StringBuilder("Layer utilisation:");
      for (int l = 0; l < layerUtilization.length; l++) {
        if (layerTrackCapacity[l] > 0) {
          sb.append(String.format(" L%d:%.0f%%", l, layerUtilization[l] * 100));
        }
      }
      FRLogger.debug(sb.toString());
    }
  }

  /**
   * Get per-layer utilisation ratio [0..1].
   */
  public double getLayerUtilization(int layer) {
    if (layer < 0 || layer >= layerUtilization.length) return 0;
    return layerUtilization[layer];
  }

  /**
   * Get the utilisation fraction for a mode [0..1].
   */
  public double getModeUtilization(int mode) {
    if (mode < 0 || mode >= 3) return 0;
    return 1.0 - (modeRemainingCapacity[mode] / Math.max(1.0, modeMaxCapacity[mode]));
  }

  /**
   * Get the number of layers with utilisation > critical threshold.
   */
  public int getCriticalLayerCount() {
    int count = 0;
    for (int l = 0; l < layerUtilization.length; l++) {
      if (layerUtilization[l] >= CAPACITY_CRITICAL_THRESHOLD) count++;
    }
    return count;
  }

  /**
   * Reset all mode cost multipliers to baseline (after congestion clears).
   */
  public void resetCostMultipliers() {
    for (int i = 0; i < modeCostMultiplier.length; i++) {
      modeCostMultiplier[i] = 1.0;
    }
  }

  private String modeName(int mode) {
    switch (mode) {
      case MODE_SURFACE:  return "SURFACE";
      case MODE_ELEVATED: return "ELEVATED";
      case MODE_SUBWAY:   return "SUBWAY";
      default: return "UNKNOWN";
    }
  }

  // ═══════════════════════════════════════════════════════════════════════

  private static class ModeCandidate {
    final int netNo;
    final double length;
    final int preferredMode;

    ModeCandidate(int netNo, double length, int preferredMode) {
      this.netNo = netNo;
      this.length = length;
      this.preferredMode = preferredMode;
    }
  }
}
