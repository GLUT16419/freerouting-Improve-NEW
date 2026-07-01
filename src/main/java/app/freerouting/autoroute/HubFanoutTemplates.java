package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.io.Serializable;
import java.util.*;

/**
 * UTPR Phase 1 — Hub fanout template library and auto-matcher for dense
 * component breakout (BGA, QFP, connector).
 * <p>
 * In the city-traffic metaphor, this is the <b>interchange construction
 * authority</b> that pre-builds standardised tunnel/ramp configurations for
 * major transport hubs (BGA = central station, connector = border crossing).
 * <p>
 * <b>Template types:</b>
 * <ul>
 *   <li><b>Dogbone (犬骨式)</b> — standard via-in-pad escape for fine-pitch BGAs</li>
 *   <li><b>Staggered (交错式)</b> — alternating via placement for higher density</li>
 *   <li><b>Coplanar (共面式)</b> — same-layer escape for coarse-pitch parts</li>
 *   <li><b>Via-in-pad (盘中孔)</b> — direct via on pad for ultra-fine pitch</li>
 * </ul>
 * <p>
 * Each template describes the via position, escape direction, and layer
 * assignment relative to the pad. The auto-matcher selects the best template
 * based on pad pitch, layer stack, and DRC rules.
 */
public class HubFanoutTemplates implements Serializable {

  private static final long serialVersionUID = 1L;

  // ── Template type constants ───────────────────────────────────────────

  public static final int TEMPLATE_DOGBONE = 0;
  public static final int TEMPLATE_STAGGERED = 1;
  public static final int TEMPLATE_COPLANAR = 2;
  public static final int TEMPLATE_VIA_IN_PAD = 3;

  // ═══════════════════════════════════════════════════════════════════════
  //  FanoutTemplate
  // ═══════════════════════════════════════════════════════════════════════

  /** Describes a single fanout via pattern for one pad. */
  public static class FanoutTemplate implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int type;
    public final String name;
    public final int targetLayer;      // which layer the via escapes to
    public final double viaOffsetX;    // via position offset from pad center (X)
    public final double viaOffsetY;    // via position offset from pad center (Y)
    public final double escapeLength;  // trace length from pad to via
    public final double minPitch;      // minimum pad pitch this template supports
    public final double maxPitch;      // maximum pad pitch
    public final boolean requiresBlindVia;

    public FanoutTemplate(int type, String name, int targetLayer,
                          double viaOffsetX, double viaOffsetY,
                          double escapeLength, double minPitch, double maxPitch,
                          boolean requiresBlindVia) {
      this.type = type;
      this.name = name;
      this.targetLayer = targetLayer;
      this.viaOffsetX = viaOffsetX;
      this.viaOffsetY = viaOffsetY;
      this.escapeLength = escapeLength;
      this.minPitch = minPitch;
      this.maxPitch = maxPitch;
      this.requiresBlindVia = requiresBlindVia;
    }

    public boolean supportsPitch(double pitch) {
      return pitch >= minPitch && pitch <= maxPitch;
    }

