package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import org.junit.jupiter.api.Test;

/**
 * Tests for CongestionMap using only real objects (no mocking).
 */
class CongestionMapTest {

  private static CongestionMap createMap(double cellSize) {
    Layer[] layers = new Layer[] {
        new Layer("TOP", true),
        new Layer("BOTTOM", true)
    };
    LayerStructure ls = new LayerStructure(layers);
    // Create a minimal stub board that provides what CongestionMap needs
    app.freerouting.board.BasicBoard board = createMinimalBoard(ls);
    return new CongestionMap(board, cellSize);
  }

  private static app.freerouting.board.BasicBoard createMinimalBoard(LayerStructure ls) {
    app.freerouting.rules.ClearanceMatrix cm = 
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    app.freerouting.board.RoutingBoard board = new app.freerouting.board.RoutingBoard(
        new IntBox(0, 0, 100000, 100000),
        ls,
        new app.freerouting.geometry.planar.PolylineShape[] {
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, 100000, 100000)
        },
        0, rules, comm);
    return board;
  }

  @Test
  void constructWithCustomCellSize() {
    CongestionMap cm = createMap(50000.0);
    assertEquals(2, cm.getGridCols());
    assertEquals(2, cm.getGridRows());
    assertEquals(50000.0, cm.getCellSize(), 1e-6);
  }

  @Test
  void defaultCellSizeIsUsed() {
    CongestionMap cm = createMap(CongestionMap.DEFAULT_CELL_SIZE);
    assertEquals(CongestionMap.DEFAULT_CELL_SIZE, cm.getCellSize(), 1e-6);
  }

  @Test
  void addAndRemoveUsage() {
    CongestionMap cm = createMap(50000.0);
    FloatPoint point = new FloatPoint(25000, 25000);
    double before = cm.getCongestion(point, 0);
    cm.addUsage(point, 0, 5.0);
    assertTrue(cm.getCongestion(point, 0) > before, "Usage should increase congestion");
    cm.removeUsage(point, 0, 5.0);
    assertEquals(before, cm.getCongestion(point, 0), 1e-6);
  }

  @Test
  void historicalCostIncrements() {
    CongestionMap cm = createMap(50000.0);
    FloatPoint point = new FloatPoint(25000, 25000);
    assertEquals(0.0, cm.getHistoricalCost(point, 0), 1e-6);
    cm.incrementHistoricalCost(point, 0);
    assertEquals(1.0, cm.getHistoricalCost(point, 0), 1e-6);
    cm.incrementHistoricalCost(point, 0);
    assertEquals(2.0, cm.getHistoricalCost(point, 0), 1e-6);
  }

  @Test
  void resetHistoricalCosts() {
    CongestionMap cm = createMap(50000.0);
    FloatPoint point = new FloatPoint(25000, 25000);
    cm.incrementHistoricalCost(point, 0);
    cm.incrementHistoricalCost(point, 1);
    cm.resetHistoricalCosts();
    assertEquals(0.0, cm.getHistoricalCost(point, 0), 1e-6);
    assertEquals(0.0, cm.getHistoricalCost(point, 1), 1e-6);
  }
}
