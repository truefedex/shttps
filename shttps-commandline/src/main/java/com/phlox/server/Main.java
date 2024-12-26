package com.phlox.server;

import com.phlox.server.database.DatabaseFabricImpl;
import com.phlox.server.utils.CertUtil;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.PlatformUtilsImpl;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;

@CommandLine.Command(name = "shttps", mixinStandardHelpOptions = true, version = "shttps 1.0",
        description = "Starts a Simple HTTP server")
public class Main implements Callable<Integer> {
    @CommandLine.Option(names = {"-c", "--config"}, description = "Path to config file", required = false)
    private String configPath = System.getProperty("user.home") + File.separator + ".shttps" +
            File.separator + "config.json";

    @CommandLine.ArgGroup(exclusive = true)
    LaunchMode launchMode;

    static class LaunchMode {
        @CommandLine.Option(names = {"-l", "--listen"}, description = "Listen connections", required = false)
        boolean listenConnections;

        @CommandLine.Option(names = {"--genCert"}, description = "Generate self signed certificate", required = false)
        String pathToStoreCertificate = null;
    }

    private final Terminal terminal;
    private final LineReader reader;

    public Main(Terminal terminal, LineReader reader) {
        this.terminal = terminal;
        this.reader = reader;
    }

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

        if (launchMode != null) {
            if (launchMode.pathToStoreCertificate != null) {
                generateCertificate(launchMode.pathToStoreCertificate, config);
                return 0;
            } else if (launchMode.listenConnections) {
                startServer(config, platformUtils, databaseFabric);
                return 0;
            }
        } else {
            //no options, start server
            startServer(config, platformUtils, databaseFabric);
            return 0;
        }
        return 0;
    }

    private void generateCertificate(String pathToStoreCertificate, SHTTPSConfigImpl config) {
        System.out.println("Generating self signed certificate");
        try {
            File file = new File(pathToStoreCertificate);
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                throw new IOException("Can't create directory for keystore");
            }
            if (file.isDirectory()) {
                file = new File(file, "keystore.p12");
            }

            String password = CertUtil.genRandomPassword(16);
            CertUtil.genSelfSignedPKCS12CertAtPath(file.getAbsolutePath(), password);
            config.startBatchModifications();
            config.setTLSCert(file.getAbsolutePath().getBytes());
            config.setTLSCertPassword(password);
            config.setUseTLS(true);
            config.endBatchModifications();
            System.out.println("Certificate generated at " + file.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startServer(SHTTPSConfig config, SHTTPSPlatformUtils platformUtils, DatabaseFabricImpl databaseFabric) {
        System.out.println("Starting server");
        SHTTPSApp app = SHTTPSApp.init(config, platformUtils, databaseFabric);

        try {
            app.startServer();
            System.out.println("Server started at http://localhost:" + config.getPort());
        } catch (IOException | CertificateException | NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException | KeyManagementException e) {
            e.printStackTrace();
        }

        PrintWriter writer = terminal.writer();

        while (true) {
            //read Ctrl+C
            try {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                if (line.equals("stop")) {
                    break;
                } else {
                    writer.println("Unknown command: " + line);
                    writer.println("Type 'stop' to stop server");
                    terminal.flush();
                }
            } catch (UserInterruptException | EndOfFileException e) {
                break;
            }
        }

        System.out.println("Stopping server");
        app.stopServer();

    }

    public static void main(String[] args) {
        Logger jlineLogger = Logger.getLogger("org.jline");
        jlineLogger.setLevel(Level.WARNING);

        Parser parser = new DefaultParser();
        PicocliCommands.PicocliCommandsFactory factory = new PicocliCommands.PicocliCommandsFactory();
        try (Terminal terminal = TerminalBuilder.builder().build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(parser)
                    .variable(LineReader.LIST_MAX, 50)   // max tab completion candidates
                    .build();
            factory.setTerminal(terminal);
            CommandLine cmd = new CommandLine(new Main(terminal, reader))
                    .setOut(terminal.writer())
                    .setErr(terminal.writer());
            cmd.execute(args);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}