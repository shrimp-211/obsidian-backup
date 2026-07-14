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
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.Consumer

/**
 * IPC client that communicates with the Obsidian Sidecar daemon
 * via Unix Domain Socket (UDS) using Java 16+ native support.
 */
class IpcClient(private val config: ModConfig) {

    private var channel: SocketChannel? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var connected = false

    private val pendingRequests = ConcurrentHashMap<String, Consumer<IpcProtocol.Response>>()
    private val responseQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var readThread: Thread? = null

    /**
     * Connect to the Sidecar daemon via UDS.
     */
    fun connect(): Boolean {
        if (connected) return true

        return try {
            val socketPath = Path.of(config.sidecarSocketPath)
            val address = UnixDomainSocketAddress.of(socketPath)
            channel = SocketChannel.open(address).apply {
                configureBlocking(true)
            }

            writer = BufferedWriter(OutputStreamWriter(Channels.newOutputStream(channel)))
            reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))

            connected = true
            ObsidianBackupMod.LOGGER.info("[IPC] Connected to Sidecar at {}", config.sidecarSocketPath)

            // Start background read thread
            startReadLoop()
            true
        } catch (e: Exception) {
            ObsidianBackupMod.LOGGER.error("[IPC] Failed to connect to Sidecar: {}", e.message)
            false
        }
    }

    /**
     * Disconnect from the Sidecar.
     */
    fun disconnect() {
        connected = false
        readThread?.interrupt()
        try {
            writer?.close()
            reader?.close()
            channel?.close()
        } catch (_: Exception) {}
        writer = null
        reader = null
        channel = null
        ObsidianBackupMod.LOGGER.info("[IPC] Disconnected from Sidecar")
    }

    /**
     * Start a background thread to continuously read responses from the Sidecar.
     */
    private fun startReadLoop() {
        readThread = Thread {
            while (connected) {
                try {
                    val line = reader?.readLine() ?: break
                    if (line.isBlank()) continue
                    responseQueue.add(line)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (connected) {
                        ObsidianBackupMod.LOGGER.error("[IPC] Read error: {}", e.message)
                    }
                    break
                }
            }
            // If we exit the read loop unexpectedly, mark as disconnected
            if (connected) {
                ObsidianBackupMod.LOGGER.warn("[IPC] Read loop exited unexpectedly")
                connected = false
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
        if (!connected) {
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
     */
    fun sendRequestSync(op: IpcProtocol.OpCode, params: Map<String, Any?>): IpcProtocol.Response? {
        if (!connected) return null

        val request = IpcProtocol.Request(op = op.code, params = params)

        return try {
            val json = IpcProtocol.toJson(request)
            synchronized(writer!!) {
                writer!!.write(json)
                writer!!.newLine()
                writer!!.flush()
            }
            // Read response (blocking)
            val responseLine = reader?.readLine() ?: return null
            IpcProtocol.parseResponse(responseLine)
        } catch (e: Exception) {
            ObsidianBackupMod.LOGGER.error("[IPC] Sync request failed: {}", e.message)
            null
        }
    }

    fun isConnected(): Boolean = connected
}
