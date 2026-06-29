package app.freerouting.autoroute;

import app.freerouting.board.Item;
import app.freerouting.board.RoutingBoard;
import app.freerouting.board.Trace;
import app.freerouting.board.Via;
import app.freerouting.core.ProgressThrottler;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.scoring.BoardStatistics;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.RouterSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.*;
import java.util.concurrent.*;

/** Handles the sequencing of the fanout inside the batch autorouter. */
public class BatchFanout {

  private final StoppableThread thread;
  private final RoutingBoard routing_board;
  private final RouterSettings settings;
  private final SortedSet<Component> sorted_components;
  private final int totalSmdPinCount;
  private final int alreadyConnectedPinCount;
  private final ProgressThrottler progressThrottler = new ProgressThrottler(1000);
  private int lastNotRoutedCount;
  private int extraViasTotal;
  /** Net numbers classified as Power or Ground — these pins are fanned out first. */
  private final Set<Integer> powerGroundNetNumbers;

  private BatchFanout(RoutingBoard p_board, RouterSettings p_settings, StoppableThread p_thread) {
    this(p_board, p_settings, p_thread, null);
  }

  private BatchFanout(RoutingBoard p_board, RouterSettings p_settings, StoppableThread p_thread,
      Set<Integer> p_powerGroundNetNumbers) {
    this.powerGroundNetNumbers = (p_powerGroundNetNumbers != null) ? p_powerGroundNetNumbers : new HashSet<>();
    this.thread = p_thread;
    this.routing_board = p_board;
    this.settings = p_settings;
    String sortingOrder = (p_settings.fanout != null && p_settings.fanout.pinSortingOrder != null)
        ? p_settings.fanout.pinSortingOrder : "outer_first";
    Collection<app.freerouting.board.Pin> board_smd_pin_list = routing_board.get_smd_pins();
    // Filter out SMD pins that belong to no net — they don't need fanout and would inflate
    // total pin counts and escape statistics.
    Collection<app.freerouting.board.Pin> board_smd_pin_list_with_nets = new LinkedList<>();
    for (app.freerouting.board.Pin pin : board_smd_pin_list) {
      if (pin.net_count() > 0) {
        board_smd_pin_list_with_nets.add(pin);
      }
    }
    this.sorted_components = new TreeSet<>();
    for (int i = 1; i <= routing_board.components.count(); ++i) {
      app.freerouting.board.Component curr_board_component = routing_board.components.get(i);
      Component curr_component = new Component(curr_board_component, board_smd_pin_list_with_nets, sortingOrder, routing_board);
      if (curr_component.smd_pin_count > 0) {
        sorted_components.add(curr_component);
      }
    }
    int pinCount = 0;
    int alreadyConnected = 0;
    for (Component component : sorted_components) {
      pinCount += component.smd_pin_count;
      for (Component.Pin pin : component.smd_pins) {
        // A pin is already connected if all items in its connected set are on the pin's layer
        // and its unconnected set is empty — same logic as RoutingBoard.fanout().
        app.freerouting.board.Pin boardPin = pin.board_pin;
        int netNo = boardPin.get_net_no(0);
        if (boardPin.get_unconnected_set(netNo).isEmpty()) {
          alreadyConnected++;
        }
      }
    }
    this.totalSmdPinCount = pinCount;
    this.alreadyConnectedPinCount = alreadyConnected;
  }

  /**
   * Creates a BatchFanout that only fans out components whose component_no is in the given filter.
   * If p_componentNosFilter is null or empty, all components are included (same as normal constructor).
   */
  BatchFanout(RoutingBoard p_board, RouterSettings p_settings, StoppableThread p_thread,
      Set<Integer> p_powerGroundNetNumbers,
      Collection<Integer> p_componentNosFilter) {
    this.powerGroundNetNumbers = (p_powerGroundNetNumbers != null) ? p_powerGroundNetNumbers : new HashSet<>();
    this.thread = p_thread;
    this.routing_board = p_board;
    this.settings = p_settings;
    String sortingOrder = (p_settings.fanout != null && p_settings.fanout.pinSortingOrder != null)
        ? p_settings.fanout.pinSortingOrder : "outer_first";
    Collection<app.freerouting.board.Pin> board_smd_pin_list = routing_board.get_smd_pins();
    Collection<app.freerouting.board.Pin> board_smd_pin_list_with_nets = new LinkedList<>();
    for (app.freerouting.board.Pin pin : board_smd_pin_list) {
      if (pin.net_count() > 0) {
        board_smd_pin_list_with_nets.add(pin);
      }
    }
    this.sorted_components = new TreeSet<>();
    for (int i = 1; i <= routing_board.components.count(); ++i) {
      if (p_componentNosFilter != null && !p_componentNosFilter.contains(i)) {
        continue;
      }
      app.freerouting.board.Component curr_board_component = routing_board.components.get(i);
      Component curr_component = new Component(curr_board_component, board_smd_pin_list_with_nets, sortingOrder, routing_board);
      if (curr_component.smd_pin_count > 0) {
        sorted_components.add(curr_component);
      }
    }
    int pinCount = 0;
    int alreadyConnected = 0;
    for (Component component : sorted_components) {
      pinCount += component.smd_pin_count;
      for (Component.Pin pin : component.smd_pins) {
        app.freerouting.board.Pin boardPin = pin.board_pin;
        int netNo = boardPin.get_net_no(0);
        if (boardPin.get_unconnected_set(netNo).isEmpty()) {
          alreadyConnected++;
        }
      }
    }
    this.totalSmdPinCount = pinCount;
    this.alreadyConnectedPinCount = alreadyConnected;
  }

  public static FanoutRunSummary fanout_board(RoutingBoard p_board, RouterSettings p_settings,
      StoppableThread p_thread) {
    return fanout_board(p_board, p_settings, p_thread, null, null);
  }

