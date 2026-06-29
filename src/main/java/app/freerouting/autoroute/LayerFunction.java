package app.freerouting.autoroute;

/**
 * Enum representing the functional role of a PCB layer.
 * Supports English and Chinese naming conventions.
 */
public enum LayerFunction {
  SIGNAL, POWER_PLANE, GROUND_PLANE, MIXED, MECHANICAL,
  SOLDER_MASK, PASTE_MASK, SILKSCREEN, OTHER;

  /**
   * Determines layer function from its name using heuristic pattern matching.
   * Supports English, Chinese, and mixed naming conventions used by EDA tools.
   */
  public static LayerFunction fromName(String name) {
    if (name == null || name.isEmpty()) return OTHER;
    String upper = name.toUpperCase();

    // ── Silkscreen / 丝印层 ──
    if (upper.contains("SILKSCREEN") || upper.contains("SILK_SCREEN")
        || upper.contains("LEGEND") || upper.contains("OVERLAY")
        || upper.contains("SILK") || upper.contains("丝印")) {
      return SILKSCREEN;
    }

    // ── Solder mask / 阻焊层 ──
    if (upper.contains("SOLDERMASK") || upper.contains("SOLDER_MASK")
        || upper.contains("RESIST") || upper.startsWith("SM")
        || upper.contains("阻焊") || upper.contains("MASK")) {
      return SOLDER_MASK;
    }

    // ── Paste mask / 钢网层 ──
    if (upper.contains("PASTE") || upper.contains("STENCIL") || upper.contains("钢网")) {
      return PASTE_MASK;
    }

    // ── Ground planes / 地层 ──
    if (upper.contains("GND") || upper.contains("GROUND")
        || upper.contains("VSS") || upper.contains("地层")) {
      return GROUND_PLANE;
    }

    // ── Power planes / 电源层 ──
    if (upper.contains("POWER") || upper.contains("PWR")
        || upper.contains("VCC") || upper.contains("VDD")
        || upper.contains("电源") || upper.contains("PLANE")) {
      return POWER_PLANE;
    }

    // ── Keepout / 禁止布线层 ──
    if (upper.contains("KEEPOUT") || upper.contains("KEEP_OUT")
        || upper.contains("禁止布线") || upper.contains("RESTRICT")) {
      return MECHANICAL;
    }

    // ── Signal layers / 信号层 ──
    if (upper.contains("SIGNAL") || upper.contains("ROUTING")
        || upper.contains("TOP") || upper.contains("BOTTOM")
        || upper.contains("INNER") || upper.contains("SIG")
        || upper.startsWith("L")
        || upper.contains("信号") || upper.contains("走线")
        || upper.contains("顶层") || upper.contains("底层")
        || upper.contains("LAYER") || upper.contains("MID")) {
      return SIGNAL;
    }

    // ── Mechanical / 机械层 ──
    if (upper.contains("MECH") || upper.contains("MECHANICAL")
        || upper.contains("BOARD") || upper.contains("EDGE")
        || upper.contains("CUTS") || upper.contains("DIMENSION")
        || upper.contains("COURTYARD") || upper.contains("ASSEMBLY")
        || upper.contains("FAB") || upper.contains("MARK")
        || upper.contains("FIDUCIAL") || upper.contains("DRILL")
        || upper.contains("机械") || upper.contains("外形")
        || upper.contains("板框") || upper.contains("钻孔")
        || upper.contains("安装") || upper.contains("装配")) {
      return MECHANICAL;
    }

    return OTHER;
  }

  public boolean isRoutable() { return this == SIGNAL || this == MIXED; }
  public boolean isPlane() { return this == POWER_PLANE || this == GROUND_PLANE; }

  /**
   * Returns a Chinese display name for this layer function.
   */
  public String getChineseName() {
    switch (this) {
      case SIGNAL: return "信号层";
      case POWER_PLANE: return "电源层";
      case GROUND_PLANE: return "地层";
      case MIXED: return "混合层";
      case MECHANICAL: return "机械层";
      case SOLDER_MASK: return "阻焊层";
      case PASTE_MASK: return "钢网层";
      case SILKSCREEN: return "丝印层";
      default: return "其他";
    }
  }
}
