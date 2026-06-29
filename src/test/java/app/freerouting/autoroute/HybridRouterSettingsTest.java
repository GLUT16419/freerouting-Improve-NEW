package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HybridRouterSettingsTest {

  @Test
  void defaultSettingsAllPhasesEnabled() {
    HybridRouterSettings settings = new HybridRouterSettings();
    assertTrue(settings.phase0Enabled);
    assertTrue(settings.phase1Enabled);
    assertTrue(settings.phase2Enabled);
    assertTrue(settings.phase3Enabled);
  }

  @Test
  void qualityLevelDraftDisablesOptionalPhases() {
    HybridRouterSettings settings = new HybridRouterSettings();
    settings.qualityLevel = HybridRouterSettings.QualityLevel.DRAFT;
    settings.applyQualityLevel();

    assertFalse(settings.phase0Enabled);
    assertTrue(settings.phase1Enabled);
    assertFalse(settings.phase2Enabled);
    assertFalse(settings.phase3Enabled);
    assertEquals(1, settings.maxRefinementIterations);
    assertEquals(10, settings.negotiationMaxIterations);
  }

  @Test
  void qualityLevelBalancedDefault() {
    HybridRouterSettings settings = new HybridRouterSettings();
    assertEquals(HybridRouterSettings.QualityLevel.BALANCED, settings.qualityLevel);
  }

  @Test
  void qualityLevelBalancedAppliesCorrectly() {
    HybridRouterSettings settings = new HybridRouterSettings();
    settings.qualityLevel = HybridRouterSettings.QualityLevel.BALANCED;
    settings.applyQualityLevel();

    assertTrue(settings.phase0Enabled);
    assertTrue(settings.phase1Enabled);
    assertTrue(settings.phase2Enabled);
    assertFalse(settings.phase3Enabled);
    assertEquals(3, settings.maxRefinementIterations);
  }

  @Test
  void qualityLevelHighEnablesAllPhases() {
    HybridRouterSettings settings = new HybridRouterSettings();
    settings.qualityLevel = HybridRouterSettings.QualityLevel.HIGH;
    settings.applyQualityLevel();

    assertTrue(settings.phase0Enabled);
    assertTrue(settings.phase1Enabled);
    assertTrue(settings.phase2Enabled);
    assertTrue(settings.phase3Enabled);
    assertEquals(5, settings.maxRefinementIterations);
    assertEquals(30, settings.satMaxCandidatesPerNet);
  }

  @Test
  void cloneProducesEqualIndependentCopy() {
    HybridRouterSettings original = new HybridRouterSettings();
    original.qualityLevel = HybridRouterSettings.QualityLevel.HIGH;
    original.shortNetThresholdUm = 10000;
    original.maxNetsPerCluster = 15;

    HybridRouterSettings cloned = original.clone();
    assertEquals(original.qualityLevel, cloned.qualityLevel);
    assertEquals(original.shortNetThresholdUm, cloned.shortNetThresholdUm);
    assertEquals(original.maxNetsPerCluster, cloned.maxNetsPerCluster);

    // Verify independence
    cloned.shortNetThresholdUm = 5000;
    assertEquals(10000, original.shortNetThresholdUm);
  }

  @Test
  void autoFeaturesEnabledByDefault() {
    HybridRouterSettings settings = new HybridRouterSettings();
    assertTrue(settings.autoAssignLayerFunctions);
    assertTrue(settings.autoLabelPowerGnd);
  }

  @Test
  void satSettingsDefaultValues() {
    HybridRouterSettings settings = new HybridRouterSettings();
    assertEquals(20, settings.satMaxCandidatesPerNet);
    assertEquals(5, settings.satMaxRelaxationLevels);
  }
}
