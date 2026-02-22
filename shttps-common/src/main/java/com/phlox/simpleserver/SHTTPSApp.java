package com.phlox.simpleserver;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.CORSMiddleware;
import com.phlox.server.handlers.RateLimitingMiddleware;
import com.phlox.server.handlers.RedirectsMiddleware;
import com.phlox.server.handlers.Router;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.ConfigBasedUserStore;
import com.phlox.simpleserver.auth.DBBasedUserStore;
import com.phlox.simpleserver.auth.DummyAuthManager;
import com.phlox.simpleserver.auth.UserStore;
import com.phlox.simpleserver.auth.basic.BasicAuthManager;
import com.phlox.simpleserver.auth.basic.BasicAuthMiddleware;
import com.phlox.simpleserver.auth.web.CaptchaManager;
import com.phlox.simpleserver.auth.web.CaptchaRequestHandler;
import com.phlox.simpleserver.auth.web.InMemorySessionManager;
import com.phlox.simpleserver.auth.web.LoginRequestHandler;
import com.phlox.simpleserver.auth.web.LogoutRequestHandler;
import com.phlox.simpleserver.auth.web.SessionManager;
import com.phlox.simpleserver.auth.web.UserRegistrationRequestHandler;
import com.phlox.simpleserver.auth.web.WebAuthManager;
import com.phlox.simpleserver.auth.web.WebAuthMiddleware;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseMigrator;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.exec.CgiMiddleware;
import com.phlox.simpleserver.handlers.database.DBCustomSQLRequestHandler;
import com.phlox.simpleserver.handlers.database.DBDeleteRequestHandler;
import com.phlox.simpleserver.handlers.database.DBInsertRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSchemaRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSingleCellDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBTableDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBUpdateRequestHandler;
import com.phlox.simpleserver.handlers.files.DeleteFileRequestHandler;
import com.phlox.simpleserver.handlers.files.FileListRequestHandler;
import com.phlox.simpleserver.handlers.files.MoveFileRequestHandler;
import com.phlox.simpleserver.handlers.files.NewFolderRequestHandler;
import com.phlox.simpleserver.handlers.files.RenameFileRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticAssetsRequestHandler;
import com.phlox.simpleserver.handlers.files.StaticFileRequestHandler;
import com.phlox.simpleserver.handlers.files.ThumbnailHandler;
import com.phlox.simpleserver.handlers.files.ZipDownloadRequestHandler;
import com.phlox.simpleserver.handlers.files.upload.UploadFileRequestHandler;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.handlers.system.StatusRequestHandler;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.ServerLogsCollector;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SHTTPSApp {
    private static SHTTPSApp instance;
    public final SHTTPSConfig config;
    public final SHTTPSPlatformUtils platformUtils;
    public final SHTTPSDatabaseFabric databaseFabric;
    private final SHTTPSLoggerProxy.Logger logger = SHTTPSLoggerProxy.getLogger(getClass());

    private final Holder<Database> database = new Holder<>(null);
    public final ServerLogsCollector logsCollector = new ServerLogsCollector(1000);
    public RateLimitingMiddleware rateLimitingMiddleware;
    private UserStore userStore;
    private SessionManager sessionManager;
    public Callback callback;
    private volatile SimpleHttpServer server = null;
    private volatile int shutdownTimeout = 0;
    private final ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);
    private volatile ScheduledFuture<?> shutdownFuture = null;
    public ServerVersionInfo serverVersionInfo = new ServerVersionInfo("SHTTPS", "unknown");
    public long serverStartTimeMillis;

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
        SHTTPSApp instance = SHTTPSApp.instance;
        if (instance == null) {
            throw new IllegalStateException("App not initialized");
        }
        instance.stopServer();
        Database db = instance.database.get();
        if (db != null) {
            try {
                db.close();
            } catch (Exception e) {
                instance.logger.e("Failed to close database", e);
            }
            instance.database.set(null);
        }
        instance.scheduledThreadPool.shutdownNow();
        SHTTPSApp.instance = null;
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
                DatabaseMigrator.runMigrations(database, config.isStoreUsersInDatabase());
                setDatabase(database);
            } catch (Exception e) {
                logger.e("Failed to open database", e);
            }
        }
    }

    public synchronized void startServer() throws UnrecoverableKeyException, CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        Router.Middlewares globalMiddlewares = new Router.Middlewares();
        //Setup middlewares
        Router.Middlewares authMiddlewares = new Router.Middlewares();

        final String loginFormPath = "/shttps-static-public/auth/";
        AuthManager authManager;

        switch (config.getAuthMode()) {
            case BASIC_AUTH:
                authManager = new BasicAuthManager(provideUserStore());
                BasicAuthMiddleware authMiddleware = new BasicAuthMiddleware(authManager);
                authMiddlewares.addPreMiddleware(authMiddleware);
                break;
            case WEB:
                authManager = new WebAuthManager(provideUserStore(),
                        provideSessionManager(false));
                WebAuthMiddleware authMiddleware1 = new WebAuthMiddleware(authManager,
                        loginFormPath, config.isAllowedUserRegistration());
                authMiddlewares.addPreMiddleware(authMiddleware1);
                break;
            default:
                authManager = new DummyAuthManager();
                break;
        }

        rateLimitingMiddleware = new RateLimitingMiddleware(
                config.getGlobalRateLimit(),
                1000 * 60,//minute
                config.getRateLimiterTrustToIPHeaders()
        );
        if (config.getGlobalRateLimit() > 0) {
            globalMiddlewares.addPreMiddleware(rateLimitingMiddleware);
        }

        List<CORSMiddleware.CORSRule> corsRules = config.getCORSRules();
        if (corsRules != null) {
            globalMiddlewares.addPreMiddleware(new CORSMiddleware(corsRules));
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
        globalMiddlewares.addPreMiddleware(redirectsMiddleware);

        //Setup routes
        Router router = new Router(logsCollector, globalMiddlewares);
        //file handlers
        router.addRoute("/api/file/download", Set.of("GET"), new StaticFileRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/new-folder", Set.of("POST"), new NewFolderRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/rename", Set.of("POST"), new RenameFileRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/upload", Set.of("PUT"), new UploadFileRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/delete", Set.of("DELETE"), new DeleteFileRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/move", Set.of("POST"), new MoveFileRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/list", Set.of("GET"), new FileListRequestHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/thumbnail", Set.of("GET"), new ThumbnailHandler(config, authManager, userStore), authMiddlewares);
        router.addRoute("/api/file/zip", Set.of("POST"), new ZipDownloadRequestHandler(config, authManager, userStore), authMiddlewares);

        //database handlers
        router.addRoute("/api/db/schema", Set.of("GET"), new DBSchemaRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/table", Set.of("GET", "POST"), new DBTableDataRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/insert", Set.of("POST"), new DBInsertRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/update", Set.of("PUT"), new DBUpdateRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/delete", Set.of("DELETE"), new DBDeleteRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/query", Set.of("POST"), new DBCustomSQLRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/cell-data", Set.of("GET", "POST"), new DBSingleCellDataRequestHandler(database, config, authManager), authMiddlewares);

        //system handlers
        router.addRoute("/api/system/status", Set.of("GET"), new StatusRequestHandler(this, authManager), authMiddlewares);

        //auth handlers (if any)
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.WEB)) {
            assert authManager instanceof WebAuthManager;
            Router.Middlewares loginMiddlewares = new Router.Middlewares();
            loginMiddlewares.addPreMiddleware(new RateLimitingMiddleware(10, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
            router.addRoute("/api/user/login", Set.of("POST"), new LoginRequestHandler(authManager), loginMiddlewares);

            router.addRoute("/api/user/logout", Set.of("POST"), new LogoutRequestHandler(authManager), authMiddlewares);

            if (config.isAllowedUserRegistration()) {
                CaptchaManager captchaManager = new CaptchaManager(platformUtils);
                Router.Middlewares captchaMiddlewares = new Router.Middlewares();
                captchaMiddlewares.addPreMiddleware(new RateLimitingMiddleware(5, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
                router.addRoute("/api/captcha", Set.of("GET"), new CaptchaRequestHandler(captchaManager), captchaMiddlewares);

                Router.Middlewares userRegMiddlewares = new Router.Middlewares();
                userRegMiddlewares.addPreMiddleware(new RateLimitingMiddleware(3, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
                router.addRoute("/api/user/register", Set.of("POST"),
                        new UserRegistrationRequestHandler((WebAuthManager) authManager, captchaManager), userRegMiddlewares);
            }
        }



        //build-in pages
        router.addRouteByPathPrefix("/shttps-static-public",
                new StaticAssetsRequestHandler("shttps-static-public", "/shttps-static-public", config), authMiddlewares);


        Router.Middlewares filesRequestHandlerMiddlewares = new Router.Middlewares();
        filesRequestHandlerMiddlewares.preMiddlewares.addAll(authMiddlewares.preMiddlewares);
        if (config.isCGIEnabled()) {
            //CGI middleware will be added behind auth middleware
            //but before any static file processing (except built-in assets files)
            //Is it ideal place? Time will show
            filesRequestHandlerMiddlewares.preMiddlewares.add(new CgiMiddleware(config, authManager));
        }
        FilesRequestHandler filesRequestHandler = new FilesRequestHandler(config, authManager, userStore);
        filesRequestHandler.renderFolders = config.getRenderFolders();
        filesRequestHandler.allowEditing = config.getAllowEditing();
        router.addRouteByPathPrefix("/", filesRequestHandler, filesRequestHandlerMiddlewares);

        Callback callback = SHTTPSApp.this.callback;
        if (callback != null) {
            callback.onRouterPrepared(router, filesRequestHandlerMiddlewares);
        }

        SimpleHttpServer.Callback serverCallback = new SimpleHttpServer.Callback() {
            @Override
            public void onServerStarted() {
                logger.i("Server started");
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onServerStarted();
                }
            }
            @Override
            public void onServerStopped() {
                logger.i("Server stopped");
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onServerStopped();
                }
            }
            @Override
            public void onNewConnection(Socket socket, long connectionId) {
                SocketAddress address = socket.getRemoteSocketAddress();
                logger.i("New connection #" + connectionId + " from " + (address != null ? address.toString() : "unknown address"));
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onNewConnection(socket);
                }
                setupShutdownTimeout();
            }

            @Override
            public void onConnectionTracked(Socket socket, long connectionId) {
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onConnectionTracked(socket);
                }
            }

            @Override
            public void onConnectionClosed(Socket socket, String reason, long connectionId) {
                if (reason == null) {
                    logger.i("Connection #" + connectionId + " closed normally");
                } else {
                    logger.i("Connection #" + connectionId + " closed by reason: " + reason);
                }
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onConnectionClosed(socket, reason);
                }
            }
            @Override
            public void onConnectionError(Socket socket, Exception e, long connectionId) {
                logger.e("Connection #" + connectionId + " error: " + e.toString());
            }
            @Override
            public void onConnectionRequest(RequestContext context, Request request) {
                logger.d("cn#" + request.connectionId + " >" + request.method + " " + request.path);
            }
            @Override
            public void onConnectionResponse(RequestContext context, Request request, Response response) {
                logger.d("cn#" + request.connectionId + " <" + response.code + " " + response.phrase);
            }

            @Override
            public void onConnectionRejected(Socket socket, int reason, long connectionId) {
                Callback callback = SHTTPSApp.this.callback;
                if (callback != null) {
                    callback.onConnectionRejected(socket, reason);
                    logger.w("Connection #" + connectionId + " rejected reason: " + reason);
                }
            }
        };

        SimpleHttpServer srv = new SimpleHttpServer(router, serverCallback);
        if (config.getVerifyHost()) {
            srv.hostName = config.getHost();
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
                        logger.e("Error while processing whitelist", e);
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
            String certPassword = config.getTLSKeystorePassword();
            String keyPassword = config.getTLSKeyPassword();
            // For compatibility if key password is not set, it is assumed to be the same as the store password
            keyPassword = (keyPassword != null && !keyPassword.isEmpty()) ? keyPassword : certPassword;
            if (cert == null)  {
                //use default self-signed cert
                String password = "z47x#vt6Rm$!y;LK";
                try (InputStream certStream = platformUtils.openAssetStream("default_self_signed.pfx")) {
                    cert = KeyStore.getInstance("PKCS12");
                    cert.load(certStream, password.toCharArray());
                    keyPassword = password;
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to load default self-signed certificate", e);
                }
            } else if (certPassword == null) {
                throw new IllegalStateException("Certificate password not set!");
            }
            srv.startListen(config.getPort(), cert, keyPassword);
        } else {
            srv.startListen(config.getPort());
        }
        this.serverStartTimeMillis = System.currentTimeMillis();
        this.server = srv;
        setupShutdownTimeout();
    }

    public synchronized void stopServer() {
        SimpleHttpServer srv = this.server;
        if (srv != null && srv.isListenThreadRunning()) {
            srv.stopListen();
            server = null;
        }

        RateLimitingMiddleware rateLimitingMiddleware = this.rateLimitingMiddleware;
        if (rateLimitingMiddleware != null) {
            rateLimitingMiddleware.shutdown();
            this.rateLimitingMiddleware = null;
        }

        ScheduledFuture<?> future = this.shutdownFuture;
        if (future != null) {
            future.cancel(false);
            this.shutdownFuture = null;
        }
    }

    public UserStore provideUserStore() {
        return provideUserStore(false);
    }

    public UserStore provideUserStore(boolean invalidate) {
        UserStore userStore = this.userStore;
        if (invalidate || userStore == null) {
            userStore = config.isStoreUsersInDatabase() ?
                    new DBBasedUserStore(database, config) :
                    new ConfigBasedUserStore(config);
            this.userStore = userStore;
        }
        return userStore;
    }

    public SessionManager provideSessionManager(boolean invalidate) {
        SessionManager sessionManager = this.sessionManager;
        if (invalidate || sessionManager == null) {
            sessionManager = new InMemorySessionManager();
            this.sessionManager = sessionManager;
        }
        return sessionManager;
    }

    public boolean isServerRunning() {
        SimpleHttpServer srv = this.server;
        return srv != null && srv.isListenThreadRunning();
    }

    public synchronized void restartServerIfRunning() {
        if (isServerRunning()) {
            stopServer();
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
        Database oldDb = this.database.get();
        if (oldDb != null) {
            try {
                oldDb.close();
            } catch (Exception e) {
                logger.e("Failed to close old database", e);
            }
        }
        this.database.set(db);
    }

    public Database getDatabase() {
        return database.get();
    }

    public SimpleHttpServer getServer() {
        return server;
    }

    public void setShutdownTimeout(int shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        if (isServerRunning()) setupShutdownTimeout();
    }

    private void setupShutdownTimeout() {
        ScheduledFuture<?> future = this.shutdownFuture;
        if (future != null) {
            future.cancel(false);
            this.shutdownFuture = null;
        }
        if (shutdownTimeout > 0) {
            this.shutdownFuture = scheduledThreadPool.schedule(autoShutdownHandler, shutdownTimeout, TimeUnit.SECONDS);
        }
    }

    private final Runnable autoShutdownHandler = () -> {
        SimpleHttpServer server = SHTTPSApp.this.server;
        if (server != null) {
            if (server.hasOpenConnections()) {
                setupShutdownTimeout();
            } else {
                stopServer();
            }
        }
    };

    public interface Callback {
        default void onConnectionRejected(Socket socket, int reason) {}

        default void onNewConnection(Socket socket) {}
        default void onConnectionTracked(Socket socket) {}
        default void onConnectionClosed(Socket socket, String reason) {}

        default void onServerStarted() {}

        default void onServerStopped() {}

        default void onRouterPrepared(Router router, Router.Middlewares filesRequestHandlerMiddlewares) {}
    }

    public static class ServerVersionInfo {
        public String name;
        public String version;
        public ServerVersionInfo(String name, String version) {
            this.name = name;
            this.version = version;
        }
    }
}
