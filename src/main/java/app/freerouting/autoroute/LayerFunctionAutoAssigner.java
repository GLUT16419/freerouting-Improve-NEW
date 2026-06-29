package app.freerouting.autoroute;

import app.freerouting.board.Layer;
import app.freerouting.board.LayerStructure;
import app.freerouting.logger.FRLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Automatically assigns layer functions to all layers in a board.
 * <p>
 * Uses a two-pass strategy:
 * <ol>
 *   <li><b>Name-based heuristic</b> — match common layer names (TOP, GND, POWER, BOTTOM, ...)</li>
 *   <li><b>Stackup-context refinement</b> — apply mainstream multi‑layer PCB patterns
 *       (4‑layer, 6‑layer, 8‑layer, 10‑layer) to layers that are still ambiguous</li>
 * </ol>
 */
public class LayerFunctionAutoAssigner {

  /**
   * Main entry: analyze and assign functions for every layer in the given structure.
   * Also writes the result back into each {@link Layer#function} field.
   */
  public static LayerFunction[] assignFunctions(LayerStructure layerStructure) {
    if (layerStructure == null || layerStructure.arr == null) {
      return new LayerFunction[0];
    }

    int layerCount = layerStructure.arr.length;
    LayerFunction[] functions = new LayerFunction[layerCount];
    List<Integer> signalLayerIndices = new ArrayList<>();
    List<Integer> planeLayerIndices = new ArrayList<>();

    // ── Pass 1: name-based heuristic ──────────────────────────────────────
    for (int i = 0; i < layerCount; i++) {
      Layer layer = layerStructure.arr[i];
      if (layer == null) {
        functions[i] = LayerFunction.OTHER;
        continue;
      }

      LayerFunction nameBased = LayerFunction.fromName(layer.name);
      if (nameBased == LayerFunction.OTHER && !layer.is_signal) {
        functions[i] = LayerFunction.MECHANICAL;
      } else if (nameBased == LayerFunction.SIGNAL || nameBased == LayerFunction.OTHER) {
        functions[i] = layer.is_signal ? LayerFunction.SIGNAL : LayerFunction.OTHER;
      } else {
        functions[i] = nameBased;
      }

      if (functions[i] == LayerFunction.SIGNAL || functions[i] == LayerFunction.MIXED) {
        signalLayerIndices.add(i);
      } else if (functions[i].isPlane()) {
        planeLayerIndices.add(i);
      }
    }

    // ── Pass 2: mainstream multi‑layer stackup patterns ───────────────────
    applyMainstreamStackupPattern(functions, layerStructure);

    // ── Pass 3: write back to Layer objects ────────────────────────────────
    for (int i = 0; i < layerCount; i++) {
      if (layerStructure.arr[i] != null) {
        layerStructure.arr[i].function = functions[i];
      }
    }

    // ── Log ────────────────────────────────────────────────────────────────
    StringBuilder sb = new StringBuilder("Layer Function Assignment complete:\n");
    for (int i = 0; i < layerCount; i++) {
      String layerName = layerStructure.arr[i] != null ? layerStructure.arr[i].name : "null";
      sb.append("  Layer ").append(i).append(" [").append(layerName).append("] -> ").append(functions[i]).append("\n");
    }
    FRLogger.debug(sb.toString());

    return functions;
  }

  // ─────────────────────────────────────────────────────────────────────────
  //  Mainstream stackup patterns
  //  (based on JEDEC / IPC‑2221 / common industry practice)
  // ─────────────────────────────────────────────────────────────────────────
  private static void applyMainstreamStackupPattern(LayerFunction[] functions,
                                                    LayerStructure layerStructure) {
    int n = functions.length;
    if (n < 4) return; // only 2‑layer or 3‑layer — keep name‑based result

    // Count how many inner layers are still marked as SIGNAL
    int innerSignalCount = 0;
    for (int i = 1; i < n - 1; i++) {
      if (functions[i] == LayerFunction.SIGNAL) innerSignalCount++;
    }

    // 4‑layer board:  TOP(Signal) – GND – PWR – BOTTOM(Signal)
    if (n == 4 && innerSignalCount >= 2) {
      // If the two inner layers are still SIGNAL, assign GND / POWER from bottom to top
      if (functions[1] == LayerFunction.SIGNAL) functions[1] = LayerFunction.GROUND_PLANE;
      if (functions[2] == LayerFunction.SIGNAL) functions[2] = LayerFunction.POWER_PLANE;
    }

    // 6‑layer board: TOP(S) – GND – SIG – SIG – PWR – BOTTOM(S)
    if (n == 6) {
      if (functions[1] == LayerFunction.SIGNAL) functions[1] = LayerFunction.GROUND_PLANE;
      if (functions[4] == LayerFunction.SIGNAL) functions[4] = LayerFunction.POWER_PLANE;
    }

    // 8‑layer board: TOP(S) – GND – SIG – GND – PWR – SIG – GND – BOTTOM(S)
    if (n == 8) {
      if (functions[1] == LayerFunction.SIGNAL) functions[1] = LayerFunction.GROUND_PLANE;
      if (functions[3] == LayerFunction.SIGNAL) functions[3] = LayerFunction.GROUND_PLANE;
      if (functions[4] == LayerFunction.SIGNAL) functions[4] = LayerFunction.POWER_PLANE;
      if (functions[6] == LayerFunction.SIGNAL) functions[6] = LayerFunction.GROUND_PLANE;
    }

    // 10‑layer board: TOP(S) – GND – SIG – SIG – GND – PWR – SIG – SIG – GND – BOTTOM(S)
    if (n == 10) {
      if (functions[1] == LayerFunction.SIGNAL) functions[1] = LayerFunction.GROUND_PLANE;
      if (functions[4] == LayerFunction.SIGNAL) functions[4] = LayerFunction.GROUND_PLANE;
      if (functions[5] == LayerFunction.SIGNAL) functions[5] = LayerFunction.POWER_PLANE;
      if (functions[8] == LayerFunction.SIGNAL) functions[8] = LayerFunction.GROUND_PLANE;
    }

    // For any board >= 4 layers, if the outermost inner layers are still SIGNAL
    // but adjacent to an already‑classified plane, leave them be.
    // (layer‑count‑specific patterns above already handle the common cases.)
  }

  /** Determines if a layer should be considered routable based on its assigned function. */
  public static boolean isLayerRoutable(LayerFunction function) {
    return function != null && function.isRoutable();
  }

  /** Counts the number of routable layers for a given assignment. */
  public static int countRoutableLayers(LayerFunction[] functions) {
    int count = 0;
    for (LayerFunction f : functions) {
      if (f != null && f.isRoutable()) count++;
    }
    return count;
  }
}
