package com.phlox.simpleserver;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.BasicAuthRequestHandler;
import com.phlox.server.handlers.LoggingRequestHandler;
import com.phlox.server.handlers.RoutingRequestHandler;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.handlers.main.MainRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSchemaRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSingleCellDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBTableDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBInsertRequestHandler;
import com.phlox.simpleserver.handlers.database.DBUpdateRequestHandler;
import com.phlox.simpleserver.handlers.database.DBDeleteRequestHandler;
import com.phlox.simpleserver.handlers.database.DBCustomSQLRequestHandler;
import com.phlox.simpleserver.handlers.files.DeleteFileRequestHandler;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.handlers.files.MoveFileRequestHandler;
import com.phlox.simpleserver.handlers.files.NewFolderRequestHandler;
import com.phlox.simpleserver.handlers.files.RenameFileRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticAssetsRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticFileRequestHandler;
import com.phlox.simpleserver.handlers.files.ThumbnailHandler;
import com.phlox.simpleserver.handlers.files.upload.UploadFileRequestHandler;
import com.phlox.simpleserver.handlers.files.ZipDownloadRequestHandler;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;


import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class SHTTPSApp {

    public static final int MAX_AUTH_ATTEMPTS_IF_IP_WHITELIST_NOT_SET = 10;
    private static SHTTPSApp instance;
    public final SHTTPSConfig config;
    public final SHTTPSPlatformUtils platformUtils;
    public final SHTTPSDatabaseFabric databaseFabric;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    public Holder<Database> database = new Holder<>(null);

    public final RoutingRequestHandler routingRequestHandler;
    public final MainRequestHandler mainRequestHandler;
    public final BasicAuthRequestHandler authRequestHandler;
    public final LoggingRequestHandler loggingRequestHandler;
    public Callback callback;
    private volatile SimpleHttpServer server;

    public static SHTTPSApp init(SHTTPSConfig config, SHTTPSPlatformUtils platformUtils, SHTTPSDatabaseFabric databaseFabric) {
        if (instance != null) {
            throw new IllegalStateException("App already initialized");
        }
        instance = new SHTTPSApp(config, platformUtils, databaseFabric);
        config.runMigrations();
        return instance;
    }

    public static SHTTPSApp getInstance() {
        if (instance == null) {
            throw new IllegalStateException("App not initialized");
        }
        return instance;
    }

    public static void destroy() {
        if (instance == null) {
            throw new IllegalStateException("App not initialized");
        }
        instance = null;
    }

    private SHTTPSApp(SHTTPSConfig config, SHTTPSPlatformUtils platformUtils, SHTTPSDatabaseFabric databaseFabric) {
        instance = this;
        this.config = config;
        this.platformUtils = platformUtils;
        this.databaseFabric = databaseFabric;

        DocumentFile www = config.getRootDir();
        if (www == null) {
            www = platformUtils.getDefaultRootDir();
            config.setRootDir(www.getUri());
        }

        loggingRequestHandler = new LoggingRequestHandler(1000);

        authRequestHandler = new BasicAuthRequestHandler();
        authRequestHandler.username = config.getUsername();
        authRequestHandler.password = config.getPassword();
        authRequestHandler.authEnabled = config.getUseBasicAuth();
        authRequestHandler.maxAttempts = config.getWhiteListMode().isEmpty() ? MAX_AUTH_ATTEMPTS_IF_IP_WHITELIST_NOT_SET : -1;
        loggingRequestHandler.childRequestHandler = authRequestHandler;

        routingRequestHandler = new RoutingRequestHandler();
        authRequestHandler.childHandler = routingRequestHandler;

        mainRequestHandler = new MainRequestHandler(www);
        mainRequestHandler.renderFolders = config.getRenderFolders();
        mainRequestHandler.allowEditing = config.getAllowEditing();

        //file handlers
        routingRequestHandler.addRoute("/api/file/download", new HashSet<>(Collections.singletonList("GET")), new StaticFileRequestHandler(www));
        routingRequestHandler.addRoute("/api/file/new-folder", new HashSet<>(Collections.singletonList("POST")), new NewFolderRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/rename", new HashSet<>(Collections.singletonList("POST")), new RenameFileRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/upload", new HashSet<>(Collections.singletonList("PUT")), new UploadFileRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/delete", new HashSet<>(Collections.singletonList("DELETE")), new DeleteFileRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/move", new HashSet<>(Collections.singletonList("POST")), new MoveFileRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/list", new HashSet<>(Collections.singletonList("GET")), new FileListRequestHandler(config));
        routingRequestHandler.addRoute("/api/file/thumbnail", new HashSet<>(Collections.singletonList("GET")), new ThumbnailHandler(config));
        routingRequestHandler.addRoute("/api/file/zip", new HashSet<>(Collections.singletonList("POST")), new ZipDownloadRequestHandler(config));

        //database handlers
        routingRequestHandler.addRoute("/api/db/schema", new HashSet<>(Collections.singletonList("GET")), new DBSchemaRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/table", new HashSet<>(Arrays.asList("GET", "POST")), new DBTableDataRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/insert", new HashSet<>(Collections.singletonList("POST")), new DBInsertRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/update", new HashSet<>(Collections.singletonList("PUT")), new DBUpdateRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/delete", new HashSet<>(Collections.singletonList("DELETE")), new DBDeleteRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/query", new HashSet<>(Collections.singletonList("POST")), new DBCustomSQLRequestHandler(database, config));
        routingRequestHandler.addRoute("/api/db/cell-data", new HashSet<>(Arrays.asList("GET", "POST")), new DBSingleCellDataRequestHandler(database, config));

        routingRequestHandler.addRouteByPathPrefix("/shttps-static-public", new StaticAssetsRequestHandler("shttps-static-public", "/shttps-static-public"));

        routingRequestHandler.addRouteByPathPrefix("/", mainRequestHandler);
    }

    public synchronized void initIO() {
        if (config.isDatabaseEnabled()) {
            try {
                Database database = databaseFabric.openDatabase(config.getDatabasePath());
                Map<String, Object> bdStatus = database.getStatus();
                logger.i("Database opened: " + bdStatus);
                setDatabase(database);
            } catch (Exception e) {
                logger.e("Failed to open database", e);
            }
        }
    }

    public synchronized SimpleHttpServer startServer() throws UnrecoverableKeyException, CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        SimpleHttpServer srv = new SimpleHttpServer.Builder()
                .setRequestHandler(loggingRequestHandler)
                .setCallback(new SimpleHttpServer.Callback() {
                    @Override
                    public void onServerStarted() {
                        logger.i("Server started");
                    }
                    @Override
                    public void onServerStopped() {
                        logger.i("Server stopped");
                    }
                    @Override
                    public void onNewConnection(Socket socket, long connectionNumber) {
                        SocketAddress address = socket.getRemoteSocketAddress();
                        logger.i("New connection #" + connectionNumber + " from " + (address != null ? address.toString() : "unknown address"));
                    }
                    @Override
                    public void onConnectionClosed(Socket socket) {
                        SocketAddress address = socket.getRemoteSocketAddress();
                        logger.i("Connection closed from " + (address  != null  ? address.toString()  : "unknown address"));
                    }
                    @Override
                    public void onConnectionError(Socket socket, Exception e) {
                        SocketAddress address = socket.getRemoteSocketAddress();
                        logger.e("Connection error from " + (address   != null   ? address.toString()    : "unknown address"));
                    }
                    @Override
                    public void onConnectionRequest(RequestContext context, Request request) {}
                    @Override
                    public void onConnectionResponse(RequestContext context, Request request, Response response) {}

                    @Override
                    public void onConnectionRejected(Socket socket, int reason) {
                        Callback callback = SHTTPSApp.this.callback;
                        if (callback != null) {
                            callback.onConnectionRejected(socket, reason);
                        }
                    }
                })
                .build();

        List<RoutingRequestHandler.RedirectRule> redirectRules = config.getRedirectRules();
        if (redirectRules == null) {
            redirectRules = genPredefinedRedirectRules();
            config.setRedirectRules(redirectRules);
        }
        routingRequestHandler.setRedirectRules(Collections.emptyList());
        for (RoutingRequestHandler.RedirectRule rule : redirectRules) {
            if (rule.enabled) {
                routingRequestHandler.addRedirectRule(rule);
            }
        }

        String[] allowedInterfaces = config.getAllowedNetworkInterfaces();
        if (allowedInterfaces != null && allowedInterfaces.length > 0) {
            srv.allowedNetworkInterfaces = platformUtils.findInterfaces(allowedInterfaces);
        }

        if (!config.getWhiteListMode().isEmpty()) {
            HashSet<InetAddress> allowedClientAddressesParsed = new HashSet<>();
            if (config.getWhiteListMode().contains(SHTTPSConfig.WhiteListMode.PREDEFINED)) {
                HashSet<String> allowedClientAddresses = config.getWhiteList();
                for (String address : allowedClientAddresses) {
                    try {
                        allowedClientAddressesParsed.add(InetAddress.getByName(address));
                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                }
            }
            srv.allowedClientAddresses = allowedClientAddressesParsed;
        }

        String customResponseHeadersStr = config.getCustomHeaders();
        if (!customResponseHeadersStr.isEmpty()) {
            Map<String, String> customResponseHeadersMap = HTTPUtils.parseHttpHeaders(customResponseHeadersStr);
            if (!customResponseHeadersMap.isEmpty()) {
                srv.additionalResponseHeaders = customResponseHeadersMap;
            }
        }

        if (config.getUseTLS()) {
            KeyStore cert = config.getTLSCert();
            String certPassword = config.getTLSCertPassword();
            if (cert == null)  {
                //use default self-signed cert
                String password = "z47x#vt6Rm$!y;LK";
                try (InputStream certStream = platformUtils.openAssetStream("default_self_signed.pfx")) {
                    cert = KeyStore.getInstance("PKCS12");
                    cert.load(certStream, password.toCharArray());
                    certPassword = password;
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to load default self-signed certificate", e);
                }
            } else if (certPassword == null) {
                throw new IllegalStateException("Certificate password not set!");
            }
            srv.startListen(config.getPort(), cert, certPassword);
        } else {
            srv.startListen(config.getPort());
        }
        this.server = srv;
        return srv;
    }

    public List<RoutingRequestHandler.RedirectRule> genPredefinedRedirectRules() {
        List<RoutingRequestHandler.RedirectRule> redirectRules = new ArrayList<>();

        RoutingRequestHandler.RedirectRule rule = new RoutingRequestHandler.RedirectRule(
                "^((?:/.+/)|/)$",
                "{1}index.html",
                0,
                RoutingRequestHandler.RedirectMode.INTERNAL,
                new RoutingRequestHandler.RedirectFlags[]{RoutingRequestHandler.RedirectFlags.IF_DEST_CAN_BE_PROCESSED},
                config.getRedirectToIndex(),
                "Redirect to index.html file inside the folder (only if index.html file exists)"
        );
        rule.shttpsInternal = true;
        redirectRules.add(rule);
        return redirectRules;
    }

    public synchronized void stopServer() {
        SimpleHttpServer srv = this.server;
        if (srv != null && srv.isListenThreadRunning()) {
            srv.stopListen();
            server = null;
        }
    }

    public synchronized boolean isServerRunning() {
        SimpleHttpServer srv = this.server;
        return srv != null && srv.isListenThreadRunning();
    }

    public void notifyConfigChanged() {
        if (isServerRunning()) {
            throw new IllegalStateException("Server is running, cannot change config");
        }
        //just reinit the app for now
        instance = new SHTTPSApp(config, platformUtils, databaseFabric);
    }

    public void setDatabase(Database db) {
        this.database.set(db);
    }

    public Database getDatabase() {
        return database.get();
    }

    public SimpleHttpServer getServer() {
        return server;
    }

    public interface Callback {
        void onConnectionRejected(Socket socket, int reason);
    }
}
