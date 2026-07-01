package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * UTPR Phase 1 — Backbone network selector/router for critical signal
 * identification and priority ordering.
 * <p>
 * In the city-traffic metaphor, this is the <b>urban expressway planning
 * authority</b> that decides which traffic arteries must be built first:
 * highways (DDR/PCIe), bus routes (clock trees), and emergency vehicle
 * lanes (analog/IMSAFE signals).
 * <p>
 * <b>Priority ordering:</b>
 * <ol>
 *   <li>Constraint strictness (differential pair, matched-length, impedance)
 *       determines the primary sorting key.</li>
 *   <li>Total wire length (longer nets first — harder to fit).</li>
 *   <li>Channel criticality (must pass through narrow bottlenecks).</li>
 * </ol>
 * <p>
 * <b>Matched-length group coordination:</b>
 * <ol>
 *   <li>Detect groups by name pattern (e.g. common prefix + suffix
 *       like "_P"/"_N", or explicit "GROUP"/"EQ" markers).</li>
 *   <li>Within each group, select a <i>reference net</i> (longest or
 *       highest priority) to set the length target.</li>
 *   <li>For other group members, compute a <i>deviation cost</i>
 *       proportional to their length deficit from the reference.</li>
 *   <li>Route the reference net first (normal priority), then other
 *       group members with length-matching guidance.</li>
 * </ol>
 */
