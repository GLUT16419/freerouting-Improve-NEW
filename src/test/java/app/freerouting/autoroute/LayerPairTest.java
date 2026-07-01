package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayerPairTest {

  @Test
  void buildForBoard2LayerCreatesSingleSurfacePair() {
    List<LayerPair> pairs = LayerPair.buildForBoard(2);
    assertEquals(1, pairs.size());
    assertEquals(LayerPair.GRADE_SURFACE, pairs.get(0).grade);
    assertEquals("surface", pairs.get(0).gradeName);
  }

  @Test
  void buildForBoard4LayerCreatesSurfaceAndSubway() {
    List<LayerPair> pairs = LayerPair.buildForBoard(4);
    assertEquals(2, pairs.size());
    assertEquals(LayerPair.GRADE_SURFACE, pairs.get(0).grade);
    assertEquals(LayerPair.GRADE_SUBWAY, pairs.get(1).grade);
  }

  @Test
  void buildForBoard6LayerCreatesThreeGrades() {
    List<LayerPair> pairs = LayerPair.buildForBoard(6);
    assertEquals(3, pairs.size());
    assertEquals(LayerPair.GRADE_SURFACE, pairs.get(0).grade);
    assertEquals(LayerPair.GRADE_ELEVATED, pairs.get(1).grade);
    assertEquals(LayerPair.GRADE_SUBWAY, pairs.get(2).grade);
  }

  @Test
  void containsLayerReturnsTrueForPrimary() {
    LayerPair pair = new LayerPair(0, 1, 2, "H", "V", false,
        List.of("数字"), 1e9, LayerPair.GRADE_SUBWAY, "subway");
    assertTrue(pair.containsLayer(1));
    assertTrue(pair.containsLayer(2));
    assertFalse(pair.containsLayer(0));
  }

  @Test
  void isSurfaceElevatedSubwayHelpers() {
    LayerPair surface = new LayerPair(0, 0, 1, "V", "H", false,
        List.of("低速"), 1e9, LayerPair.GRADE_SURFACE, "surface");
    assertTrue(surface.isSurface());
    assertFalse(surface.isElevated());
    assertFalse(surface.isSubway());

    LayerPair subway = new LayerPair(1, 2, 3, "H", "V", true,
        List.of("高速"), 1e9, LayerPair.GRADE_SUBWAY, "subway");
    assertTrue(subway.isSubway());
    assertFalse(subway.isSurface());
  }

  @Test
  void assignmentCostLowerForMatchingGrade() {
    LayerPair surface = new LayerPair(0, 0, 1, "V", "H", false,
        List.of("低速"), 1e9, LayerPair.GRADE_SURFACE, "surface");
    LayerPair subway = new LayerPair(1, 2, 3, "H", "V", true,
        List.of("高速"), 1e9, LayerPair.GRADE_SUBWAY, "subway");

    // For a high-speed cluster, subway should be cheaper
    double surfaceCost = surface.assignmentCost(5, 100, 50,
        "subway", true, false);
    double subwayCost = subway.assignmentCost(5, 100, 50,
        "subway", true, false);
    assertTrue(subwayCost < surfaceCost,
        "Subway pair should cost less for high-speed nets");
  }

  @Test
  void updateRemainingCapacityWorks() {
    LayerPair pair = new LayerPair(0, 0, 1, "V", "H", false,
        List.of("数字"), 1e9);
    assertEquals(0.0, pair.currentOccupancy, 1e-6);
    pair.updateRemainingCapacity(500.0);
    assertEquals(500.0, pair.currentOccupancy, 1e-6);
    assertTrue(pair.remainingCapacity < pair.maxUsableArea);
  }

  @Test
  void resetOccupancyClearsCounters() {
    LayerPair pair = new LayerPair(0, 0, 1, "V", "H", false,
        List.of("数字"), 1e9);
    pair.updateRemainingCapacity(500.0);
    pair.avgCongestion = 0.5;
    pair.resetOccupancy();
    assertEquals(0.0, pair.currentOccupancy, 1e-6);
    assertEquals(pair.maxUsableArea, pair.remainingCapacity, 1e-6);
    assertEquals(0.0, pair.avgCongestion, 1e-6);
  }

  @Test
  void gradeFromNameReturnsCorrectConstant() {
    assertEquals(LayerPair.GRADE_SURFACE, LayerPair.gradeFromName("surface"));
    assertEquals(LayerPair.GRADE_SURFACE, LayerPair.gradeFromName("Surface"));
    assertEquals(LayerPair.GRADE_ELEVATED, LayerPair.gradeFromName("elevated"));
    assertEquals(LayerPair.GRADE_SUBWAY, LayerPair.gradeFromName("subway"));
    assertEquals(LayerPair.GRADE_ELEVATED, LayerPair.gradeFromName("unknown"));
  }

  @Test
  void toStringContainsGradeInfo() {
    LayerPair pair = new LayerPair(0, 0, 1, "V", "H", false,
        List.of("数字"), 1e9, LayerPair.GRADE_SURFACE, "surface");
    String str = pair.toString();
    assertTrue(str.contains("Surface"));
    assertTrue(str.contains("LP#0"));
  }
}
