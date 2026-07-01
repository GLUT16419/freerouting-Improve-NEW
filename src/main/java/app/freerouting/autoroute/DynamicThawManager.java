package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.Pin;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;

import java.util.*;

/**
 * V6: Dynamic thaw manager for Phase 2 cluster routing.
 *
 * Detects conflicts between routed clusters, generates thaw requests to
 * selectively release specific nets from previously routed clusters, and
 * coordinates local re-routing of thawed nets to resolve congestion.
 */
public class DynamicThawManager {

  private static final int MAX_THAWED_CLUSTERS = 2;
  private static final int MAX_NETS_PER_THAW = 5;
  private static final double CONFLICT_OVERLAP_MARGIN = 10000; // units

  private final RoutingBoard board;
  private final List<NetCluster> routedClusters;
  private final Map<Integer, Set<Integer>> netToRoutedItems; // netNo → item IDs
  private final List<ThawRequest> pendingThawRequests;
  private int thawCount = 0;

  public DynamicThawManager(RoutingBoard board) {
    this.board = board;
    this.routedClusters = new ArrayList<>();
    this.netToRoutedItems = new HashMap<>();
    this.pendingThawRequests = new ArrayList<>();
  }

  /** Record that a cluster has been routed. */
  public void recordRoutedCluster(NetCluster cluster) {
    routedClusters.add(cluster);
  }

  /** Register routed items for a specific net. */
  public void registerNetItems(int netNo, Set<Integer> itemIds) {
    netToRoutedItems.computeIfAbsent(netNo, _k -> new HashSet<>()).addAll(itemIds);
  }

  /**
   * Detect conflicts between the latest routed cluster and previously routed ones.
   * Returns true if conflicts were detected.
   */
  public boolean detectConflicts(NetCluster latestCluster) {
    if (routedClusters.size() < 2) return false;

    boolean hasConflict = false;
    for (int i = 0; i < routedClusters.size() - 1; i++) {
      NetCluster prev = routedClusters.get(i);
      if (clustersOverlap(latestCluster, prev)) {
        FRLogger.info("ThawManager: Conflict between cluster #" + latestCluster.getClusterId()
            + " and #" + prev.getClusterId());
        generateThawRequest(latestCluster, prev);
        hasConflict = true;
      }
    }
    return hasConflict;
  }

  /** Check if two clusters' bounding boxes overlap significantly (= conflict). */
  private boolean clustersOverlap(NetCluster a, NetCluster b) {
    double overlapX = Math.max(0,
        Math.min(a.getMaxX(), b.getMaxX() + CONFLICT_OVERLAP_MARGIN)
      - Math.max(a.getMinX(), b.getMinX() - CONFLICT_OVERLAP_MARGIN));
    double overlapY = Math.max(0,
        Math.min(a.getMaxY(), b.getMaxY() + CONFLICT_OVERLAP_MARGIN)
      - Math.max(a.getMinY(), b.getMinY() - CONFLICT_OVERLAP_MARGIN));
    return overlapX > 0 && overlapY > 0;
  }

  /** Generate a thaw request to release conflicting nets from the earlier cluster. */
  private void generateThawRequest(NetCluster latest, NetCluster earlier) {
    if (thawCount >= MAX_THAWED_CLUSTERS) {
      FRLogger.debug("ThawManager: Max thawed clusters reached (" + MAX_THAWED_CLUSTERS + ")");
      return;
    }

    // Pick up to MAX_NETS_PER_THAW nets from the earlier cluster for re-routing
    List<Integer> netsToThaw = new ArrayList<>(earlier.getNetNumbers());
    Collections.shuffle(netsToThaw, new Random(thawCount));
    if (netsToThaw.size() > MAX_NETS_PER_THAW)
      netsToThaw = netsToThaw.subList(0, MAX_NETS_PER_THAW);

    double cx = (earlier.getMinX() + earlier.getMaxX()) / 2;
    double cy = (earlier.getMinY() + earlier.getMaxY()) / 2;

    ThawRequest request = new ThawRequest(
        new HashSet<>(netsToThaw),
        new HashSet<>(Collections.singletonList(earlier.getClusterId())),
        earlier.getMinX(), earlier.getMinY(), earlier.getMaxX(), earlier.getMaxY(),
        Math.min(5, thawCount + 1)
    );
    pendingThawRequests.add(request);
    thawCount++;

    FRLogger.info("ThawManager: Thaw request #" + thawCount + " — "
        + netsToThaw.size() + " nets from cluster #" + earlier.getClusterId());
  }

  /** Get pending thaw requests. */
  public List<ThawRequest> getPendingThawRequests() {
    return new ArrayList<>(pendingThawRequests);
  }

  /** Clear processed thaw requests. */
  public void clearProcessedThawRequests() {
    pendingThawRequests.clear();
  }

  /**
   * Execute thawing: remove traces belonging to thawed nets, then
   * return the set of net numbers that need re-routing.
   */
  public Set<Integer> executeThaw(ThawRequest request) {
    Set<Integer> thawedNets = request.targetNets;
    int removedCount = 0;

    board.start_marking_changed_area();
    List<Item> itemsToRemove = new ArrayList<>();
    Iterator<app.freerouting.datastructures.UndoableObjects.UndoableObjectNode> it
        = board.item_list.start_read_object();
    for (;;) {
      app.freerouting.datastructures.UndoableObjects.Storable ob
          = board.item_list.read_object(it);
      if (ob == null) break;
      if (ob instanceof Trace trace && !(ob instanceof Pin)) {
        for (int i = 0; i < trace.net_count(); i++) {
          if (thawedNets.contains(trace.get_net_no(i))) {
            FloatPoint mid = trace.first_corner().to_float();
            if (mid.x >= request.regionMinX && mid.x <= request.regionMaxX
                && mid.y >= request.regionMinY && mid.y <= request.regionMaxY) {
              itemsToRemove.add(trace);
            }
            break;
          }
        }
      }
    }
    board.remove_items(itemsToRemove);
    removedCount = itemsToRemove.size();

    FRLogger.info("ThawManager: Executed thaw — removed " + removedCount
        + " traces for " + thawedNets.size() + " nets");
    return thawedNets;
  }

  /** Check if maximum number of thaws has been reached. */
  public boolean isThawExhausted() {
    return thawCount >= MAX_THAWED_CLUSTERS;
  }

  /** Thaw request data container. */
  public static class ThawRequest {
    public final Set<Integer> targetNets;
    public final Set<Integer> conflictingClusters;
    public final double regionMinX, regionMinY, regionMaxX, regionMaxY;
    public final int urgency;

    public ThawRequest(Set<Integer> targetNets, Set<Integer> conflictingClusters,
                       double regionMinX, double regionMinY,
                       double regionMaxX, double regionMaxY, int urgency) {
      this.targetNets = targetNets;
      this.conflictingClusters = conflictingClusters;
      this.regionMinX = regionMinX;
      this.regionMinY = regionMinY;
      this.regionMaxX = regionMaxX;
      this.regionMaxY = regionMaxY;
      this.urgency = urgency;
    }
  }
}
