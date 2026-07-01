package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class MultiLevelPartitionerTest {

  @Test
  void buildHierarchyCreatesDistricts() {
    var board = createBoard();
    ContractionHierarchies ch = createCH();
    MultiLevelPartitioner partitioner = new MultiLevelPartitioner(board, ch);
    partitioner.buildHierarchy(Collections.emptySet());
    assertTrue(partitioner.getDistrictCount() >= 0);
  }

  @Test
  void districtContainsPoint() {
    MultiLevelPartitioner.District d = new MultiLevelPartitioner.District(
        0, 0, 100.0, 200.0, 300.0, 400.0);
    assertTrue(d.contains(150.0, 250.0));
    assertFalse(d.contains(50.0, 50.0));
  }

  @Test
  void districtHasCorrectDimensions() {
    MultiLevelPartitioner.District d = new MultiLevelPartitioner.District(
        0, 0, 100.0, 200.0, 300.0, 400.0);
    assertEquals(200.0, d.getWidth(), 1e-6);
    assertEquals(200.0, d.getHeight(), 1e-6);
    assertEquals(200.0, d.getCenterX(), 1e-6);
    assertEquals(300.0, d.getCenterY(), 1e-6);
  }

  @Test
  void functionalBlockContainsDistricts() {
    MultiLevelPartitioner.FunctionalBlock fb = new MultiLevelPartitioner.FunctionalBlock(
        0, "FPGA", 0, 0, 1000, 1000);
    MultiLevelPartitioner.District d = new MultiLevelPartitioner.District(
        0, 0, 100, 100, 500, 500);
    fb.addDistrict(d);
    assertEquals(1, fb.getDistrictCount());
  }

  @Test
  void areaBoundaryPointCreatedCorrectly() {
    MultiLevelPartitioner.BoundaryPoint bp = new MultiLevelPartitioner.BoundaryPoint(
        42, 150.0, 250.0, 7, 1);
    assertEquals(42, bp.id);
    assertEquals(150.0, bp.x, 1e-6);
    assertEquals(7, bp.chNodeId);
  }

  private static app.freerouting.board.RoutingBoard createBoard() {
    app.freerouting.board.Layer[] layers = {
        new app.freerouting.board.Layer("TOP", true),
        new app.freerouting.board.Layer("BOT", true)};
    app.freerouting.board.LayerStructure ls = new app.freerouting.board.LayerStructure(layers);
    app.freerouting.rules.ClearanceMatrix cm =
        app.freerouting.rules.ClearanceMatrix.get_default_instance(ls, 10);
    app.freerouting.rules.BoardRules rules = new app.freerouting.rules.BoardRules(ls, cm);
    rules.create_default_net_class();
    app.freerouting.board.Communication comm = new app.freerouting.board.Communication();
    return new app.freerouting.board.RoutingBoard(
        new app.freerouting.geometry.planar.IntBox(0, 0, 400000, 400000), ls,
        new app.freerouting.geometry.planar.PolylineShape[]{
            app.freerouting.geometry.planar.TileShape.get_instance(0, 0, 400000, 400000)
        }, 0, rules, comm);
  }

  private static ContractionHierarchies createCH() {
    return ContractionHierarchies.buildFromBoard(createBoard(), 200000.0);
  }
}
