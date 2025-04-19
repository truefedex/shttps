package com.phlox.simpleserver.test;

import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;

public class FileServerTestConfig {
    private final String serverUrl;
    private final boolean useLocalServer;
    private final SHTTPSConfig config;
    private final SHTTPSPlatformUtils platformUtils;
    private final SHTTPSDatabaseFabric databaseFabric;

    public FileServerTestConfig(String serverUrl, boolean useLocalServer, 
                              SHTTPSConfig config, SHTTPSPlatformUtils platformUtils,
                              SHTTPSDatabaseFabric databaseFabric) {
        this.serverUrl = serverUrl;
        this.useLocalServer = useLocalServer;
        this.config = config;
        this.platformUtils = platformUtils;
        this.databaseFabric = databaseFabric;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public boolean isUseLocalServer() {
        return useLocalServer;
    }

    public SHTTPSConfig getConfig() {
        return config;
    }

    public SHTTPSPlatformUtils getPlatformUtils() {
        return platformUtils;
    }

    public SHTTPSDatabaseFabric getDatabaseFabric() {
        return databaseFabric;
    }

    public static FileServerTestConfig forLocalServer(SHTTPSConfig config, 
                                                    SHTTPSPlatformUtils platformUtils,
                                                    SHTTPSDatabaseFabric databaseFabric) {
        return new FileServerTestConfig("http://localhost:" + config.getPort(), 
                                      true, config, platformUtils, databaseFabric);
    }

    public static FileServerTestConfig forRemoteServer(String serverUrl) {
        return new FileServerTestConfig(serverUrl, false, null, null, null);
    }
} 