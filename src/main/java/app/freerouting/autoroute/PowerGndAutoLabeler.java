package app.freerouting.autoroute;

import app.freerouting.board.*;
import app.freerouting.datastructures.UndoableObjects;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.Net;
import app.freerouting.rules.Nets;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Automatically detects and labels power and ground nets.
 */
public class PowerGndAutoLabeler {

  private final Map<Integer, NetType> netTypeCache = new HashMap<>();
  private final Set<Integer> powerNets = new HashSet<>();
  private final Set<Integer> groundNets = new HashSet<>();
  private final Set<Integer> planeNets = new HashSet<>();
  private final BasicBoard board;

  public PowerGndAutoLabeler(BasicBoard board) {
    this.board = board;
  }

  public Map<Integer, NetType> autoLabelAllNets() {
    netTypeCache.clear();
    powerNets.clear();
    groundNets.clear();

    if (board == null || board.rules == null || board.rules.nets == null) {
      FRLogger.warn("PowerGndAutoLabeler: Board or nets data is null");
      return Collections.emptyMap();
    }

    Nets nets = board.rules.nets;
    int netCount = nets.max_net_no();
    for (int netNo = 1; netNo <= netCount; netNo++) {
      Net net = nets.get(netNo);
      if (net == null) continue;
      NetType type = classifyNet(net);
      netTypeCache.put(net.net_number, type);
      if (type == NetType.POWER) powerNets.add(net.net_number);
      else if (type == NetType.GROUND) groundNets.add(net.net_number);
    }

    detectPlaneNets();
    refinePlaneNetClassifications();
    logResults();
    return Collections.unmodifiableMap(netTypeCache);
  }

  private NetType classifyNet(Net net) {
    if (net == null || net.name == null) return NetType.SIGNAL;
    NetType nameBased = NetType.fromName(net.name);
    if (nameBased != NetType.SIGNAL) return nameBased;
    if (net.contains_plane()) return determineTypeFromConnectivity(net);
    return NetType.SIGNAL;
  }

  private NetType determineTypeFromConnectivity(Net net) {
    if (board == null) return NetType.SIGNAL;
    Collection<Pin> pins = net.get_pins();
    if (pins.isEmpty()) return NetType.SIGNAL;
    int gndCount = 0;
    int vccCount = 0;
    for (Pin pin : pins) {
      String name = null;
      try {
        // Try to get pin name from component info
        name = board.components.get(pin.get_component_no()).get_package().get_pin(pin.pin_no).name;
      } catch (Exception e) {
        // Pin name not available
      }
      if (name == null) continue;
      String up = name.toUpperCase();
      if (up.equals("GND") || up.equals("VSS") || up.contains("GND")) gndCount++;
      else if (up.equals("VCC") || up.equals("VDD") || up.contains("VCC") || up.contains("VDD")) vccCount++;
    }
    if (gndCount > vccCount && gndCount > 0) return NetType.GROUND;
    if (vccCount > gndCount && vccCount > 0) return NetType.POWER;
    return NetType.SIGNAL;
  }

  private void detectPlaneNets() {
    planeNets.clear();
    if (board == null) return;
    Iterator<UndoableObjects.UndoableObjectNode> it = board.item_list.start_read_object();
    while (true) {
      Item currItem = (Item) board.item_list.read_object(it);
      if (currItem == null) break;
      if (currItem instanceof Connectable && !currItem.is_routable()) {
        int netCount = currItem.net_count();
        for (int i = 0; i < netCount; i++) {
          planeNets.add(currItem.get_net_no(i));
        }
      }
    }
    for (int netNo : planeNets) {
      NetType current = netTypeCache.get(netNo);
      if (current == NetType.POWER || current == NetType.GROUND) continue;
      // Re-classify plane nets by their name instead of blindly marking GROUND
      Net net = findNetByNumber(netNo);
      if (net != null && net.name != null) {
        NetType nameBased = NetType.fromName(net.name);
        if (nameBased == NetType.POWER) {
          netTypeCache.put(netNo, NetType.POWER);
          powerNets.add(netNo);
        } else if (nameBased == NetType.GROUND) {
          netTypeCache.put(netNo, NetType.GROUND);
          groundNets.add(netNo);
        } else {
          // If name-based classification fails, try connectivity
          NetType connBased = determineTypeFromConnectivity(net);
          netTypeCache.put(netNo, connBased);
          if (connBased == NetType.GROUND) groundNets.add(netNo);
          else if (connBased == NetType.POWER) powerNets.add(netNo);
        }
      } else {
        // No name available — keep as SIGNAL rather than blindly GROUND
        netTypeCache.put(netNo, NetType.SIGNAL);
      }
    }
  }

  private void refinePlaneNetClassifications() {
    for (int netNo : planeNets) {
      NetType currentType = netTypeCache.get(netNo);
      if (currentType == null || currentType == NetType.SIGNAL) {
        Net net = findNetByNumber(netNo);
        if (net != null) {
          NetType refinedType = determineTypeFromConnectivity(net);
          if (refinedType != NetType.SIGNAL) {
            netTypeCache.put(netNo, refinedType);
            if (refinedType == NetType.POWER) { powerNets.add(netNo); groundNets.remove(netNo); }
            else { groundNets.add(netNo); powerNets.remove(netNo); }
          }
        }
      }
    }
  }

  private Net findNetByNumber(int netNo) {
    if (board.rules == null || board.rules.nets == null) return null;
    return board.rules.nets.get(netNo);
  }

  public NetType getNetType(int netNumber) { return netTypeCache.getOrDefault(netNumber, NetType.SIGNAL); }
  public boolean isPowerNet(int netNumber) { return powerNets.contains(netNumber); }
  public boolean isGroundNet(int netNumber) { return groundNets.contains(netNumber); }
  public boolean isPowerOrGroundNet(int netNumber) {
    NetType type = netTypeCache.get(netNumber);
    return type != null && type.isPowerOrGround();
  }
  public Set<Integer> getPowerNetNumbers() { return Collections.unmodifiableSet(powerNets); }
  public Set<Integer> getGroundNetNumbers() { return Collections.unmodifiableSet(groundNets); }
  public int getPowerGroundCount() { return powerNets.size() + groundNets.size(); }

  private void logResults() {
    StringBuilder sb = new StringBuilder("Power/GND Auto-Labeler Results:\n");
    sb.append("  Power nets (").append(powerNets.size()).append("), ");
    sb.append("Ground nets (").append(groundNets.size()).append("), ");
    sb.append("Plane nets (").append(planeNets.size()).append(")");
    FRLogger.debug(sb.toString());
  }
}