  public static FanoutRunSummary fanout_board(RoutingBoard p_board, RouterSettings p_settings,
      StoppableThread p_thread,
      Set<Integer> p_powerGroundNetNumbers) {
    return fanout_board(p_board, p_settings, p_thread, null, p_powerGroundNetNumbers);
  }

  public static FanoutRunSummary fanout_board(RoutingBoard p_board, RouterSettings p_settings,
      StoppableThread p_thread,
      FanoutProgressListener progressListener) {
    return fanout_board(p_board, p_settings, p_thread, progressListener, null);
  }

  public static FanoutRunSummary fanout_board(RoutingBoard p_board, RouterSettings p_settings,
      StoppableThread p_thread,
      FanoutProgressListener progressListener,
      Set<Integer> p_powerGroundNetNumbers) {
    BatchFanout fanout_instance = new BatchFanout(p_board, p_settings, p_thread, p_powerGroundNetNumbers);
    long fanoutStart = System.currentTimeMillis();
    int maxPasses = (p_settings.fanout != null && p_settings.fanout.maxPasses != null)
        ? p_settings.fanout.maxPasses : 20;
    int completedPasses = 0;
    String lastBoardHash = p_board.get_hash();
    int previousEscapedCount = -1;
    int stagnationPasses = 0;
    for (int i = 0; i < maxPasses; ++i) {
      int routed_count = fanout_instance.fanout_pass(i, progressListener);
      completedPasses++;
      if (routed_count == 0) {
        break;
      }
      String currentBoardHash = p_board.get_hash();
      if (currentBoardHash.equals(lastBoardHash)) {
        break;
      }
      lastBoardHash = currentBoardHash;

      // Stagnation detection: if escaped SMD pin count hasn't increased for 3 consecutive
      // passes, the fanout has converged — stop early instead of wasting passes.
      int currentEscapedCount = fanout_instance.computeEscapeStatistics().escapedCount();
      if (currentEscapedCount <= previousEscapedCount) {
        stagnationPasses++;
        if (stagnationPasses >= 3) {
          FRLogger.info("Fanout converged after " + (i + 1) + " passes (escaped pins: "
              + currentEscapedCount + "/" + fanout_instance.totalSmdPinCount
              + " unchanged for " + stagnationPasses + " passes). Stopping early.");
          break;
        }
      } else {
        stagnationPasses = 0;
      }
      previousEscapedCount = currentEscapedCount;
    }
    EscapeStatistics finalEscape = fanout_instance.computeEscapeStatistics();
    long totalDurationMillis = Math.max(0, System.currentTimeMillis() - fanoutStart);
    return new FanoutRunSummary(completedPasses, totalDurationMillis, finalEscape);
  }

  /**
   * Runs the full multi-pass fanout loop (same logic as the static fanout_board) but
   * using this instance's (potentially region-filtered) sorted_components.
   */
  FanoutRunSummary fanoutBoardRegion(FanoutProgressListener progressListener) {
    long fanoutStart = System.currentTimeMillis();
    int maxPasses = (this.settings.fanout != null && this.settings.fanout.maxPasses != null)
        ? this.settings.fanout.maxPasses : 20;
    int completedPasses = 0;
    String lastBoardHash = this.routing_board.get_hash();
    int previousEscapedCount = -1;
    int stagnationPasses = 0;
    for (int i = 0; i < maxPasses; ++i) {
      int routed_count = this.fanout_pass(i, progressListener);
      completedPasses++;
      if (routed_count == 0) break;
      String currentBoardHash = this.routing_board.get_hash();
      if (currentBoardHash.equals(lastBoardHash)) break;
      lastBoardHash = currentBoardHash;
      int currentEscapedCount = this.computeEscapeStatistics().escapedCount();
      if (currentEscapedCount <= previousEscapedCount) {
        stagnationPasses++;
        if (stagnationPasses >= 3) break;
      } else {
        stagnationPasses = 0;
      }
      previousEscapedCount = currentEscapedCount;
    }
    EscapeStatistics finalEscape = this.computeEscapeStatistics();
    long totalDurationMillis = Math.max(0, System.currentTimeMillis() - fanoutStart);
    return new FanoutRunSummary(completedPasses, totalDurationMillis, finalEscape);
  }

  /**
   * Clusters components by spatial proximity of their SMD pin gravity centers.
   * Divides the bounding box into horizontal (Y-axis) bands.
   * Returns a list of component-no sets, one per region.
   */
  private static List<Set<Integer>> clusterComponents(SortedSet<Component> components, int numRegions) {
    if (components == null || components.isEmpty() || numRegions <= 1) {
      List<Set<Integer>> result = new ArrayList<>();
      Set<Integer> all = new HashSet<>();
      for (Component c : components) all.add(c.board_component.no);
      result.add(all);
      return result;
    }
    // Compute Y-axis bounding box of component gravity centers
    double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
    for (Component c : components) {
      double y = c.gravity_center_of_smd_pins.y;
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
    double rangeY = maxY - minY;
    if (rangeY < 1e-6) {
      // All components at same Y — partition by X instead
      double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
      for (Component c : components) {
        double x = c.gravity_center_of_smd_pins.x;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
      }
      double rangeX = maxX - minX;
      if (rangeX < 1e-6) {
        List<Set<Integer>> result = new ArrayList<>();
        Set<Integer> all = new HashSet<>();
        for (Component c : components) all.add(c.board_component.no);
        result.add(all);
        return result;
      }
      double regionWidth = rangeX / numRegions;
      List<Set<Integer>> regions = new ArrayList<>();
      for (int r = 0; r < numRegions; r++) regions.add(new HashSet<>());
      for (Component c : components) {
        double x = c.gravity_center_of_smd_pins.x;
        int idx = Math.min((int) ((x - minX) / regionWidth), numRegions - 1);
        regions.get(idx).add(c.board_component.no);
      }
      regions.removeIf(Set::isEmpty);
      return regions;
    }
    double regionHeight = rangeY / numRegions;
    List<Set<Integer>> regions = new ArrayList<>();
    for (int r = 0; r < numRegions; r++) regions.add(new HashSet<>());
    for (Component c : components) {
      double y = c.gravity_center_of_smd_pins.y;
      int idx = Math.min((int) ((y - minY) / regionHeight), numRegions - 1);
      regions.get(idx).add(c.board_component.no);
    }
    // Remove empty regions
    regions.removeIf(Set::isEmpty);
    return regions;
  }

  /**
   * Collects all item id_no values from the board into a set for fast lookup.
   * Used to identify new items added during fanout.
   */
  private static Set<Integer> collectItemIds(RoutingBoard board) {
    Set<Integer> ids = new HashSet<>();
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = board.item_list.read_object(it);
      if (ob == null) break;
      if (ob instanceof Item item) {
        ids.add(item.get_id_no());
      }
    }
    return ids;
  }

