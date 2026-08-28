package com.phlox.simpleserver.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.phlox.server.utils.LinuxDeviceInfoReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Linux host reader, exercised against fake {@code /proc} and DMI directories in a temp folder.
 * Both roots are constructor parameters for exactly this reason - these run on any OS, including the
 * Windows machine where the reader itself never fires.
 */
public class LinuxDeviceInfoReaderTest {

    @TempDir
    Path root;

    private Path procDir() {
        return root.resolve("proc");
    }

    private Path dmiDir() {
        return root.resolve("dmi");
    }

    private LinuxDeviceInfoReader reader() {
        return new LinuxDeviceInfoReader(procDir(), dmiDir());
    }

    private void write(Path dir, String name, String content) throws IOException {
        Path file = Files.createDirectories(dir).resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    // --- uptime ---

    @Test
    public void uptimeIsReadFromItsFirstFieldAndConvertedToMilliseconds() throws IOException {
        //seconds since boot, then idle time summed over every core
        write(procDir(), "uptime", "350735.47 234388.90\n");

        assertEquals(350735470L, reader().readUptimeMillis());
    }

    @Test
    public void aMissingUptimeFileIsNotAnError() {
        assertNull(reader().readUptimeMillis());
    }

    @Test
    public void anUnparseableUptimeIsDroppedRatherThanThrowing() throws IOException {
        write(procDir(), "uptime", "not a number\n");

        assertNull(reader().readUptimeMillis());
    }

    // --- memory ---

    @Test
    public void memoryIsReadFromMeminfoAndConvertedFromKilobytes() throws IOException {
        write(procDir(), "meminfo",
                "MemTotal:       16316412 kB\n"
                        + "MemFree:          412332 kB\n"
                        + "MemAvailable:   12043180 kB\n"
                        + "Buffers:          283916 kB\n");

        LinuxDeviceInfoReader reader = reader();
        assertEquals(16316412L * 1024, reader.readTotalRamBytes());
        //MemAvailable, not MemFree - the difference here is 11.6 GB of reclaimable page cache
        assertEquals(12043180L * 1024, reader.readAvailableRamBytes());
    }

    @Test
    public void anOldKernelWithoutMemAvailableStillReportsTheTotal() throws IOException {
        write(procDir(), "meminfo", "MemTotal:        2048000 kB\nMemFree:          128000 kB\n");

        LinuxDeviceInfoReader reader = reader();
        assertEquals(2048000L * 1024, reader.readTotalRamBytes());
        //better no bar at all than one drawn from MemFree, which would claim the machine is nearly full
        assertNull(reader.readAvailableRamBytes());
    }

    @Test
    public void aMeminfoLineThatIsNotANumberLosesOnlyThatValue() throws IOException {
        write(procDir(), "meminfo", "MemTotal:       corrupted kB\nMemAvailable:   12043180 kB\n");

        LinuxDeviceInfoReader reader = reader();
        assertNull(reader.readTotalRamBytes());
        assertEquals(12043180L * 1024, reader.readAvailableRamBytes());
    }

    @Test
    public void aMissingMeminfoIsNotAnError() {
        LinuxDeviceInfoReader reader = reader();
        assertNull(reader.readTotalRamBytes());
        assertNull(reader.readAvailableRamBytes());
    }

    // --- machine identity ---

    @Test
    public void theManufacturerAndModelComeFromTheFirmwareTables() throws IOException {
        write(dmiDir(), "sys_vendor", "LENOVO\n");
        write(dmiDir(), "product_name", "20XW00KGUS\n");

        LinuxDeviceInfoReader reader = reader();
        assertEquals("LENOVO", reader.readManufacturer());
        assertEquals("20XW00KGUS", reader.readModel());
    }

    @Test
    public void eitherFirmwareStringMayBeMissingOnItsOwn() throws IOException {
        write(dmiDir(), "sys_vendor", "QEMU\n");

        LinuxDeviceInfoReader reader = reader();
        assertEquals("QEMU", reader.readManufacturer());
        assertNull(reader.readModel());
    }

    @Test
    public void firmwarePlaceholdersAreTreatedAsNoAnswer() throws IOException {
        //what a great many desktop motherboards actually ship with
        write(dmiDir(), "sys_vendor", "To Be Filled By O.E.M.\n");
        write(dmiDir(), "product_name", "Default string\n");

        LinuxDeviceInfoReader reader = reader();
        assertNull(reader.readManufacturer());
        assertNull(reader.readModel());
    }

    @Test
    public void aMachineWithNoDmiTablesReportsNeither() {
        //a container, or most VMs
        LinuxDeviceInfoReader reader = reader();
        assertNull(reader.readManufacturer());
        assertNull(reader.readModel());
    }

    @Test
    public void theHostNameComesFromTheKernelRatherThanTheEnvironment() throws IOException {
        write(procDir().resolve("sys").resolve("kernel"), "hostname", "build-box\n");

        assertEquals("build-box", reader().readHostName());
    }

    @Test
    public void aMissingHostNameIsNotAnError() {
        assertNull(reader().readHostName());
    }

    @Test
    public void nothingAtAllReadsAsNothingRatherThanThrowing() {
        //what a non-Linux machine would see if the reader were ever called there
        LinuxDeviceInfoReader reader =
                new LinuxDeviceInfoReader(root.resolve("no-proc"), root.resolve("no-dmi"));
        assertNull(reader.readUptimeMillis());
        assertNull(reader.readTotalRamBytes());
        assertNull(reader.readAvailableRamBytes());
        assertNull(reader.readManufacturer());
        assertNull(reader.readModel());
        assertNull(reader.readHostName());
    }
}
