package com.phlox.simpleserver;

import com.phlox.server.SimpleHttpServer;
import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.handlers.router.middleware.Middleware;
import com.phlox.server.handlers.router.middleware.impl.CORSMiddleware;
import com.phlox.server.handlers.router.middleware.impl.CustomHeadersMiddleware;
import com.phlox.server.handlers.router.middleware.impl.RateLimitingMiddleware;
import com.phlox.server.handlers.router.middleware.impl.RedirectsMiddleware;
import com.phlox.server.handlers.router.Router;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.Response;
import com.phlox.server.utils.HTTPUtils;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.server.websocket.WebSocketCloseCodes;
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
import com.phlox.simpleserver.channels.ChannelManager;
import com.phlox.simpleserver.database.Database;
import com.phlox.simpleserver.database.DatabaseMigrator;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.exec.CgiMiddleware;
import com.phlox.simpleserver.handlers.channels.ChannelWebSocketHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsCollectionRequestHandler;
import com.phlox.simpleserver.handlers.channels.ChannelsPathRequestHandler;
import com.phlox.simpleserver.handlers.database.DBCustomSQLRequestHandler;
import com.phlox.simpleserver.handlers.database.DBDeleteRequestHandler;
import com.phlox.simpleserver.handlers.database.DBInsertRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSchemaRequestHandler;
import com.phlox.simpleserver.handlers.database.DBSingleCellDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBTableDataRequestHandler;
import com.phlox.simpleserver.handlers.database.DBTransactionWebSocketHandler;
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
import com.phlox.simpleserver.handlers.files.webdav.LockManager;
import com.phlox.simpleserver.handlers.main.FilesRequestHandler;
import com.phlox.simpleserver.handlers.system.StatusRequestHandler;
import com.phlox.simpleserver.utils.Holder;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;
import com.phlox.simpleserver.utils.ServerLogsCollector;

