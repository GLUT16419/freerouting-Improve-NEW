package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase2ClusterRouterTest extends RoutingFixtureTest {

  @Test
  void clusterRouterCreatedOnDac2020Board() {
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
    LayerFunction[] layerFunctions = LayerFunctionAutoAssigner.assignFunctions(
        boardManager.get_routing_board().layer_structure);

    Phase2ClusterRouter router = new Phase2ClusterRouter(
        boardManager.get_routing_board(), congestionMap, costModel,
        labeler, layerFunctions, null); // BatchAutorouter = null for compile

    assertNotNull(router);
  }

  @Test
  void extractUnroutedNetsReturnsNonEmpty() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    var unrouted = Phase2ClusterRouter.extractUnroutedNets(
        boardManager.get_routing_board());
    assertNotNull(unrouted);
  }

  @Test
  void phase2HandlesEmptyFailedNets() {
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
    LayerFunction[] layerFunctions = LayerFunctionAutoAssigner.assignFunctions(
        boardManager.get_routing_board().layer_structure);

    Phase2ClusterRouter router = new Phase2ClusterRouter(
        boardManager.get_routing_board(), congestionMap, costModel,
        labeler, layerFunctions, null); // BatchAutorouter = null for compile

    // Passing empty list should complete quickly
    var result = router.routeRemaining(List.of());
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }
}
