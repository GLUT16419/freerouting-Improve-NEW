package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.BasicBoard;
import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.IntBox;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BottleneckAnalyzerTest {

  @Test
  void bottleneckRegionCreatedCorrectly() {
    Set<Integer> nets = new HashSet<>(Arrays.asList(1, 2, 3));
    BottleneckAnalyzer.BottleneckRegion region =
        new BottleneckAnalyzer.BottleneckRegion(0, 100, 200, 300, 400, nets, 3);
    assertEquals(0, region.id);
    assertEquals(3, region.getNetCount());
    assertFalse(region.resolved);
  }

  @Test
  void emptyBottleneckIsEmpty() {
    BottleneckAnalyzer.BottleneckRegion region =
        new BottleneckAnalyzer.BottleneckRegion(0, 0, 0, 10, 10, new HashSet<>(), 0);
    assertTrue(region.isEmpty());
  }

  @Test
  void densityScoreIsCalculated() {
    Set<Integer> nets = new HashSet<>(Arrays.asList(1, 2));
    BottleneckAnalyzer.BottleneckRegion region =
        new BottleneckAnalyzer.BottleneckRegion(0, 0, 0, 100, 100, nets, 2);
    assertTrue(region.densityScore > 0);
  }

  @Test
  void markResolvedUpdatesState() {
    Set<Integer> nets = new HashSet<>(Arrays.asList(1));
    BottleneckAnalyzer.BottleneckRegion region =
        new BottleneckAnalyzer.BottleneckRegion(0, 0, 0, 10, 10, nets, 1);
    region.resolved = true;
    region.resolvedCount = 1;
    assertTrue(region.resolved);
    assertEquals(1, region.resolvedCount);
  }

  @Test
  void identifyBottlenecksWithEmptyNetsReturnsEmpty() {
    var board = createBoard();
    BottleneckAnalyzer analyzer = new BottleneckAnalyzer(board);
    var result = analyzer.identifyBottlenecks(new HashSet<>());
    assertTrue(result.isEmpty());
  }

  @Test
  void identifyBottlenecksWithFewNetsReturnsEmpty() {
    var board = createBoard();
    BottleneckAnalyzer analyzer = new BottleneckAnalyzer(board);
    Set<Integer> fewNets = new HashSet<>();
    fewNets.add(1);
    var result = analyzer.identifyBottlenecks(fewNets);
    assertTrue(result.isEmpty());
  }

  private static BasicBoard createBoard() {
    Layer[] layers = {new Layer("TOP", true), new Layer("BOT", true)};
    LayerStructure ls = new LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new IntBox(0, 0, 400000, 400000), ls,
        new app.freerouting.geometry.planar.PolylineShape[]{
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, 400000, 400000)
        }, 0, rules, comm);
  }
}
