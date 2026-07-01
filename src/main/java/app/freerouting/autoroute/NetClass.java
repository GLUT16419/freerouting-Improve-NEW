package app.freerouting.autoroute;

import java.io.Serializable;

/**
 * UTPR V7 — Network Classification Metadata Model.
 * <p>
 * Holds the automatically detected type, confidence, grouping info,
 * degradation state, and relaxation multipliers for a single net.
 * Produced by {@link AutomaticNetworkAnalyzer} and consumed by
 * the rest of the UTPR pipeline.
 */
public class NetClass implements Serializable {

  private static final long serialVersionUID = 1L;

  // ═══════════════════════════════════════════════════════════════════════
  //  Enums
  // ═══════════════════════════════════════════════════════════════════════

  /** Extended net type classification from AutomaticNetworkAnalyzer. */
  public enum NetType {
    DIFF_PAIR,
    DIFF_PAIR_WEAK,
    CLOCK,
    DDR_DATA,
    DDR_CMD,
    ANALOG,
    POWER_GND,
    HIGH_SPEED_SERIAL,
    BUS_GROUPED,
    I2C_SPI,
    ETHERNET,
    USB,
    HDMI,
    NORMAL,
    UNKNOWN
  }

  /** Degradation level for differential pairs / length-match groups. */
  public enum DegradationLevel {
    /** Strict coupling (spacing=1×, via sync, matched length, same layer). */
    LEVEL_0,
    /** Relaxed coupling (spacing=2×, ±1 via diff, ±15 % tolerance). */
    LEVEL_1,
    /** Weak coupling (spacing=3×, independent vias, ±30 % tolerance). */
    LEVEL_2,
    /** Released — route as independent single-ended nets, no group constraints. */
    LEVEL_3,
    /** Abandoned — marked unroutable, left for manual routing. */
    LEVEL_4
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  /** The net number this metadata belongs to. */
  private final int netNo;

  /** Identified net type (from automatic analysis). */
  private NetType type;

  /** Confidence of the automatic classification [0, 1]. */
  private double confidence;

  /** Differential pair ID, or -1 if not applicable. */
  private int diffPairId = -1;

  /** Length-match group ID, or -1 if not applicable. */
  private int lengthGroupId = -1;

  /** Index within a bus group (e.g. 0 for DATA0). */
  private int groupIndex = -1;

  /** Bus prefix name (e.g. "DATA", "ADDR"). */
  private String groupPrefix;

  /** Backbone priority assigned automatically (0=POWER_GND … 4=CRITICAL). */
  private int backbonePriority;

  /** Preferred layer grade (0=surface, 1=elevated, 2=subway). */
  private int preferredLayerGrade;

  /** Whether this is a critical signal. */
  private boolean isCritical;

  /** Current degradation level for this net/group. */
  private DegradationLevel degradationLevel = DegradationLevel.LEVEL_0;

  // ── Low-requirement relaxation fields ──────────────────────────────────

  /** Whether this net is marked as low-requirement (relaxed routing). */
  private boolean isLowRequirement = false;

  /** Whether relaxed cost multipliers have been loaded. */
  private boolean hasRelaxedCosts = false;

  /** Via cost multiplier (relaxed = 0.3). */
  private double viaCostMultiplier = 1.0;

  /** STOM penalty multiplier (relaxed = 0.2). */
  private double stomMultiplier = 1.0;

  /** Congestion penalty multiplier (relaxed = 0.1). */
  private double congestionMultiplier = 1.0;

  /** Bend turn penalty multiplier (relaxed = 0.0). */
  private double bendMultiplier = 1.0;

  /** Ripup cost multiplier (relaxed = 0.1). */
  private double ripupCostMultiplier = 1.0;

