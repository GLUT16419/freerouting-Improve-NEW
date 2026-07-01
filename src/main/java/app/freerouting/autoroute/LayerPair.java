package app.freerouting.autoroute;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Enhanced: Layer pair data model with city-traffic-grade support.
 * <p>
 * In the UTPR city metaphor, a layer pair corresponds to a <b>transportation
 * grade</b> within the urban road network:
 * <ul>
 *   <li><b>Grade 0 — Surface (地面层微带线)</b>: Outer (top/bottom) signal
 *       layers for short-range, low-speed connections. Microstrip topology.</li>
 *   <li><b>Grade 1 — Elevated / Outer (高架层/外层)</b>: Middle layers for
 *       medium-range connections. Stripline or buried microstrip.</li>
 *   <li><b>Grade 2 — Subway / Inner (地铁层/内层带状线)</b>: Inner stripline
 *       pairs for long-distance, high-speed, impedance-controlled signals.</li>
 * </ul>
 * <p>
 * Each layer pair now carries:
 * <ul>
 *   <li>{@code grade} — the traffic grade (0, 1, or 2)</li>
 *   <li>{@code gradeName} — human-readable label</li>
 *   <li>{@code functionalBlockId} — CRP level-2 functional block assignment
 *       (-1 = global/shared across blocks)</li>
 *   <li>{@code districtIds} — CRP level-3 district assignments (which
 *       urban districts use this layer pair)</li>
 * </ul>
 *
 * @see MultiLevelPartitioner
 */