    @Override
    public String toString() {
      return String.format("Template '%s' layer=%d offset=(%.0f,%.0f) pitch=[%.0f-%.0f]",
          name, targetLayer, viaOffsetX, viaOffsetY, minPitch, maxPitch);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  FanoutInstruction
  // ═══════════════════════════════════════════════════════════════════════

  /** A concrete fanout instruction for a single pin, produced by the planner. */
  public static class FanoutInstruction implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int componentNo;
    public final int pinIndex;
    public final int netNo;
    public final FloatPoint padCenter;
    public final FloatPoint viaPosition;
    public final int targetLayer;
    public final FanoutTemplate template;
    public final int templateType;

    public FanoutInstruction(int componentNo, int pinIndex, int netNo,
                             FloatPoint padCenter, FloatPoint viaPosition,
                             int targetLayer, FanoutTemplate template) {
      this.componentNo = componentNo;
      this.pinIndex = pinIndex;
      this.netNo = netNo;
      this.padCenter = padCenter;
      this.viaPosition = viaPosition;
      this.targetLayer = targetLayer;
      this.template = template;
      this.templateType = template != null ? template.type : TEMPLATE_DOGBONE;
    }

    @Override
    public String toString() {
      return String.format("Fanout: C%d-P%d net=%d via(%.0f,%.0f) -> L%d [%s]",
          componentNo, pinIndex, netNo, viaPosition.x, viaPosition.y,
          targetLayer, template != null ? template.name : "auto");
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Template library
  // ═══════════════════════════════════════════════════════════════════════

  private final List<FanoutTemplate> templateLibrary;

  public HubFanoutTemplates(double defaultViaDrill, double defaultPadDiameter,
                             int signalLayerCount) {
    this.templateLibrary = buildTemplateLibrary(defaultViaDrill, defaultPadDiameter, signalLayerCount);
  }

  private List<FanoutTemplate> buildTemplateLibrary(double viaDrill, double padDia,
                                                     int signalLayerCount) {
    List<FanoutTemplate> lib = new ArrayList<>();
    double halfPad = padDia / 2.0;
    double halfVia = viaDrill / 2.0;
    double minSpacing = halfPad + halfVia + 1000; // ~10um clearance

    // Dogbone templates for various pitch ranges
    lib.add(new FanoutTemplate(TEMPLATE_DOGBONE, "Dogbone-Tight",
        1, minSpacing, 0, minSpacing,
        200_000, 500_000, false));
    lib.add(new FanoutTemplate(TEMPLATE_DOGBONE, "Dogbone-Standard",
        1, halfPad + halfVia + 2000, 0, halfPad + halfVia + 2000,
        500_000, 800_000, false));
    lib.add(new FanoutTemplate(TEMPLATE_DOGBONE, "Dogbone-Loose",
        1, halfPad + halfVia + 3000, 0, halfPad + halfVia + 3000,
        800_000, 1_500_000, false));

    // Staggered templates (alternating directions)
    lib.add(new FanoutTemplate(TEMPLATE_STAGGERED, "Staggered-Standard",
        1, halfPad + halfVia + 2000, halfPad + halfVia + 2000,
        3000, 300_000, 600_000, false));

    // Coplanar (no via — stay on same layer)
    int surfaceLayer = signalLayerCount > 0 ? 0 : 0;
    lib.add(new FanoutTemplate(TEMPLATE_COPLANAR, "Coplanar",
        surfaceLayer, 0, halfPad + 2000, 2000,
        800_000, Double.MAX_VALUE, false));

    // Via-in-pad for very fine pitch
    if (signalLayerCount >= 4) {
      lib.add(new FanoutTemplate(TEMPLATE_VIA_IN_PAD, "ViaInPad-Inner",
          2, 0, 0, 0,
          0, 250_000, true));
    }

    FRLogger.info("HubFanoutTemplates: built " + lib.size() + " templates");
    return lib;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Template matching
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Select the best fanout template for a given pad pitch.
   * Prefers the most specific (tightest-range) matching template.
   */
  public FanoutTemplate matchTemplate(double pitch) {
    FanoutTemplate best = null;
    double bestRange = Double.MAX_VALUE;

    for (FanoutTemplate t : templateLibrary) {
      if (t.supportsPitch(pitch)) {
        double range = t.maxPitch - t.minPitch;
        if (range < bestRange) {
          bestRange = range;
          best = t;
        }
      }
    }

    // Fallback: use the loosest dogbone
    if (best == null) {
      for (FanoutTemplate t : templateLibrary) {
        if (t.type == TEMPLATE_DOGBONE && (best == null || t.maxPitch > best.maxPitch)) {
          best = t;
        }
      }
    }

    return best;
  }

  // ═══════════════════════════════════════════════════════════════════════
  //  Batch fanout plan generation
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Generate a simple fanout description for a BGA-like component.
   * Uses the board's SMD pin list instead of Component API to avoid
   * dependency on the Component package internals.
   * @return list of FanoutInstructions (can be executed in parallel)
   */
  public List<FanoutInstruction> planHubFanout(RoutingBoard board, int componentNo) {
    List<FanoutInstruction> instructions = new ArrayList<>();
    // The actual fanout execution is delegated to BatchFanout;
    // this method returns the template-based plan for a given pitch.
    // Average pitch estimated from the board's SMD pins.
    double avgPitch = 500_000; // default 0.5mm
    Collection<app.freerouting.board.Pin> smdPins = board.get_smd_pins();
    if (smdPins != null && !smdPins.isEmpty()) {
      double totalDist = 0;
      int count = 0;
      app.freerouting.board.Pin prev = null;
      for (app.freerouting.board.Pin p : smdPins) {
        if (p.get_net_no(0) <= 0) continue;
        if (prev != null) {
          totalDist += p.get_center().to_float().distance(prev.get_center().to_float());
          count++;
        }
        prev = p;
      }
      if (count > 0) avgPitch = totalDist / count;
    }
    FanoutTemplate template = matchTemplate(avgPitch);
    // Create instruction with template info
    for (app.freerouting.board.Pin pin : smdPins) {
      if (pin == null) continue;
      if (pin.get_net_no(0) <= 0) continue;
      int netNo = pin.get_net_no(0);
      FloatPoint center = pin.get_center().to_float();
      instructions.add(new FanoutInstruction(
          componentNo, pin.get_component_no(), netNo,
          center, new FloatPoint(center.x, center.y),
          template.targetLayer, template));
    }
    return instructions;
  }

  /** Get the template library (read-only). */
  public List<FanoutTemplate> getLibrary() {
    return Collections.unmodifiableList(templateLibrary);
  }
}
