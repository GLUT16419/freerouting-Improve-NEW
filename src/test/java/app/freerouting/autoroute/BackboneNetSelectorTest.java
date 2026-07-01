package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackboneNetSelectorTest {

  @Test
  void backboneNetCreatedCorrectly() {
    BackboneNetSelector.BackboneNet bn =
        new BackboneNetSelector.BackboneNet(1, BackboneNetSelector.PRIORITY_CRITICAL,
            5000.0, 0.8, true, false, true, "CLK_P");
    assertEquals(1, bn.netNo);
    assertEquals(BackboneNetSelector.PRIORITY_CRITICAL, bn.priority);
    assertTrue(bn.isDifferential);
    assertFalse(bn.routed);
  }

  @Test
  void priorityConstantsAreOrdered() {
    assertTrue(BackboneNetSelector.PRIORITY_CRITICAL > BackboneNetSelector.PRIORITY_HIGH);
    assertTrue(BackboneNetSelector.PRIORITY_HIGH > BackboneNetSelector.PRIORITY_MEDIUM);
    assertTrue(BackboneNetSelector.PRIORITY_MEDIUM > BackboneNetSelector.PRIORITY_LOW);
    assertTrue(BackboneNetSelector.PRIORITY_LOW > BackboneNetSelector.PRIORITY_POWER_GND);
  }

  @Test
  void selectBackboneNetsWithEmptyInput() {
    var board = createMinimalBoard();
    BackboneNetSelector selector = new BackboneNetSelector(board, null);
    List<BackboneNetSelector.BackboneNet> result = selector.selectBackboneNets(Collections.emptySet());
    assertTrue(result.isEmpty());
  }

  @Test
  void getBackboneNetsReturnsUnmodifiableAfterSelection() {
    var board = createMinimalBoard();
    BackboneNetSelector selector = new BackboneNetSelector(board, null);
    selector.selectBackboneNets(Collections.emptySet());
    assertThrows(UnsupportedOperationException.class,
        () -> selector.getBackboneNets().add(null));
  }

  private static app.freerouting.board.RoutingBoard createMinimalBoard() {
    app.freerouting.board.Layer[] layers = {new app.freerouting.board.Layer("TOP", true)};
    app.freerouting.board.LayerStructure ls = new app.freerouting.board.LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new app.freerouting.geometry.planar.IntBox(0, 0, 1000, 1000), ls,
        new app.freerouting.geometry.planar.PolylineShape[]{
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, 1000, 1000)
        }, 0, rules, comm);
  }
}
