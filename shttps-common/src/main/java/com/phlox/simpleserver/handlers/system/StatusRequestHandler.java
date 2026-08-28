package com.phlox.simpleserver.handlers.system;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.channels.ChannelManager;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.json.JSONObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

public class StatusRequestHandler implements RequestHandler {
    private final SHTTPSApp app;
    protected final SHTTPSConfig config;
    protected final AuthManager authManager;

    public StatusRequestHandler(SHTTPSApp app, AuthManager authManager) {
        this.app = app;
        this.config = app.config;
        this.authManager = authManager;
    }

    @Override
    public Response handleRequest(RequestContext context, Request request) throws Exception {
        if (!request.method.equals(Request.METHOD_GET)) {
            return StandardResponses.METHOD_NOT_ALLOWED(new String[]{Request.METHOD_GET});
        }
        User user = checkUser(context);
        String scopesStr = request.queryParams.get("scopes");
        List<String> scopes;
        if (scopesStr != null) {
            scopes = List.of(scopesStr.split(","));
        } else {
            scopes = List.of();
        }
        JSONObject answer = new JSONObject();
        if (user != null && (scopes.isEmpty() || scopes.contains("user"))) {
            JSONObject userInfo = new JSONObject();
            userInfo.put("identity", user.identity);
            userInfo.put("role", user.role);
            userInfo.put("system_rights", authManager.getUserRightsEvaluator().userSystemRights(user).toString());
            userInfo.put("db_rights", authManager.getUserRightsEvaluator().userDBRights(user).toString());
            userInfo.put("fs_rights", authManager.getUserRightsEvaluator().userFSRights(user).toString());
            userInfo.put("channel_rights", authManager.getUserRightsEvaluator().userChannelRights(user).toString());
            userInfo.put("storage_limit_bytes", authManager.getUserRightsEvaluator().getStorageLimit(user));
            userInfo.put("storage_used_bytes", user.usedStorage);
            userInfo.put("registered_at", user.registeredAt);
            answer.put("user", userInfo);
        }
        if (!checkIsForbidden(user, User.SystemRights.READ_STATUS)) {
            if (scopes.isEmpty() || scopes.contains("filesystem")) {
                JSONObject fsInfo = new JSONObject();
                fsInfo.put("storage_path", config.getRootDir().getUri());
                fsInfo.put("total_space_bytes", config.getRootDir().getStorageSize());
                fsInfo.put("free_space_bytes", config.getRootDir().getStorageFreeSpace());
                answer.put("filesystem", fsInfo);
            }
            Database db = app.getDatabase();
            if (db != null && (scopes.isEmpty() || scopes.contains("database"))) {
                JSONObject dbInfo = new JSONObject();
                Map<String, Object> bdStatus = db.getStatus();
                for (Map.Entry<String, Object> entry : bdStatus.entrySet()) {
                    dbInfo.put(entry.getKey(), entry.getValue());
                }
                answer.put("database", dbInfo);
            }
            if (scopes.isEmpty() || scopes.contains("channels")) {
                ChannelManager channelManager = app.getChannelManager();
                JSONObject channelsInfo = new JSONObject();
                channelsInfo.put("enabled", config.isChannelsEnabled());
                channelsInfo.put("predefined", channelManager.countPredefined());
                channelsInfo.put("dynamic", channelManager.countDynamic());
                channelsInfo.put("totalParticipants", channelManager.totalParticipants());
                answer.put("channels", channelsInfo);
            }
            if (scopes.isEmpty() || scopes.contains("device")) {
                SHTTPSPlatformUtils.DeviceInfo device = app.platformUtils.getDeviceInfo();
                if (device != null && device.hasAnyData()) {
                    JSONObject deviceInfo = new JSONObject();
                    //org.json drops keys with a null value, so unavailable fields simply do not appear
                    deviceInfo.put("device_name", device.deviceName);
                    deviceInfo.put("manufacturer", device.manufacturer);
                    deviceInfo.put("model", device.model);
                    deviceInfo.put("os_name", device.osName);
                    deviceInfo.put("os_release", device.osRelease);
                    deviceInfo.put("api_level", device.apiLevel);
                    //physical memory, unlike the JVM heap reported in the system scope
                    deviceInfo.put("ram_total_bytes", device.totalRamBytes);
                    deviceInfo.put("ram_available_bytes", device.availableRamBytes);
                    deviceInfo.put("system_uptime", device.systemUptimeMillis);
                    answer.put("device", deviceInfo);
                }
            }
            if (scopes.isEmpty() || scopes.contains("battery")) {
                SHTTPSPlatformUtils.BatteryInfo battery = app.platformUtils.getBatteryInfo();
                if (battery != null && battery.hasAnyData()) {
                    JSONObject batteryInfo = new JSONObject();
                    //org.json drops keys with a null value, so unavailable fields simply do not appear
                    batteryInfo.put("level_percent", battery.levelPercent);
                    batteryInfo.put("temperature_celsius", battery.temperatureCelsius);
                    batteryInfo.put("status", battery.status);
                    batteryInfo.put("health", battery.health);
                    batteryInfo.put("capacity_mah", battery.capacityMah);
                    batteryInfo.put("charge_counter_mah", battery.chargeCounterMah);
                    batteryInfo.put("voltage_millivolts", battery.voltageMillivolts);
                    batteryInfo.put("technology", battery.technology);
                    batteryInfo.put("power_source", battery.powerSource);
                    answer.put("battery", batteryInfo);
                }
            }
            if (scopes.isEmpty() || scopes.contains("system")) {
                JSONObject systemInfo = new JSONObject();
                systemInfo.put("server_name", app.serverVersionInfo.name);
                systemInfo.put("server_version", app.serverVersionInfo.version);
                systemInfo.put("server_uptime", System.currentTimeMillis() - app.serverStartTimeMillis);
                //the absolute counterpart of the uptime, so a viewer can see the server's clock and
                //how far it is from their own
                systemInfo.put("server_time", System.currentTimeMillis());
                systemInfo.put("server_timezone", TimeZone.getDefault().getID());
                systemInfo.put("os_name", System.getProperty("os.name"));
                systemInfo.put("os_version", System.getProperty("os.version"));
                systemInfo.put("java_runtime_name", System.getProperty("java.runtime.name"));
                systemInfo.put("java_vm_version", System.getProperty("java.vm.version"));
                systemInfo.put("cpu_cores", Runtime.getRuntime().availableProcessors());
                systemInfo.put("cpu_arch", System.getProperty("os.arch"));
                systemInfo.put("ram_total_bytes", Runtime.getRuntime().totalMemory());
                systemInfo.put("ram_free_bytes", Runtime.getRuntime().freeMemory());
                answer.put("system", systemInfo);
            }
        }

        return new TextResponse(200, "Ok", answer.toString(), "application/json");
    }

    protected @Nullable User checkUser(@NotNull RequestContext context) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return null;
        return authManager.getAuthenticatedUser(context);
    }

    protected boolean checkIsForbidden(@Nullable User user, User.SystemRights... right) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) return false;
        if (user == null) return true;
        EnumSet<User.SystemRights> fsRights = authManager.getUserRightsEvaluator().userSystemRights(user);
        return !fsRights.containsAll(List.of(right));
    }
}
