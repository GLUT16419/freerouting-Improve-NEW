package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.geometry.planar.IntBox;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpatioTemporalOccupancyMapTest {

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
  void initiallyEmpty() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    assertEquals(0, stom.getOccupiedCellCount());
    assertEquals(0, stom.getTotalReservations());
    assertEquals(0, stom.getCurrentTimeStep());
  }

  @Test
  void reservePathIncrementsTimeStep() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    List<Long> cells = new ArrayList<>();
    cells.add(SpatioTemporalOccupancyMap.cellKey(0, 1, 1));
    cells.add(SpatioTemporalOccupancyMap.cellKey(0, 1, 2));

    int ts = stom.reserve(cells);
    assertEquals(0, ts);
    assertEquals(1, stom.getCurrentTimeStep());
  }

  @Test
  void isOccupiedReturnsTrueAfterReservation() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    List<Long> cells = List.of(SpatioTemporalOccupancyMap.cellKey(0, 1, 1));
    stom.reserve(cells);
    assertTrue(stom.isOccupied(0, 1, 1, 0));
    assertFalse(stom.isOccupied(0, 1, 1, 1)); // not reserved at time 1
  }

  @Test
  void reserveAtSpecificTimeWorks() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    List<Long> cells = List.of(SpatioTemporalOccupancyMap.cellKey(0, 0, 0));
    stom.reserveAt(cells, 5);
    assertTrue(stom.isOccupied(0, 0, 0, 5));
  }

  @Test
  void releaseClearsOccupancy() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    List<Long> cells = List.of(SpatioTemporalOccupancyMap.cellKey(0, 1, 1));
    int ts = stom.reserve(cells);
    assertTrue(stom.isOccupied(0, 1, 1, ts));
    stom.release(ts);
    assertFalse(stom.isOccupied(0, 1, 1, ts));
  }

  @Test
  void getOccupancyCountReturnsCorrectCount() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    long key = SpatioTemporalOccupancyMap.cellKey(0, 2, 2);
    // Reserve same cell at two time steps
    stom.reserveAt(List.of(key), 0);
    stom.reserveAt(List.of(key), 1);
    assertEquals(2, stom.getOccupancyCount(0, 2, 2));
  }

  @Test
  void pathToCellKeysReturnsBresenhamPath() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    // From (0,0) to (400000,400000) - diagonal across the board
    List<Long> keys = stom.pathToCellKeys(0, 0, 400000, 400000, 0);
    assertFalse(keys.isEmpty());
    // Should contain start and end cells
    long startKey = SpatioTemporalOccupancyMap.cellKey(0, 0, 0);
    long endKey = SpatioTemporalOccupancyMap.cellKey(0, 1, 1);
    assertTrue(keys.contains(startKey));
    assertTrue(keys.contains(endKey));
  }

  @Test
  void cellKeyEncodingRoundTrip() {
    long key = SpatioTemporalOccupancyMap.cellKey(3, 100, 200);
    int[] decoded = SpatioTemporalOccupancyMap.decodeCellKey(key);
    assertEquals(3, decoded[0]);
    assertEquals(100, decoded[1]);
    assertEquals(200, decoded[2]);
  }

  @Test
  void clearRemovesAllReservations() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    stom.reserve(List.of(SpatioTemporalOccupancyMap.cellKey(0, 0, 0)));
    stom.reserve(List.of(SpatioTemporalOccupancyMap.cellKey(0, 0, 1)));
    assertTrue(stom.getTotalReservations() > 0);
    stom.clear();
    assertEquals(0, stom.getTotalReservations());
    assertEquals(0, stom.getOccupiedCellCount());
    assertEquals(0, stom.getCurrentTimeStep());
  }

  @Test
  void logSummaryDoesNotThrow() {
    var board = createBoard();
    SpatioTemporalOccupancyMap stom = new SpatioTemporalOccupancyMap(board, 200000.0);
    stom.reserve(List.of(SpatioTemporalOccupancyMap.cellKey(0, 0, 0)));
    stom.logSummary(); // Just verify no exception
  }
}
