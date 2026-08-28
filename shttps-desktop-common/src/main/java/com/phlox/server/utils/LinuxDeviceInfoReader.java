package com.phlox.server.utils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The pieces of host information the Linux kernel hands out as plain files: how long the machine has
 * been up, how much memory it really has, and what the machine calls itself.
 * <p>
 * Same shape as {@link SysfsBatteryReader} - file reads only, no native library and no subprocess, so
 * it is cheap enough for a request thread, and its directories are constructor parameters so the
 * parsing can be tested on any OS.
 * <p>
 * Every value is optional. A container, a VM or a locked down machine exposes some of these and not
 * others, and a fact we cannot read leaves its field null rather than failing the read.
 */
public class LinuxDeviceInfoReader {
    public static final Path DEFAULT_PROC_DIR = Path.of("/proc");
    /** Where the firmware describes the machine. Absent on most VMs and in most containers. */
    public static final Path DEFAULT_DMI_DIR = Path.of("/sys/devices/virtual/dmi/id");

    private final Path procDir;
    private final Path dmiDir;

    public LinuxDeviceInfoReader() {
        this(DEFAULT_PROC_DIR, DEFAULT_DMI_DIR);
    }

    public LinuxDeviceInfoReader(Path procDir, Path dmiDir) {
        this.procDir = procDir;
        this.dmiDir = dmiDir;
    }

    /** Uptime of the machine in milliseconds, or null. */
    public Long readUptimeMillis() {
        //"12345.67 54321.00" - seconds since boot, then idle time summed over all cores
        String uptime = readString(procDir.resolve("uptime"));
        if (uptime == null) return null;
        String seconds = uptime.split("\\s+")[0];
        try {
            double value = Double.parseDouble(seconds);
            return value >= 0 ? (long) (value * 1000) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Total physical memory in bytes, or null. */
    public Long readTotalRamBytes() {
        return readMemInfoBytes("MemTotal");
    }

    /**
     * Memory that could still be handed out, in bytes, or null.
     * <p>
     * Deliberately MemAvailable and not MemFree: most of a healthy machine's memory is page cache,
     * which MemFree does not count, so MemFree makes a perfectly comfortable 16 GB host look like it
     * is down to its last few hundred megabytes. MemAvailable predates every kernel we care about,
     * but it is absent on very old ones, in which case this is simply null.
     */
    public Long readAvailableRamBytes() {
        return readMemInfoBytes("MemAvailable");
    }

    /** Who made the machine, per its firmware, or null. */
    public String readManufacturer() {
        return sanitizeDmi(readString(dmiDir.resolve("sys_vendor")));
    }

    /** What the machine calls itself, per its firmware, or null. */
    public String readModel() {
        return sanitizeDmi(readString(dmiDir.resolve("product_name")));
    }

    /** The machine's host name, or null. */
    public String readHostName() {
        //the kernel's own copy, which unlike $HOSTNAME is always there and always current
        String hostName = readString(procDir.resolve("sys/kernel/hostname"));
        return hostName == null || hostName.isEmpty() ? null : hostName;
    }

    /**
     * One {@code /proc/meminfo} entry, converted from the kB the file reports into bytes.
     */
    private Long readMemInfoBytes(String key) {
        List<String> lines = readLines(procDir.resolve("meminfo"));
        if (lines == null) return null;
        String prefix = key + ":";
        for (String line : lines) {
            if (!line.startsWith(prefix)) continue;
            //"MemTotal:       16316412 kB"
            String[] parts = line.substring(prefix.length()).trim().split("\\s+");
            if (parts.length == 0) return null;
            try {
                long value = Long.parseLong(parts[0]);
                if (value < 0) return null;
                //the unit is kB on every kernel that writes this file, but do not assume it is there
                boolean kilobytes = parts.length < 2 || parts[1].equalsIgnoreCase("kB");
                return kilobytes ? value * 1024 : value;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Firmware strings are famous for being placeholders. "To Be Filled By O.E.M." on the status page
     * is worse than no row at all.
     */
    private static String sanitizeDmi(String value) {
        if (value == null) return null;
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("to be filled") || lower.contains("o.e.m.") || lower.contains("default string")
                || lower.equals("system manufacturer") || lower.equals("system product name")
                || lower.equals("unknown") || lower.equals("none")) {
            return null;
        }
        return value;
    }

    private String readString(Path file) {
        try {
            if (!Files.isReadable(file)) return null;
            String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Throwable e) {
            return null;
        }
    }

    private List<String> readLines(Path file) {
        try {
            if (!Files.isReadable(file)) return null;
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }
}