  /**
   * Creates a lightweight StoppableThread for a region fanout trial,
   * so stop-requests don't cross-talk between regions.
   */
  private static StoppableThread createRegionThread() {
    return new StoppableThread() {
      @Override
      protected void thread_action() { /* no-op */ }
    };
  }

  /**
   * Collects result from one parallel region fanout trial.
   */
  private static class FanoutRegionResult {
    final RoutingBoard board;
    final FanoutRunSummary summary;
    final Set<Integer> componentNos;
    final int regionIndex;

    FanoutRegionResult(RoutingBoard board, FanoutRunSummary summary, Set<Integer> componentNos, int regionIndex) {
      this.board = board;
      this.summary = summary;
      this.componentNos = componentNos;
      this.regionIndex = regionIndex;
    }
  }

  /**
   * Performs region-based parallel fanout on the given board.
   *
   * <p>Strategy: Clusters all SMD components into N spatial regions by gravity center,
   * deep-copies the board N times (one per region), fans out each region's components
   * on its own copy in parallel, then merges the fanout items back into the main board
   * via item serialization.
   *
   * <p>If clustering yields &le; 1 region or deepCopy fails, falls back to single-threaded fanout.
   */
  public static FanoutRunSummary parallelFanoutBoard(RoutingBoard p_board, RouterSettings p_settings,
      StoppableThread p_thread,
      FanoutProgressListener progressListener,
      Set<Integer> p_powerGroundNetNumbers) {

    // Phase 1: Create temporary BatchFanout for clustering
    BatchFanout tmp = new BatchFanout(p_board, p_settings, p_thread, p_powerGroundNetNumbers);
    if (tmp.sorted_components.size() <= 3) {
      FRLogger.info("ParallelFanoutBoard: Only " + tmp.sorted_components.size()
          + " components, falling back to single-threaded fanout.");
      return fanout_board(p_board, p_settings, p_thread, progressListener, p_powerGroundNetNumbers);
    }

    int numRegions = Math.min(Runtime.getRuntime().availableProcessors(), 6);
    numRegions = Math.min(numRegions, tmp.sorted_components.size());
    List<Set<Integer>> regions = clusterComponents(tmp.sorted_components, numRegions);
    if (regions.size() <= 1) {
      FRLogger.info("ParallelFanoutBoard: Clustering yielded only 1 region, falling back to single-threaded.");
      return fanout_board(p_board, p_settings, p_thread, progressListener, p_powerGroundNetNumbers);
    }

    FRLogger.info("ParallelFanoutBoard: " + regions.size() + " regions, "
        + tmp.sorted_components.size() + " components, starting parallel fanout...");

    // Collect original item IDs before any fanout — these identify pre-existing items.
    // New items created during fanout on region copies will have different id_no values.
    Set<Integer> originalItemIds = collectItemIds(p_board);

    // Phase 2: Execute parallel region fanout
    ExecutorService executor = Executors.newFixedThreadPool(regions.size());
    List<Future<FanoutRegionResult>> futures = new ArrayList<>();
    long startTime = System.currentTimeMillis();
    int regionIdx = 0;

    for (Set<Integer> compFilter : regions) {
      final int rIdx = regionIdx++;
      futures.add(executor.submit(() -> {
        RoutingBoard regionBoard = p_board.deepCopy();
        if (regionBoard == null) {
          FRLogger.warn("ParallelFanoutBoard: deepCopy failed for region " + rIdx);
          return null;
        }
        StoppableThread regionThread = createRegionThread();
        BatchFanout regionFanout = new BatchFanout(regionBoard, p_settings, regionThread,
            p_powerGroundNetNumbers, compFilter);
        long regionStart = System.currentTimeMillis();
        FanoutRunSummary summary = regionFanout.fanoutBoardRegion(progressListener);
        long duration = System.currentTimeMillis() - regionStart;
        int escaped = summary.escapeStatistics().escapedCount();
        int total = summary.escapeStatistics().totalSmdPins();
        FRLogger.info("ParallelFanoutBoard: Region " + rIdx + " complete — "
            + escaped + "/" + total + " escaped, " + summary.completedPassCount()
            + " passes, " + duration + "ms");
        return new FanoutRegionResult(regionBoard, summary, compFilter, rIdx);
      }));
    }

    // Phase 3: Collect results
    List<FanoutRegionResult> results = new ArrayList<>();
    for (Future<FanoutRegionResult> future : futures) {
      try {
        FanoutRegionResult result = future.get();
        if (result != null) results.add(result);
      } catch (Exception e) {
        FRLogger.warn("ParallelFanoutBoard: Region execution error: " + e.getMessage());
      }
    }
    executor.shutdownNow();

    if (results.isEmpty()) {
      FRLogger.warn("ParallelFanoutBoard: All regions failed, falling back to single-threaded fanout.");
      return fanout_board(p_board, p_settings, p_thread, progressListener, p_powerGroundNetNumbers);
    }

    // Phase 4: Merge fanout items from all region copies back to the main board.
    // New items are identified by their id_no not being in the original item set.
    int mergedCount = 0;
    int mergeErrorCount = 0;
    for (FanoutRegionResult result : results) {
      try {
        mergedCount += mergeFanoutItems(p_board, result.board, originalItemIds);
      } catch (Exception e) {
        FRLogger.warn("ParallelFanoutBoard: Merge error for region " + result.regionIndex + ": " + e.getMessage());
        mergeErrorCount++;
      }
    }

    // Phase 5: Run finish/autoroute cleanup on the main board after merge
    p_board.clear_all_item_temporary_autoroute_data();
    p_board.finish_autoroute();

    EscapeStatistics finalEscape = new BatchFanout(p_board, p_settings, p_thread,
        p_powerGroundNetNumbers).computeEscapeStatistics();
    long totalDuration = System.currentTimeMillis() - startTime;
    int totalPasses = results.stream().mapToInt(r -> r.summary.completedPassCount()).sum();

    FRLogger.info("ParallelFanoutBoard: Complete — " + results.size() + " regions, "
        + mergedCount + " items merged (" + mergeErrorCount + " errors), "
        + finalEscape.escapedCount() + "/" + finalEscape.totalSmdPins()
        + " escaped, " + totalDuration + "ms");
    return new FanoutRunSummary(totalPasses, totalDuration, finalEscape);
  }

