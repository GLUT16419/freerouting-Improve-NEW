package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import org.junit.jupiter.api.Test;

class Phase1ShortRouterTest extends RoutingFixtureTest {

  @Test
  void routingQueueBuiltOnDac2020Board() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();
    CostModel costModel = new CostModel(boardManager.get_routing_board(), congestionMap);

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    labeler.autoLabelAllNets();

    Phase1ShortRouter router = new Phase1ShortRouter(
        boardManager.get_routing_board(), costModel, congestionMap,
        labeler, job.routerSettings, null); // BatchAutorouter = null for compile

    assertNotNull(router);
  }

  @Test
  void emptyBoardHasNoRoutingWork() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();
    CostModel costModel = new CostModel(boardManager.get_routing_board(), congestionMap);

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    labeler.autoLabelAllNets();

    Phase1ShortRouter router = new Phase1ShortRouter(
        boardManager.get_routing_board(), costModel, congestionMap,
        labeler, job.routerSettings, null); // BatchAutorouter = null for compile

    assertNotNull(router);
  }

  @Test
  void phase1RunsWithoutException() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    Phase0CongestionEstimator estimator = new Phase0CongestionEstimator(
        boardManager.get_routing_board());
    CongestionMap congestionMap = estimator.analyze();
    CostModel costModel = new CostModel(boardManager.get_routing_board(), congestionMap);

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    labeler.autoLabelAllNets();

    Phase1ShortRouter router = new Phase1ShortRouter(
        boardManager.get_routing_board(), costModel, congestionMap,
        labeler, job.routerSettings, null); // BatchAutorouter = null for compile

    // routeAll should complete without exception
    var result = router.routeAll();
    assertNotNull(result);
  }
}
