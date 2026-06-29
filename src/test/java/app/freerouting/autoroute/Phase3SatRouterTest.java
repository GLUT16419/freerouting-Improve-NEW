package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.IntBox;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase3SatRouterTest {

  private static app.freerouting.board.RoutingBoard createBoard() {
    Layer[] layers = new Layer[] {
        new Layer("TOP", true), new Layer("BOTTOM", true)
    };
    LayerStructure ls = new LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new IntBox(0, 0, 100000, 100000), ls,
        new app.freerouting.geometry.planar.PolylineShape[] {
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, 100000, 100000)
        }, 0, rules, comm);
  }

  @Test
  void emptyUnroutedNetsReturnsEmptyResult() {
    var board = createBoard();
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel costModel = new CostModel(board, cm);
    Phase3SatRouter router = new Phase3SatRouter(board, cm, costModel, null);
    Set<Integer> result = router.routeRemaining(Collections.emptySet());
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  void relaxationLevelStartsAtZero() {
    var board = createBoard();
    CongestionMap cm = new CongestionMap(board, 50000.0);
    CostModel costModel = new CostModel(board, cm);
    Phase3SatRouter router = new Phase3SatRouter(board, cm, costModel, null);
    assertEquals(0, router.getRelaxationLevel());
  }
}
