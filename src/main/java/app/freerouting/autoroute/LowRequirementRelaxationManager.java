package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR V7 — Low-Requirement Relaxation Manager.
 * <p>
 * Identifies nets with no strict electrical/signal-integrity requirements
 * (GPIO, control signals, test points, etc.) and applies relaxed cost
 * multipliers so they can route through gaps left by higher-priority nets,
 * maximizing overall routability without compromising DRC rules.
 * <p>
 * Traffic analogy: bicycle/pedestrian paths — use leftover space between
 * main roads, no requirement for straightness, speed, or surface quality.
 */
public class LowRequirementRelaxationManager implements Serializable {

  private static final long serialVersionUID = 1L;

  private static final double SHORT_NET_THRESHOLD = 10_000; // 10mm in board units

  // ── Relaxation cost multipliers ──────────────────────────────────────
  private static final double RELAXED_VIA_MULTIPLIER = 0.3;
  private static final double RELAXED_STOM_MULTIPLIER = 0.2;
  private static final double RELAXED_CONGESTION_MULTIPLIER = 0.1;
  private static final double RELAXED_BEND_MULTIPLIER = 0.0;
  private static final double RELAXED_RIPUP_MULTIPLIER = 0.1;

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final Map<Integer, NetClass> netClassMap;

  /** Set of net numbers that are marked as low-requirement. */
  private final Set<Integer> lowRequirementNets = new HashSet<>();

  private boolean relaxationEnabled = true;

  // ── 最大空隙优先 (V7.x) ──
  private boolean maxGapFirstEnabled = false;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public LowRequirementRelaxationManager(BasicBoard board,
                                         Map<Integer, NetClass> netClassMap) {
    this.board = board;
    this.netClassMap = netClassMap != null ? netClassMap : new HashMap<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Low-requirement net identification
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Build the plan by identifying low-requirement nets and applying relaxed
   * cost multipliers. Called once during Phase 0.
   */
  public void buildPlan() {
    if (!relaxationEnabled) return;
    lowRequirementNets.clear();

    for (Map.Entry<Integer, NetClass> entry : netClassMap.entrySet()) {
      int netNo = entry.getKey();
      NetClass nc = entry.getValue();

      if (isLowRequirementNet(netNo, nc)) {
        nc.setLowRequirement(true);
        nc.applyRelaxedCosts();
        lowRequirementNets.add(netNo);
      }
    }

    FRLogger.info("LowRequirementRelaxationManager: " + lowRequirementNets.size()
        + " low-requirement nets identified");
  }

  /**
   * Determine if a net is low-requirement (eligible for relaxed routing).
   * A net must NOT be a differential pair, length-match group, clock, or analog.
   */
  private boolean isLowRequirementNet(int netNo, NetClass nc) {
    // Protected nets: never mark as low-requirement
    if (nc.isPartOfDiffPair()) return false;
    if (nc.isPartOfLengthGroup()) return false;
    if (nc.getType() == NetClass.NetType.CLOCK) return false;
    if (nc.getType() == NetClass.NetType.ANALOG) return false;
    if (nc.getType() == NetClass.NetType.DDR_DATA) return false;
    if (nc.getType() == NetClass.NetType.DDR_CMD) return false;
    if (nc.getType() == NetClass.NetType.HIGH_SPEED_SERIAL) return false;
    if (nc.getType() == NetClass.NetType.ETHERNET) return false;
    if (nc.getType() == NetClass.NetType.USB) return false;
    if (nc.getType() == NetClass.NetType.HDMI) return false;

    // Rule 1: Already marked by name analysis (GPIO, TEST, etc.)
    if (nc.isLowRequirement()) return true;

    // Rule 2: POWER/GND → low requirement (no signal integrity concerns)
    if (nc.getType() == NetClass.NetType.POWER_GND) return true;

    // Rule 3: UNKNOWN type + short length → likely auxiliary/test signal
    if (nc.getType() == NetClass.NetType.UNKNOWN) {
      double estLength = estimateNetLength(netNo);
      if (estLength >= 0 && estLength < SHORT_NET_THRESHOLD) {
        return true;
      }
    }

    // Rule 4: LOW priority (1) + NORMAL type → GPIO/control signals
    if (nc.getBackbonePriority() <= 1 && nc.getType() == NetClass.NetType.NORMAL) {
      return true;
    }

    return false;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Public API
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Apply relaxed cost multipliers to a NetClass instance.
   */
  public void applyRelaxedCosts(NetClass nc) {
    if (nc == null || nc.hasRelaxedCosts()) return;
    nc.setLowRequirement(true);
    nc.applyRelaxedCosts();
    lowRequirementNets.add(nc.getNetNo());
  }

  /**
   * Check if a net is marked as low-requirement.
   */
  public boolean isLowRequirement(int netNo) {
    return lowRequirementNets.contains(netNo);
  }

  /**
   * Get the set of all low-requirement net numbers.
   */
  public Set<Integer> getLowRequirementNets() {
    return Collections.unmodifiableSet(lowRequirementNets);
  }

  /**
   * Get the count of low-requirement nets.
   */
  public int getLowRequirementCount() {
    return lowRequirementNets.size();
  }

  // ── Accessors for cost multipliers ─────────────────────────────────────

  public double getViaMultiplier() { return RELAXED_VIA_MULTIPLIER; }

  public double getStomMultiplier() { return RELAXED_STOM_MULTIPLIER; }

  public double getCongestionMultiplier() { return RELAXED_CONGESTION_MULTIPLIER; }

  public double getBendMultiplier() { return RELAXED_BEND_MULTIPLIER; }

  public double getRipupCostMultiplier() { return RELAXED_RIPUP_MULTIPLIER; }

  // ── Settings ──────────────────────────────────────────────────────────

  public void setRelaxationEnabled(boolean enabled) { this.relaxationEnabled = enabled; }

  public boolean isRelaxationEnabled() { return relaxationEnabled; }

  /** Enable/disable max gap first guidance (V7.x). */
  public void setMaxGapFirstEnabled(boolean enabled) { this.maxGapFirstEnabled = enabled; }

  public boolean isMaxGapFirstEnabled() { return maxGapFirstEnabled; }

  /**
   * Apply max-gap guidance: find the largest free rectangle between source
   * and target, and return it to guide the A* search.
   *
   * @param obstacleGrid 2D grid: 0 = free, > 0 = obstacle/occupied
   * @param srcR    source row
   * @param srcC    source col
   * @param tgtR    target row
   * @param tgtC    target col
   * @return the gap result, or NOT_FOUND if no significant gap exists
   */
  public MaxGapFinder.GapResult applyMaxGapGuidance(int[][] obstacleGrid,
                                                     int srcR, int srcC,
                                                     int tgtR, int tgtC) {
    if (!maxGapFirstEnabled || obstacleGrid == null) {
      return MaxGapFinder.GapResult.NOT_FOUND;
    }
    return MaxGapFinder.findMaxGap(obstacleGrid, srcR, srcC, tgtR, tgtC);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private double estimateNetLength(int netNo) {
    app.freerouting.rules.Net net = board.rules.nets.get(netNo);
    if (net == null || net.get_pins().isEmpty()) return -1;
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
      minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y);
    }
    return Math.sqrt(Math.pow(maxX - minX, 2) + Math.pow(maxY - minY, 2));
  }
}
