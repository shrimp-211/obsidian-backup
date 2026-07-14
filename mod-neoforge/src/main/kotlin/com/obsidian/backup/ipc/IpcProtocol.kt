package com.obsidian.backup.ipc

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Defines the IPC message protocol between the Minecraft mod (Java/Kotlin)
 * and the Obsidian Sidecar daemon (Rust) over Unix Domain Socket.
 */
object IpcProtocol {

    val GSON: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()

    // --- Operation Codes ---
    enum class OpCode(val code: String) {
        @SerializedName("backup")  BACKUP("backup"),
        @SerializedName("status")  STATUS("status"),
        @SerializedName("restore") RESTORE("restore"),
        @SerializedName("diff")    DIFF("diff"),
        @SerializedName("browse")  BROWSE("browse"),
        @SerializedName("top")     TOP("top"),
        @SerializedName("verify")  VERIFY("verify"),
        @SerializedName("pin")     PIN("pin"),
        @SerializedName("clone")   CLONE("clone"),
        @SerializedName("rollback") ROLLBACK("rollback"),
        @SerializedName("cancel")  CANCEL("cancel"),
        @SerializedName("forecast") FORECAST("forecast"),
    }

    // --- Message Types ---
    data class Request(
        val tx_id: String = UUID.randomUUID().toString().take(8),
        val op: String,               // OpCode code
        val params: Map<String, Any?> = emptyMap()
    )

    data class Response(
        val tx_id: String,
        val status: String,           // "ok" | "error" | "progress"
        val message: String? = null,
        val data: JsonObject? = null
    )

    data class ProgressMessage(
        val tx_id: String,
        val phase: String,            // "snapshot", "scanning", "chunking", "compressing", "encrypting", "uploading", "committing"
        val progress: List<PhaseProgress>
    )

    data class PhaseProgress(
        val phase: String,
        val percent: Double,
        val files_done: Long,
        val files_total: Long,
        val bytes_done: Long,
        val bytes_total: Long
    )

    data class StatusData(
        val running: Boolean,
        val current_tx: String?,
        val state: String,            // "idle", "backing_up", "restoring", "verifying"
        val tps: Double,
        val cpu_percent: Double,
        val memory_mb: Long,
        val disk_iops_read: Long,
        val disk_iops_write: Long,
        val network_upload_mbps: Double,
        val queue_status: QueueStatus,
        val storage_stats: StorageStats?
    )

    data class QueueStatus(
        val scanner: Int,
        val chunk: Int,
        val compress: Int,
        val encrypt: Int,
        val upload: Int
    )

    data class StorageStats(
        val total_snapshots: Long,
        val total_size_bytes: Long,
        val dedup_ratio: Double,
        val packfile_count: Long
    )

    // --- Serialization helpers ---
    fun toJson(obj: Any): String = GSON.toJson(obj)

    fun parseRequest(json: String): Request = GSON.fromJson(json, Request::class.java)

    fun parseResponse(json: String): Response = GSON.fromJson(json, Response::class.java)

    fun parseProgress(json: String): ProgressMessage = GSON.fromJson(json, ProgressMessage::class.java)

    fun parseStatusData(data: JsonObject): StatusData = GSON.fromJson(data, StatusData::class.java)

    /**
     * Parameter builders for each operation.
     */
    object Params {
        fun backup(
            tag: String? = null,
            worldPath: String? = null,
            incremental: Boolean = true
        ): Map<String, Any?> = mapOf(
            "tag" to tag,
            "world_path" to worldPath,
            "incremental" to incremental,
            "timestamp" to System.currentTimeMillis()
        )

        fun status(): Map<String, Any?> = emptyMap<String, Any?>()

        fun restore(
            snapshotId: String,
            filePath: String? = null,
            chunkCoord: String? = null
        ): Map<String, Any?> = mapOf(
            "snapshot_id" to snapshotId,
            "file_path" to filePath,
            "chunk_coord" to chunkCoord
        )

        fun top(limit: Int = 5): Map<String, Any?> = mapOf("limit" to limit)

        fun diff(idA: String, idB: String): Map<String, Any?> = mapOf("id_a" to idA, "id_b" to idB)

        fun browse(snapshotId: String, path: String? = null): Map<String, Any?> = mapOf(
            "snapshot_id" to snapshotId,
            "path" to path
        )

        fun verify(repair: Boolean = false): Map<String, Any?> = mapOf("repair" to repair)

        fun pin(snapshotId: String, days: Int): Map<String, Any?> = mapOf(
            "snapshot_id" to snapshotId,
            "days" to days
        )

        fun clone(snapshotId: String, newName: String): Map<String, Any?> = mapOf(
            "snapshot_id" to snapshotId,
            "new_name" to newName
        )

        fun rollback(duration: String): Map<String, Any?> = mapOf("duration" to duration)

        fun forecast(): Map<String, Any?> = emptyMap<String, Any?>()
    }
}
