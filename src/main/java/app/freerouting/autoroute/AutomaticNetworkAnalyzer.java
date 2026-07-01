package app.freerouting.autoroute;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Pin;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;

import java.io.Serializable;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UTPR V7 — Automatic Network Intelligent Analysis.
 * <p>
 * Automatically identifies net types, differential pairs, and length-match
 * groups from net names and spatial pin distribution, without requiring
 * manual user configuration. Produces a {@code Map<Integer, NetClass>}
 * consumed by all subsequent UTPR pipeline phases.
 * <p>
 * Contains three inner components:
 * <ul>
 *   <li>{@link NetNameParser} — regex rule engine for name-based classification</li>
 *   <li>{@link DiffPairValidator} — name matching + spatial verification</li>
 *   <li>{@link LengthMatchGroupDetector} — three-stage group detection</li>
 * </ul>
 */
public class AutomaticNetworkAnalyzer implements Serializable {

  private static final long serialVersionUID = 1L;

  private static final double MAX_MATCH_RADIUS = 2_000; // 2mm in board units
  private static final double GROUP_SPATIAL_RADIUS = 10_000; // 10mm in board units
  private static final double SHORT_NET_THRESHOLD = 10_000; // 10mm
  private static final int MIN_BUS_SIZE = 4;

  // ═══════════════════════════════════════════════════════════════════════
  //  PatternRule inner class
  // ═══════════════════════════════════════════════════════════════════════

  /** A single regex matching rule with associated NetType and confidence. */
  private static class PatternRule implements Serializable {
    private static final long serialVersionUID = 1L;
    final Pattern pattern;
    final NetClass.NetType type;
    final double confidence;

    PatternRule(String regex, NetClass.NetType type, double confidence) {
      this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
      this.type = type;
      this.confidence = confidence;
    }

    Matcher matcher(String name) { return pattern.matcher(name); }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Net name regex rules (priority-ordered)
  // ═══════════════════════════════════════════════════════════════════════

  private static final List<PatternRule> RULES = buildRules();

