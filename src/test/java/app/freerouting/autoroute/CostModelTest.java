package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import org.junit.jupiter.api.Test;

/**
 * Tests for CostModel using real board objects.
 */
class CostModelTest {

  private static app.freerouting.board.RoutingBoard createBoard(int width, int height, int layerCount) {
    Layer[] layers = new Layer[layerCount];
    for (int i = 0; i < layerCount; i++) {
      layers[i] = new Layer("Layer" + i, true);
    }
    LayerStructure ls = new LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new IntBox(0, 0, width, height),
        ls,
        new app.freerouting.geometry.planar.PolylineShape[] {
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, width, height)
        },
        0, rules, comm);
  }

  @Test
  void segmentCostIsPositive() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    assertTrue(model.computeSegmentCost(
        new FloatPoint(0, 0), new FloatPoint(10000, 10000), 0, false, 1.0) > 0.0);
  }

  @Test
  void shorterSegmentCostsLess() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    double shortCost = model.computeSegmentCost(
        new FloatPoint(0, 0), new FloatPoint(1000, 1000), 0, false, 1.0);
    double longCost = model.computeSegmentCost(
        new FloatPoint(0, 0), new FloatPoint(10000, 10000), 0, false, 1.0);
    assertTrue(shortCost < longCost);
  }

  @Test
  void viaCostZeroForSameLayer() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    assertEquals(0.0, model.computeViaCost(0, 0, false), 1e-6);
  }

  @Test
  void viaCostPositiveForDifferentLayers() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    assertTrue(model.computeViaCost(0, 3, false) > 0.0);
  }

  @Test
  void powerGroundGetDiscountedVias() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    assertTrue(model.computeViaCost(0, 2, true) < model.computeViaCost(0, 2, false));
  }

  @Test
  void bendCostIsPositive() {
    var board = createBoard(100000, 100000, 4);
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel model = new CostModel(board, cm);
    assertTrue(model.computeBendCost(0) > 0.0);
  }
}
