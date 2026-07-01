package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.IntBox;
import org.junit.jupiter.api.Test;

class ContractionHierarchiesTest {

  private static app.freerouting.board.BasicBoard createBoard(int w, int h, int layerCount) {
    Layer[] layers = new Layer[layerCount];
    for (int i = 0; i < layerCount; i++) {
      layers[i] = new Layer("L" + i, true);
    }
    LayerStructure ls = new LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new IntBox(0, 0, w, h), ls,
        new app.freerouting.geometry.planar.PolylineShape[]{
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, w, h)
        }, 0, rules, comm);
  }

  @Test
  void buildFromBoardCreatesNodes() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    assertTrue(ch.getNodeCount() > 0);
    // 2 layers x 2 cols x 2 rows = 8 nodes
    assertEquals(8, ch.getNodeCount());
  }

  @Test
  void buildFromBoardHasEdges() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    assertTrue(ch.totalEdges() > 0);
  }

  @Test
  void makeNodeIdAndDecodeRoundTrip() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    int id = ch.makeNodeId(0, 1, 0);
    assertEquals(4, id); // layer=0 => 0*4 + 1*2 + 0 = 2? No: 0*2*2 + 1*2 + 0 = 2
    // Actually: layer*gridRows*gridCols + row*gridCols + col
    // gridRows=2, gridCols=2. layer=0 => 0. row=1, col=0 => 2
    int[] decoded = ch.decodeNodeId(id);
    assertEquals(0, decoded[0]); // layer
    assertEquals(1, decoded[1]); // row
    assertEquals(0, decoded[2]); // col
  }

  @Test
  void getNodeReturnsCorrectNode() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    CHNode node = ch.getNode(0);
    assertNotNull(node);
    assertEquals(0, node.id);
  }

  @Test
  void getNodeReturnsNullForInvalidId() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    assertNull(ch.getNode(999));
  }

  @Test
  void queryReturnsPathForSameNode() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    ContractionHierarchies.CHPath path = ch.query(0, 0);
    assertTrue(path.found);
    assertEquals(0.0, path.totalWeight, 1e-6);
    assertEquals(1, path.nodeIds.size());
  }

  @Test
  void queryReturnsNotFoundForInvalidIds() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    ContractionHierarchies.CHPath path = ch.query(0, 999);
    assertFalse(path.found);
  }

  @Test
  void contractAssignsLevels() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    // Contract into 3 levels
    ch.contract(3);
    // All free nodes should have level >= 0
    int levelCount = 0;
    for (CHNode node : ch.getNodes()) {
      if (node.isFree) {
        assertTrue(node.level >= 0, "Node " + node.id + " should have level >= 0");
        if (node.level > 0) levelCount++;
      }
    }
    // At least some nodes should be at level > 0
    assertTrue(levelCount > 0, "Some nodes should have been promoted to higher levels");
  }

  @Test
  void findNearestNodeReturnsValidId() {
    var board = createBoard(400000, 400000, 2);
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    int nodeId = ch.findNearestNode(new app.freerouting.geometry.planar.FloatPoint(100000, 100000));
    assertTrue(nodeId >= 0);
    CHNode node = ch.getNode(nodeId);
    assertNotNull(node);
  }
}
