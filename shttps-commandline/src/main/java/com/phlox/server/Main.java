package com.phlox.server;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import java.io.File;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import sun.misc.Signal;

@CommandLine.Command(name = "shttps", mixinStandardHelpOptions = true,
        versionProvider = Main.ManifestVersion.class,
        description = "Starts a Simple HTTP server")
public class Main implements Callable<Integer> {
    /**
     * What {@code --version} prints. Taken from the jar manifest rather than written here: the
     * number lives in shttps-oss/version.properties, reaches this module's build.gradle as a
     * project property and the manifest from there, so there is one place to raise it. Hardcoding
     * it in the annotation is what left this command reporting 1.0 while the jar said 3.0.0 and
     * the rest of the project was on 3.4.
     * <p>
     * Run from a classpath rather than the packaged jar - {@code gradle run}, or an IDE - there is
     * no manifest to read, which is what the fallback is for.
     */
    static class ManifestVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[]{"shttps " + (version != null ? version : "(development build)")};
        }
    }

    @CommandLine.Option(names = {"-c", "--config"}, description = "Path to config file", required = false)
    private String configPath = System.getProperty("user.home") + File.separator + ".shttps" +
            File.separator + "config.json";

    @Override
    public Integer call() throws Exception {
        SHTTPSLoggerProxy.setFactory(tag -> new SHTTPSLoggerProxy.TaggedJavaLogger(tag, SHTTPSLoggerProxy.Logger.ALL));
        SHTTPSPlatformUtils platformUtils = new PlatformUtilsImpl();

        System.out.println("Loading config from " + configPath);
        File configFile = new File(configPath);
        File parent = configFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            System.err.println("Can't create directory " + parent.getAbsolutePath());
            return 1;
        }
        SHTTPSConfigImpl config = new SHTTPSConfigImpl(configFile);
        DatabaseFabricImpl databaseFabric = new DatabaseFabricImpl();

        startServer(config, platformUtils, databaseFabric);
        return 0;
    }

    private void startServer(SHTTPSConfig config, SHTTPSPlatformUtils platformUtils, DatabaseFabricImpl databaseFabric) {
        System.out.println("Starting server");
        SHTTPSApp app = SHTTPSApp.init(config, platformUtils, databaseFabric);
        app.initIO();
        try {
            app.startServer();
            String scheme = config.getUseTLS() ? "https" : "http";
            System.out.println(String.format("Server started at %s://localhost:%d", scheme, config.getPort()));
        } catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException | KeyManagementException e) {
            e.printStackTrace();
        }

        Signal.handle(new Signal("INT"),  // SIGINT
                signal -> {
            System.out.println("Interrupted by Ctrl+C");
            System.out.println("Stopping server");
            app.stopServer();
            System.exit(0);
        });
    }

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}