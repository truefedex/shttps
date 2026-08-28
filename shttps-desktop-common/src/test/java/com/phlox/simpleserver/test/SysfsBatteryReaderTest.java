package com.phlox.simpleserver.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.utils.SysfsBatteryReader;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils.BatteryInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The Linux battery reader, exercised against a fake {@code /sys/class/power_supply} in a temp
 * directory. The reader takes its root as a constructor parameter precisely so that these run
 * anywhere, including on the Windows and macOS machines where the reader itself never fires.
 */
public class SysfsBatteryReaderTest {

    @TempDir
    Path root;

    private void supply(String name, Map<String, String> files) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Files.write(dir.resolve(entry.getKey()), (entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private BatteryInfo read() {
        return new SysfsBatteryReader(root).read();
    }

    @Test
    public void aPhoneStyleBatteryReportsEveryFieldTheCardShows() throws IOException {
        supply("battery", Map.of(
                "type", "Battery",
                "present", "1",
                "capacity", "52",
                "status", "Discharging",
                "health", "Good",
                "temp", "261",
                "voltage_now", "3874000",
                "technology", "Li-ion",
                "charge_full_design", "4410000",
                "charge_now", "2293000"));

        BatteryInfo info = read();
        assertNotNull(info);
        assertEquals(52, info.levelPercent);
        assertEquals(BatteryInfo.STATUS_DISCHARGING, info.status);
        assertEquals(BatteryInfo.HEALTH_GOOD, info.health);
        //decidegrees Celsius in the file, degrees on the card
        assertEquals(26.1f, info.temperatureCelsius.floatValue(), 0.001f);
        assertEquals(3874, info.voltageMillivolts);
        assertEquals("Li-ion", info.technology);
        assertEquals(4410, info.capacityMah);
        assertEquals(2293, info.chargeCounterMah);
    }

    @Test
    public void aLaptopReportingEnergyIsConvertedToMilliampHours() throws IOException {
        //most laptop batteries expose uWh instead of uAh: 50 Wh at 11.1 V is 4504 mAh
        supply("BAT0", Map.of(
                "type", "Battery",
                "present", "1",
                "capacity", "88",
                "status", "Charging",
                "energy_full_design", "50000000",
                "energy_now", "44000000",
                "voltage_min_design", "11100000",
                "voltage_now", "12300000"));

        BatteryInfo info = read();
        assertNotNull(info);
        assertEquals(4504, info.capacityMah);
        assertEquals(3963, info.chargeCounterMah);
        //the reported voltage stays the live one, the conversion uses the design voltage
        assertEquals(12300, info.voltageMillivolts);
    }

    @Test
    public void energyWithoutAVoltageIsOmittedRatherThanReportedAsMilliampHours() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "70",
                "energy_full_design", "50000000"));

        BatteryInfo info = read();
        assertNotNull(info);
        assertEquals(70, info.levelPercent);
        //50000000 uWh is not 50000 mAh, and a wrong number is worse than a missing row
        assertNull(info.capacityMah);
    }

    @Test
    public void theDesignCapacityIsPreferredOverTheCurrentFullCapacity() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "charge_full_design", "4410000",
                "charge_full", "3900000"));

        assertEquals(4410, read().capacityMah);
    }

    @Test
    public void aWornBatteryWithoutADesignFigureFallsBackToItsCurrentFullCapacity() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "charge_full", "3900000"));

        assertEquals(3900, read().capacityMah);
    }

    @Test
    public void aChargeOutsideEveryPlausibleBatteryIsDropped() throws IOException {
        //a driver reporting mAh where it documents uAh: 4410 uAh would become 4 mAh on the card
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "charge_full_design", "4410"));

        assertNull(read().capacityMah);
    }

    @Test
    public void aMissingThermistorReadsAsNoTemperatureRatherThanFreezing() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "temp", "0"));

        //a literal 0 would render as a perfectly plausible looking "0.0 C"
        assertNull(read().temperatureCelsius);
    }

    @Test
    public void unknownStatusAndHealthLeaveTheirRowsOut() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "status", "Unknown",
                "health", "Watchdog timer expire"));

        BatteryInfo info = read();
        assertNull(info.status);
        assertNull(info.health);
    }

    @Test
    public void unparseableFilesLoseOnlyTheirOwnField() throws IOException {
        supply("BAT0", Map.of(
                "type", "Battery",
                "capacity", "40",
                "voltage_now", "not a number",
                "status", "Full"));

        BatteryInfo info = read();
        assertNotNull(info);
        assertEquals(40, info.levelPercent);
        assertEquals(BatteryInfo.STATUS_FULL, info.status);
        assertNull(info.voltageMillivolts);
    }

    @Test
    public void chargersAreNotMistakenForBatteries() throws IOException {
        supply("AC", Map.of("type", "Mains", "online", "1"));
        supply("BAT0", Map.of("type", "Battery", "capacity", "40", "status", "Charging"));

        BatteryInfo info = read();
        assertNotNull(info);
        assertEquals(40, info.levelPercent);
        assertEquals(BatteryInfo.POWER_SOURCE_AC, info.powerSource);
    }

    @Test
    public void anOfflineChargerReadsAsRunningOnBattery() throws IOException {
        supply("AC", Map.of("type", "Mains", "online", "0"));
        supply("BAT0", Map.of("type", "Battery", "capacity", "40", "status", "Discharging"));

        assertEquals(BatteryInfo.POWER_SOURCE_NONE, read().powerSource);
    }

    @Test
    public void anEmptyBatteryBayIsSkipped() throws IOException {
        supply("BAT0", Map.of("type", "Battery", "present", "0", "capacity", "0"));
        supply("BAT1", Map.of("type", "Battery", "present", "1", "capacity", "77"));

        assertEquals(77, read().levelPercent);
    }

    @Test
    public void aDesktopWithNoBatteryReportsNothing() throws IOException {
        supply("AC", Map.of("type", "Mains", "online", "1"));

        assertNull(read());
    }

    @Test
    public void anEmptyPowerSupplyClassReportsNothing() {
        assertNull(read());
    }

    @Test
    public void aMissingPowerSupplyClassReportsNothingRatherThanThrowing() {
        //what a non-Linux machine would see if the reader were ever called there
        assertNull(new SysfsBatteryReader(root.resolve("no-such-directory")).read());
    }
}
