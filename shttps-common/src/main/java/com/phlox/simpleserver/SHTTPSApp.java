package com.phlox.simpleserver;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.LoggingMiddleware;
import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.handlers.Router;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.Utils;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.DummyAuthManager;
import com.phlox.simpleserver.auth.web.InMemorySessionManager;
import com.phlox.simpleserver.auth.web.LoginRequestHandler;
import com.phlox.simpleserver.auth.web.LogoutRequestHandler;
import com.phlox.simpleserver.auth.web.SessionManager;
import com.phlox.simpleserver.auth.web.WebAuthManager;
import com.phlox.simpleserver.auth.web.WebAuthMiddleware;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.auth.ConfigBasedUserStore;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.auth.basic.BasicAuthManager;
import com.phlox.simpleserver.auth.basic.BasicAuthMiddleware;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
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
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SHTTPSApp {
    private static SHTTPSApp instance;
    public final SHTTPSConfig config;
    public final SHTTPSPlatformUtils platformUtils;
    public final SHTTPSDatabaseFabric databaseFabric;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    private final Holder<Database> database = new Holder<>(null);

    private final Router router = new Router();
    public LoggingMiddleware loggingMiddleware;
    private UserStore userStore;
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

    public synchronized void startServer() throws UnrecoverableKeyException, CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        //Setup middlewares
        Router.Middlewares authMiddlewares = new Router.Middlewares();
        router.globalMiddlewares.reset();

        final String loginFormPath = "/shttps-pages/login/";
        AuthManager authManager;
        SessionManager sessionManager = null;
        switch (config.getAuthMode()) {
            case BASIC_AUTH:
                authManager = new BasicAuthManager(provideUserStore(true));
                BasicAuthMiddleware authMiddleware = new BasicAuthMiddleware(authManager);
                authMiddlewares.addPreMiddleware(authMiddleware);
                break;
            case WEB:
                sessionManager = new InMemorySessionManager();
                authManager = new WebAuthManager(provideUserStore(true), sessionManager);
                WebAuthMiddleware authMiddleware1 = new WebAuthMiddleware(authManager, loginFormPath);
                authMiddlewares.addPreMiddleware(authMiddleware1);
                break;
            default:
                authManager = new DummyAuthManager();
                break;
        }

        RedirectsMiddleware redirectsMiddleware = new RedirectsMiddleware();
        List<RedirectsMiddleware.RedirectRule> redirectRules = config.getRedirectRules();
        redirectsMiddleware.setRedirectRules(Collections.emptyList());
        if (redirectRules != null) {
            for (RedirectsMiddleware.RedirectRule rule : redirectRules) {
                if (rule.enabled) {
                    redirectsMiddleware.addRedirectRule(rule);
                }
            }
        }
        router.globalMiddlewares.addPreMiddleware(redirectsMiddleware);

        loggingMiddleware = new LoggingMiddleware(1000);
        router.globalMiddlewares.addPostMiddleware(loggingMiddleware);

        //Setup routes
        router.resetRoutes();
        //file handlers
        router.addRoute("/api/file/download", Set.of("GET"), new StaticFileRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/new-folder", Set.of("POST"), new NewFolderRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/rename", Set.of("POST"), new RenameFileRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/upload", Set.of("PUT"), new UploadFileRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/delete", Set.of("DELETE"), new DeleteFileRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/move", Set.of("POST"), new MoveFileRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/list", Set.of("GET"), new FileListRequestHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/thumbnail", Set.of("GET"), new ThumbnailHandler(config, authManager), authMiddlewares);
        router.addRoute("/api/file/zip", Set.of("POST"), new ZipDownloadRequestHandler(config, authManager), authMiddlewares);

        //database handlers
        router.addRoute("/api/db/schema", Set.of("GET"), new DBSchemaRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/table", Set.of("GET", "POST"), new DBTableDataRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/insert", Set.of("POST"), new DBInsertRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/update", Set.of("PUT"), new DBUpdateRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/delete", Set.of("DELETE"), new DBDeleteRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/query", Set.of("POST"), new DBCustomSQLRequestHandler(database, config), authMiddlewares);
        router.addRoute("/api/db/cell-data", Set.of("GET", "POST"), new DBSingleCellDataRequestHandler(database, config), authMiddlewares);

        //auth handlers (if any)
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.WEB)) {
            router.addRoute("/api/user/login", Set.of("POST"), new LoginRequestHandler(authManager, sessionManager));
            router.addRoute("/api/user/logout", Set.of("POST"), new LogoutRequestHandler(authManager), authMiddlewares);
        }

        //build-in pages
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.WEB)) {
            router.addRoute(loginFormPath, Set.of("GET"), (context, request) -> {
                try (InputStream is = platformUtils.openAssetStream("shttps-static-public/auth/login.html")) {
                    String html = new String(Utils.readAllBytes(is), StandardCharsets.UTF_8);
                    return new TextResponse(html, "text/html");
                } catch (IOException e) {
                    throw new RuntimeException("Can not read assets");
                }
            });
        }

        router.addRouteByPathPrefix("/shttps-static-public",
                new StaticAssetsRequestHandler("shttps-static-public", "/shttps-static-public", config), authMiddlewares);

        FilesRequestHandler filesRequestHandler = new FilesRequestHandler(config, authManager);
        filesRequestHandler.renderFolders = config.getRenderFolders();
        filesRequestHandler.allowEditing = config.getAllowEditing();
        router.addRouteByPathPrefix("/", filesRequestHandler, authMiddlewares);


        SimpleHttpServer srv = new SimpleHttpServer.Builder()
                .setRequestHandler(router)
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
                        Callback callback = SHTTPSApp.this.callback;
                        if (callback != null) {
                            callback.onNewConnection(socket);
                        }
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
    }

    public synchronized void stopServer() {
        SimpleHttpServer srv = this.server;
        if (srv != null && srv.isListenThreadRunning()) {
            srv.stopListen();
            server = null;
        }
    }

    public UserStore provideUserStore() {
        return provideUserStore(false);
    }

    public UserStore provideUserStore(boolean invalidate) {
        UserStore userStore = this.userStore;
        if (invalidate || userStore == null) {
            userStore = new ConfigBasedUserStore(config);
            this.userStore = userStore;
        }
        return userStore;
    }

    public boolean isServerRunning() {
        SimpleHttpServer srv = this.server;
        return srv != null && srv.isListenThreadRunning();
    }

    public void restartServerIfRunning() {
        SimpleHttpServer srv = this.server;
        if (srv != null && srv.isListenThreadRunning()) {
            srv.stopListen();
            server = null;
            try {
                startServer();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
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

        void onNewConnection(Socket socket);
    }
}
