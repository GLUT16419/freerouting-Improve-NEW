package app.freerouting.autoroute;

/**
 * Enum representing the electrical type of a net in the PCB design.
 * Used for auto-labelling power and ground nets and guiding routing priorities.
 * Supports English and Chinese naming conventions.
 */
public enum NetType {
  /** Ordinary signal net */
  SIGNAL,
  /** Power net (VCC, VDD, +5V, etc.) */
  POWER,
  /** Ground net (GND, VSS, AGND, DGND, etc.) */
  GROUND;

  /**
   * Determines the net type from its name using heuristic pattern matching.
   * Checks for common power and ground naming conventions used in EDA tools,
   * including Chinese names.
   */
  public static NetType fromName(String name) {
    if (name == null || name.isEmpty()) {
      return SIGNAL;
    }
    String upper = name.toUpperCase();

    // ── Ground patterns / 地网络 ──
    if (upper.equals("GND")
        || upper.equals("VSS")
        || upper.equals("AGND")
        || upper.equals("DGND")
        || upper.equals("SGND")
        || upper.equals("PGND")
        || upper.equals("GNDD")
        || upper.equals("GNDA")
        || upper.equals("GROUND")
        || upper.equals("地")
        || upper.equals("GND网络")
        || upper.startsWith("GND_")
        || upper.startsWith("GND-")
        || upper.startsWith("VSS_")
        || upper.startsWith("AGND")
        || upper.startsWith("DGND")
        || upper.contains("_GND")
        || upper.contains("-GND")
        || upper.contains("_VSS")
        || upper.contains("PWR_GND")
        || upper.contains("PWRGND")
        || upper.equals("SUBSTRATE")
        || upper.equals("SUB")
        || upper.equals("CHASSIS")
        || upper.contains("_CHASSIS")
        || upper.contains("-CHASSIS")
        || upper.contains("地线")
        || upper.contains("接地")
        || upper.startsWith("GND")) {
      return GROUND;
    }

    // ── Power patterns / 电源网络 ──
    if (upper.equals("VCC")
        || upper.equals("VDD")
        || upper.equals("VEE")
        || upper.equals("VBB")
        || upper.equals("VPP")
        || upper.equals("AVCC")
        || upper.equals("AVDD")
        || upper.equals("DVCC")
        || upper.equals("DVDD")
        || upper.equals("3V3")
        || upper.equals("5V")
        || upper.equals("12V")
        || upper.equals("+5V")
        || upper.equals("+3V3")
        || upper.equals("+12V")
        || upper.equals("-5V")
        || upper.equals("-12V")
        || upper.equals("VBAT")
        || upper.equals("VREF")
        || upper.equals("VREFP")
        || upper.equals("VREFN")
        || upper.equals("VCORE")
        || upper.equals("VIO")
        || upper.equals("VPLL")
        || upper.equals("VCCIO")
        || upper.equals("VCCINT")
        || upper.equals("VCCAUX")
        || upper.equals("电源")
        || upper.equals("VCC网络")
        || upper.startsWith("VCC_")
        || upper.startsWith("VCC-")
        || upper.startsWith("VDD_")
        || upper.startsWith("VDD-")
        || upper.startsWith("VEE_")
        || upper.startsWith("+")
        || upper.startsWith("-")
        || upper.endsWith("V")
        || upper.endsWith("VCC")
        || upper.endsWith("VDD")
        || upper.contains("_VCC")
        || upper.contains("-VCC")
        || upper.contains("_VDD")
        || upper.contains("-VDD")
        || upper.contains("_POWER")
        || upper.contains("_PWR")
        || upper.contains("-POWER")
        || upper.contains("-PWR")
        || upper.startsWith("PWR_")
        || upper.startsWith("PWR-")
        || upper.equals("POWER")
        || upper.equals("VPOWER")
        || upper.contains("电源")
        || upper.contains("供电")
        || upper.contains("POWER")
        || upper.matches("^[0-9.]+V$")              // e.g. "3.3V", "5V", "12V"
        || upper.matches("^[0-9.]+V[0-9]+$")         // e.g. "1.8V2", "3.3V1"
        || upper.matches("^[+-]?[0-9.]+V$")          // e.g. "+5V", "-12V"
        || upper.matches("^V[0-9]+_[0-9]+V?$")       // e.g. "V3_3", "V1_8"
        || upper.matches("^VDD[0-9A-Z_]+$")          // e.g. "VDD3V3", "VDD_1V8"
        || upper.matches("^VCC[0-9A-Z_]+$")          // e.g. "VCC3V3", "VCC_1V8"
        || upper.matches("^V[0-9]+P[0-9]+[A-Z]*$")  // e.g. "V3P3", "V1P8"
        || upper.matches("^V[0-9]+N[0-9]+[A-Z]*$")  // e.g. "V3N3"
        || upper.matches("^V[0-9]+[A-Z]*$")          // e.g. "V33", "V18", "V5"
        || upper.matches("^[0-9]+V[0-9A-Z]*$")       // e.g. "33V", "5VA", "12VIO"
        || upper.matches("^V[A-Z0-9_]+[0-9]+.*$")) { // e.g. "VDD_3V3", "VCC3V3"
      return POWER;
    }

    // Extended regex patterns for common power
    if (upper.matches(".*[0-9]+V[0-9]*")
        || upper.matches("V[0-9]+_[0-9]+")
        || upper.matches("V[0-9]+P[0-9]+")
        || upper.matches("V[0-9]+N[0-9]+")
        || upper.matches(".*V[A-Z]*$")
        || upper.matches("V[A-Z]{2,4}[0-9]?[0-9]?")) {
      return POWER;
    }

    return SIGNAL;
  }

  /**
   * Returns true if this net type is a power or ground net.
   */
  public boolean isPowerOrGround() {
    return this == POWER || this == GROUND;
  }

  /**
   * Returns a Chinese display name for this net type.
   */
  public String getChineseName() {
    switch (this) {
      case POWER: return "电源";
      case GROUND: return "地";
      default: return "信号";
    }
  }
}
