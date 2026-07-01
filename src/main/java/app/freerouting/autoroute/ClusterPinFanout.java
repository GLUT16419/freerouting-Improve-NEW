package app.freerouting.autoroute;

import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;
import app.freerouting.core.StoppableThread;
import app.freerouting.rules.Net;

import java.util.*;

/**
 * V6: Cluster pin fanout for BGA, SMD, and differential pairs.
 *
 * Uses BatchAutorouter.routeNet() for targeted per-net fanout routing
 * within a cluster, falling back to a short BatchAutorouter.runBatchLoop()
 * if per-net routing is insufficient.
 */
public class ClusterPinFanout {

  private final RoutingBoard board;
  private final RouterSettings settings;
  private final StoppableThread thread;

  public ClusterPinFanout(RoutingBoard board, RouterSettings settings, StoppableThread thread) {
    this.board = board;
    this.settings = settings;
    this.thread = thread;
  }

  /**
   * Fan out all SMD pins in the given cluster's nets.
   * @return number of successfully fanned-out pins
   */
  public int fanoutCluster(NetCluster cluster) {
    if (cluster == null || cluster.getNetNumbers().isEmpty()) return 0;

    Set<Integer> netNumbers = new HashSet<>(cluster.getNetNumbers());

    // Count SMD pins needing fanout
    Collection<Pin> allSmdPins = board.get_smd_pins();
    if (allSmdPins == null || allSmdPins.isEmpty()) return 0;

    List<Pin> pinsToFanout = new ArrayList<>();
    for (Pin pin : allSmdPins) {
      for (int netNo : netNumbers) {
        if (pin.contains_net(netNo)) {
          pinsToFanout.add(pin);
          break;
        }
      }
    }
    if (pinsToFanout.isEmpty()) return 0;

    // Detect density and differential pairs
    double avgPinSpacing = estimateAveragePinSpacing(pinsToFanout);
    boolean isDenseBga = avgPinSpacing < 150000;
    Set<String> diffPairNames = detectDifferentialPairs(netNumbers);

    // Route each net through a targeted BatchAutorouter trial
    int fannedOut = 0;
    for (int netNo : netNumbers) {
      if (thread.is_stop_auto_router_requested()) break;

      try {
        // Create a trial router for this net with fanout enabled
        StoppableThread ct = RegionRouter.createTrialThread();
        RoutingBoard netBoard = board.deepCopy();
        if (netBoard == null) continue;

        RouterSettings netSettings = settings.clone();
        netSettings.netsToRoute = new HashSet<>(Collections.singletonList(netNo));
        netSettings.setFanoutEnabled(true);

        BatchAutorouter router = new BatchAutorouter(ct, netBoard, netSettings,
            true, true, settings.get_start_ripup_costs(),
            settings.trace_pull_tight_accuracy);
        router.setStagnationPassLimit(2);
        router.setOptimizerAutorouter(true);
        router.runBatchLoop();

        // Merge new items (fanout vias/traces) back
        Set<Integer> originalItemIds = RegionRouter.collectItemIds(board);
        int merged = RegionRouter.mergeNewItems(board, netBoard, originalItemIds);
        if (merged > 0) {
          fannedOut += merged;
          FRLogger.debug("ClusterPinFanout: Net " + netNo + " → " + merged + " items merged");
        }
      } catch (Exception e) {
        FRLogger.debug("ClusterPinFanout: Error routing net " + netNo + ": " + e.getMessage());
      }
    }

    if (fannedOut > 0) {
      board.finish_autoroute();
      FRLogger.info("ClusterPinFanout: " + fannedOut + " items fanned out for cluster #"
          + cluster.getClusterId() + " (" + pinsToFanout.size() + " SMD pins, "
          + "denseBGA=" + isDenseBga + ", diffPairs=" + diffPairNames.size() + ")");
    }
    return fannedOut;
  }

  /** Estimate average spacing between SMD pins. */
  private double estimateAveragePinSpacing(List<Pin> pins) {
    if (pins.size() < 2) return Double.MAX_VALUE;
    double totalDist = 0; int pairs = 0;
    for (int i = 0; i < pins.size() && i < 20; i++) {
      for (int j = i + 1; j < pins.size() && j < 20; j++) {
        double dx = pins.get(i).get_center().to_float().x
                  - pins.get(j).get_center().to_float().x;
        double dy = pins.get(i).get_center().to_float().y
                  - pins.get(j).get_center().to_float().y;
        double d = Math.sqrt(dx * dx + dy * dy);
        if (d > 0) { totalDist += d; pairs++; }
      }
    }
    return pairs > 0 ? totalDist / pairs : Double.MAX_VALUE;
  }

  /** Detect differential pair naming convention (e.g. _P/_N, _+/_-). */
  private Set<String> detectDifferentialPairs(Set<Integer> netNumbers) {
    Set<String> diffPairNames = new HashSet<>();
    Set<String> pNets = new HashSet<>();
    Set<String> nNets = new HashSet<>();
    for (int netNo : netNumbers) {
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      String name = net.name.toUpperCase();
      if (name.endsWith("_P") || name.endsWith("_+") || name.endsWith("_POS")) {
        pNets.add(name.substring(0, name.length() - 2));
      } else if (name.endsWith("_N") || name.endsWith("_-") || name.endsWith("_NEG")) {
        nNets.add(name.substring(0, name.length() - 2));
      }
    }
    for (String base : pNets) {
      if (nNets.contains(base)) diffPairNames.add(base);
    }
    return diffPairNames;
  }
}