  /**
   * Region-based parallel fanout that accepts a pre-computed list of Regions from RegionDivider.
   * <p>
   * For each region, components whose SMD pin gravity center falls within the region's core bbox
   * are fanned out independently on a deep copy. Results are merged back to the main board.
   * <p>
   * Unlike {@link #parallelFanoutBoard} which clusters components by Y-axis bands, this method
   * uses the adaptive regions from the V2 pipeline (Phase 1 output).
   *
   * @param board                   the main routing board
   * @param settings                router settings
   * @param thread                  the stoppable thread
   * @param powerGroundNetNumbers   set of power/ground net numbers (for fanout prioritization)
   * @param regions                 pre-computed list of adaptive regions
   * @param componentFilters        component number filters per region (output from assignComponentsToRegions)
   * @return fanout run summary
   */
  public static FanoutRunSummary parallelFanoutBoardRegions(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      Set<Integer> powerGroundNetNumbers,
      List<Region> regions,
      List<Set<Integer>> componentFilters) {

    if (board.get_smd_pins() == null || board.get_smd_pins().isEmpty()) {
      FRLogger.info("parallelFanoutBoardRegions: No SMD pins, skipping.");
      return new FanoutRunSummary(0, 0, new EscapeStatistics(0, 0, 0.0));
    }

    // Determine which regions are active (have components assigned)
    List<Integer> activeIndices = new ArrayList<>();
    for (int i = 0; i < regions.size(); i++) {
      if (regions.get(i).isActive && componentFilters.get(i) != null
          && !componentFilters.get(i).isEmpty()) {
        activeIndices.add(i);
      }
    }
    if (activeIndices.size() <= 1) {
      FRLogger.info("parallelFanoutBoardRegions: " + activeIndices.size()
          + " active region(s), falling back to standard parallel fanout.");
      return parallelFanoutBoard(board, settings, thread, null, powerGroundNetNumbers);
    }

    FRLogger.info("parallelFanoutBoardRegions: " + activeIndices.size()
        + " active regions, " + board.get_smd_pins().size() + " SMD pins...");

    // Collect original item IDs before fanout
    Set<Integer> originalItemIds = collectItemIds(board);

    // Parallel fanout per region
    ExecutorService executor = Executors.newFixedThreadPool(activeIndices.size());
    List<Future<FanoutRegionResult>> futures = new ArrayList<>();
    long startTime = System.currentTimeMillis();

    for (int idx : activeIndices) {
      final int rIdx = idx;
      final Set<Integer> compFilter = componentFilters.get(idx);

      futures.add(executor.submit(() -> {
        RoutingBoard regionBoard = board.deepCopy();
        if (regionBoard == null) {
          FRLogger.warn("parallelFanoutBoardRegions: deepCopy failed for region " + rIdx);
          return null;
        }
        StoppableThread regionThread = createRegionThread();
        BatchFanout regionFanout = new BatchFanout(regionBoard, settings, regionThread,
            powerGroundNetNumbers, compFilter);
        long regionStart = System.currentTimeMillis();
        FanoutRunSummary summary = regionFanout.fanoutBoardRegion(null);
        long duration = System.currentTimeMillis() - regionStart;
        int escaped = summary.escapeStatistics().escapedCount();
        int total = summary.escapeStatistics().totalSmdPins();
        FRLogger.info("parallelFanoutBoardRegions: Region " + rIdx + " — "
            + escaped + "/" + total + " escaped, "
            + summary.completedPassCount() + " passes, " + duration + "ms");
        return new FanoutRegionResult(regionBoard, summary, compFilter, rIdx);
      }));
    }

    // Collect results
    List<FanoutRegionResult> results = new ArrayList<>();
    for (Future<FanoutRegionResult> future : futures) {
      try {
        FanoutRegionResult result = future.get();
        if (result != null) results.add(result);
      } catch (Exception e) {
        FRLogger.warn("parallelFanoutBoardRegions: Region execution error: " + e.getMessage());
      }
    }
    executor.shutdownNow();

    if (results.isEmpty()) {
      FRLogger.warn("parallelFanoutBoardRegions: All regions failed, falling back.");
      return parallelFanoutBoard(board, settings, thread, null, powerGroundNetNumbers);
    }

    // Merge fanout items from all region copies
    int mergedCount = 0;
    int mergeErrorCount = 0;
    for (FanoutRegionResult result : results) {
      try {
        mergedCount += mergeFanoutItems(board, result.board, originalItemIds);
      } catch (Exception e) {
        FRLogger.warn("parallelFanoutBoardRegions: Merge error for region "
            + result.regionIndex + ": " + e.getMessage());
        mergeErrorCount++;
      }
    }

    // Finalize
    board.clear_all_item_temporary_autoroute_data();
    board.finish_autoroute();

    EscapeStatistics finalEscape = new BatchFanout(board, settings, thread,
        powerGroundNetNumbers).computeEscapeStatistics();
    long totalDuration = System.currentTimeMillis() - startTime;
    int totalPasses = results.stream().mapToInt(r -> r.summary.completedPassCount()).sum();

    FRLogger.info("parallelFanoutBoardRegions: Complete — " + results.size() + " regions, "
        + mergedCount + " items merged (" + mergeErrorCount + " errors), "
        + finalEscape.escapedCount() + "/" + finalEscape.totalSmdPins()
        + " escaped, " + totalDuration + "ms");
    return new FanoutRunSummary(totalPasses, totalDuration, finalEscape);
  }