public class LayerPair implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Grade constants ──────────────────────────────────────────────────

  /** Surface grade (microstrip, 表层微带线). */
  public static final int GRADE_SURFACE = 0;

  /** Elevated / outer grade (stripline/buried, 外层/高架层). */
  public static final int GRADE_ELEVATED = 1;

  /** Subway / inner grade (stripline, 内层带状线/地铁层). */
  public static final int GRADE_SUBWAY = 2;

  // ── Existing V6 fields ──────────────────────────────────────────────

  public final int id;
  public final int primaryLayer;   // signal layer index (e.g. 1)
  public final int secondaryLayer; // signal layer index (e.g. 2)
  public final String primaryDir;  // "H" or "V"
  public final String secondaryDir;
  public final boolean impedanceControlled;
  public final List<String> allowedSignalTypes;

  public double maxUsableArea;    // sq units minus power-plane cuts
  public double currentOccupancy;
  public double avgCongestion;

  // ── UTPR traffic-grade fields ───────────────────────────────────────

  /**
   * Traffic grade of this layer pair:
   * <ul>
   *   <li>{@link #GRADE_SURFACE} (0) — surface microstrip</li>
   *   <li>{@link #GRADE_ELEVATED} (1) — elevated/inner</li>
   *   <li>{@link #GRADE_SUBWAY} (2) — subway/inner stripline</li>
   * </ul>
   */
  public final int grade;

  /** Human-readable grade name. */
  public final String gradeName;

  /**
   * CRP level-2 functional block ID (-1 = global/unassigned).
   * Assigned by the MultiLevelPartitioner during Phase 0.
   */
  public int functionalBlockId;

  /**
   * CRP level-3 district IDs that use this layer pair.
   * Populated by the TrafficModeLayerAssigner during Phase 2.
   */
  public final Set<Integer> districtIds;

  // ── Dynamic occupancy tracking per grade ────────────────────────────

  /** Remaining capacity tracking for dynamic traffic-based routing decisions. */
  public double remainingCapacity;

  // =====================================================================
  //  Constructors
  // =====================================================================

  /** Original V6 constructor (defaults to grade=1 / ELEVATED for backward compat). */
  public LayerPair(int id, int primaryLayer, int secondaryLayer,
                   String primaryDir, String secondaryDir,
                   boolean impedanceControlled,
                   List<String> allowedSignalTypes,
                   double maxUsableArea) {
    this(id, primaryLayer, secondaryLayer, primaryDir, secondaryDir,
        impedanceControlled, allowedSignalTypes, maxUsableArea,
        GRADE_ELEVATED, "elevated");
  }

  /** Full UTPR constructor with grade. */
  public LayerPair(int id, int primaryLayer, int secondaryLayer,
                   String primaryDir, String secondaryDir,
                   boolean impedanceControlled,
                   List<String> allowedSignalTypes,
                   double maxUsableArea,
                   int grade, String gradeName) {
    this.id = id;
    this.primaryLayer = primaryLayer;
    this.secondaryLayer = secondaryLayer;
    this.primaryDir = primaryDir;
    this.secondaryDir = secondaryDir;
    this.impedanceControlled = impedanceControlled;
    this.allowedSignalTypes = allowedSignalTypes;
    this.maxUsableArea = maxUsableArea;
    this.currentOccupancy = 0;
    this.avgCongestion = 0;
    this.grade = grade;
    this.gradeName = gradeName;
    this.functionalBlockId = -1;
    this.districtIds = new HashSet<>();
    this.remainingCapacity = maxUsableArea;
  }

  // =====================================================================
  //  Factory methods
  // =====================================================================

  /**
   * Build default layer pairs from signal layer count, with UTPR grade
   * assignment.
   * <p>
   * <b>Grade assignment logic:</b>
   * <ul>
   *   <li><b>2-layer boards</b>: single pair, surface grade (microstrip)</li>
   *   <li><b>4-layer boards</b>: surface pair (top/bottom) + subway pair
   *       (inner stripline)</li>
   *   <li><b>6+ layers</b>: surface + elevated + subway triples</li>
   * </ul>
   */
  public static List<LayerPair> buildForBoard(int signalLayerCount) {
    List<LayerPair> pairs = new ArrayList<>();

    if (signalLayerCount <= 2) {
      // Single surface pair
      pairs.add(new LayerPair(0, 0, 1, "V", "H", false,
          Arrays.asList("高速数字", "模拟", "低速"), 1e9,
          GRADE_SURFACE, "surface"));
    } else if (signalLayerCount <= 4) {
      // Surface pair: layers 0-1
      pairs.add(new LayerPair(0, 0, 1, "V", "H", false,
          Arrays.asList("高速数字", "模拟", "低速"), 1e9,
          GRADE_SURFACE, "surface"));
      // Subway pair: layers 2-3
      pairs.add(new LayerPair(1, 2, 3, "H", "V", true,
          Arrays.asList("高速数字"), 1e9,
          GRADE_SUBWAY, "subway"));
    } else {
      int pairId = 0;
      // Grade assignment cycles: surface → elevated → subway → elevated → ...
      for (int i = 0; i + 1 < signalLayerCount; i += 2) {
        boolean primaryH = (i % 4 == 0);
        int grade;
        String gradeName;

        // First pair = surface
        if (i == 0) {
          grade = GRADE_SURFACE;
          gradeName = "surface";
        }
        // Last pair = subway (for deep inner layers)
        else if (i + 2 >= signalLayerCount - 1) {
          grade = GRADE_SUBWAY;
          gradeName = "subway";
        }
        // Middle pairs = elevated
        else {
          grade = GRADE_ELEVATED;
          gradeName = "elevated";
        }

        pairs.add(new LayerPair(pairId, i, i + 1,
            primaryH ? "H" : "V",
            primaryH ? "V" : "H",
            grade == GRADE_SUBWAY,  // only subway pairs are impedance controlled
            grade == GRADE_SURFACE
                ? Arrays.asList("低速", "模拟")
                : (grade == GRADE_SUBWAY
                    ? Arrays.asList("高速数字", "差分信号")
                    : Arrays.asList("高速数字", "模拟", "低速")),
            1e9,
            grade, gradeName));
        pairId++;
      }
    }
    return pairs;
  }

  // =====================================================================
  //  Query methods
  // =====================================================================

  /** True if this pair covers the given signal layer index. */
  public boolean containsLayer(int layer) {
    return layer == primaryLayer || layer == secondaryLayer;
  }

  /** True if this pair is a surface-grade pair. */
  public boolean isSurface() { return grade == GRADE_SURFACE; }

  /** True if this pair is an elevated-grade pair. */
  public boolean isElevated() { return grade == GRADE_ELEVATED; }

  /** True if this pair is a subway-grade pair. */
  public boolean isSubway() { return grade == GRADE_SUBWAY; }

  /**
   * Assign a weight for a net cluster being placed on this pair (lower = better fit).
   * <p>
   * UTPR enhancement: includes grade-appropriateness penalty based on net type.
   */
  public double assignmentCost(int netCount, double clusterWidth, double clusterHeight,
                                String preferredGrade, boolean isHighSpeed,
                                boolean isDifferential) {
    double dirCost = 0;
    // Penalise if the cluster's dominant direction does not match pair orientation
    boolean clusterIsWider = clusterWidth > clusterHeight;
    if (clusterIsWider && !primaryDir.equals("H") && !secondaryDir.equals("H")) {
      dirCost += 0.3;
    }
    if (!clusterIsWider && !primaryDir.equals("V") && !secondaryDir.equals("V")) {
      dirCost += 0.3;
    }

    // Grade appropriateness: which grade should this cluster use?
    double gradeCost = 0;
    if (isHighSpeed || isDifferential) {
      // High-speed / differential → prefer subway (grade 2)
      gradeCost = Math.abs(this.grade - GRADE_SUBWAY) * 0.5;
    } else if (preferredGrade != null) {
      gradeCost = Math.abs(this.grade - gradeFromName(preferredGrade)) * 0.3;
    }

    double congestionCost = avgCongestion;
    double occupancyRatio = maxUsableArea > 0 ? currentOccupancy / maxUsableArea : 0;
    return dirCost * 10 + gradeCost * 15 + congestionCost * 5 + occupancyRatio * 20;
  }

  /** Backward-compatible V6 signature. */
  public double assignmentCost(int netCount, double clusterWidth, double clusterHeight) {
    return assignmentCost(netCount, clusterWidth, clusterHeight, null, false, false);
  }

  // =====================================================================
  //  Capacity management
  // =====================================================================

  /** Update remaining capacity after reservations. */
  public void updateRemainingCapacity(double newlyOccupiedArea) {
    this.currentOccupancy += newlyOccupiedArea;
    this.remainingCapacity = Math.max(0, this.maxUsableArea - this.currentOccupancy);
  }

  /** Reset occupancy tracking (for iterative routing). */
  public void resetOccupancy() {
    this.currentOccupancy = 0;
    this.remainingCapacity = this.maxUsableArea;
    this.avgCongestion = 0;
  }

  // =====================================================================
  //  Utility
  // =====================================================================

  /** Convert grade name string to grade constant. */
  public static int gradeFromName(String name) {
    if (name == null) return GRADE_ELEVATED;
    switch (name.toLowerCase()) {
      case "surface": return GRADE_SURFACE;
      case "elevated": return GRADE_ELEVATED;
      case "subway": return GRADE_SUBWAY;
      default: return GRADE_ELEVATED;
    }
  }

  @Override
  public String toString() {
    String gLabel;
    switch (grade) {
      case GRADE_SURFACE:  gLabel = "Surface"; break;
      case GRADE_ELEVATED: gLabel = "Elevated"; break;
      case GRADE_SUBWAY:   gLabel = "Subway"; break;
      default:             gLabel = "Grade" + grade;
    }
    return "LP#" + id + " [" + gLabel + "] L" + primaryLayer + "(" + primaryDir
        + ")/L" + secondaryLayer + "(" + secondaryDir + ")"
        + " occ=" + String.format("%.1f%%", maxUsableArea > 0
            ? currentOccupancy / maxUsableArea * 100 : 0)
        + " FB=" + functionalBlockId
        + " dists=" + districtIds.size();
  }
}
