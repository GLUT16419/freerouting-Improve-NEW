package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 1: Short-Net First Routing.
 * Routes short Manhattan-distance nets first using real A* maze routing
 * via BatchAutorouter. Longer nets are deferred to Phase 2 cluster routing.
 */
public class Phase1ShortRouter {

  private static final double SHORT_NET_THRESHOLD = 500000.0;

  private final BasicBoard board;
  private final CostModel costModel;
  private final CongestionMap congestionMap;
  private final PowerGndAutoLabeler powerGndLabeler;
  private final RouterSettings settings;
  private final BatchAutorouter batchAutorouter;

  private final List<RoutingTask> routingQueue = new ArrayList<>();
  private final Map<Integer, Double> netPriorities = new HashMap<>();

  private final AtomicInteger totalShortNetCount = new AtomicInteger(0);
  private final AtomicInteger routedCount = new AtomicInteger(0);
  private final AtomicInteger failedCount = new AtomicInteger(0);
  private final List<Integer> failedNetNumbers = new ArrayList<>();
  private final List<Integer> successfulNetNumbers = new ArrayList<>();
  private final Set<Integer> allNetNumbers = new HashSet<>();

  public Phase1ShortRouter(BasicBoard board, CostModel costModel,
                           CongestionMap congestionMap,
                           PowerGndAutoLabeler powerGndLabeler,
                           RouterSettings settings,
                           BatchAutorouter batchAutorouter) {
    this.board = board;
    this.costModel = costModel;
    this.congestionMap = congestionMap;
    this.powerGndLabeler = powerGndLabeler;
    this.settings = settings;
    this.batchAutorouter = batchAutorouter;
  }

  /**
   * Routes all short nets (below distance threshold).
   * @return List of successfully routed net numbers
   */
  public List<Integer> routeAll() {
    FRLogger.debug("Phase 1: Starting short-net first routing...");
    allNetNumbers.clear();
    buildRoutingQueue();
    reserveChannelsForLongNets();
    routeShortNets();
    logResults();
    return successfulNetNumbers;
  }

  private void buildRoutingQueue() {
    if (board.rules == null || board.rules.nets == null) return;
    Map<Integer, List<Item>> netItemsMap = new HashMap<>();
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    while (true) {
      Item item = (Item) board.item_list.read_object(it);
      if (item == null) break;
      if (item instanceof Connectable && item.is_routable()) {
        int netCount = item.net_count();
        for (int i = 0; i < netCount; i++) {
          int netNo = item.get_net_no(i);
          netItemsMap.computeIfAbsent(netNo, k -> new ArrayList<>()).add(item);
          allNetNumbers.add(netNo);
        }
      }
    }
    for (Map.Entry<Integer, List<Item>> entry : netItemsMap.entrySet()) {
      int netNo = entry.getKey();
      List<Item> items = entry.getValue();
      if (items.size() < 2) continue;
      Net net = board.rules.nets.get(netNo);
      if (net == null) continue;
      double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
      double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
      for (Item item : items) {
        FloatPoint center = item instanceof DrillItem d
            ? d.get_center().to_float()
            : new FloatPoint(0, 0);
        minX = Math.min(minX, center.x);
        maxX = Math.max(maxX, center.x);
        minY = Math.min(minY, center.y);
        maxY = Math.max(maxY, center.y);
      }
      double manhattanDist = (maxX - minX) + (maxY - minY);
      NetType netType = powerGndLabeler.getNetType(netNo);
      double basePriority = manhattanDist;
      if (netType.isPowerOrGround()) {
        basePriority *= 2.0;
      }
      netPriorities.put(netNo, basePriority);
      boolean isShort = manhattanDist <= SHORT_NET_THRESHOLD;
      routingQueue.add(new RoutingTask(netNo, net.name, items, manhattanDist, netType, basePriority, isShort));
    }
    // Sort by priority: short nets first, then by distance (shortest first)
    routingQueue.sort((a, b) -> {
      int cmp = Boolean.compare(b.isShort, a.isShort); // Short nets first
      if (cmp == 0) cmp = Double.compare(a.priority, b.priority);
      return cmp;
    });
    long shortCount = routingQueue.stream().filter(t -> t.isShort).count();
    totalShortNetCount.set((int) shortCount);
    FRLogger.debug("Phase 1: Built routing queue with " + routingQueue.size()
        + " nets (" + shortCount + " short nets)");
  }

  private void reserveChannelsForLongNets() {
    for (RoutingTask task : routingQueue) {
      if (task.manhattanDist > SHORT_NET_THRESHOLD) {
        for (Item item : task.items) {
          if (item instanceof DrillItem d) {
            FloatPoint center = d.get_center().to_float();
            int layer = d.first_layer();
            costModel.reserveChannel(layer, center, task.priority);
          }
        }
      }
    }
  }

  /**
   * Route short nets by calling BatchAutorouter.routeNet().
   */
  private void routeShortNets() {
    for (RoutingTask task : routingQueue) {
      if (!task.isShort) continue; // Skip long nets (handled by Phase 2)

      BatchAutorouter.NetRouteResult result = batchAutorouter.routeNet(task.netNo, 1);

      if (result.isRouted()) {
        successfulNetNumbers.add(task.netNo);
        routedCount.addAndGet(result.routedCount);
        FRLogger.debug("Phase 1: Net " + task.netNo + " (" + task.netName
            + ") routed successfully (" + result.routedCount + " items)");
      } else if (result.isAllFailed()) {
        failedNetNumbers.add(task.netNo);
        failedCount.incrementAndGet();
        FRLogger.debug("Phase 1: Net " + task.netNo + " (" + task.netName
            + ") failed (" + result.failedCount + " failures)");
      } else {
        // All items already connected
        successfulNetNumbers.add(task.netNo);
        FRLogger.debug("Phase 1: Net " + task.netNo + " (" + task.netName
            + ") already connected");
      }
    }
  }

  public List<Integer> getFailedNetNumbers() { return failedNetNumbers; }
  public List<Integer> getSuccessfulNetNumbers() { return successfulNetNumbers; }

  /**
   * Returns all net numbers that were NOT successfully routed by Phase 1.
   * This includes both failed short nets and long nets not attempted.
   */
  public List<Integer> getUnroutedNetNumbers() {
    Set<Integer> routed = new HashSet<>(successfulNetNumbers);
    List<Integer> unrouted = new ArrayList<>();
    for (int netNo : allNetNumbers) {
      if (!routed.contains(netNo)) {
        unrouted.add(netNo);
      }
    }
    return unrouted;
  }

  public int getRoutedCount() { return routedCount.get(); }
  public int getFailedCount() { return failedCount.get(); }

  private void logResults() {
    FRLogger.debug(String.format(
        "Phase 1 complete: %d short nets routed, %d failed out of %d total",
        successfulNetNumbers.size(), failedNetNumbers.size(), routingQueue.size()));
  }

  private static class RoutingTask {
    final int netNo;
    final String netName;
    final List<Item> items;
    final double manhattanDist;
    final NetType netType;
    final double priority;
    final boolean isShort;

    RoutingTask(int netNo, String netName, List<Item> items,
                double manhattanDist, NetType netType, double priority, boolean isShort) {
      this.netNo = netNo;
      this.netName = netName;
      this.items = items;
      this.manhattanDist = manhattanDist;
      this.netType = netType;
      this.priority = priority;
      this.isShort = isShort;
    }
  }
}
