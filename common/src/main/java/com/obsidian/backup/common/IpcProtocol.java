package com.obsidian.backup.common;

import com.google.gson.*;
import java.util.*;

/**
 * Defines the IPC message protocol between the Minecraft mod/plugin (Java)
 * and the Obsidian Sidecar daemon (Rust) over Unix Domain Socket.
 *
 * This class is loader-agnostic and version-independent — it depends only
 * on Gson and the Java standard library (Java 16+ for UnixDomainSocket).
 */
public final class IpcProtocol {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** IPC operation codes matching the Sidecar's op dispatch table. */
    public enum OpCode {
        BACKUP("backup"),
        STATUS("status"),
        RESTORE("restore"),
        DIFF("diff"),
        BROWSE("browse"),
        TOP("top"),
        VERIFY("verify"),
        PIN("pin"),
        CLONE("clone"),
        ROLLBACK("rollback"),
        CANCEL("cancel"),
        FORECAST("forecast"),
        EXPORT("export"),
        IMPORT("import"),
        AUTH("auth");

        public final String code;

        OpCode(String code) {
            this.code = code;
        }
    }

    /** IPC request sent from mod → sidecar. */
    public static class Request {
        public String tx_id;
        public String op;
        public JsonObject params;

        public Request() {}

        public Request(String op, Map<String, Object> params) {
            this.tx_id = UUID.randomUUID().toString().substring(0, 8);
            this.op = op;
            this.params = paramsToJson(params);
        }

        private static JsonObject paramsToJson(Map<String, Object> params) {
            JsonObject obj = new JsonObject();
            if (params != null) {
                for (var entry : params.entrySet()) {
                    Object v = entry.getValue();
                    if (v instanceof String) obj.addProperty(entry.getKey(), (String) v);
                    else if (v instanceof Number) obj.addProperty(entry.getKey(), (Number) v);
                    else if (v instanceof Boolean) obj.addProperty(entry.getKey(), (Boolean) v);
                    else if (v instanceof JsonElement) obj.add(entry.getKey(), (JsonElement) v);
                }
            }
            return obj;
        }
    }

    /** IPC response sent from sidecar → mod. */
    public static class Response {
        public String tx_id;
        public String status;    // "ok" | "error" | "progress"
        public String message;
        public JsonObject data;
    }

    /** Status data from the Sidecar. */
    public static class StatusData {
        public boolean running;
        public String current_tx;
        public String state;
        public double tps;
        public double cpu_percent;
        public long memory_mb;
        public long disk_iops_read;
        public long disk_iops_write;
        public double network_upload_mbps;
        public QueueStatus queue_status;
        public StorageStats storage_stats;

        public static StatusData fromJson(JsonObject data) {
            return GSON.fromJson(data, StatusData.class);
        }
    }

    public static class QueueStatus {
        public int scanner;
        public int chunk;
        public int compress;
        public int encrypt;
        public int upload;
    }

    public static class StorageStats {
        public long total_snapshots;
        public long total_size_bytes;
        public double dedup_ratio;
        public long packfile_count;
    }

    // --- Serialization ---

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static Response parseResponse(String json) {
        return GSON.fromJson(json, Response.class);
    }

    // --- Parameter builders ---

    public static Map<String, Object> paramsBackup(String tag, boolean incremental) {
        var params = new LinkedHashMap<String, Object>();
        params.put("tag", tag);
        params.put("incremental", incremental);
        params.put("timestamp", System.currentTimeMillis());
        return params;
    }

    public static Map<String, Object> paramsStatus() {
        return Collections.emptyMap();
    }

    public static Map<String, Object> paramsRestore(String snapshotId, String filePath, String chunkCoord) {
        var params = new LinkedHashMap<String, Object>();
        params.put("snapshot_id", snapshotId);
        if (filePath != null) params.put("file_path", filePath);
        if (chunkCoord != null) params.put("chunk_coord", chunkCoord);
        return params;
    }

    public static Map<String, Object> paramsTop(int limit) {
        return Map.of("limit", limit);
    }

    public static Map<String, Object> paramsDiff(String idA, String idB) {
        return Map.of("id_a", idA, "id_b", idB);
    }

    public static Map<String, Object> paramsBrowse(String snapshotId, String path) {
        var params = new LinkedHashMap<String, Object>();
        params.put("snapshot_id", snapshotId);
        if (path != null) params.put("path", path);
        return params;
    }

    public static Map<String, Object> paramsVerify(boolean repair) {
        return Map.of("repair", repair);
    }

    public static Map<String, Object> paramsPin(String snapshotId, int days) {
        return Map.of("snapshot_id", snapshotId, "days", days);
    }

    public static Map<String, Object> paramsClone(String snapshotId, String newName) {
        return Map.of("snapshot_id", snapshotId, "new_name", newName);
    }

    public static Map<String, Object> paramsRollback(String duration) {
        return Map.of("duration", duration);
    }

    public static Map<String, Object> paramsForecast() {
        return Collections.emptyMap();
    }

    public static Map<String, Object> paramsExport(String path) {
        return Map.of("path", path);
    }

    public static Map<String, Object> paramsImport(String path) {
        return Map.of("path", path);
    }

    public static Map<String, Object> paramsAuth(String token) {
        return Map.of("token", token);
    }
}
