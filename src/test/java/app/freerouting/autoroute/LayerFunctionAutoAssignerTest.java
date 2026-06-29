package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.ItemIdentificationNumberGenerator;
import app.freerouting.board.LayerStructure;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.HeadlessBoardManager;
import org.junit.jupiter.api.Test;

class LayerFunctionAutoAssignerTest extends RoutingFixtureTest {

  @Test
  void assignFunctionsFromDac2020Board() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    LayerStructure layerStructure = boardManager.get_routing_board().layer_structure;
    assertNotNull(layerStructure);
    assertTrue(layerStructure.arr.length > 0, "Board should have at least one layer");

    LayerFunction[] functions = LayerFunctionAutoAssigner.assignFunctions(layerStructure);
    assertNotNull(functions);
    assertEquals(layerStructure.arr.length, functions.length);

    // All layers should have a function assigned (not null)
    for (int i = 0; i < functions.length; i++) {
      assertNotNull(functions[i], "Layer " + i + " should have a function assigned");
    }

    // At least one signal layer should be routable
    int routableCount = LayerFunctionAutoAssigner.countRoutableLayers(functions);
    assertTrue(routableCount > 0, "At least one layer should be routable");
  }

  @Test
  void assignFunctionsFromEmptyBoard() {
    RoutingJob job = GetRoutingJob("empty_board.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    LayerStructure layerStructure = boardManager.get_routing_board().layer_structure;
    LayerFunction[] functions = LayerFunctionAutoAssigner.assignFunctions(layerStructure);
    assertNotNull(functions);

    for (LayerFunction f : functions) {
      assertNotNull(f);
    }
  }

  @Test
  void nullLayerStructureReturnsEmptyArray() {
    LayerFunction[] functions = LayerFunctionAutoAssigner.assignFunctions(null);
    assertNotNull(functions);
    assertEquals(0, functions.length);
  }

  @Test
  void routableLayerCountMatchesSignals() {
    RoutingJob job = GetRoutingJob("Issue508-DAC2020_bm01.dsn");
    HeadlessBoardManager boardManager = new HeadlessBoardManager(job);
    try {
      boardManager.loadFromSpecctraDsn(job.input.getData(), null, new ItemIdentificationNumberGenerator());
    } catch (Exception e) {
      throw new RuntimeException("Failed to load DSN board", e);
    }

    LayerStructure layerStructure = boardManager.get_routing_board().layer_structure;
    LayerFunction[] functions = LayerFunctionAutoAssigner.assignFunctions(layerStructure);

    int routableCount = LayerFunctionAutoAssigner.countRoutableLayers(functions);
    int signalLayerCount = layerStructure.signal_layer_count();

    // Routable count should be >= signal layer count (MIXED layers also count)
    assertTrue(routableCount >= signalLayerCount,
        "Routable layers (" + routableCount + ") should be >= signal layers (" + signalLayerCount + ")");
  }
}