  /** Estimated net length in board units. */
  private double estimatedLength;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public NetClass(int netNo) {
    this.netNo = netNo;
    this.type = NetType.UNKNOWN;
    this.confidence = 0.0;
    this.backbonePriority = 1; // LOW default
    this.preferredLayerGrade = 0;
    this.isCritical = false;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Getters & setters
  // ═══════════════════════════════════════════════════════════════════════

  public int getNetNo() { return netNo; }

  public NetType getType() { return type; }
  public void setType(NetType type) { this.type = type; }

  public double getConfidence() { return confidence; }
  public void setConfidence(double confidence) { this.confidence = confidence; }

  public int getDiffPairId() { return diffPairId; }
  public void setDiffPairId(int diffPairId) { this.diffPairId = diffPairId; }

  public boolean isPartOfDiffPair() { return diffPairId >= 0; }

  public int getLengthGroupId() { return lengthGroupId; }
  public void setLengthGroupId(int lengthGroupId) { this.lengthGroupId = lengthGroupId; }

  public boolean isPartOfLengthGroup() { return lengthGroupId >= 0; }

  public int getGroupIndex() { return groupIndex; }
  public void setGroupIndex(int groupIndex) { this.groupIndex = groupIndex; }

  public String getGroupPrefix() { return groupPrefix; }
  public void setGroupPrefix(String groupPrefix) { this.groupPrefix = groupPrefix; }

  public int getBackbonePriority() { return backbonePriority; }
  public void setBackbonePriority(int backbonePriority) { this.backbonePriority = backbonePriority; }

  public int getPreferredLayerGrade() { return preferredLayerGrade; }
  public void setPreferredLayerGrade(int preferredLayerGrade) { this.preferredLayerGrade = preferredLayerGrade; }

  public boolean isCritical() { return isCritical; }
  public void setCritical(boolean critical) { isCritical = critical; }

  public DegradationLevel getDegradationLevel() { return degradationLevel; }
  public void setDegradationLevel(DegradationLevel degradationLevel) { this.degradationLevel = degradationLevel; }

  // ── Low-requirement getters/setters ─────────────────────────────────────

  public boolean isLowRequirement() { return isLowRequirement; }
  public void setLowRequirement(boolean lowRequirement) { isLowRequirement = lowRequirement; }

  public boolean hasRelaxedCosts() { return hasRelaxedCosts; }
  public void setHasRelaxedCosts(boolean hasRelaxedCosts) { this.hasRelaxedCosts = hasRelaxedCosts; }

  public double getViaCostMultiplier() { return viaCostMultiplier; }
  public void setViaCostMultiplier(double viaCostMultiplier) { this.viaCostMultiplier = viaCostMultiplier; }

  public double getStomMultiplier() { return stomMultiplier; }
  public void setStomMultiplier(double stomMultiplier) { this.stomMultiplier = stomMultiplier; }

  public double getCongestionMultiplier() { return congestionMultiplier; }
  public void setCongestionMultiplier(double congestionMultiplier) { this.congestionMultiplier = congestionMultiplier; }

  public double getBendMultiplier() { return bendMultiplier; }
  public void setBendMultiplier(double bendMultiplier) { this.bendMultiplier = bendMultiplier; }

  public double getRipupCostMultiplier() { return ripupCostMultiplier; }
  public void setRipupCostMultiplier(double ripupCostMultiplier) { this.ripupCostMultiplier = ripupCostMultiplier; }

  public double getEstimatedLength() { return estimatedLength; }
  public void setEstimatedLength(double estimatedLength) { this.estimatedLength = estimatedLength; }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Apply low-requirement relaxation cost multipliers to this net class. */
  public void applyRelaxedCosts() {
    this.viaCostMultiplier = 0.3;
    this.stomMultiplier = 0.2;
    this.congestionMultiplier = 0.1;
    this.bendMultiplier = 0.0;
    this.ripupCostMultiplier = 0.1;
    this.hasRelaxedCosts = true;
  }

  @Override
  public String toString() {
    return "NetClass{net=" + netNo + ", type=" + type
        + ", conf=" + String.format("%.2f", confidence)
        + ", dp=" + diffPairId + ", lg=" + lengthGroupId
        + ", prio=" + backbonePriority + ", deg=" + degradationLevel
        + ", lowReq=" + isLowRequirement + "}";
  }
}
