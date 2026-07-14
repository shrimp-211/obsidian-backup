package com.obsidian.backup.ipc

import com.obsidian.backup.config.ModConfig
import com.obsidian.backup.ObsidianBackupMod
import net.minecraft.network.chat.Component
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * IPC client that communicates with the Obsidian Sidecar daemon
 * via Unix Domain Socket (UDS) using Java 16+ native support.
 *
 * Thread safety: uses AtomicBoolean for the connected flag to ensure
 * visibility across the read thread and the main server thread.
 * The read loop handles all known channel-closure exceptions explicitly.
 */
class IpcClient(private val config: ModConfig) {

    private var channel: SocketChannel? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    // AtomicBoolean ensures the read thread sees disconnection immediately
    private val connected = AtomicBoolean(false)

    private val pendingRequests = ConcurrentHashMap<String, Consumer<IpcProtocol.Response>>()
    private val responseQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var readThread: Thread? = null

    /**
     * Connect to the Sidecar daemon via UDS.
     */
    fun connect(): Boolean {
        if (isConnected()) return true

        return try {
            val socketPath = Path.of(config.sidecarSocketPath)
            val address = UnixDomainSocketAddress.of(socketPath)
            channel = SocketChannel.open(address).apply {
                configureBlocking(true)
            }

            writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel)))
            reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))

            connected.set(true)
            ObsidianBackupMod.LOGGER.info("[IPC] Connected to Sidecar at {}", config.sidecarSocketPath)

            startReadLoop()
            true
        } catch (e: Exception) {
            ObsidianBackupMod.LOGGER.error("[IPC] Failed to connect to Sidecar: {}", e.message)
            false
        }
    }

    /**
     * Disconnect from the Sidecar gracefully.
     *
     * Order of operations is important:
     * 1. Set connected = false (stops the read loop)
     * 2. Interrupt the read thread (breaks any blocking read)
     * 3. Wait briefly for the thread to exit
     * 4. Close resources
     */
    fun disconnect() {
        connected.set(false)
        val thread = readThread
        readThread = null
        thread?.interrupt()

        // Give the read thread a moment to exit gracefully
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            writer?.close()
            reader?.close()
            channel?.close()
        } catch (_: Exception) {
            // Best-effort cleanup
        }
        writer = null
        reader = null
        channel = null
        ObsidianBackupMod.LOGGER.info("[IPC] Disconnected from Sidecar")
    }

    /**
     * Background thread that reads responses from the Sidecar.
     *
     * Uses AtomicBoolean.get() for the loop condition to guarantee
     * visibility of disconnection. Handles all known channel-closure
     * exceptions explicitly to avoid silent thread exit.
     */
    private fun startReadLoop() {
        readThread = Thread {
            try {
                while (connected.get()) {
                    try {
                        val line = reader?.readLine()
                        if (line == null) {
                            // EOF — sidecar closed the connection
                            ObsidianBackupMod.LOGGER.warn("[IPC] Sidecar closed connection (EOF)")
                            break
                        }
                        if (line.isBlank()) continue
                        responseQueue.add(line)
                    } catch (e: InterruptedException) {
                        // Expected during disconnect — exit cleanly
                        break
                    } catch (e: ClosedChannelException) {
                        // Channel closed by disconnect() — exit cleanly
                        break
                    } catch (e: Exception) {
                        if (connected.get()) {
                            ObsidianBackupMod.LOGGER.error("[IPC] Read error: {}", e.message)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (connected.get()) {
                    ObsidianBackupMod.LOGGER.error("[IPC] Read loop crashed: {}", e.message)
                }
            } finally {
                // Ensure connected is false if we exited unexpectedly
                connected.set(false)
            }
        }.apply {
            name = "Obsidian-IPC-Reader"
            isDaemon = true
            start()
        }
    }

    /**
     * Called every server tick to process queued responses on the main thread.
     */
    fun pollResponses() {
        while (true) {
            val raw = responseQueue.poll() ?: break
            try {
                val response = IpcProtocol.parseResponse(raw)
                val callback = pendingRequests.remove(response.tx_id)
                callback?.accept(response)
            } catch (e: Exception) {
                ObsidianBackupMod.LOGGER.error("[IPC] Failed to parse response: {}", e.message)
            }
        }
    }

    /**
     * Send a request to the Sidecar and register a callback for the response.
     */
    fun sendRequest(
        op: IpcProtocol.OpCode,
        params: Map<String, Any?>,
        callback: Consumer<IpcProtocol.Response>
    ): Boolean {
        if (!isConnected()) {
            ObsidianBackupMod.LOGGER.warn("[IPC] Not connected to Sidecar")
            return false
        }

        val request = IpcProtocol.Request(op = op.code, params = params)
        pendingRequests[request.tx_id] = callback

        return try {
            val json = IpcProtocol.toJson(request)
            synchronized(writer!!) {
                writer!!.write(json)
                writer!!.newLine()
                writer!!.flush()
            }
            true
        } catch (e: Exception) {
            pendingRequests.remove(request.tx_id)
            ObsidianBackupMod.LOGGER.error("[IPC] Failed to send request: {}", e.message)
            false
        }
    }

    /**
     * Send a request synchronously (blocking, for use off the main thread).
     *
     * NOTE: This bypasses the callback/pollResponse mechanism. The caller
     * must ensure this is not interleaved with async sendRequest calls.
     */
    fun sendRequestSync(op: IpcProtocol.OpCode, params: Map<String, Any?>): IpcProtocol.Response? {
        if (!isConnected()) return null

        val request = IpcProtocol.Request(op = op.code, params = params)

        return try {
            val json = IpcProtocol.toJson(request)
            synchronized(writer!!) {
                writer!!.write(json)
                writer!!.newLine()
                writer!!.flush()
            }
            val responseLine = reader?.readLine() ?: return null
            IpcProtocol.parseResponse(responseLine)
        } catch (e: Exception) {
            ObsidianBackupMod.LOGGER.error("[IPC] Sync request failed: {}", e.message)
            null
        }
    }

    fun isConnected(): Boolean = connected.get()
}
