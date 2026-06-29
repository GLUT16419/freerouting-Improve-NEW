package app.freerouting.autoroute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NetTypeTest {

  @Test
  void groundNamesAreDetected() {
    assertEquals(NetType.GROUND, NetType.fromName("GND"));
    assertEquals(NetType.GROUND, NetType.fromName("VSS"));
    assertEquals(NetType.GROUND, NetType.fromName("AGND"));
    assertEquals(NetType.GROUND, NetType.fromName("DGND"));
    assertEquals(NetType.GROUND, NetType.fromName("SGND"));
    assertEquals(NetType.GROUND, NetType.fromName("PGND"));
    assertEquals(NetType.GROUND, NetType.fromName("GNDD"));
    assertEquals(NetType.GROUND, NetType.fromName("GROUND"));
    assertEquals(NetType.GROUND, NetType.fromName("SUBSTRATE"));
  }

  @Test
  void groundNamesWithPrefixSuffixAreDetected() {
    assertEquals(NetType.GROUND, NetType.fromName("GND_A"));
    assertEquals(NetType.GROUND, NetType.fromName("GND_D"));
    assertEquals(NetType.GROUND, NetType.fromName("VSS_PLL"));
    assertEquals(NetType.GROUND, NetType.fromName("AGND_PLL"));
    assertEquals(NetType.GROUND, NetType.fromName("DGND_IO"));
    assertEquals(NetType.GROUND, NetType.fromName("PWR_GND"));
    assertEquals(NetType.GROUND, NetType.fromName("CHASSIS_GND"));
  }

  @Test
  void powerNamesAreDetected() {
    assertEquals(NetType.POWER, NetType.fromName("VCC"));
    assertEquals(NetType.POWER, NetType.fromName("VDD"));
    assertEquals(NetType.POWER, NetType.fromName("VEE"));
    assertEquals(NetType.POWER, NetType.fromName("AVCC"));
    assertEquals(NetType.POWER, NetType.fromName("AVDD"));
    assertEquals(NetType.POWER, NetType.fromName("DVCC"));
    assertEquals(NetType.POWER, NetType.fromName("DVDD"));
    assertEquals(NetType.POWER, NetType.fromName("VBAT"));
    assertEquals(NetType.POWER, NetType.fromName("VREF"));
    assertEquals(NetType.POWER, NetType.fromName("VCORE"));
    assertEquals(NetType.POWER, NetType.fromName("VIO"));
    assertEquals(NetType.POWER, NetType.fromName("POWER"));
  }

  @Test
  void voltageValuesAreDetected() {
    assertEquals(NetType.POWER, NetType.fromName("3V3"));
    assertEquals(NetType.POWER, NetType.fromName("5V"));
    assertEquals(NetType.POWER, NetType.fromName("12V"));
    assertEquals(NetType.POWER, NetType.fromName("+5V"));
    assertEquals(NetType.POWER, NetType.fromName("+3V3"));
    assertEquals(NetType.POWER, NetType.fromName("+12V"));
    assertEquals(NetType.POWER, NetType.fromName("-5V"));
    assertEquals(NetType.POWER, NetType.fromName("-12V"));
    assertEquals(NetType.POWER, NetType.fromName("3.3V"));
    assertEquals(NetType.POWER, NetType.fromName("1.8V"));
  }

  @Test
  void powerNamesWithPrefixSuffixAreDetected() {
    assertEquals(NetType.POWER, NetType.fromName("VCC_IO"));
    assertEquals(NetType.POWER, NetType.fromName("VDD_CORE"));
    assertEquals(NetType.POWER, NetType.fromName("VCC3V3"));
    assertEquals(NetType.POWER, NetType.fromName("VDD_1V8"));
    assertEquals(NetType.POWER, NetType.fromName("VCCINT"));
    assertEquals(NetType.POWER, NetType.fromName("VCCAUX"));
    assertEquals(NetType.POWER, NetType.fromName("VCCIO"));
    assertEquals(NetType.POWER, NetType.fromName("PWR_5V"));
  }

  @Test
  void signalNamesRemainSignal() {
    assertEquals(NetType.SIGNAL, NetType.fromName("DATA0"));
    assertEquals(NetType.SIGNAL, NetType.fromName("CLK"));
    assertEquals(NetType.SIGNAL, NetType.fromName("RST"));
    assertEquals(NetType.SIGNAL, NetType.fromName("ADDR0"));
    assertEquals(NetType.SIGNAL, NetType.fromName("TX"));
    assertEquals(NetType.SIGNAL, NetType.fromName("RX"));
    assertEquals(NetType.SIGNAL, NetType.fromName("USB_D"));
    assertEquals(NetType.SIGNAL, NetType.fromName("ETH_TX"));
    assertEquals(NetType.SIGNAL, NetType.fromName("GPIO0"));
    assertEquals(NetType.SIGNAL, NetType.fromName("SDA"));
    assertEquals(NetType.SIGNAL, NetType.fromName("SCL"));
  }

  @Test
  void nullAndEmptyReturnSignal() {
    assertEquals(NetType.SIGNAL, NetType.fromName(null));
    assertEquals(NetType.SIGNAL, NetType.fromName(""));
  }

  @Test
  void caseInsensitiveMatching() {
    assertEquals(NetType.GROUND, NetType.fromName("gnd"));
    assertEquals(NetType.GROUND, NetType.fromName("Gnd"));
    assertEquals(NetType.POWER, NetType.fromName("vcc"));
    assertEquals(NetType.POWER, NetType.fromName("Vcc"));
  }

  @Test
  void powerGroundClassification() {
    assertEquals(true, NetType.POWER.isPowerOrGround());
    assertEquals(true, NetType.GROUND.isPowerOrGround());
    assertEquals(false, NetType.SIGNAL.isPowerOrGround());
  }
}
