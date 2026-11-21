package com.phlox.server;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.BadRequestException;
import com.phlox.server.request.BodyInputStream;
import com.phlox.server.request.DefaultRequestBodyReader;
import com.phlox.server.request.DefaultRequestHeadersParser;
import com.phlox.server.request.ExpectContinueBodyStream;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.responses.ConnectionTakeoverHandler;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.StandardResponses;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.responses.UpgradeResponse;
import com.phlox.server.utils.MultiMap;
import com.phlox.server.utils.PayloadTooLargeException;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;


/*
 * SimpleHttpServer.java
 *
 * Copyright (c) 2013, Fedir Tsapana <truefedex@gmail.com> All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 	Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 	Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 	Neither the name of the Fedir Tsapana nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

public class SimpleHttpServer {
    public static final int REASON_CLIENT_ADDRESS_NOT_ALLOWED = 1;
    public static final int REASON_NETWORK_INTERFACE_NOT_ALLOWED = 2;
    public static final int REASON_TOO_MANY_CONNECTIONS = 3;
    public static final int DEFAULT_MAX_CONNECTIONS = 256;

    private static final Logger logger = SHTTPSLoggerProxy.getLogger(SimpleHttpServer.class);
    public static final String SERVER_NAME = "SHTTPS/3.x";
    public static final String HTTP_PROTOCOL = "HTTP/1.1";
    //how much of an unread request body we are willing to drain to keep the connection reusable
    private static final int MAX_UNREAD_BODY_DRAIN_BYTES = 64 * 1024;
    private static final int UNREAD_BODY_DRAIN_READ_TIMEOUT_MS = 1000;
    private static final byte[] RESPONSE_100_CONTINUE =
            (HTTP_PROTOCOL + " 100 Continue\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    @NotNull
    private final RequestHandler requestHandler;

    @Nullable
    private final Callback callback;

    private volatile ServerSocket serverSocket;
    private final Set<Socket> trackedSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean shouldStopListen = false;
    private volatile boolean listenThreadRunning = false;
    private final ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
    private Thread listenThread;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    /** Limits of the request head (line length, header count, head read deadline) live here. */
    public final DefaultRequestHeadersParser requestHeadersParser = new DefaultRequestHeadersParser();

    public volatile String hostName = null;

    public volatile Set<NetworkInterface> allowedNetworkInterfaces = null;

    public volatile Set<InetAddress> allowedClientAddresses = null;

    public volatile MultiMap<String, String> additionalResponseHeaders = null;
    public volatile int connectionMinimalReadTimeoutMilliseconds = 10000;
    public volatile int connectionKeepAliveTimeoutSeconds = 30;
    /**
     * Connections served at once; each one holds a thread. Connections beyond it are closed
     * right away (without a response: answering would mean a TLS handshake on the accept thread).
     */
    public volatile int maxConnections = DEFAULT_MAX_CONNECTIONS;

    private final AtomicLong connectionHistoryCounter = new AtomicLong(0);

    public interface Callback {
        void onServerStarted();

        void onServerStopped();

        //An early stage of connection procession
        void onNewConnection(Socket socket, long connectionId);

        //Connection established and passed initial checks
        void onConnectionTracked(Socket socket, long connectionId);

        void onConnectionClosed(Socket socket, String reason, long connectionId);

        void onConnectionError(Socket socket, Exception e, long connectionId);

        void onConnectionRequest(RequestContext context, Request request);

        void onConnectionResponse(RequestContext context, Request request, Response response);

        void onConnectionRejected(Socket socket, int reason, long connectionId);
    }

    public SimpleHttpServer(@NotNull RequestHandler requestHandler, @Nullable Callback callback) {
        this.requestHandler = requestHandler;
        this.callback = callback;
    }

    public void startListen(ServerSocket serverSocket) {
        if (listenThread != null && listenThread.isAlive()) {
            throw new IllegalStateException("Listen thread already running. Stop it first");
        }
        this.serverSocket = serverSocket;
        //a stopped server may be started again
        shouldStopListen = false;
        try {
            serverSocket.setSoTimeout(500);
        } catch (SocketException e) {
            logger.e("Cannot set socket timeout", e);
        }
        listenThread = new Thread(this::listenLoop, "SHTTPS listener");
        listenThread.start();
    }

    public void startListen(int port) throws IOException {
        startListen(new ServerSocket(port));
    }

    public void startListen(int port, byte[] p12cert, String keyStorePassword, String keyPassword) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(p12cert), keyStorePassword.toCharArray());
        startListen(port, ks, keyPassword);
    }

    public void startListen(int port, KeyStore ks, String keyPassword) throws IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassword.toCharArray());

        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory ssf = sc.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(port);
        startListen(serverSocket);
    }

    public void stopListen() {
        shouldStopListen = true;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                logger.e("Error while closing server socket", e);
            }
        }
        synchronized (trackedSockets) {
            for (Socket client : trackedSockets) {
                try {
                    client.close();
                } catch (IOException e) {
                    logger.e("Error while closing client socket", e);
                }
            }
            trackedSockets.clear();
        }
        Thread lthr = listenThread;
        if (lthr != null && lthr.isAlive()) {
            try {
                lthr.join();
            } catch (InterruptedException e) {
                logger.e("Error while waiting for listen thread to finish", e);
                Thread.currentThread().interrupt();
            }
            listenThread = null;
        }
        listenThreadRunning = false;
    }

    private void listenLoop() {
        listenThreadRunning = true;

        if (callback != null) {
            callback.onServerStarted();
        }

        while (!shouldStopListen) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (Exception e) {
                if (e instanceof SocketException && "Socket closed".equals(e.getMessage())) {
                    logger.i("Server stopped listening");
                } else {
                    logger.stackTrace(e);
                }
                shouldStopListen = true;
                continue;
            }

            long connectionNumber = connectionHistoryCounter.incrementAndGet();

            if (callback != null) {
                callback.onNewConnection(socket, connectionNumber);
            }

            int rejectionReason = rejectionReasonFor(socket);
            if (rejectionReason != 0) {
                reject(socket, rejectionReason, connectionNumber);
                continue;
            }

            dispatch(socket, connectionNumber);
        }
        try {
            ServerSocket ss = serverSocket;
            if (ss != null && !ss.isClosed()) {
                ss.close();
            }
        } catch (IOException e) {
            logger.e("Error closing server socket", e);
        }

        serverSocket = null;
        listenThreadRunning = false;

        if (callback != null) {
            callback.onServerStopped();
        }
    }

    /**
     * @return 0 if the connection may be served, otherwise the REASON_* it is rejected for
     */
    private int rejectionReasonFor(Socket socket) {
        Set<InetAddress> allowedClientAddresses = this.allowedClientAddresses;
        if (allowedClientAddresses != null && !allowedClientAddresses.contains(socket.getInetAddress())) {
            return REASON_CLIENT_ADDRESS_NOT_ALLOWED;
        }
        Set<NetworkInterface> allowedInterfaces = this.allowedNetworkInterfaces;
        if (allowedInterfaces != null && !isAddressOfAny(socket.getLocalAddress(), allowedInterfaces)) {
            return REASON_NETWORK_INTERFACE_NOT_ALLOWED;
        }
        return 0;
    }

    private static boolean isAddressOfAny(InetAddress address, Set<NetworkInterface> interfaces) {
        for (NetworkInterface ni : interfaces) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress().equals(address)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reject(Socket socket, int reason, long connectionNumber) {
        String description;
        switch (reason) {
            case REASON_CLIENT_ADDRESS_NOT_ALLOWED:
                description = "filtered by server's client whitelist";
                break;
            case REASON_NETWORK_INTERFACE_NOT_ALLOWED:
                description = "forbidden network interface";
                break;
            default:
                description = "too many connections";
                break;
        }
        logger.i("Connection from " + socket.getInetAddress().getHostAddress() + " rejected: " + description);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (callback != null) {
            callback.onConnectionClosed(socket, description, connectionNumber);
            callback.onConnectionRejected(socket, reason, connectionNumber);
        }
    }

    /** Hands an accepted connection to a thread of its own, within {@link #maxConnections}. */
    private void dispatch(Socket socket, long connectionNumber) {
        if (activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet();
            reject(socket, REASON_TOO_MANY_CONNECTIONS, connectionNumber);
            return;
        }
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    handleConnection(socket, connectionNumber);
                } finally {
                    activeConnections.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            activeConnections.decrementAndGet();
            logger.e("Can not start a connection handler", e);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** State of one client connection, shared by the requests served over it. */
    private static final class Connection {
        final Socket socket;
        final long number;
        final RequestBodyReader requestBodyReader = new DefaultRequestBodyReader();
        InputStream input;
        OutputStream output;
        //the request served last, while its body may still be in the stream
        Request lastRequest;
        String closeReason;

        Connection(Socket socket, long number) {
            this.socket = socket;
            this.number = number;
        }
    }

    private void handleConnection(final Socket socket, long connectionNumber) {
        synchronized (trackedSockets) {
            trackedSockets.add(socket);
        }
        if (callback != null) {
            callback.onConnectionTracked(socket, connectionNumber);
        }

        Connection connection = new Connection(socket, connectionNumber);
        try (socket;
             OutputStream output = new BufferedOutputStream(socket.getOutputStream());
             InputStream input = new BufferedInputStream(socket.getInputStream())) {
            connection.input = input;
            connection.output = output;

            int soTimeout = Math.max(connectionMinimalReadTimeoutMilliseconds,
                    connectionKeepAliveTimeoutSeconds * 1000);
            socket.setSoTimeout(soTimeout);
            socket.setKeepAlive(true);

            while (serveNextRequest(connection)) {
                //keep-alive: the next request comes over the same connection
            }

            Request lastRequest = connection.lastRequest;
            if (lastRequest != null && lastRequest.bodyStream != null && !lastRequest.bodyStream.isFullyConsumed()) {
                halfCloseAndDrain(socket, input);
            }
        } catch (SocketTimeoutException e) {
            connection.closeReason = "timeout";
        } catch (Exception e) {
            String message = e.getMessage();
            if (e instanceof SocketException && message != null && message.contains("Connection reset")) {
                connection.closeReason = "closed by client";
            } else {
                connection.closeReason = e.getClass().getSimpleName();
                if (callback != null) {
                    callback.onConnectionError(socket, e, connectionNumber);
                }
            }
        } finally {
            synchronized (trackedSockets) {
                trackedSockets.remove(socket);
            }
            try {
                if (!socket.isOutputShutdown()) {
                    socket.shutdownOutput();
                }
            } catch (IOException ignored) {}
            try {
                socket.shutdownInput();
            } catch (IOException ignored) {}
            try {
                socket.close();
            } catch (IOException ignored) {}
            if (callback != null) {
                callback.onConnectionClosed(socket, connection.closeReason, connectionNumber);
            }
        }
    }

    /**
     * Reads one request off the connection and answers it.
     *
     * @return whether the connection stays open for another request
     */
    private boolean serveNextRequest(Connection connection) throws Exception {
        connection.lastRequest = null;
        Socket socket = connection.socket;
        RequestContext requestContext = new RequestContext(connection.requestBodyReader);

        Request request;
        try {
            request = requestHeadersParser.readRequestHeaders(connection.input, socket.getInetAddress().getHostAddress());
        } catch (BadRequestException e) {
            answerBadRequest(connection, e);
            return false;
        }
        if (request == null) {
            connection.closeReason = "No request data";
            return false;
        }
        connection.lastRequest = request;
        request.connectionId = connection.number;
        boolean keepAlive = !request.requestToCloseConnection && connectionKeepAliveTimeoutSeconds > 0;

        if (callback != null) {
            callback.onConnectionRequest(requestContext, request);
        }

        String hostName = this.hostName;
        if (hostName != null && !isRequestForHost(hostName, request.headers.get(Request.HEADER_HOST))) {
            logger.w("Request host header check failed");
            return false;
        }

        //the client waits for an interim "100 Continue" response before sending the
        //body; send it lazily on the first body read, so handlers that reject the
        //request early (auth, locks, quota) never make the client upload the body
        if (request.expectContinue && request.bodyStream != null &&
                !request.bodyStream.isFullyConsumed()) {
            OutputStream output = connection.output;
            request.bodyStream = new ExpectContinueBodyStream(request.bodyStream, () -> {
                output.write(RESPONSE_100_CONTINUE);
                output.flush();
            });
        }

        Response response = null;
        Throwable errDuringHandle = null;
        try {
            response = requestHandler.handleRequest(requestContext, request);
        } catch (Throwable e) {
            logger.stackTrace(e);
            errDuringHandle = e;
        }

        if (errDuringHandle == null && response instanceof UpgradeResponse) {
            //the handler switches this connection to another protocol: HTTP framing
            //ends with these headers and the connection never comes back to this loop
            handleConnectionUpgrade((UpgradeResponse) response, requestContext, request,
                    socket, connection.output);
            connection.closeReason = "upgraded connection finished";
            connection.lastRequest = null;
            return false;
        }

        if (errDuringHandle != null || response == null) {
            //after a failure the stream position can not be trusted
            keepAlive = false;
        }
        if (keepAlive) {
            keepAlive = drainUnreadBody(socket, request);
        }

        if (errDuringHandle != null) {
            response = errorResponseFor(errDuringHandle);
        } else if (response == null) {
            response = notFoundResponse();
        }

        keepAlive = finalizeResponseHeaders(response, request, keepAlive);

        try {
            response.validateHead();
        } catch (IllegalArgumentException e) {
            logger.e("Refusing to send a response with a malformed head", e);
            closeQuietly(response.getStream());
            response = malformedHeadResponse();
            keepAlive = false;
        }

        if (callback != null) {
            callback.onConnectionResponse(requestContext, request, response);
        }

        response.writeOut(connection.output);
        connection.output.flush();
        return keepAlive;
    }

    private void answerBadRequest(Connection connection, BadRequestException e) throws IOException, InterruptedException {
        //the stream position is lost: answer and close
        logger.i("Bad request from " + connection.socket.getInetAddress().getHostAddress() + ": " + e.getMessage());
        Response response = new TextResponse(e.code, e.phrase, e.phrase);
        response.headers.add(Response.HEADER_SERVER, SERVER_NAME);
        response.headers.add(Response.HEADER_CONNECTION, Request.CONNECTION_CLOSE);
        response.writeOut(connection.output);
        connection.output.flush();
        halfCloseAndDrain(connection.socket, connection.input);
        connection.closeReason = "bad request (" + e.code + ")";
    }

    /**
     * If the handler left the request body (partially) unread, the connection can only be
     * reused after those bytes are consumed. Drains a bounded amount.
     *
     * @return whether the connection can carry another request
     */
    private static boolean drainUnreadBody(Socket socket, Request request) throws IOException {
        BodyInputStream bodyStream = request.bodyStream;
        if (bodyStream == null || bodyStream.isFullyConsumed()) {
            return true;
        }
        if (bodyStream.isDetached()) {
            //body reading was handed off to another thread (e.g. CGI stdin pump),
            //we can not safely touch the stream or reuse the connection
            return false;
        }
        int prevTimeout = socket.getSoTimeout();
        socket.setSoTimeout(UNREAD_BODY_DRAIN_READ_TIMEOUT_MS);
        try {
            return bodyStream.drainRemaining(MAX_UNREAD_BODY_DRAIN_BYTES);
        } catch (IOException e) {
            return false;
        } finally {
            socket.setSoTimeout(prevTimeout);
        }
    }

    /**
     * The response for an exception thrown by the request handler.
     */
    static Response errorResponseFor(Throwable error) {
        if (error instanceof BadRequestException) {
            //a body found malformed while the handler read it
            BadRequestException badRequest = (BadRequestException) error;
            return new TextResponse(badRequest.code, badRequest.phrase, badRequest.phrase);
        } else if (error instanceof PayloadTooLargeException) {
            return new TextResponse(413, StandardResponses.PHRASE_PAYLOAD_TOO_LARGE, error.getMessage());
        } else if (error instanceof SecurityException) {
            return StandardResponses.FORBIDDEN(error.getMessage());
        } else if (error instanceof IllegalStateException) {
            return StandardResponses.BAD_REQUEST(error.getMessage());
        }
        //an unexpected failure: its details (paths, SQL, class names) went to the log and are
        //nobody's business on the other end of the connection. SecurityException and
        //IllegalStateException keep their messages: handlers throw those on purpose and the
        //web UI shows the text to the user
        return StandardResponses.INTERNAL_SERVER_ERROR(StandardResponses.PHRASE_INTERNAL_SERVER_ERROR);
    }

    private static Response notFoundResponse() {
        String text = "Not Found";
        Response response = new TextResponse(text);
        response.code = 404;
        response.phrase = text;
        return response;
    }

    /**
     * Adds the headers the server owns: framing (Content-Length, or closing the connection for a
     * body of unknown length), Server, Connection/Keep-Alive and the additional headers.
     *
     * @param keepAlive whether the server is willing to keep the connection open
     * @return whether the connection actually stays open after this response
     */
    boolean finalizeResponseHeaders(Response response, Request request, boolean keepAlive) {
        //make the response length explicit, otherwise clients have to wait for the
        //connection to close to detect the end of the response body
        if (!response.headers.containsKey(Response.HEADER_CONTENT_LENGTH) &&
                response.code != 204 && response.code != 304 && response.code / 100 != 1 &&
                !Request.METHOD_HEAD.equals(request.method)) {
            if (response.getStream() == null) {
                response.setContentLength(0);
            } else {
                //body of unknown length is delimited by closing the connection
                //(chunked responses are not supported)
                keepAlive = false;
            }
        }

        response.headers.add(Response.HEADER_SERVER, SERVER_NAME);
        String connectionHeader = response.headers.get(Response.HEADER_CONNECTION);
        if (!keepAlive) {
            //whatever the handler wrote there, the client has to know the connection ends
            response.headers.removeAll(Response.HEADER_KEEP_ALIVE);
            response.headers.put(Response.HEADER_CONNECTION, Request.CONNECTION_CLOSE);
        } else if (connectionHeader == null) {
            response.headers.add(Response.HEADER_CONNECTION, "Keep-Alive");
            response.headers.add(Response.HEADER_KEEP_ALIVE, "timeout=" + connectionKeepAliveTimeoutSeconds);
        } else if (Request.CONNECTION_CLOSE.equalsIgnoreCase(connectionHeader)) {
            //the handler asked to close the connection after this response
            keepAlive = false;
        }

        applyAdditionalResponseHeaders(response);
        return keepAlive;
    }

    /**
     * What is sent instead of a response whose head could not go on the wire safely - nothing
     * of the original, since any of its headers may be the problem.
     */
    private static Response malformedHeadResponse() {
        Response response = StandardResponses.INTERNAL_SERVER_ERROR(StandardResponses.PHRASE_INTERNAL_SERVER_ERROR);
        response.headers.add(Response.HEADER_SERVER, SERVER_NAME);
        response.headers.add(Response.HEADER_CONNECTION, Request.CONNECTION_CLOSE);
        return response;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Closes our side of a connection whose client may still be sending (an unread body, an
     * oversized head) without making the OS answer those bytes with a RST - which would make
     * the client discard the response it has not read yet.
     */
    private static void halfCloseAndDrain(Socket socket, InputStream input) throws IOException, InterruptedException {
        Thread.sleep(150);//to not let send to client FIN before response
        socket.shutdownOutput();
        socket.setSoTimeout(1000);

        byte[] buf = new byte[8192];
        int maxDrainBytes = 10 * 1024; // drain max 10kb
        int drained = 0;

        try {
            while (drained < maxDrainBytes) {
                int n = input.read(buf);
                if (n < 0) break; // client closed socked from his side
                drained += n;
            }
        } catch (SocketTimeoutException ignore) {
            // client closed socked from his side
        }
    }

    private void applyAdditionalResponseHeaders(Response response) {
        MultiMap<String, String> additionalResponseHeaders = this.additionalResponseHeaders;
        if (additionalResponseHeaders == null) {
            return;
        }
        for (String key: additionalResponseHeaders.keys()) {
            List<String> values = additionalResponseHeaders.getAll(key);
            for (int i = 0; i < values.size(); i++) {
                response.headers.add(key, values.get(i));
            }
        }
    }

    /**
     * Writes out the headers of a protocol upgrade response and hands the connection over to
     * the response's {@link ConnectionTakeoverHandler}. Returns when the takeover handler is
     * done with the connection, after which the caller closes the socket as usual.
     */
    private void handleConnectionUpgrade(UpgradeResponse response, RequestContext context,
                                         Request request, Socket socket, OutputStream output) throws Exception {
        //no Content-Length and no Connection/Keep-Alive headers here: the response is only
        //the handshake, everything after it is framed by the protocol we switch to
        response.headers.add(Response.HEADER_SERVER, SERVER_NAME);
        applyAdditionalResponseHeaders(response);

        if (callback != null) {
            callback.onConnectionResponse(context, request, response);
        }

        response.writeOut(output);
        output.flush();

        response.getTakeoverHandler().onConnectionTakenOver(socket, request.input, output);
    }

    /**
     * Whether the Host header names {@code hostName}, with or without a port. IPv6 literals
     * come in brackets ("[::1]:8080"), so the port is only split off after the closing one.
     */
    static boolean isRequestForHost(String hostName, String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim();
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            if (end < 0) {
                return false;
            }
            host = host.substring(1, end);
        } else {
            int portSeparator = host.indexOf(':');
            if (portSeparator != -1) {
                host = host.substring(0, portSeparator);
            }
        }
        String expected = hostName.startsWith("[") && hostName.endsWith("]") ?
                hostName.substring(1, hostName.length() - 1) : hostName;
        return host.equalsIgnoreCase(expected);
    }

    public boolean isListenThreadRunning() {
        return listenThreadRunning;
    }

    public boolean hasOpenConnections() {
        synchronized (trackedSockets) {
            return !trackedSockets.isEmpty();
        }
    }
}