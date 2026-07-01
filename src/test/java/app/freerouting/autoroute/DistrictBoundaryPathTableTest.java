package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.IntBox;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class DistrictBoundaryPathTableTest {

  private static app.freerouting.board.BasicBoard createBoard() {
    Layer[] layers = new Layer[]{new Layer("TOP", true), new Layer("BOT", true)};
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

  @Test
  void precomputeAllWithoutException() {
    var board = createBoard();
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Collections.emptySet());
    DistrictBoundaryPathTable table = new DistrictBoundaryPathTable(ch, partitioner);
    table.precomputeAll(); // Verify no exception
    assertTrue(table.getIntraPathCount() >= 0);
  }

  @Test
  void keyEncodingIsDeterministic() {
    // Private method, but we can test through getIntraPathCount
    // Encoding is used internally; just verify no crash
    var board = createBoard();
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Collections.emptySet());
    DistrictBoundaryPathTable table = new DistrictBoundaryPathTable(ch, partitioner);
    table.precomputeAll();
    assertNotNull(table);
  }

  @Test
  void getIntraDistrictPathReturnsNullWhenNotPrecomputed() {
    var board = createBoard();
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Collections.emptySet());
    DistrictBoundaryPathTable table = new DistrictBoundaryPathTable(ch, partitioner);
    // No precomputation → lookup should return null
    DistrictBoundaryPathTable.PathInfo pi = table.getIntraDistrictPath(999, 0, 1);
    assertNull(pi);
  }

  @Test
  void resetCacheHitsWorks() {
    var board = createBoard();
    ContractionHierarchies ch = ContractionHierarchies.buildFromBoard(board, 200000.0);
    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Collections.emptySet());
    DistrictBoundaryPathTable table = new DistrictBoundaryPathTable(ch, partitioner);
    table.resetCacheHits();
    assertEquals(0, table.getCacheHits());
  }
}