import org.jetbrains.annotations.Nullable;

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
    /** Every live WebSocket channel; handed to the channel handlers when the routes are built. */
    private final ChannelManager channelManager = new ChannelManager();
    public final ServerLogsCollector logsCollector = new ServerLogsCollector(1000);
    public RateLimitingMiddleware rateLimitingMiddleware;
    /**
     * Limits how often one client may open a database transaction session. Separate from the global
     * limiter because it applies to that one route whether or not the global limit is switched on -
     * a transaction holds the database's single writer, so a reconnect loop is a denial of service
     * on every other database request.
     */
    public RateLimitingMiddleware dbTransactionRateLimitingMiddleware;
    /**
     * In the same spirit as the login route's ten per minute: generous for a client that opens a
     * session per batch of work, tight enough to stop one that reconnects in a loop.
     */
    public static final int DB_TRANSACTION_HANDSHAKES_PER_MINUTE = 10;
    private UserStore userStore;
    private SessionManager sessionManager;
    //built for the configured auth mode when the server starts; handlers registered from outside
    //(see Callback.onRouterPrepared) need it to evaluate user rights
    private volatile AuthManager authManager;
    public Callback callback;
    private volatile SimpleHttpServer server = null;
    private volatile int shutdownTimeout = 0;
    private final ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);
    private volatile ScheduledFuture<?> shutdownFuture = null;
    /** The repeating sweep that collects dynamic channels nobody has used for a while. */
    private volatile ScheduledFuture<?> channelIdleSweepFuture = null;
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
            String path = resolveDatabasePath();
            if (path == null) {
                logger.e("Database is enabled but \"" + SHTTPSConfig.KEY_DATABASE_PATH + "\" is not set" +
                        " in the config, and this platform offers no default location. Set \"" +
                        SHTTPSConfig.KEY_DATABASE_PATH + "\" to the SQLite file to use, or turn \"" +
                        SHTTPSConfig.KEY_DATABASE_ENABLED + "\" off. Starting without a database.");
                return;
            }
            try {
                Database database = databaseFabric.openDatabase(path);
                Map<String, Object> bdStatus = database.getStatus();
                logger.i("Database opened: " + bdStatus);
                DatabaseMigrator.runMigrations(database, config.isStoreUsersInDatabase());
                setDatabase(database);
            } catch (Exception e) {
                logger.e("Failed to open database: " + path, e);
            }
        }
    }

    /**
     * The database file to open, filling in the platform default for a config that switched the
     * feature on without naming one - the way the constructor fills in a missing root dir. That is
     * the normal state of a hand-written command line config: {@link SHTTPSConfig#getDatabasePath()}
     * defaults to null, and a config file written by the desktop build stores it as an empty string,
     * so both count as "not set" here.
     * <p>
     * A resolved default is written back to the config, so the file is named in one place and
     * everything else reading the path afterwards - the status page, backup export - sees the same
     * one. Null only when the platform has no default either; that is the caller's to report.
     */
    private @Nullable String resolveDatabasePath() {
        String path = config.getDatabasePath();
        if (path != null && !path.isEmpty()) return path;
        String defaultPath = config.getDefaultDatabasePath();
        if (defaultPath == null || defaultPath.isEmpty()) return null;
        logger.i("Database is enabled without a \"" + SHTTPSConfig.KEY_DATABASE_PATH + "\"; using " +
                defaultPath);
        config.setDatabasePath(defaultPath);
        return defaultPath;
    }

    public synchronized void startServer() throws UnrecoverableKeyException, CertificateException, IOException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        List<Middleware> globalMiddlewares = new ArrayList<>();
        //Setup middlewares
        List<Middleware> authMiddlewares = new ArrayList<>();

        final String loginFormPath = "/shttps-static-public/auth/";
        AuthManager authManager;

        switch (config.getAuthMode()) {
            case BASIC_AUTH:
                authManager = new BasicAuthManager(provideUserStore());
                BasicAuthMiddleware authMiddleware = new BasicAuthMiddleware(authManager);
                authMiddlewares.add(authMiddleware);
                break;
            case WEB:
                authManager = new WebAuthManager(provideUserStore(),
                        provideSessionManager(false));
                WebAuthMiddleware authMiddleware1 = new WebAuthMiddleware(authManager,
                        loginFormPath, config.isAllowedUserRegistration());
                authMiddlewares.add(authMiddleware1);
                break;
            default:
                authManager = new DummyAuthManager();
                break;
        }
        this.authManager = authManager;

        rateLimitingMiddleware = new RateLimitingMiddleware(
                config.getGlobalRateLimit(),
                1000 * 60,//minute
                config.getRateLimiterTrustToIPHeaders()
        );
        if (config.getGlobalRateLimit() > 0) {
            globalMiddlewares.add(rateLimitingMiddleware);
        }

        List<CORSMiddleware.CORSRule> corsRules = config.getCORSRules();
        if (corsRules != null) {
            globalMiddlewares.add(new CORSMiddleware(corsRules));
        }


        List<RedirectsMiddleware.RedirectRule> redirectRules = config.getRedirectRules();
        if (redirectRules != null) {
            RedirectsMiddleware redirectsMiddleware = new RedirectsMiddleware();
            redirectsMiddleware.setRedirectRules(Collections.emptyList());
            for (RedirectsMiddleware.RedirectRule rule : redirectRules) {
                if (rule.enabled) {
                    redirectsMiddleware.addRedirectRule(rule);
                }
            }
            globalMiddlewares.add(redirectsMiddleware);
        }

        List<CustomHeadersMiddleware.Rule> customHeadersRules = config.getHeadersOverrides();
        if (customHeadersRules != null) {
            globalMiddlewares.add(new CustomHeadersMiddleware(customHeadersRules));
        }

        LockManager lockManager = new LockManager();

        //Setup routes
        Router router = new Router(logsCollector, globalMiddlewares);
        //file handlers
        router.addRoute("/api/file/download", Set.of("GET", "HEAD"), new StaticFileRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/new-folder", Set.of("POST"), new NewFolderRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/rename", Set.of("POST"), new RenameFileRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/upload", Set.of("PUT"), new UploadFileRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/delete", Set.of("DELETE"), new DeleteFileRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/move", Set.of("POST"), new MoveFileRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/list", Set.of("GET"), new FileListRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/thumbnail", Set.of("GET"), new ThumbnailHandler(config, authManager, userStore, lockManager), authMiddlewares);
        router.addRoute("/api/file/zip", Set.of("POST"), new ZipDownloadRequestHandler(config, authManager, userStore, lockManager), authMiddlewares);

        //database handlers
        router.addRoute("/api/db/schema", Set.of("GET"), new DBSchemaRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/table", Set.of("GET", "POST"), new DBTableDataRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/insert", Set.of("POST"), new DBInsertRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/update", Set.of("PUT"), new DBUpdateRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/delete", Set.of("DELETE"), new DBDeleteRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/query", Set.of("POST"), new DBCustomSQLRequestHandler(database, config, authManager), authMiddlewares);
        router.addRoute("/api/db/cell-data", Set.of("GET", "POST"), new DBSingleCellDataRequestHandler(database, config, authManager), authMiddlewares);
        //a WebSocket handshake is an ordinary GET, so it goes through the same middlewares as the
        //rest - plus one of its own: an open transaction holds the single database writer, so the
        //rate at which sessions may be started is limited whether or not the global limit is on
        dbTransactionRateLimitingMiddleware = new RateLimitingMiddleware(
                DB_TRANSACTION_HANDSHAKES_PER_MINUTE,
                1000 * 60,//minute
                config.getRateLimiterTrustToIPHeaders()
        );
        List<Middleware> dbTransactionMiddlewares = new ArrayList<>(authMiddlewares);
        dbTransactionMiddlewares.add(dbTransactionRateLimitingMiddleware);
        router.addRoute(DBTransactionWebSocketHandler.PATH, Set.of("GET"), new DBTransactionWebSocketHandler(database, config, authManager), dbTransactionMiddlewares);

        //channel handlers
        //the predefined channels are rebuilt here rather than kept across restarts: they are a
        //configuration setting, and this is the moment the configuration is read
        channelManager.applyPredefinedChannels(config);
        startChannelIdleSweep();
        ChannelWebSocketHandler channelWebSocketHandler =
                new ChannelWebSocketHandler(channelManager, config, authManager);
        router.addRoute(ChannelsCollectionRequestHandler.PATH, Set.of("GET", "POST"),
                new ChannelsCollectionRequestHandler(channelManager, config, authManager), authMiddlewares);
        //{id} and its sub-paths are parsed by the handler itself - the router has no path parameters
        router.addRouteByPathPrefix(ChannelsPathRequestHandler.PATH_PREFIX,
                new ChannelsPathRequestHandler(channelManager, config, authManager, channelWebSocketHandler),
                authMiddlewares);

        //system handlers
        router.addRoute("/api/system/status", Set.of("GET"), new StatusRequestHandler(this, authManager), authMiddlewares);

        //auth handlers (if any)
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.WEB)) {
            assert authManager instanceof WebAuthManager;
            List<Middleware> loginMiddlewares = new ArrayList<>();
            loginMiddlewares.add(new RateLimitingMiddleware(10, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
            router.addRoute("/api/user/login", Set.of("POST"), new LoginRequestHandler(authManager), loginMiddlewares);

            router.addRoute("/api/user/logout", Set.of("POST"), new LogoutRequestHandler(authManager), authMiddlewares);

            if (config.isAllowedUserRegistration()) {
                CaptchaManager captchaManager = new CaptchaManager(platformUtils);
                List<Middleware> captchaMiddlewares = new ArrayList<>();
                captchaMiddlewares.add(new RateLimitingMiddleware(5, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
                router.addRoute("/api/captcha", Set.of("GET"), new CaptchaRequestHandler(captchaManager), captchaMiddlewares);

                List<Middleware> userRegMiddlewares = new ArrayList<>();
                userRegMiddlewares.add(new RateLimitingMiddleware(3, 1000 * 60, config.getRateLimiterTrustToIPHeaders()));
                router.addRoute("/api/user/register", Set.of("POST"),
                        new UserRegistrationRequestHandler((WebAuthManager) authManager, captchaManager), userRegMiddlewares);
            }
        }


        //build-in pages
        router.addRouteByPathPrefix("/shttps-static-public",
                new StaticAssetsRequestHandler("shttps-static-public", "/shttps-static-public", config), authMiddlewares);


        List<Middleware> filesRequestHandlerMiddlewares = new ArrayList<>(authMiddlewares);
        if (config.isCGIEnabled()) {
            //CGI middleware will be added behind auth middleware
            //but before any static file processing (except built-in assets files)
            //Is it ideal place? Time will show
            filesRequestHandlerMiddlewares.add(new CgiMiddleware(config, authManager));
        }

        FilesRequestHandler filesRequestHandler = new FilesRequestHandler(config, authManager, userStore, lockManager);
        filesRequestHandler.renderFolders = config.getRenderFolders();
        filesRequestHandler.allowEditing = config.getAllowEditing();
        router.addRouteByPathPrefix("/", filesRequestHandler, filesRequestHandlerMiddlewares);

        //handle OPTIONS * HTTP/1.1 request
        RequestHandler optionsHandler = (context, request) ->
                FilesRequestHandler.prepareOptionsResponse(null, config);
        router.addRoute("*", Set.of("OPTIONS"), optionsHandler, authMiddlewares);

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
            MultiMap<String, String> customResponseHeadersMap = HTTPUtils.parseHttpHeaders(customResponseHeadersStr);
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

    /**
     * Starts the repeating collection of dynamic channels that have been empty for longer than
     * {@code getChannelIdleTimeoutMillis()}. Runs on the app's scheduler, like the shutdown timer;
     * {@link #stopServer()} cancels it, so a restart replaces it rather than adding a second one.
     */
    private void startChannelIdleSweep() {
        ScheduledFuture<?> previous = this.channelIdleSweepFuture;
        if (previous != null) {
            previous.cancel(false);
            this.channelIdleSweepFuture = null;
        }
        if (!config.isChannelsEnabled()) return;
        int timeout = config.getChannelIdleTimeoutMillis();
        if (timeout <= 0) return;//the sweep is switched off
        //often enough that a collected channel is gone soon after it has earned it, rarely enough
        //that a ten minute timeout does not mean a wake-up every second
        long period = Math.max(1000, Math.min(timeout / 2, 60000));
        this.channelIdleSweepFuture = scheduledThreadPool.scheduleWithFixedDelay(() -> {
            try {
                channelManager.collectIdleChannels(config);
            } catch (Throwable t) {
                //anything escaping here would cancel the repeating task for the rest of the run
                logger.e("Channel idle sweep failed", t);
            }
        }, period, period, TimeUnit.MILLISECONDS);
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

        RateLimitingMiddleware dbTransactionRateLimiter = this.dbTransactionRateLimitingMiddleware;
        if (dbTransactionRateLimiter != null) {
            //it owns a cleanup executor of its own, like the global one
            dbTransactionRateLimiter.shutdown();
            this.dbTransactionRateLimitingMiddleware = null;
        }

        //the sockets are already going away with the server; this tells the participants why, and
        //leaves no channel behind holding a closed session
        channelManager.closeAll(WebSocketCloseCodes.GOING_AWAY, "Server stopped");

        ScheduledFuture<?> sweep = this.channelIdleSweepFuture;
        if (sweep != null) {
            sweep.cancel(false);
            this.channelIdleSweepFuture = null;
        }

        ScheduledFuture<?> future = this.shutdownFuture;
        if (future != null) {
            future.cancel(false);
            this.shutdownFuture = null;
        }

        //belongs to the server that just stopped: the next start builds one for whatever auth mode
        //is configured then
        this.authManager = null;
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

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    /**
     * @return the auth manager of the running server, or null while it is not running. It is
     * replaced on every start, so it must not be held on to across a restart.
     */
    public @Nullable AuthManager getAuthManager() {
        return authManager;
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

        default void onRouterPrepared(Router router, List<Middleware> filesRequestHandlerMiddlewares) {}
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