  private static List<PatternRule> buildRules() {
    List<PatternRule> rules = new ArrayList<>();
    // Priority order: more specific patterns first

    // PCIe differential
    rules.add(new PatternRule("PCIE?_(TX|RX)[PN].*", NetClass.NetType.HIGH_SPEED_SERIAL, 0.95));
    rules.add(new PatternRule("PCIE_.*", NetClass.NetType.HIGH_SPEED_SERIAL, 0.85));

    // USB
    rules.add(new PatternRule("USB[_DPN]?\\d*", NetClass.NetType.USB, 0.85));

    // Ethernet
    rules.add(new PatternRule("(ETH|RGMII|MII|MDI)[_PN]?\\d*", NetClass.NetType.ETHERNET, 0.80));

    // HDMI / display
    rules.add(new PatternRule("HDMI|TMDS|DVI", NetClass.NetType.HDMI, 0.85));

    // High-speed serial (SERDES, SATA, SF*P, LVDS, MIPI)
    rules.add(new PatternRule("SERDES|SATA|SFP|SFPP|LVDS|MIPI", NetClass.NetType.HIGH_SPEED_SERIAL, 0.85));

    // DDR data
    rules.add(new PatternRule("DQ\\d+\\b|DQS\\d*", NetClass.NetType.DDR_DATA, 0.85));

    // DDR address / command
    rules.add(new PatternRule("ADDR|A\\d+\\b|BA\\d+|CS\\b|WE\\b|CAS|RAS", NetClass.NetType.DDR_CMD, 0.80));

    // Clock signals
    rules.add(new PatternRule("CLK|CLOCK|REFCLK|SYSCLK", NetClass.NetType.CLOCK, 0.90));

    // Analog signals
    rules.add(new PatternRule("VIN|VOUT|ADC|DAC|AMP|SENSE|VBAT|VREF", NetClass.NetType.ANALOG, 0.65));

    // SPI / I2C
    rules.add(new PatternRule("(SPI|I2C|IIC|SDA|SCL)[_\\d]*", NetClass.NetType.I2C_SPI, 0.70));

    // Reset / enable
    rules.add(new PatternRule("RESET|RST\\b|ENABLE|EN\\b", NetClass.NetType.NORMAL, 0.70));

    // Power / ground
    rules.add(new PatternRule("^V(CC|DD|SS|EE|PP)\\d*|^GND|VREF\\b|PWR", NetClass.NetType.POWER_GND, 0.60));

    // Differential pair markers — positive end
    rules.add(new PatternRule("_(P|POS|PLUS|\\+)$", NetClass.NetType.DIFF_PAIR, 0.95));
    rules.add(new PatternRule("_P\\b", NetClass.NetType.DIFF_PAIR, 0.90));

    // Differential pair markers — negative end
    rules.add(new PatternRule("_(N|NEG|MINUS|-)$", NetClass.NetType.DIFF_PAIR, 0.95));
    rules.add(new PatternRule("_N\\b", NetClass.NetType.DIFF_PAIR, 0.90));

    return rules;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Fields
  // ═══════════════════════════════════════════════════════════════════════

  private final BasicBoard board;

  /** Result map: net number → NetClass metadata. */
  private final Map<Integer, NetClass> netClassMap = new HashMap<>();

  /** Detected differential pairs: pairId → [netP, netN]. */
  private final Map<Integer, int[]> diffPairs = new HashMap<>();

  /** Detected length-match groups: groupId → group info. */
  private final Map<Integer, LengthMatchGroup> lengthGroups = new HashMap<>();

  /** Next ID counters. */
  private int nextDiffPairId = 0;
  private int nextGroupId = 0;

  // ═══════════════════════════════════════════════════════════════════════
  //  Construction & main entry
  // ═══════════════════════════════════════════════════════════════════════

  public AutomaticNetworkAnalyzer(BasicBoard board) {
    this.board = board;
  }

  /**
   * Analyze all nets on the board and return the complete NetClass map.
   * Called once during Phase 0.
   */
  public Map<Integer, NetClass> analyzeAll(Set<Integer> netNumbers) {
    long t0 = System.currentTimeMillis();
    netClassMap.clear();
    diffPairs.clear();
    lengthGroups.clear();
    nextDiffPairId = 0;
    nextGroupId = 0;

    // Step 1: Name-based classification for every net
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      NetClass nc = new NetClass(netNo);
      classifyByName(net.name, nc);
      netClassMap.put(netNo, nc);
    }

    // Step 2: Differential pair detection (name + spatial)
    detectDiffPairs(netNumbers);

    // Step 3: Length-match group detection
    detectLengthGroups(netNumbers);

    // Step 4: Assign backbone priorities and layer grades
    for (NetClass nc : netClassMap.values()) {
      assignPriorityAndGrade(nc);
    }

    long elapsed = System.currentTimeMillis() - t0;
    int diffPairCount = diffPairs.size();
    int strongDp = 0, weakDp = 0;
    for (int[] pair : diffPairs.values()) {
      NetClass ncP = netClassMap.get(pair[0]);
      if (ncP != null && ncP.getConfidence() >= 0.80) strongDp++;
      else weakDp++;
    }
    FRLogger.info("AutomaticNetworkAnalyzer: " + netNumbers.size() + " nets analyzed in " + elapsed + "ms");
    FRLogger.info("  → " + diffPairCount + " differential pairs (" + strongDp + " strong, " + weakDp + " weak)");
    FRLogger.info("  → " + lengthGroups.size() + " length-match groups");
    return netClassMap;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  NetNameParser — Regex-based classification
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Classify a single net by name using the priority-ordered regex rules.
   * Sets type, confidence, group prefix, and group index on the NetClass.
   */
  private void classifyByName(String netName, NetClass nc) {
    if (netName == null || netName.isEmpty()) {
      nc.setType(NetClass.NetType.UNKNOWN);
      nc.setConfidence(0.0);
      return;
    }

    // Try high-priority rules first
    for (PatternRule rule : RULES) {
      Matcher m = rule.matcher(netName);
      if (m.find()) {
        nc.setType(rule.type);
        nc.setConfidence(rule.confidence);
        return;
      }
    }

    // Check for bus-grouped pattern: prefix+number suffix
    Pattern busPattern = Pattern.compile("^([A-Z]+_?)(\\d+)$", Pattern.CASE_INSENSITIVE);
    Matcher m = busPattern.matcher(netName);
    if (m.find()) {
      nc.setType(NetClass.NetType.BUS_GROUPED);
      nc.setConfidence(0.75);
      nc.setGroupPrefix(m.group(1));
      try {
        nc.setGroupIndex(Integer.parseInt(m.group(2)));
      } catch (NumberFormatException e) {
        nc.setGroupIndex(-1);
      }
      return;
    }

    // Low-requirement patterns (GPIO, TEST, etc.) — still NORMAL type but low priority
    Pattern lowReqPattern = Pattern.compile(
        "GPIO\\d*|TEST|TP\\d*|NC\\b|DNC|RSVD|DEBUG|LED\\d*|SW\\d*|BUTTON|BUZZER|"
            + "JTAG_\\w+|TMS\\b|TCK\\b|TDI\\b|TDO\\b|TRST\\b", Pattern.CASE_INSENSITIVE);
    if (lowReqPattern.matcher(netName).find()) {
      nc.setType(NetClass.NetType.NORMAL);
      nc.setConfidence(0.60);
      nc.setLowRequirement(true);
      return;
    }

    // Monitor / sense / feedback nets
    Pattern monitorPattern = Pattern.compile("\\w*_FB|SENSE|MON|DET|PG\\b|PGOOD",
        Pattern.CASE_INSENSITIVE);
    if (monitorPattern.matcher(netName).find()) {
      nc.setType(NetClass.NetType.NORMAL);
      nc.setConfidence(0.55);
      nc.setLowRequirement(true);
      return;
    }

    // Fallback: NORMAL
    nc.setType(NetClass.NetType.NORMAL);
    nc.setConfidence(0.50);
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  DiffPairValidator — Name matching + spatial verification
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Detect differential pairs: name-based matching followed by spatial verification.
   */
  private void detectDiffPairs(Set<Integer> netNumbers) {
    List<Integer> netList = new ArrayList<>(netNumbers);
    // Gather pin positions for spatial verification
    Map<Integer, List<FloatPoint>> netPinPositions = new HashMap<>();
    for (int netNo : netList) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      List<FloatPoint> pts = new ArrayList<>();
      for (Pin pin : net.get_pins()) {
        pts.add(pin.get_center().to_float());
      }
      if (!pts.isEmpty()) netPinPositions.put(netNo, pts);
    }

    // Phase A: Name matching — find P/N pairs
    for (int i = 0; i < netList.size(); i++) {
      int netA = netList.get(i);
      NetClass ncA = netClassMap.get(netA);
      if (ncA == null || (ncA.getType() != NetClass.NetType.DIFF_PAIR
          && ncA.getType() != NetClass.NetType.HIGH_SPEED_SERIAL
          && ncA.getType() != NetClass.NetType.USB)) continue;

      String nameA = getNetName(netA);
      if (nameA == null) continue;
      String coreA = stripLayerSuffix(nameA);

      for (int j = i + 1; j < netList.size(); j++) {
        int netB = netList.get(j);
        NetClass ncB = netClassMap.get(netB);
        if (ncB == null) continue;
        String nameB = getNetName(netB);
        if (nameB == null) continue;
        String coreB = stripLayerSuffix(nameB);

        // Check P/N pairing
        int diffType = checkDiffPairNaming(coreA, coreB);
        if (diffType == 0) continue;

        // Both nets should have DIFF_PAIR type
        NetClass.NetType pairType = NetClass.NetType.DIFF_PAIR;
        ncA.setType(pairType);
        ncB.setType(pairType);

        // Phase B: Spatial verification
        double spatialScore = computeSpatialScore(
            netPinPositions.get(netA), netPinPositions.get(netB));
        double nameConfidence = Math.max(ncA.getConfidence(), ncB.getConfidence());
        double finalConfidence = nameConfidence * 0.6 + spatialScore * 0.4;

        ncA.setConfidence(finalConfidence);
        ncB.setConfidence(finalConfidence);

        // Decision: strong DP / weak DP / not a DP
        if (finalConfidence >= 0.80) {
          int pairId = nextDiffPairId++;
          ncA.setDiffPairId(pairId);
          ncB.setDiffPairId(pairId);
          ncA.setType(NetClass.NetType.DIFF_PAIR);
          ncB.setType(NetClass.NetType.DIFF_PAIR);
          diffPairs.put(pairId, new int[]{netA, netB});
        } else if (finalConfidence >= 0.55) {
          int pairId = nextDiffPairId++;
          ncA.setDiffPairId(pairId);
          ncB.setDiffPairId(pairId);
          ncA.setType(NetClass.NetType.DIFF_PAIR_WEAK);
          ncB.setType(NetClass.NetType.DIFF_PAIR_WEAK);
          diffPairs.put(pairId, new int[]{netA, netB});
        } else {
          // Degrade back to normal
          ncA.setType(NetClass.NetType.NORMAL);
          ncB.setType(NetClass.NetType.NORMAL);
        }
      }
    }
  }

  /**
   * Check if two core names form a P/N differential pair.
   * @return 1 if (A=P, B=N), -1 if (A=N, B=P), 0 if not a pair.
   */
  private int checkDiffPairNaming(String coreA, String coreB) {
    // Try stripping trailing P/N markers
    String baseA = coreA.replaceAll("_(P|N|POS|NEG|PLUS|MINUS|[+\\-])$", "");
    String baseB = coreB.replaceAll("_(P|N|POS|NEG|PLUS|MINUS|[+\\-])$", "");
    if (!baseA.equals(baseB)) return 0;

    // Determine polarity of each core
    boolean aIsP = coreA.matches(".*_(P|POS|PLUS|\\+)$") || coreA.endsWith("_P");
    boolean aIsN = coreA.matches(".*_(N|NEG|MINUS|-)$") || coreA.endsWith("_N");
    boolean bIsP = coreB.matches(".*_(P|POS|PLUS|\\+)$") || coreB.endsWith("_P");
    boolean bIsN = coreB.matches(".*_(N|NEG|MINUS|-)$") || coreB.endsWith("_N");

    if (aIsP && bIsN) return 1;
    if (aIsN && bIsP) return -1;
    return 0;
  }

  /**
   * Compute spatial verification score between two pin sets.
   * Uses greedy nearest-neighbor matching (simplified Hungarian).
   */
  private double computeSpatialScore(List<FloatPoint> pinsP, List<FloatPoint> pinsN) {
    if (pinsP == null || pinsN == null || pinsP.isEmpty() || pinsN.isEmpty()) {
      return 0.0;
    }
    int n = Math.min(pinsP.size(), pinsN.size());
    if (n == 0) return 0.0;

    // Pin count mismatch → penalty
    double countPenalty = (pinsP.size() == pinsN.size()) ? 1.0 : 0.5;

    // Greedy nearest-neighbor matching
    double totalDist = 0;
    List<FloatPoint> remaining = new ArrayList<>(pinsN);
    for (int i = 0; i < n; i++) {
      FloatPoint p = pinsP.get(i);
      double bestDist = Double.MAX_VALUE;
      int bestIdx = -1;
      for (int j = 0; j < remaining.size(); j++) {
        double d = Math.sqrt(Math.pow(p.x - remaining.get(j).x, 2)
            + Math.pow(p.y - remaining.get(j).y, 2));
        if (d < bestDist) {
          bestDist = d;
          bestIdx = j;
        }
      }
      totalDist += bestDist;
      if (bestIdx >= 0) remaining.remove(bestIdx);
    }
    double avgDist = totalDist / n;

    // spatialScore: 1.0 if avgDist = 0, approaching 0 as avgDist → MAX_MATCH_RADIUS
    double spatialScore = 1.0 - Math.min(avgDist / MAX_MATCH_RADIUS, 1.0);
    return spatialScore * countPenalty;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  LengthMatchGroupDetector — Three-stage group detection
  // ═══════════════════════════════════════════════════════════════════════

  /** Represents a detected length-match group. */
  public static class LengthMatchGroup implements Serializable {
    private static final long serialVersionUID = 1L;
    public final int groupId;
    public final Set<Integer> netNumbers = new HashSet<>();
    public double toleranceFraction = 0.05; // ±5% initial
    public double minLength, maxLength;
    public boolean isDiffPairExtension = false;
    public int priority = 1; // DDR > high-speed > normal

    LengthMatchGroup(int groupId) { this.groupId = groupId; }

    void addNet(int netNo) { netNumbers.add(netNo); }

    boolean isLargeGroup() { return netNumbers.size() > 8; }

    @Override
    public String toString() {
      return "LengthMatchGroup#" + groupId + " [" + netNumbers.size() + " nets]";
    }
  }

  /**
   * Three-stage length-match group detection.
   */
  private void detectLengthGroups(Set<Integer> netNumbers) {
    // Stage A: Bus prefix + numeric suffix detection
    detectBusGroups(netNumbers);

    // Stage B: Differential pair extension — P/N pair treated as matched-length
    for (Map.Entry<Integer, int[]> entry : diffPairs.entrySet()) {
      int[] nets = entry.getValue();
      // Already in a bus group? skip.
      NetClass ncP = netClassMap.get(nets[0]);
      if (ncP != null && ncP.getLengthGroupId() >= 0) continue;
      NetClass ncN = netClassMap.get(nets[1]);
      if (ncN != null && ncN.getLengthGroupId() >= 0) continue;

      int gid = nextGroupId++;
      LengthMatchGroup group = new LengthMatchGroup(gid);
      group.addNet(nets[0]);
      group.addNet(nets[1]);
      group.isDiffPairExtension = true;
      group.priority = 2;
      lengthGroups.put(gid, group);

      if (ncP != null) ncP.setLengthGroupId(gid);
      if (ncN != null) ncN.setLengthGroupId(gid);
    }

    // Stage C: Special prefix groups (DQ, DQS, ADDR, BA, CS, WE)
    detectSpecialPrefixGroups(netNumbers);

    // Compute min/max lengths for each group
    for (LengthMatchGroup group : lengthGroups.values()) {
      computeGroupLengthRange(group);
    }
  }

  /** Stage A: Detect bus prefix + numeric suffix groups (e.g. DATA0..DATA31). */
  private void detectBusGroups(Set<Integer> netNumbers) {
    // Group nets by their bus prefix
    Map<String, List<Integer>> prefixGroups = new HashMap<>();
    for (int netNo : netNumbers) {
      NetClass nc = netClassMap.get(netNo);
      if (nc == null) continue;
      if (nc.getGroupPrefix() != null) {
        prefixGroups.computeIfAbsent(nc.getGroupPrefix(), _k -> new ArrayList<>()).add(netNo);
      }
    }

    for (Map.Entry<String, List<Integer>> entry : prefixGroups.entrySet()) {
      List<Integer> members = entry.getValue();
      if (members.size() < MIN_BUS_SIZE) continue;

      // Check spatial proximity — all centers within GROUP_SPATIAL_RADIUS
      if (!allWithinSpatialRadius(members)) continue;

      int gid = nextGroupId++;
      LengthMatchGroup group = new LengthMatchGroup(gid);
      for (int netNo : members) {
        group.addNet(netNo);
        NetClass nc = netClassMap.get(netNo);
        if (nc != null) {
          nc.setLengthGroupId(gid);
          nc.setType(NetClass.NetType.BUS_GROUPED);
        }
      }
      group.priority = 2; // Bus groups are generally important
      // DDR data/address buses get higher priority
      String prefix = entry.getKey().toUpperCase();
      if (prefix.startsWith("DQ") || prefix.startsWith("DQS")
          || prefix.startsWith("ADDR") || prefix.startsWith("BA")) {
        group.priority = 3;
      }
      lengthGroups.put(gid, group);
    }
  }

  /** Stage C: Special prefix groups (DQ, DQS, ADDR, BA, CS, WE). */
  private void detectSpecialPrefixGroups(Set<Integer> netNumbers) {
    // Collect nets with special prefixes that weren't already grouped
    Set<String> specialPrefixes = new HashSet<>(Arrays.asList("DQ", "DQS", "ADDR", "BA", "CS", "WE"));
    Map<String, List<Integer>> specialGroups = new HashMap<>();

    for (int netNo : netNumbers) {
      NetClass nc = netClassMap.get(netNo);
      if (nc == null || nc.getLengthGroupId() >= 0) continue;
      String name = getNetName(netNo);
      if (name == null) continue;
      String upper = name.toUpperCase();
      for (String prefix : specialPrefixes) {
        if (upper.startsWith(prefix) || upper.contains("_" + prefix)) {
          specialGroups.computeIfAbsent(prefix, _k -> new ArrayList<>()).add(netNo);
          break;
        }
      }
    }

    for (Map.Entry<String, List<Integer>> entry : specialGroups.entrySet()) {
      List<Integer> members = entry.getValue();
      if (members.size() < 2) continue;

      // Assign to existing group if there's already one for this prefix
      // Otherwise create a new group
      Integer existingGroupId = findExistingGroupForPrefix(entry.getKey());
      if (existingGroupId != null) {
        LengthMatchGroup group = lengthGroups.get(existingGroupId);
        for (int netNo : members) {
          NetClass nc = netClassMap.get(netNo);
          if (nc != null && nc.getLengthGroupId() < 0) {
            group.addNet(netNo);
            nc.setLengthGroupId(existingGroupId);
          }
        }
      } else {
        int gid = nextGroupId++;
        LengthMatchGroup group = new LengthMatchGroup(gid);
        for (int netNo : members) {
          group.addNet(netNo);
          NetClass nc = netClassMap.get(netNo);
          if (nc != null) nc.setLengthGroupId(gid);
        }
        group.priority = 3; // DDR-related
        lengthGroups.put(gid, group);
      }
    }
  }

  private Integer findExistingGroupForPrefix(String prefix) {
    for (LengthMatchGroup group : lengthGroups.values()) {
      if (group.netNumbers.isEmpty()) continue;
      // Check if the group already contains nets with this prefix
      for (int netNo : group.netNumbers) {
        NetClass nc = netClassMap.get(netNo);
        if (nc != null && nc.getGroupPrefix() != null
            && nc.getGroupPrefix().equalsIgnoreCase(prefix)) {
          return group.groupId;
        }
      }
    }
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Priority & grade assignment
  // ═══════════════════════════════════════════════════════════════════════

  private void assignPriorityAndGrade(NetClass nc) {
    switch (nc.getType()) {
      case DIFF_PAIR:
        nc.setBackbonePriority(4); // CRITICAL
        nc.setPreferredLayerGrade(2); // Subway
        nc.setCritical(true);
        break;
      case DIFF_PAIR_WEAK:
        nc.setBackbonePriority(3); // HIGH
        nc.setPreferredLayerGrade(1); // Elevated
        break;
      case CLOCK:
      case DDR_DATA:
      case DDR_CMD:
        nc.setBackbonePriority(4); // CRITICAL
        nc.setPreferredLayerGrade(2);
        nc.setCritical(true);
        break;
      case HIGH_SPEED_SERIAL:
        nc.setBackbonePriority(4);
        nc.setPreferredLayerGrade(2);
        nc.setCritical(true);
        break;
      case BUS_GROUPED:
        nc.setBackbonePriority(3); // HIGH for bus groups
        nc.setPreferredLayerGrade(2);
        break;
      case ANALOG:
      case ETHERNET:
      case USB:
      case HDMI:
        nc.setBackbonePriority(3); // HIGH
        nc.setPreferredLayerGrade(1);
        break;
      case I2C_SPI:
        nc.setBackbonePriority(2); // MEDIUM
        nc.setPreferredLayerGrade(0);
        break;
      case POWER_GND:
        nc.setBackbonePriority(0); // POWER_GND
        nc.setPreferredLayerGrade(0);
        break;
      case NORMAL:
      case UNKNOWN:
      default:
        if (nc.isLowRequirement()) {
          nc.setBackbonePriority(1); // LOW
          nc.setPreferredLayerGrade(0);
        } else if (nc.getEstimatedLength() > SHORT_NET_THRESHOLD) {
          nc.setBackbonePriority(2); // MEDIUM
          nc.setPreferredLayerGrade(1);
        } else {
          nc.setBackbonePriority(1); // LOW
          nc.setPreferredLayerGrade(0);
        }
        break;
    }

    // LengthMatchGroup priority override: all members get group's priority
    if (nc.getLengthGroupId() >= 0) {
      LengthMatchGroup lg = lengthGroups.get(nc.getLengthGroupId());
      if (lg != null && lg.priority > nc.getBackbonePriority()) {
        nc.setBackbonePriority(lg.priority);
      }
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private String getNetName(int netNo) {
    Net net = board.rules.nets.get(netNo);
    return (net != null) ? net.name : null;
  }

  /** Strip layer / fanout suffixes (e.g. _B1, _VIA). */
  private static String stripLayerSuffix(String name) {
    if (name == null) return null;
    return name.replaceAll("_(B\\d+|VIA|TOP|BOTTOM|INNER\\d*)$", "");
  }

  /** Check if all nets in the list are within GROUP_SPATIAL_RADIUS of the group center. */
  private boolean allWithinSpatialRadius(List<Integer> netNumbers) {
    if (netNumbers.isEmpty()) return true;
    // Compute center
    double cx = 0, cy = 0;
    int count = 0;
    for (int netNo : netNumbers) {
      FloatPoint c = getNetCenter(netNo);
      if (c != null) { cx += c.x; cy += c.y; count++; }
    }
    if (count == 0) return false;
    cx /= count; cy /= count;

    // Check distance from center
    for (int netNo : netNumbers) {
      FloatPoint c = getNetCenter(netNo);
      if (c == null) continue;
      double d = Math.sqrt(Math.pow(c.x - cx, 2) + Math.pow(c.y - cy, 2));
      if (d > GROUP_SPATIAL_RADIUS) return false;
    }
    return true;
  }

  private FloatPoint getNetCenter(int netNo) {
    Net net = board.rules.nets.get(netNo);
    if (net == null) return null;
    double cx = 0, cy = 0;
    int cnt = 0;
    for (Pin pin : net.get_pins()) {
      FloatPoint p = pin.get_center().to_float();
      cx += p.x; cy += p.y; cnt++;
    }
    return cnt > 0 ? new FloatPoint(cx / cnt, cy / cnt) : null;
  }

  private void computeGroupLengthRange(LengthMatchGroup group) {
    double minL = Double.MAX_VALUE;
    double maxL = Double.MIN_VALUE;
    for (int netNo : group.netNumbers) {
      double len = estimateNetLength(netNo);
      if (len >= 0) {
        minL = Math.min(minL, len);
        maxL = Math.max(maxL, len);
      }
    }
    if (minL < Double.MAX_VALUE) {
      group.minLength = minL;
      group.maxLength = maxL;
    }
  }

  /** Estimate net length from pin bounding-box diagonal. */
  private double estimateNetLength(int netNo) {
    Net net = board.rules.nets.get(netNo);
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

  // ═══════════════════════════════════════════════════════════════════════
  //  Public accessors
  // ═══════════════════════════════════════════════════════════════════════

  public Map<Integer, NetClass> getNetClassMap() { return Collections.unmodifiableMap(netClassMap); }

  public Map<Integer, int[]> getDiffPairs() { return Collections.unmodifiableMap(diffPairs); }

  public Map<Integer, LengthMatchGroup> getLengthGroups() { return Collections.unmodifiableMap(lengthGroups); }

  public NetClass getNetClass(int netNo) { return netClassMap.get(netNo); }

  public int getDiffPairCount() { return diffPairs.size(); }

  public int getLengthGroupCount() { return lengthGroups.size(); }

  /** Get the other net in a differential pair, or -1. */
  public int getDiffPairPartner(int netNo) {
    for (int[] pair : diffPairs.values()) {
      if (pair[0] == netNo) return pair[1];
      if (pair[1] == netNo) return pair[0];
    }
    return -1;
  }
}
