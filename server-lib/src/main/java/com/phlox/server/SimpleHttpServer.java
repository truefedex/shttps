package com.phlox.server;

import com.phlox.server.handlers.RequestHandler;
import com.phlox.server.request.DefaultRequestBodyReader;
import com.phlox.server.request.DefaultRequestHeadersParser;
import com.phlox.server.request.Request;
import com.phlox.server.request.RequestBodyReader;
import com.phlox.server.request.RequestContext;
import com.phlox.server.request.RequestHeadersParser;
import com.phlox.server.responses.Response;
import com.phlox.server.responses.TextResponse;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.utils.SHTTPSLoggerProxy.Logger;

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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
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

	static final Logger logger = SHTTPSLoggerProxy.getLogger(SimpleHttpServer.class);

	public RequestHandler requestHandler;
	public Callback callback;

	private volatile ServerSocket serverSocket;
	private final Set<Socket> trackedSockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private volatile boolean shouldStopListen = false;
	private volatile boolean listenThreadRunning = false;
	private final ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
	private Thread listenThread;
	private final RequestHeadersParser requestHeadersParser = new DefaultRequestHeadersParser();

	public volatile Set<NetworkInterface> allowedNetworkInterfaces = null;

	public volatile Set<InetAddress> allowedClientAddresses = null;

	public volatile Map<String, String> additionalResponseHeaders = null;
	public volatile int connectionKeepAliveTimeoutSeconds = 15;

	public AtomicLong connectionCount = new AtomicLong(0);

	public interface Callback {
		void onServerStarted();
		void onServerStopped();
		void onNewConnection(Socket socket, long connectionNumber);
		void onConnectionClosed(Socket socket);
		void onConnectionError(Socket socket, Exception e);
		void onConnectionRequest(RequestContext context, Request request);
		void onConnectionResponse(RequestContext context, Request request, Response response);
		void onConnectionRejected(Socket socket, int reason);
	}

	public SimpleHttpServer() {
		init();
	}

	public static class Builder {
		private SimpleHttpServer server;

		public Builder() {
			server = new SimpleHttpServer();
		}

		public Builder setRequestHandler(RequestHandler requestHandler) {
			server.requestHandler = requestHandler;
			return this;
		}

		public Builder setCallback(Callback callback) {
			server.callback = callback;
			return this;
		}

		public SimpleHttpServer build() {
			return server;
		}
	}

	private void init() {
		this.requestHandler = new RequestHandler() {
			@Override
			public Response handleRequest(RequestContext context, Request request, RequestBodyReader requestBodyReader) throws Exception {
				return new TextResponse(this.getClass().getSimpleName() + "working!");
			}
		};
	}

	public void startListen(ServerSocket serverSocket) {
		if (listenThread != null && listenThread.isAlive()) {
			throw new IllegalStateException("Listen thread already running. Stop it first");
		}
		this.serverSocket = serverSocket;
		try {
			serverSocket.setSoTimeout(500);
		} catch (SocketException e) {
			logger.e("Cannot set socket timeout", e);
		}
		listenThread = new Thread(listenRunnable);
		listenThread.start();
	}

	public void startListen(int port) throws IOException {
		startListen(new ServerSocket(port));
	}

	public void startListen(int port, byte[] p12cert, String password) throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(new ByteArrayInputStream(p12cert), password.toCharArray());
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, password.toCharArray());

		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(kmf.getKeyManagers(), null, null);

		SSLServerSocketFactory ssf = sc.getServerSocketFactory();
		SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(port);
		startListen(serverSocket);
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
		try {
			serverSocket.close();
		} catch (IOException e) {
			logger.e("Error while closing server socket", e);
		}
		for (Socket client : trackedSockets) {
			try {
				client.close();
			} catch (IOException e) {
				logger.e("Error while closing client socket", e);
			}
		}
		trackedSockets.clear();
		Thread lthr = listenThread;
		if (lthr != null && lthr.isAlive()) {
			try {
				lthr.join();
			} catch (InterruptedException e) {
				logger.e("Error while waiting for listen thread to finish", e);
			}
			listenThread = null;
		}
		listenThreadRunning = false;
	}

	private final Runnable listenRunnable = new Runnable() {
		@Override
		public void run() {
			listenThreadRunning = true;

			if (callback != null) {
				callback.onServerStarted();
			}

			while (!shouldStopListen) {
				Socket socket = null;
				try {
					socket = serverSocket.accept();
				} catch (SocketTimeoutException e) {
					continue;
				} catch (Exception e) {
					logger.stackTrace(e);
                    if (!shouldStopListen) {
						shouldStopListen = true;
					}
					continue;
				}

				long connectionNumber = connectionCount.incrementAndGet();

				if (callback != null) {
					callback.onNewConnection(socket, connectionNumber);
				}

				Set<InetAddress> allowedClientAddresses = SimpleHttpServer.this.allowedClientAddresses;
				if (allowedClientAddresses != null && !allowedClientAddresses.contains(socket.getInetAddress())) {
					logger.i("Connection from " + socket.getInetAddress().getHostAddress() + " is not allowed");
					try { socket.close();} catch (IOException ignored) {}
					if (callback != null) {
						callback.onConnectionClosed(socket);
						callback.onConnectionRejected(socket, REASON_CLIENT_ADDRESS_NOT_ALLOWED);
					}
					continue;
				}

				Set<NetworkInterface> allowedInterfaces = SimpleHttpServer.this.allowedNetworkInterfaces;
				if (allowedInterfaces != null) {
					boolean found = false;
					for (NetworkInterface ni : allowedInterfaces) {
						for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
							if (ia.getAddress().equals(socket.getLocalAddress())) {
								found = true;
								break;
							}
						}
						if (found) {
							break;
						}
					}
					if (!found) {
						logger.i("Connection from " + socket.getInetAddress().getHostAddress() + " is not allowed");
						try { socket.close();} catch (IOException ignored) {}
						if (callback != null) {
							callback.onConnectionClosed(socket);
							callback.onConnectionRejected(socket, REASON_NETWORK_INTERFACE_NOT_ALLOWED);
						}
						continue;
					}
				}

				final Socket finalSocket = socket;
				threadPoolExecutor.execute(() -> handleConnection(finalSocket));
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
	};

	private void handleConnection(final Socket socket) {
		trackedSockets.add(socket);
		Request request;
		DefaultRequestBodyReader requestBodyReader = new DefaultRequestBodyReader();
		try (socket;
			 OutputStream output = new BufferedOutputStream(socket.getOutputStream());
				InputStream input = new BufferedInputStream(socket.getInputStream())) {
			socket.setSoTimeout(connectionKeepAliveTimeoutSeconds * 1000);
			socket.setKeepAlive(true);
			//socket.setTcpNoDelay(true);
			boolean keepAlive = true;
			while (keepAlive) {
				request = requestHeadersParser.readRequestHeaders(input, socket.getInetAddress().getHostAddress());
				keepAlive = !request.requestToCloseConnection;

				RequestContext requestContext = new RequestContext(SimpleHttpServer.this);

				if (callback != null) {
					callback.onConnectionRequest(requestContext, request);
				}

				Response response = null;
				Throwable errDuringHandle = null;
				try {
					requestBodyReader.bodyWasRead = false;
					response = requestHandler.handleRequest(requestContext, request, requestBodyReader);
				} catch (Throwable e) {
					logger.stackTrace(e);
					errDuringHandle = e;
				}

				if (errDuringHandle != null || response == null || (
						!requestBodyReader.bodyWasRead && request.shouldHaveABody()
				)) {
					keepAlive = false;
				}

				if (errDuringHandle != null) {
					String text = "Internal Server Error";
					response = new TextResponse(text + ": " + errDuringHandle.getMessage());
					response.code = 500;
					response.phrase = text;
				} else if (response == null) {
					String text = "Not Found";
					response = new TextResponse(text);
					response.code = 404;
					response.phrase = text;
				}

				response.headers.put(Response.HEADER_SERVER, SimpleHttpServer.class.getSimpleName());
				//keepAlive = false;
				if (keepAlive) {
					response.headers.put(Response.HEADER_CONNECTION, "Keep-Alive");
					response.headers.put(Response.HEADER_KEEP_ALIVE, "timeout=" + connectionKeepAliveTimeoutSeconds);
				} else {
					response.headers.put(Response.HEADER_CONNECTION, Request.CONNECTION_CLOSE);
				}

				Map<String, String> additionalResponseHeaders = this.additionalResponseHeaders;
				if (additionalResponseHeaders != null) {
					response.headers.putAll(additionalResponseHeaders);
				}

				if (callback != null) {
					callback.onConnectionResponse(requestContext, request, response);
				}

				response.writeOut(output);
				output.flush();

				if (!keepAlive) {
					socket.shutdownOutput();
					socket.shutdownInput();
				}
			}
		} catch (SocketTimeoutException e) {
			logger.d("Client timed out: " + socket.getRemoteSocketAddress());
		} catch (Exception e) {
			if (e instanceof SocketException && e.getMessage().contains("Connection reset")) {
				logger.d("Client closed connection: " + socket.getRemoteSocketAddress());
			} else {
				logger.stackTrace(e);
				if (callback != null) {
					callback.onConnectionError(socket, e);
				}
			}
		} finally {
			trackedSockets.remove(socket);
			if (callback != null) {
				callback.onConnectionClosed(socket);
			}
		}
	}

	public boolean isListenThreadRunning() {
		return listenThreadRunning;
	}
}