  /**
   * Merges new fanout items from the source board (region copy after fanout) into the
   * target board (main board). Items that were already present in the original board
   * (identified by matching id_no in originalItemIds) are skipped.
   *
   * <p>New items are serialized from the source, deserialized to get clean copies,
   * then inserted into the target board's item_list. The board reference is set to
   * the target board after deserialization.
   *
   * @return number of items successfully merged
   */
  private static int mergeFanoutItems(RoutingBoard targetBoard, RoutingBoard sourceBoard,
      Set<Integer> originalItemIds) {
    int count = 0;
    Iterator<UndoableObjects.UndoableObjectNode> it = sourceBoard.item_list.start_read_object();
    for (;;) {
      UndoableObjects.Storable ob = sourceBoard.item_list.read_object(it);
      if (ob == null) break;
      if (ob instanceof Item item) {
        // Skip items that were already in the original board (pre-fanout state)
        if (originalItemIds.contains(item.get_id_no())) continue;
        // Skip Pin items — they're original board items and shouldn't be re-inserted
        if (item instanceof app.freerouting.board.Pin) continue;
        try {
          // Serialize the item to get a clean copy
          ByteArrayOutputStream bos = new ByteArrayOutputStream();
          ObjectOutputStream oos = new ObjectOutputStream(bos);
          oos.writeObject(item);
          oos.flush();
          ByteArrayInputStream bin = new ByteArrayInputStream(bos.toByteArray());
          ObjectInputStream ois = new ObjectInputStream(bin);
          Item copy = (Item) ois.readObject();
          // Set the board reference to the target board
          copy.board = targetBoard;
          // Insert into the target board's item list
          targetBoard.item_list.insert(copy);
          count++;
        } catch (Exception e) {
          FRLogger.warn("mergeFanoutItems: Failed to copy item id=" + item.get_id_no()
              + ": " + e.getMessage());
        }
      }
    }
    return count;
  }

