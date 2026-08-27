package com.phlox.server.utils;

import com.phlox.simpleserver.utils.SHTTPSPlatformUtils.BatteryInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the host battery from the Linux power supply class, {@code /sys/class/power_supply}.
 * <p>
 * Plain file reads - no native library, no subprocess - so this is cheap enough to do on a request
 * thread. The root directory is a constructor parameter so that the parsing can be tested on any OS.
 * <p>
 * Every value is optional: a kernel driver exposes only what its hardware reports, so a missing or
 * unparseable file leaves that one field null instead of failing the whole read.
 */
public class SysfsBatteryReader {
    public static final Path DEFAULT_ROOT = Path.of("/sys/class/power_supply");

    private final Path root;

    public SysfsBatteryReader() {
        this(DEFAULT_ROOT);
    }

    public SysfsBatteryReader(Path root) {
        this.root = root;
    }

    /**
     * @return the first present battery, or null when this machine exposes none.
     */
    public BatteryInfo read() {
        try {
            if (!Files.isDirectory(root)) return null;
            List<Path> supplies = listSupplies();
            for (Path supply : supplies) {
                if (!"battery".equals(lowerCase(readString(supply, "type")))) continue;
                //an empty battery bay
                if ("0".equals(readString(supply, "present"))) continue;
                BatteryInfo info = readBattery(supply);
                if (info.hasAnyData()) {
                    info.powerSource = readPowerSource(supplies);
                    return info;
                }
            }
            return null;
        } catch (Throwable e) {
            //optional information on the status page - it must never fail the status response
            return null;
        }
    }

    private List<Path> listSupplies() throws Exception {
        try (Stream<Path> entries = Files.list(root)) {
            //sorted so that a machine with two batteries always reports the same one
            return entries.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    private BatteryInfo readBattery(Path supply) {
        BatteryInfo info = new BatteryInfo();

        Long level = readLong(supply, "capacity");
        if (level != null && level >= 0) {
            info.levelPercent = (int) Math.min(100, level);
        }

        //decidegrees Celsius. A 0 here means there is no thermistor, and would otherwise render as
        //a perfectly plausible looking "0.0 C"
        Long temperature = readLong(supply, "temp");
        if (temperature != null && temperature != 0 && temperature > -500 && temperature < 1500) {
            info.temperatureCelsius = temperature / 10f;
        }

        info.status = mapStatus(readString(supply, "status"));
        info.health = mapHealth(readString(supply, "health"));

        Long voltageUv = readLong(supply, "voltage_now");
        if (voltageUv != null && voltageUv > 0) {
            info.voltageMillivolts = (int) (voltageUv / 1000);
        }

        String technology = readString(supply, "technology");
        if (technology != null && !"unknown".equals(lowerCase(technology))) {
            info.technology = technology;
        }

        //a driver exposes either the charge (uAh) files or the energy (uWh) ones, rarely both.
        //Design capacity first; a battery too old or too generic to report one falls back to what a
        //full charge currently holds, which is still a measured figure rather than a derived guess
        info.capacityMah = chargeMah(supply, "charge_full_design", "energy_full_design");
        if (info.capacityMah == null) {
            info.capacityMah = chargeMah(supply, "charge_full", "energy_full");
        }
        info.chargeCounterMah = chargeMah(supply, "charge_now", "energy_now");

        return info;
    }

    /**
     * A charge in mAh, taken from {@code chargeFile} (uAh) when the driver has it, otherwise converted
     * from {@code energyFile} (uWh) using the battery voltage. Null when neither is available - reporting
     * uWh as though it were mAh would simply be a wrong number - and null again when the result is
     * outside {@link #saneMah}, since a driver answering nonsense should cost us the row, not the card.
     */
    private Integer chargeMah(Path supply, String chargeFile, String energyFile) {
        Long chargeUah = readLong(supply, chargeFile);
        if (chargeUah != null && chargeUah > 0) {
            return saneMah(chargeUah / 1000);
        }
        Long energyUwh = readLong(supply, energyFile);
        if (energyUwh == null || energyUwh <= 0) return null;
        Long voltageUv = readLong(supply, "voltage_min_design");
        if (voltageUv == null || voltageUv <= 0) voltageUv = readLong(supply, "voltage_now");
        if (voltageUv == null || voltageUv <= 0) return null;
        //Ah = Wh / V, and both sides are in micro-units, so the prefixes cancel
        return saneMah(1000L * energyUwh / voltageUv);
    }

    /**
     * A charge only counts if it could plausibly belong to a battery. Nothing forces a kernel driver to
     * report the unit it documents, and a number two orders of magnitude out would land on the status
     * page looking perfectly ordinary.
     */
    static Integer saneMah(long mah) {
        return mah >= 50 && mah <= 100000 ? (int) mah : null;
    }

    /**
     * Whether the machine is on mains or USB power, read from the other power supplies in the same
     * directory. Null when this kernel exposes no charger at all.
     */
    private String readPowerSource(List<Path> supplies) {
        boolean anyCharger = false;
        String usb = null;
        for (Path supply : supplies) {
            String type = lowerCase(readString(supply, "type"));
            if (type == null || "battery".equals(type)) continue;
            anyCharger = true;
            if (!"1".equals(readString(supply, "online"))) continue;
            if ("mains".equals(type)) {
                return BatteryInfo.POWER_SOURCE_AC;
            } else if ("wireless".equals(type)) {
                return BatteryInfo.POWER_SOURCE_WIRELESS;
            } else if (usb == null) {
                //"USB", but also the USB-C variants: USB_PD, USB_DCP, ...
                usb = BatteryInfo.POWER_SOURCE_USB;
            }
        }
        if (usb != null) return usb;
        return anyCharger ? BatteryInfo.POWER_SOURCE_NONE : null;
    }

    private static String mapStatus(String status) {
        if (status == null) return null;
        switch (lowerCase(status)) {
            case "charging": return BatteryInfo.STATUS_CHARGING;
            case "discharging": return BatteryInfo.STATUS_DISCHARGING;
            case "full": return BatteryInfo.STATUS_FULL;
            case "not charging": return BatteryInfo.STATUS_NOT_CHARGING;
            default: return null;
        }
    }

    private static String mapHealth(String health) {
        if (health == null) return null;
        switch (lowerCase(health)) {
            case "good": return BatteryInfo.HEALTH_GOOD;
            case "overheat": return BatteryInfo.HEALTH_OVERHEAT;
            case "cold": return BatteryInfo.HEALTH_COLD;
            case "dead": return BatteryInfo.HEALTH_DEAD;
            case "over voltage": return BatteryInfo.HEALTH_OVER_VOLTAGE;
            case "unspecified failure": return BatteryInfo.HEALTH_UNSPECIFIED_FAILURE;
            default: return null;
        }
    }

    private static String lowerCase(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String readString(Path supply, String name) {
        try {
            Path file = supply.resolve(name);
            if (!Files.isReadable(file)) return null;
            String value = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Throwable e) {
            return null;
        }
    }

    private Long readLong(Path supply, String name) {
        String value = readString(supply, name);
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
