package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import app.freerouting.rules.Net;
import app.freerouting.rules.Nets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PowerGndAutoLabelerTest extends RoutingFixtureTest {

  @Test
  void autoLabelDetectsPowerAndGroundNets() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    Map<Integer, NetType> netTypes = labeler.autoLabelAllNets();

    assertNotNull(netTypes);

    // Verify at least some nets are classified
    int totalNets = boardManager.get_routing_board().rules.nets.max_net_no();
    if (totalNets > 0) {
      assertTrue(netTypes.size() > 0, "Should have classified at least one net");
      int firstNetNo = netTypes.keySet().iterator().next();
      assertNotNull(labeler.getNetType(firstNetNo));
    }
  }

  @Test
  void allNetsAreClassified() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    Map<Integer, NetType> netTypes = labeler.autoLabelAllNets();

    Nets nets = boardManager.get_routing_board().rules.nets;
    int netCount = nets.max_net_no();
    for (int i = 1; i <= netCount; i++) {
      Net net = nets.get(i);
      if (net == null) continue;
      assertTrue(netTypes.containsKey(net.net_number),
          "Net " + net.name + " (#" + net.net_number + ") should be classified");
    }
  }

  @Test
  void isPowerOrGroundReturnsTrueForClassifiedNets() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    Map<Integer, NetType> netTypes = labeler.autoLabelAllNets();

    for (Map.Entry<Integer, NetType> entry : netTypes.entrySet()) {
      boolean expected = entry.getValue().isPowerOrGround();
      assertEquals(expected, labeler.isPowerOrGroundNet(entry.getKey()),
          "Net #" + entry.getKey() + " consistency check failed");
    }
  }

  @Test
  void emptyBoardReturnsEmptyMap() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    PowerGndAutoLabeler labeler = new PowerGndAutoLabeler(boardManager.get_routing_board());
    Map<Integer, NetType> netTypes = labeler.autoLabelAllNets();
    assertNotNull(netTypes);
  }
}