  /** Routes a fanout pass and returns the number of new fanouted SMD-pins in this pass. */
  private int fanout_pass(int p_pass_no, FanoutProgressListener progressListener) {
    long passStart = System.currentTimeMillis();
    int pinsToGo = this.totalSmdPinCount;
    int routed_count = 0;
    int not_routed_count = 0;
    int insert_error_count = 0;
    int already_connected_count = 0;
    int viasBeforePass = this.routing_board.get_vias().size();
    int ripup_costs = this.settings.get_start_ripup_costs() * (p_pass_no + 1);

    long baseMillisPerPin = (this.settings.fanout != null && this.settings.fanout.maxMillisecondsPerPin != null)
        ? this.settings.fanout.maxMillisecondsPerPin : 10000L;
    boolean ripupAllowed = (this.settings.fanout == null || this.settings.fanout.ripupAllowed == null)
        || Boolean.TRUE.equals(this.settings.fanout.ripupAllowed);
    // Negative ripup costs signal "no ripup" to RoutingBoard.fanout()
    int effectiveRipupCosts = ripupAllowed ? ripup_costs : -1;

    FRLogger.trace("BatchFanout.fanout_pass", "pass_start",
        "pass=" + (p_pass_no + 1)
            + ", totalPins=" + this.totalSmdPinCount
            + ", alreadyConnected=" + this.alreadyConnectedPinCount
            + ", pinsToFanout=" + (this.totalSmdPinCount - this.alreadyConnectedPinCount)
            + ", ripupCosts=" + effectiveRipupCosts
            + ", baseMillisPerPin=" + baseMillisPerPin,
        "", new app.freerouting.geometry.planar.Point[0]);

    this.progressThrottler.reset();
    publishProgress(progressListener, p_pass_no, ripup_costs, pinsToGo, routed_count, not_routed_count,
        insert_error_count, 0, new EscapeStatistics(this.totalSmdPinCount, 0, 0.0), false, passStart);

    // Process in two phases: Power/GND pins first, then signal pins.
    // Critical nets like DGND and VCC get fanout priority for better power distribution.
    for (int phase = 0; phase < 2; phase++) {
      boolean processingPowerGnd = (phase == 0);
      for (Component curr_component : this.sorted_components) {
        for (Component.Pin curr_pin : curr_component.smd_pins) {
          int netNo = curr_pin.board_pin.get_net_no(0);
          boolean isPowerGnd = this.powerGroundNetNumbers.contains(netNo);
          // Phase 0 = Power/GND only; Phase 1 = Signal only
          if (processingPowerGnd != isPowerGnd) {
            continue;
          }

          double max_milliseconds = baseMillisPerPin * (p_pass_no + 1);
          TimeLimit time_limit = new TimeLimit((int) max_milliseconds);
          String fullPinName = curr_component.board_component.name + "-" + curr_pin.board_pin.name();
          int targetCount = curr_pin.board_pin.get_unconnected_set(netNo).size();

          FRLogger.trace("BatchFanout.fanout_pass", "pin_start",
              "pin=" + fullPinName
                  + ", net=" + netNo
                  + ", targetCount=" + targetCount
                  + ", center=" + curr_pin.board_pin.get_center()
                  + ", layer=" + curr_pin.board_pin.first_layer()
                  + ", pass=" + (p_pass_no + 1),
              fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});

          this.routing_board.start_marking_changed_area();
          long pinStartNanos = System.nanoTime();
          AutorouteAttemptResult curr_result =
              this.routing_board.fanout(
                  curr_pin.board_pin,
                  this.settings,
                  effectiveRipupCosts,
                  this.thread,
                  time_limit);
          long pinDurationMs = (System.nanoTime() - pinStartNanos) / 1_000_000L;

          switch (curr_result.state) {
            case ROUTED       -> {
               ++routed_count;
               FRLogger.trace("BatchFanout.fanout_pass", "pin_routed",
                   "pin=" + fullPinName
                       + ", net=" + netNo
                       + ", durationMs=" + pinDurationMs
                       + ", targetCount=" + targetCount,
                   fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
            case ALREADY_CONNECTED -> {
              ++already_connected_count;
              FRLogger.trace("BatchFanout.fanout_pass", "pin_already_connected",
                  "pin=" + fullPinName
                      + ", net=" + netNo
                      + ", targetCount=" + targetCount
                      + ", detail=" + curr_result.details,
                  fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
            case FAILED       -> {
              ++not_routed_count;
              FRLogger.trace("BatchFanout.fanout_pass", "pin_failed",
                  "pin=" + fullPinName
                      + ", net=" + netNo
                      + ", targetCount=" + targetCount
                      + ", durationMs=" + pinDurationMs
                      + ", detail=" + (curr_result.details == null || curr_result.details.isEmpty()
                      ? "no detail"
                      : curr_result.details),
                  fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
            case INSERT_ERROR -> {
              ++insert_error_count;
              FRLogger.trace("BatchFanout.fanout_pass", "pin_insert_error",
                  "pin=" + fullPinName
                      + ", net=" + netNo
                      + ", detail=" + (curr_result.details == null || curr_result.details.isEmpty()
                      ? "no detail"
                      : curr_result.details),
                  fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
            case NO_UNCONNECTED_NETS -> {
              FRLogger.trace("BatchFanout.fanout_pass", "pin_no_unconnected_nets",
                  "pin=" + fullPinName
                      + ", net=" + netNo
                      + ", detail=" + curr_result.details,
                  fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
            default -> {
              FRLogger.trace("BatchFanout.fanout_pass", "pin_other_state",
                  "pin=" + fullPinName
                      + ", net=" + netNo
                      + ", state=" + curr_result.state
                      + ", detail=" + curr_result.details,
                  fullPinName, new app.freerouting.geometry.planar.Point[]{curr_pin.board_pin.get_center()});
            }
          }
          --pinsToGo;
          int extraViasThisPass = Math.max(0, this.routing_board.get_vias().size() - viasBeforePass);
          maybePublishProgress(progressListener, p_pass_no, ripup_costs, pinsToGo, routed_count, not_routed_count,
              insert_error_count, extraViasThisPass, false, passStart);
          if (this.thread.is_stop_auto_router_requested()) {
            EscapeStatistics escapeStats = computeEscapeStatistics();
            publishProgress(progressListener, p_pass_no, ripup_costs, pinsToGo, routed_count, not_routed_count,
                insert_error_count, extraViasThisPass, escapeStats, true, passStart);
            return routed_count;
          }
        }
      }
    }
    int extraViasThisPass = Math.max(0, this.routing_board.get_vias().size() - viasBeforePass);
    this.extraViasTotal += extraViasThisPass;
    EscapeStatistics escapeStats = computeEscapeStatistics();

    long passDurationMs = System.currentTimeMillis() - passStart;
    FRLogger.trace("BatchFanout.fanout_pass", "pass_end",
        "pass=" + (p_pass_no + 1)
            + ", durationMs=" + passDurationMs
            + ", routed=" + routed_count
            + ", notRouted=" + not_routed_count
            + ", insertErrors=" + insert_error_count
            + ", alreadyConnected=" + already_connected_count
            + ", escaped=" + escapeStats.escapedCount()
            + "/" + escapeStats.totalSmdPins()
            + " (" + String.format("%.1f", escapeStats.escapedPercentage()) + "%)",
        "", new app.freerouting.geometry.planar.Point[0]);

    if (progressListener == null) {
      FRLogger.info(
          "fanout pass: "
              + (p_pass_no + 1)
              + ", routed: "
              + routed_count
              + ", not routed: "
              + not_routed_count
              + ", errors: "
              + insert_error_count
              + ", extra vias: +"
              + extraViasThisPass
              + ", escaped SMD pins: "
              + escapeStats.escapedCount()
              + "/"
              + escapeStats.totalSmdPins()
              + " ("
              + String.format("%.1f", escapeStats.escapedPercentage())
              + "%)");
    }
    this.lastNotRoutedCount = not_routed_count;
    publishProgress(progressListener, p_pass_no, ripup_costs, pinsToGo, routed_count, not_routed_count,
        insert_error_count, extraViasThisPass, escapeStats, true, passStart);

    return routed_count;
  }

  private void maybePublishProgress(FanoutProgressListener progressListener, int passNo, int ripupCosts, int pinsToGo,
      int routedCount, int notRoutedCount, int insertErrorCount, int extraViasThisPass, boolean passCompleted,
      long passStart) {
    if (passCompleted || progressThrottler.shouldUpdate()) {
      // Mid-pass interim updates use a lightweight empty escape statistics placeholder
      // to avoid the cost of a full escape scan on every tick.
      EscapeStatistics interimEscape = new EscapeStatistics(this.totalSmdPinCount, 0, 0.0);
      publishProgress(progressListener, passNo, ripupCosts, pinsToGo, routedCount, notRoutedCount, insertErrorCount,
          extraViasThisPass, interimEscape, passCompleted, passStart);
    }
  }

  private void publishProgress(FanoutProgressListener progressListener, int passNo, int ripupCosts, int pinsToGo,
      int routedCount, int notRoutedCount, int insertErrorCount, int extraViasThisPass,
      EscapeStatistics escapeStatistics, boolean passCompleted, long passStart) {
    if (progressListener == null) {
      return;
    }
    BoardStatistics boardStatistics = this.routing_board.get_statistics();
    long duration = Math.max(0, System.currentTimeMillis() - passStart);
    progressListener.onProgress(
        new FanoutPassStatus(
            passNo + 1,
            ripupCosts,
            this.totalSmdPinCount,
            pinsToGo,
            routedCount,
            notRoutedCount,
            insertErrorCount,
            extraViasThisPass,
            this.extraViasTotal + extraViasThisPass,
            duration,
            boardStatistics,
            passCompleted,
            escapeStatistics));
  }

  /**
   * Computes escape statistics for all SMD pins currently in the sorted_components list.
   * A pin is considered "escaped" when it has at least one Trace (wire) or Via directly
   * connected to it at the pin's center — with no clearance violations on that trace/via —
   * or when a Via connected to the pin itself has a Trace connected to it.
   */
  EscapeStatistics computeEscapeStatistics() {
    int total = 0;
    int escaped = 0;
    for (Component component : this.sorted_components) {
      for (Component.Pin pin : component.smd_pins) {
        total++;
        if (isPinEscaped(pin.board_pin)) {
          escaped++;
        } else {
          // Log details about non-escaped pins to aid investigation of incomplete fanout
          app.freerouting.board.Pin boardPin = pin.board_pin;
          String pinName = component.board_component.name + "-" + boardPin.name();
          int netNo = boardPin.net_count() > 0 ? boardPin.get_net_no(0) : 0;
          Set<Item> contacts = boardPin.get_normal_contacts();
          int contactCount = contacts.size();
          int traceContacts = 0;
          int viaContacts = 0;
          int tracesWithViolations = 0;
          int viasWithViolations = 0;
          int viasWithoutTrace = 0;
          for (Item contact : contacts) {
            if (contact instanceof Trace) {
              traceContacts++;
              if (!contact.clearance_violations().isEmpty()) tracesWithViolations++;
            } else if (contact instanceof Via) {
              viaContacts++;
              if (!contact.clearance_violations().isEmpty()) {
                viasWithViolations++;
              } else {
                boolean hasTraceContact = false;
                for (Item viaContact : contact.get_normal_contacts()) {
                  if (viaContact instanceof Trace) { hasTraceContact = true; break; }
                }
                if (!hasTraceContact) viasWithoutTrace++;
              }
            }
          }
          FRLogger.trace("BatchFanout.computeEscape", "pin_not_escaped",
              "pin=" + pinName
                  + ", net=" + netNo
                  + ", center=" + boardPin.get_center()
                  + ", layer=" + boardPin.first_layer()
                  + ", contacts=" + contactCount
                  + ", traces=" + traceContacts
                  + " (withViolations=" + tracesWithViolations + ")"
                  + ", vias=" + viaContacts
                  + " (withViolations=" + viasWithViolations
                  + ", noTraceAttached=" + viasWithoutTrace + ")",
              pinName, new app.freerouting.geometry.planar.Point[]{boardPin.get_center()});
        }
      }
    }
    double pct = total == 0 ? 0.0 : 100.0 * escaped / total;
    return new EscapeStatistics(total, escaped, pct);
  }

  /**
   * Returns {@code true} if the given SMD pin has a valid escape route:
   * <ul>
   *   <li>A {@link Trace} (wire) is directly connected to the pin center with no clearance
   *       violations on that trace, <em>or</em></li>
   *   <li>A {@link Via} is directly connected to the pin center with no clearance violations
   *       on the via, <em>and</em> that via has at least one {@link Trace} connected to it.</li>
   * </ul>
   */
  private boolean isPinEscaped(app.freerouting.board.Pin pin) {
    Set<Item> contacts = pin.get_normal_contacts();
    for (Item contact : contacts) {
      if (contact instanceof Trace trace) {
        // Direct wire exit from the pin — check no clearance violations on the trace itself
        if (trace.clearance_violations().isEmpty()) {
          return true;
        }
      } else if (contact instanceof Via via) {
        // Via planted on the pin — check the via is clean and has at least one trace attached
        if (via.clearance_violations().isEmpty()) {
          Set<Item> viaContacts = via.get_normal_contacts();
          for (Item viaContact : viaContacts) {
            if (viaContact instanceof Trace) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @FunctionalInterface
  public interface FanoutProgressListener {
    void onProgress(FanoutPassStatus status);
  }

  /**
   * Statistics about how many SMD pins were successfully escaped after a fanout pass.
   * A pin is considered escaped when it has at least one Trace (wire) or Via directly connected
   * to it (with no clearance violations on the trace/via), or a Via that itself has a Trace
   * connected to it (also without clearance violations).
   */
  public record EscapeStatistics(
      int totalSmdPins,
      int escapedCount,
      double escapedPercentage) {

    @Override
    public String toString() {
      return String.format("%d/%d (%.1f%%)", escapedCount, totalSmdPins, escapedPercentage);
    }
  }

  public record FanoutPassStatus(
      int passNo,
      int ripupCosts,
      int totalPins,
      int pinsToGo,
      int routedCount,
      int notRoutedCount,
      int insertErrorCount,
      int extraViasThisPass,
      int extraViasTotal,
      long passDurationMillis,
      BoardStatistics boardStatistics,
      boolean passCompleted,
      EscapeStatistics escapeStatistics) {
  }

  public record FanoutRunSummary(
      int completedPassCount,
      long totalDurationMillis,
      EscapeStatistics escapeStatistics) {
  }

  private static class Component implements Comparable<Component> {

    final app.freerouting.board.Component board_component;
    final int smd_pin_count;
    final SortedSet<Pin> smd_pins;
    /** The center of gravity of all SMD pins of this component. */
    final FloatPoint gravity_center_of_smd_pins;
    final String pinSortingOrder;

    Component(
        app.freerouting.board.Component p_board_component,
        Collection<app.freerouting.board.Pin> p_board_smd_pin_list,
        String p_pin_sorting_order,
        RoutingBoard p_routing_board) {
      this.board_component = p_board_component;
      this.pinSortingOrder = p_pin_sorting_order;

      // calculate the center of gravity of all SMD pins of this component.
      Collection<app.freerouting.board.Pin> curr_pin_list =
          new LinkedList<>();
      int cmp_no = p_board_component.no;
      for (app.freerouting.board.Pin curr_board_pin : p_board_smd_pin_list) {
        if (curr_board_pin.get_component_no() == cmp_no) {
          curr_pin_list.add(curr_board_pin);
        }
      }
      double x = 0;
      double y = 0;
      for (app.freerouting.board.Pin curr_pin : curr_pin_list) {
        FloatPoint curr_point = curr_pin.get_center().to_float();
        x += curr_point.x;
        y += curr_point.y;
      }
      this.smd_pin_count = curr_pin_list.size();
      if (this.smd_pin_count > 0) {
        x /= this.smd_pin_count;
        y /= this.smd_pin_count;
      }
      this.gravity_center_of_smd_pins = new FloatPoint(x, y);

      // calculate the sorted SMD pins of this component
      this.smd_pins = new TreeSet<>();

      for (app.freerouting.board.Pin curr_board_pin : curr_pin_list) {
        this.smd_pins.add(new Pin(curr_board_pin, p_board_smd_pin_list, p_routing_board));
      }
    }

    /** Sort the components, so that components with more pins come first */
    @Override
    public int compareTo(Component p_other) {
      int compare_value = this.smd_pin_count - p_other.smd_pin_count;
      int result;
      if (compare_value > 0) {
        result = -1;
      } else if (compare_value < 0) {
        result = 1;
      } else {
        result = this.board_component.no - p_other.board_component.no;
      }
      return result;
    }

    class Pin implements Comparable<Pin> {

      final app.freerouting.board.Pin board_pin;
      final double distance_to_component_center;
      final double distance_to_closest_on_net;
      final int surroundings_density;

      Pin(app.freerouting.board.Pin p_board_pin, Collection<app.freerouting.board.Pin> p_board_smd_pin_list, RoutingBoard p_routing_board) {
        this.board_pin = p_board_pin;
        FloatPoint pin_location = p_board_pin.get_center().to_float();
        this.distance_to_component_center = pin_location.distance(gravity_center_of_smd_pins);

        // distance_to_closest_on_net calculation
        double minDistance = Double.MAX_VALUE;
        int netNo = p_board_pin.net_count() > 0 ? p_board_pin.get_net_no(0) : 0;
        if (netNo > 0) {
          for (app.freerouting.board.Pin otherPin : p_routing_board.get_pins()) {
            if (otherPin != p_board_pin && otherPin.contains_net(netNo)) {
              double dist = pin_location.distance(otherPin.get_center().to_float());
              if (dist < minDistance) {
                minDistance = dist;
              }
            }
          }
        }
        this.distance_to_closest_on_net = minDistance;

        // surroundings_density calculation
        double resolution = p_routing_board.communication.get_resolution(app.freerouting.board.Unit.UM);
        double maxDist = 20000.0 * resolution; // 20.0 mm in coordinate units
        int density = 0;
        for (app.freerouting.board.Pin otherPin : p_board_smd_pin_list) {
          if (otherPin != p_board_pin) {
            double dist = pin_location.distance(otherPin.get_center().to_float());
            if (dist <= maxDist) {
              density++;
            }
          }
        }
        this.surroundings_density = density;
      }

      @Override
      public int compareTo(Pin p_other) {
        int result = 0;
        if ("inner_first".equals(pinSortingOrder)) {
          double delta_dist =
              this.distance_to_component_center - p_other.distance_to_component_center;
          if (delta_dist > 0) {
            result = 1;
          } else if (delta_dist < 0) {
            result = -1;
          }
        } else if ("outer_first".equals(pinSortingOrder)) {
          double delta_dist =
              this.distance_to_component_center - p_other.distance_to_component_center;
          if (delta_dist > 0) {
            result = -1;
          } else if (delta_dist < 0) {
            result = 1;
          }
        } else if ("distance_to_closest_on_net".equals(pinSortingOrder)) {
          double delta = this.distance_to_closest_on_net - p_other.distance_to_closest_on_net;
          if (delta > 0) {
            result = 1;
          } else if (delta < 0) {
            result = -1;
          }
        } else if ("surroundings_density".equals(pinSortingOrder)) {
          int delta = p_other.surroundings_density - this.surroundings_density; // densest first
          if (delta > 0) {
            result = 1;
          } else if (delta < 0) {
            result = -1;
          }
        }
        if (result == 0) {
          result = this.board_pin.pin_no - p_other.board_pin.pin_no;
        }
        return result;
      }
    }
  }
}