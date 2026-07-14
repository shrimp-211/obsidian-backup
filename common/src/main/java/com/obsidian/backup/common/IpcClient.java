package com.obsidian.backup.common;

import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Loader-agnostic IPC client for communicating with the Obsidian Sidecar daemon
 * via Unix Domain Socket (UDS), using Java 16+ native support.
 *
 * Thread safety: uses AtomicBoolean for the connected flag to ensure
 * visibility across the read thread and the main server thread.
 */
public class IpcClient implements AutoCloseable {

    private final String socketPath;
    private final String authToken;
    private SocketChannel channel;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, Consumer<IpcProtocol.Response>> pendingRequests = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> responseQueue = new ConcurrentLinkedQueue<>();

    private volatile Thread readThread;
    private final Logger logger;

    public interface Logger {
        void info(String msg, Object... args);
        void warn(String msg, Object... args);
        void error(String msg, Object... args);
    }

    public IpcClient(String socketPath, String authToken, Logger logger) {
        this.socketPath = socketPath;
        this.authToken = authToken;
        this.logger = logger;
    }

    /** Connect to the Sidecar daemon and authenticate. */
    public boolean connect() {
        if (isConnected()) return true;

        try {
            var address = UnixDomainSocketAddress.of(Path.of(socketPath));
            channel = SocketChannel.open(address);
            channel.configureBlocking(true);

            writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));
            reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));

            // Authenticate
            var authRequest = new IpcProtocol.Request(
                IpcProtocol.OpCode.AUTH.code,
                IpcProtocol.paramsAuth(authToken != null ? authToken : "obsidian-default-token")
            );
            String authJson = IpcProtocol.toJson(authRequest);
            writer.write(authJson);
            writer.newLine();
            writer.flush();

            String authLine = reader.readLine();
            if (authLine == null) {
                logger.error("[IPC] Auth failed: no response from sidecar");
                close();
                return false;
            }

            var authResp = IpcProtocol.parseResponse(authLine);
            if (!"ok".equals(authResp.status)) {
                logger.error("[IPC] Auth rejected: {}", authResp.message);
                close();
                return false;
            }

            connected.set(true);
            logger.info("[IPC] Connected and authenticated at {}", socketPath);
            startReadLoop();
            return true;

        } catch (Exception e) {
            logger.error("[IPC] Connection failed: {}", e.getMessage());
            return false;
        }
    }

    /** Gracefully disconnect. */
    public void close() {
        connected.set(false);
        var thread = readThread;
        readThread = null;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { writer.close(); } catch (Exception ignored) {}
        try { reader.close(); } catch (Exception ignored) {}
        try { channel.close(); } catch (Exception ignored) {}
        writer = null; reader = null; channel = null;
        logger.info("[IPC] Disconnected");
    }

    private void startReadLoop() {
        readThread = new Thread(() -> {
            try {
                while (connected.get()) {
                    try {
                        String line = reader.readLine();
                        if (line == null) {
                            logger.warn("[IPC] Sidecar closed connection (EOF)");
                            break;
                        }
                        if (line.isBlank()) continue;
                        responseQueue.add(line);
                    } catch (InterruptedException | ClosedChannelException e) {
                        break;
                    } catch (Exception e) {
                        if (connected.get()) logger.error("[IPC] Read error: {}", e.getMessage());
                        break;
                    }
                }
            } catch (Exception e) {
                if (connected.get()) logger.error("[IPC] Read loop crashed: {}", e.getMessage());
            } finally {
                connected.set(false);
            }
        }, "Obsidian-IPC-Reader");
        readThread.setDaemon(true);
        readThread.start();
    }

    /** Called each tick to dispatch queued responses on the main thread. */
    public void pollResponses() {
        String raw;
        while ((raw = responseQueue.poll()) != null) {
            try {
                var response = IpcProtocol.parseResponse(raw);
                var callback = pendingRequests.remove(response.tx_id);
                if (callback != null) callback.accept(response);
            } catch (Exception e) {
                logger.error("[IPC] Failed to parse response: {}", e.getMessage());
            }
        }
    }

    /** Send an async request with a callback. */
    public boolean sendRequest(IpcProtocol.OpCode op, Map<String, Object> params,
                                Consumer<IpcProtocol.Response> callback) {
        if (!isConnected()) {
            logger.warn("[IPC] Not connected");
            return false;
        }

        var request = new IpcProtocol.Request(op.code, params);
        pendingRequests.put(request.tx_id, callback);

        try {
            String json = IpcProtocol.toJson(request);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
            return true;
        } catch (Exception e) {
            pendingRequests.remove(request.tx_id);
            logger.error("[IPC] Send failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return connected.get();
    }
}