public class BackboneNetSelector implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Priority levels ────────────────────────────────────────────────────

  /** Highest — differential pairs & matched-length groups. */
  public static final int PRIORITY_CRITICAL = 4;

  /** High — clocks, DDR buses, high-speed serial. */
  public static final int PRIORITY_HIGH = 3;

  /** Medium — regular buses, analog signals. */
  public static final int PRIORITY_MEDIUM = 2;

  /** Low — general digital signals. */
  public static final int PRIORITY_LOW = 1;

  /** Lowest — power/ground after fanout. */
  public static final int PRIORITY_POWER_GND = 0;

  // ── Matched-length group parameters ────────────────────────────────────

  /** Length mismatch tolerance (fraction of reference length). */
  static final double LENGTH_MISMATCH_TOLERANCE = 0.05;

  /** Deviation cost per unit of length deficit. */
  static final double DEVIATION_COST_PER_UNIT = 0.001;

  // ═══════════════════════════════════════════════════════════════════════
  //  BackboneNet
  // ═══════════════════════════════════════════════════════════════════════

  /** Backbone net with priority and routing metadata. */
  public static class BackboneNet implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int netNo;
    public final int priority;
    public final double estimatedLength;
    public final double channelScore;
    public final boolean isDifferential;
    public boolean isLengthMatched;
    public final boolean isHighSpeed;
    public final String netName;

    /** Time step assigned after routing (set by Phase 1). */
    public int reservedTimeStep = -1;

    /** Whether this net has been successfully routed. */
    public boolean routed;

    /** Matched-length group ID (-1 if not in any group). */
    public int matchedGroupId = -1;

    /** Length deficit from the group reference net (0 for the reference). */
    public double lengthDeficit = 0;

    /** Deviation cost for length matching (added to route cost). */
    public double deviationCost = 0;

    /** Whether this net is the reference net of its matched-length group. */
    public boolean isGroupReference = false;

    public BackboneNet(int netNo, int priority, double estimatedLength,
                       double channelScore, boolean isDifferential,
                       boolean isLengthMatched, boolean isHighSpeed,
                       String netName) {
      this.netNo = netNo;
      this.priority = priority;
      this.estimatedLength = estimatedLength;
      this.channelScore = channelScore;
      this.isDifferential = isDifferential;
      this.isLengthMatched = isLengthMatched;
      this.isHighSpeed = isHighSpeed;
      this.netName = netName;
      this.routed = false;
    }

    @Override
    public String toString() {
      return String.format("BackboneNet#%d '%s' pri=%d len=%.0f diff=%b matched=%b grp=%d def=%.0f%s",
          netNo, netName, priority, estimatedLength, isDifferential, isLengthMatched,
          matchedGroupId, lengthDeficit, routed ? " [ROUTED]" : "");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  MatchedLengthGroup
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * A matched-length group: a set of nets that must have approximately
   * equal routed length.
   */
  public static class MatchedLengthGroup implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int groupId;
    public final Set<Integer> netNumbers;
    /** The reference net (longest) in this group. */
    public int referenceNetNo;
    /** The target length (reference net's estimated length). */
    public double targetLength;
    /** Deviation tolerance in board units. */
    public double tolerance;

    public MatchedLengthGroup(int groupId) {
      this.groupId = groupId;
      this.netNumbers = new HashSet<>();
      this.referenceNetNo = -1;
      this.targetLength = 0;
      this.tolerance = 0;
    }

    public int getNetCount() { return netNumbers.size(); }

    @Override
    public String toString() {
      return String.format("MatchedGroup#%d nets=%d ref=%d target=%.0f",
          groupId, netNumbers.size(), referenceNetNo, targetLength);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;
  private final ContractionHierarchies ch;
  private final List<BackboneNet> backboneNets;
  private final Set<Integer> matchedLengthGroupNets;
  private final List<MatchedLengthGroup> matchedLengthGroups;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction
  // ═══════════════════════════════════════════════════════════════════════

  public BackboneNetSelector(BasicBoard board, ContractionHierarchies ch) {
    this.board = board;
    this.ch = ch;
    this.backboneNets = new ArrayList<>();
    this.matchedLengthGroupNets = new HashSet<>();
    this.matchedLengthGroups = new ArrayList<>();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Main selection & ordering
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Select and rank backbone nets from the given set of net numbers.
   * Returns them in routing order (highest priority first).
   * <p>
   * After selection, matched-length groups are identified and their
   * reference nets and deviation costs are computed.
   */
  public List<BackboneNet> selectBackboneNets(Set<Integer> allNetNumbers) {
    backboneNets.clear();
    matchedLengthGroupNets.clear();
    matchedLengthGroups.clear();

    // Phase 1: Build all backbone net entries
    for (int netNo : allNetNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      if (net.get_pins().size() < 2) continue;

      String name = net.name != null ? net.name.toUpperCase() : "";
      boolean isDiff = name.endsWith("_P") || name.endsWith("_N")
          || name.endsWith("+") || name.endsWith("-")
          || name.contains("_P_") || name.contains("_N_");
      boolean isHighSpeed = name.contains("CLK") || name.contains("CLOCK")
          || name.contains("DDR") || name.contains("PCIE")
          || name.contains("SATA") || name.contains("USB")
          || name.contains("ETH") || name.contains("HDMI")
          || name.contains("MIPI") || name.contains("LVDS");
      boolean isAnalog = name.contains("ADC") || name.contains("DAC")
          || name.contains("SENSE") || name.contains("REF")
          || name.contains("IMSAFE");
      boolean isLengthMatched = name.contains("DDR") || name.contains("EQ")
          || name.contains("MATCH") || name.contains("GROUP");

      double length = estimateNetLength(net);
      double channelScore = computeChannelScore(net);

      int priority;
      if (isDiff || isHighSpeed) {
        priority = PRIORITY_CRITICAL;
      } else if (isAnalog || isLengthMatched) {
        priority = PRIORITY_HIGH;
      } else if (length > 1000000) {
        priority = PRIORITY_MEDIUM;
      } else {
        priority = PRIORITY_LOW;
      }

      BackboneNet bn = new BackboneNet(netNo, priority, length,
          channelScore, isDiff, isLengthMatched, isHighSpeed, net.name);
      backboneNets.add(bn);
    }

    // Phase 2: Detect matched-length groups
    detectMatchedLengthGroups();

    // Phase 3: Assign group IDs and compute deviation costs
    assignGroupMetadata();

    // Phase 4: Sort by priority desc → channelScore desc → length desc
    backboneNets.sort((a, b) -> {
      int cmp = Integer.compare(b.priority, a.priority);
      if (cmp != 0) return cmp;
      cmp = Double.compare(b.channelScore, a.channelScore);
      if (cmp != 0) return cmp;
      return Double.compare(b.estimatedLength, a.estimatedLength);
    });

    FRLogger.info("BackboneNetSelector: selected " + backboneNets.size()
        + " backbone nets (crit=" + countByPriority(PRIORITY_CRITICAL)
        + ", high=" + countByPriority(PRIORITY_HIGH)
        + ", med=" + countByPriority(PRIORITY_MEDIUM)
        + ", low=" + countByPriority(PRIORITY_LOW)
        + "), " + matchedLengthGroupNets.size() + " length-matched"
        + " in " + matchedLengthGroups.size() + " groups");

    return Collections.unmodifiableList(backboneNets);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Matched-length group detection
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Detect matched-length groups by analysing net names.
   *
   * Detection rules:
   * <ul>
   *   <li>Nets with explicit "GROUP" / "EQ" / "MATCH" in name are grouped
   *       by the number/identifier following the keyword.</li>
   *   <li>Differential pair halves (_P/_N) with same base name form a
   *       natural matched pair.</li>
   *   <li>DDR bus signals (DQ0-DQ7) with the same prefix are grouped.</li>
   * </ul>
   */
  private void detectMatchedLengthGroups() {
    Map<String, Set<Integer>> rawGroups = new HashMap<>();

    for (BackboneNet bn : backboneNets) {
      String name = bn.netName != null ? bn.netName.toUpperCase() : "";

      // Rule 1: Explicit GROUP/EQ/MATCH patterns
      String groupKey = extractGroupKey(name);
      if (groupKey != null) {
        rawGroups.computeIfAbsent(groupKey, k -> new HashSet<>()).add(bn.netNo);
        continue;
      }

      // Rule 2: DDR-style bus (DQ, DQS, etc.)
      String busKey = extractBusKey(name);
      if (busKey != null) {
        rawGroups.computeIfAbsent(busKey, k -> new HashSet<>()).add(bn.netNo);
        continue;
      }

      // Rule 3: Differential pair halves
      if (name.endsWith("_P") || name.endsWith("_N")) {
        String baseName = name.substring(0, name.length() - 2);
        if (baseName.length() > 1) {
          rawGroups.computeIfAbsent("DIFF_" + baseName, k -> new HashSet<>()).add(bn.netNo);
        }
      }
    }

    // Filter: only keep groups with >= 2 members
    int groupId = 0;
    for (Map.Entry<String, Set<Integer>> e : rawGroups.entrySet()) {
      if (e.getValue().size() >= 2) {
        MatchedLengthGroup group = new MatchedLengthGroup(groupId++);
        group.netNumbers.addAll(e.getValue());
        matchedLengthGroups.add(group);
        matchedLengthGroupNets.addAll(e.getValue());
      }
    }
  }

  /**
   * Extract a group key from an explicit group-marked net name.
   * Returns null if no group marker is found.
   */
  private String extractGroupKey(String name) {
    for (String marker : new String[]{"GROUP", "EQ", "MATCH", "_G"}) {
      int idx = name.indexOf(marker);
      if (idx >= 0) {
        StringBuilder sb = new StringBuilder();
        for (int i = idx + marker.length(); i < name.length(); i++) {
          char ch = name.charAt(i);
          if (Character.isDigit(ch) || ch == '_' || ch == '-') {
            sb.append(ch);
          } else break;
        }
        if (sb.length() > 0) {
          return marker + sb.toString();
        }
      }
    }
    if (name.startsWith("DDR")) {
      int underscoreIdx = name.indexOf('_');
      if (underscoreIdx > 0 && underscoreIdx < name.length() - 1) {
        return name.substring(0, underscoreIdx);
      }
    }
    return null;
  }

  /**
   * Extract a bus group key from a bus-signal name.
   * E.g. "ADDR0", "DATA7" -> "ADDR" / "DATA" bus group.
   */
  private String extractBusKey(String name) {
    String alpha = name.replaceAll("[0-9_\\-]+$", "");
    String numPart = name.substring(alpha.length());
    if (!numPart.isEmpty() && alpha.length() >= 3) {
      return "BUS_" + alpha;
    }
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Group metadata assignment
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Assign group IDs and compute deviation costs for all backbone nets.
   */
  private void assignGroupMetadata() {
    for (MatchedLengthGroup group : matchedLengthGroups) {
      if (group.netNumbers.isEmpty()) continue;

      double maxLength = 0;
      int refNet = -1;
      for (int netNo : group.netNumbers) {
        for (BackboneNet bn : backboneNets) {
          if (bn.netNo == netNo && bn.estimatedLength > maxLength) {
            maxLength = bn.estimatedLength;
            refNet = netNo;
          }
        }
      }

      group.referenceNetNo = refNet;
      group.targetLength = maxLength;
      group.tolerance = maxLength * LENGTH_MISMATCH_TOLERANCE;

      for (int netNo : group.netNumbers) {
        for (BackboneNet bn : backboneNets) {
          if (bn.netNo == netNo) {
            bn.matchedGroupId = group.groupId;
            if (netNo == refNet) {
              bn.isGroupReference = true;
              bn.lengthDeficit = 0;
              bn.deviationCost = 0;
            } else {
              bn.lengthDeficit = maxLength - bn.estimatedLength;
              bn.deviationCost = bn.lengthDeficit * DEVIATION_COST_PER_UNIT;
            }
            bn.isLengthMatched = true;
            break;
          }
        }
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private double estimateNetLength(Net net) {
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); minY = Math.min(minY, c.y);
      maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y);
    }
    return (maxX - minX) + (maxY - minY);
  }

  private double computeChannelScore(Net net) {
    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Pin pin : net.get_pins()) {
      FloatPoint c = pin.get_center().to_float();
      minX = Math.min(minX, c.x); minY = Math.min(minY, c.y);
      maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y);
    }
    double area = Math.max(1, (maxX - minX) * (maxY - minY));
    int pinCount = net.get_pins().size();
    return pinCount / area;
  }

  private int countByPriority(int priority) {
    return (int) backboneNets.stream().filter(b -> b.priority == priority).count();
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Query methods
  // ═══════════════════════════════════════════════════════════════════════

  /** Get a sorted list of backbone nets. */
  public List<BackboneNet> getBackboneNets() {
    return Collections.unmodifiableList(backboneNets);
  }

  /** Get net numbers in matched-length groups. */
  public Set<Integer> getMatchedLengthGroupNets() {
    return Collections.unmodifiableSet(matchedLengthGroupNets);
  }

  /** Get all detected matched-length groups. */
  public List<MatchedLengthGroup> getMatchedLengthGroups() {
    return Collections.unmodifiableList(matchedLengthGroups);
  }

  /** Get the reference net for a matched-length group. */
  public int getGroupReferenceNet(int groupId) {
    for (MatchedLengthGroup g : matchedLengthGroups) {
      if (g.groupId == groupId) return g.referenceNetNo;
    }
    return -1;
  }

  /** Get the matched-length group for a given net. */
  public MatchedLengthGroup getGroupForNet(int netNo) {
    for (MatchedLengthGroup g : matchedLengthGroups) {
      if (g.netNumbers.contains(netNo)) return g;
    }
    return null;
  }

  /** Get max priority backbone net for a set of nets. */
  public int getGroupReferenceNet(Set<Integer> groupNets) {
    for (BackboneNet bn : backboneNets) {
      if (groupNets.contains(bn.netNo)) return bn.netNo;
    }
    return groupNets.isEmpty() ? -1 : groupNets.iterator().next();
  }

  /** Get the deviation cost for a net (for length-matched groups). */
  public double getDeviationCost(int netNo) {
    for (BackboneNet bn : backboneNets) {
      if (bn.netNo == netNo) return bn.deviationCost;
    }
    return 0;
  }
}
