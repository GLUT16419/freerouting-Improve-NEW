package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LayerFunctionTest {

  @Test
  void signalLayerNames() {
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("TOP"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("BOTTOM"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("SIGNAL1"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("SIGNAL_2"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("Inner1"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("L1"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("Layer1"));
    assertEquals(LayerFunction.SIGNAL, LayerFunction.fromName("ROUTING"));
  }

  @Test
  void groundPlaneLayerNames() {
    assertEquals(LayerFunction.GROUND_PLANE, LayerFunction.fromName("GND"));
    assertEquals(LayerFunction.GROUND_PLANE, LayerFunction.fromName("GROUND"));
    assertEquals(LayerFunction.GROUND_PLANE, LayerFunction.fromName("GND_PLANE"));
    assertEquals(LayerFunction.GROUND_PLANE, LayerFunction.fromName("VSS"));
    assertEquals(LayerFunction.GROUND_PLANE, LayerFunction.fromName("PWR/GND"));
  }

  @Test
  void powerPlaneLayerNames() {
    assertEquals(LayerFunction.POWER_PLANE, LayerFunction.fromName("POWER"));
    assertEquals(LayerFunction.POWER_PLANE, LayerFunction.fromName("VCC"));
    assertEquals(LayerFunction.POWER_PLANE, LayerFunction.fromName("VDD"));
    assertEquals(LayerFunction.POWER_PLANE, LayerFunction.fromName("PLANE1"));
    assertEquals(LayerFunction.POWER_PLANE, LayerFunction.fromName("PWR_PLANE"));
  }

  @Test
  void mechanicalLayerNames() {
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("MECH1"));
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("MECHANICAL_1"));
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("EDGE_CUTS"));
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("BOARD_OUTLINE"));
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("DIMENSIONS"));
    assertEquals(LayerFunction.MECHANICAL, LayerFunction.fromName("FAB_LAYER"));
  }

  @Test
  void solderMaskLayerNames() {
    assertEquals(LayerFunction.SOLDER_MASK, LayerFunction.fromName("SOLDERMASK_TOP"));
    assertEquals(LayerFunction.SOLDER_MASK, LayerFunction.fromName("SM_BOTTOM"));
    assertEquals(LayerFunction.SOLDER_MASK, LayerFunction.fromName("SOLDER_RESIST"));
    assertEquals(LayerFunction.SOLDER_MASK, LayerFunction.fromName("MASK"));
  }

  @Test
  void silkscreenLayerNames() {
    assertEquals(LayerFunction.SILKSCREEN, LayerFunction.fromName("SILKSCREEN_TOP"));
    assertEquals(LayerFunction.SILKSCREEN, LayerFunction.fromName("SILK_SCREEN"));
    assertEquals(LayerFunction.SILKSCREEN, LayerFunction.fromName("LEGEND"));
    assertEquals(LayerFunction.SILKSCREEN, LayerFunction.fromName("OVERLAY"));
    assertEquals(LayerFunction.SILKSCREEN, LayerFunction.fromName("FAB_SILKSCREEN"));
  }

  @Test
  void nullReturnsOther() {
    assertEquals(LayerFunction.OTHER, LayerFunction.fromName(null));
  }

  @Test
  void emptyReturnsOther() {
    assertEquals(LayerFunction.OTHER, LayerFunction.fromName(""));
  }

  @Test
  void routableClassification() {
    assertTrue(LayerFunction.SIGNAL.isRoutable());
    assertTrue(LayerFunction.MIXED.isRoutable());
    assertFalse(LayerFunction.POWER_PLANE.isRoutable());
    assertFalse(LayerFunction.GROUND_PLANE.isRoutable());
    assertFalse(LayerFunction.MECHANICAL.isRoutable());
    assertFalse(LayerFunction.SOLDER_MASK.isRoutable());
  }

  @Test
  void planeClassification() {
    assertTrue(LayerFunction.POWER_PLANE.isPlane());
    assertTrue(LayerFunction.GROUND_PLANE.isPlane());
    assertFalse(LayerFunction.SIGNAL.isPlane());
    assertFalse(LayerFunction.MECHANICAL.isPlane());
  }
}
