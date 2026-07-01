package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class ProbabilisticCongestionEstimatorTest {

  @Test
  void estimateWithEmptyNets() {
    var board = createBoard();
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    estimator.estimate(new HashSet<>());
    assertEquals(0.0, estimator.getMaxCongestion(), 1e-6);
  }

  @Test
  void getCongestionAtReturnsValue() {
    var board = createBoard();
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    estimator.estimate(new HashSet<>());
    assertEquals(0.0, estimator.getCongestionAt(100000, 100000), 1e-6);
  }

  @Test
  void getGradientReturnsTwoValues() {
    var board = createBoard();
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    estimator.estimate(new HashSet<>());
    double[] grad = estimator.getGradientAt(100000, 100000);
    assertEquals(2, grad.length);
  }

  @Test
  void getDetourGuidePointsReturnsEmptyWhenNoCongestion() {
    var board = createBoard();
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 200000.0);
    estimator.estimate(new HashSet<>());
    assertTrue(estimator.getDetourGuidePoints(0.8).isEmpty());
  }

  @Test
  void getCellSizeMatchesConstructor() {
    var board = createBoard();
    ProbabilisticCongestionEstimator estimator =
        new ProbabilisticCongestionEstimator(board, 100000.0);
    assertEquals(100000.0, estimator.getCellSize(), 1e-6);
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
}
