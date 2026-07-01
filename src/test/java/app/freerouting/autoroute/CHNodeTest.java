package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CHNodeTest {

  @Test
  void createNodeHasCorrectId() {
    CHNode node = new CHNode(42, 1, 5, 10, 100.0, 200.0);
    assertEquals(42, node.id);
    assertEquals(1, node.layer);
    assertEquals(5, node.gridX);
    assertEquals(10, node.gridY);
    assertEquals(100.0, node.floatX, 1e-6);
    assertEquals(200.0, node.floatY, 1e-6);
  }

  @Test
  void defaultStateIsFreeAndNotBlocked() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    assertTrue(node.isFree);
    assertFalse(node.isBlocked);
    assertEquals(0, node.importance);
    assertEquals(0, node.level);
    assertTrue(node.originalEdges.isEmpty());
    assertTrue(node.shortcuts.isEmpty());
  }

  @Test
  void addOriginalEdgeIncreasesDegree() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    node.addOriginalEdge(1, 10.0);
    assertEquals(1, node.originalEdges.size());
    assertEquals(10.0, node.getWeightTo(1), 1e-6);
  }

  @Test
  void addShortcutCreatesShortcutInfo() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    int[] bypassed = {5, 7};
    node.addShortcut(2, 5, 25.0, bypassed);
    assertTrue(node.shortcuts.containsKey(2));
    CHNode.ShortcutInfo si = node.shortcuts.get(2);
    assertEquals(5, si.viaNode);
    assertEquals(25.0, si.weight, 1e-6);
    assertArrayEquals(new int[]{5, 7}, si.bypassedNodes);
  }

  @Test
  void removeOriginalEdgeRemovesIt() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    node.addOriginalEdge(1, 10.0);
    node.addOriginalEdge(2, 20.0);
    node.removeOriginalEdge(1);
    assertFalse(node.originalEdges.containsKey(1));
    assertTrue(node.originalEdges.containsKey(2));
  }

  @Test
  void getWeightToReturnsInfinityForUnknown() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    assertEquals(Double.POSITIVE_INFINITY, node.getWeightTo(999), 1e-6);
  }

  @Test
  void getNeighbourIdsReturnsAllNeighbours() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    node.addOriginalEdge(1, 10.0);
    node.addOriginalEdge(2, 20.0);
    node.addShortcut(3, 1, 15.0, new int[]{1});
    var neighbours = node.getNeighbourIds();
    assertEquals(3, neighbours.size());
    assertTrue(neighbours.contains(1));
    assertTrue(neighbours.contains(2));
    assertTrue(neighbours.contains(3));
  }

  @Test
  void isContractedWhenOriginalEdgesEmptyAndHasShortcuts() {
    CHNode node = new CHNode(0, 0, 0, 0, 0, 0);
    assertFalse(node.isContracted());
    node.addShortcut(1, 2, 10.0, new int[]{2});
    assertTrue(node.isContracted());
  }

  @Test
  void distanceToCalculatesEuclidean() {
    CHNode a = new CHNode(0, 0, 0, 0, 0.0, 0.0);
    CHNode b = new CHNode(1, 0, 0, 0, 3.0, 4.0);
    assertEquals(5.0, a.distanceTo(b), 1e-6);
  }

  @Test
  void equalsById() {
    CHNode a = new CHNode(5, 0, 0, 0, 0, 0);
    CHNode b = new CHNode(5, 1, 2, 3, 10, 20);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void toStringContainsIdAndLevel() {
    CHNode node = new CHNode(7, 2, 3, 4, 10, 20);
    node.importance = 50;
    node.level = 3;
    String str = node.toString();
    assertTrue(str.contains("CHNode#7"));
    assertTrue(str.contains("L2"));
    assertTrue(str.contains("imp=50"));
    assertTrue(str.contains("lv=3"));
  }
}
