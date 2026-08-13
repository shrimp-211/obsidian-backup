package com.obsidian.backup.common.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * FastCDC content-defined chunking (Java implementation, mirroring the Rust
 * sidecar's chunker).
 *
 * Chunk boundaries are decided by the content (a rolling gear hash), so the
 * same bytes always produce the same chunks — the key property that makes
 * deduplication effective even as files shift around.
 */
public final class ChunkEngine {

    // Deterministic pseudo-random gear table (must be identical across runs).
    private static final long[] GEAR = new long[256];

    static {
        long x = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 256; i++) {
            x ^= (x << 13); x ^= (x >>> 7); x ^= (x << 17);
            GEAR[i] = x;
        }
    }

    private final int minSize;
    private final int avgSize;
    private final int maxSize;
    private final long mask;

    public ChunkEngine() {
        this(4 * 1024, 64 * 1024, 256 * 1024);
    }

    public ChunkEngine(int minSize, int avgSize, int maxSize) {
        this.minSize = minSize;
        this.avgSize = avgSize;
        this.maxSize = maxSize;
        this.mask = avgSize - 1L;
    }

    /** A single chunk: content hash (SHA-256), byte offset, size and payload. */
    public static final class Chunk {
        public final String hash;
        public final long offset;
        public final int size;
        public final byte[] data;
        public Chunk(String hash, long offset, int size, byte[] data) {
            this.hash = hash; this.offset = offset; this.size = size; this.data = data;
        }
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(Chunk chunk) throws IOException;
    }

    /** Chunk a whole file, streaming with bounded memory (max_size at a time). */
    public List<Chunk> chunkFile(Path file) throws IOException {
        List<Chunk> chunks = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            chunkStream(in, chunk -> chunks.add(chunk));
        }
        return chunks;
    }

    /** Stream-chunk an input, invoking the consumer per chunk. */
    public void chunkStream(InputStream in, ChunkConsumer consumer) throws IOException {
        byte[] buf = new byte[maxSize + 64 * 1024];
        byte[] read = new byte[64 * 1024];
        int fill = 0;   // valid bytes in buf
        boolean eof = false;
        long offset = 0;

        while (true) {
            while (fill < minSize && !eof) {
                int n = in.read(read);
                if (n < 0) { eof = true; }
                else {
                    if (fill + n > buf.length) break; // safety
                    System.arraycopy(read, 0, buf, fill, n);
                    fill += n;
                }
            }
            if (fill == 0) break;

            int chunkLen = fill <= minSize ? fill : findBoundary(buf, fill);
            byte[] data = new byte[chunkLen];
            System.arraycopy(buf, 0, data, 0, chunkLen);
            String hash = hashOf(data);

            consumer.accept(new Chunk(hash, offset, chunkLen, data));

            offset += chunkLen;
            System.arraycopy(buf, chunkLen, buf, 0, fill - chunkLen);
            fill -= chunkLen;

            if (eof && fill == 0) break;
        }
    }

    /** Find the next chunk boundary in [minSize, maxSize] via rolling gear hash. */
    private int findBoundary(byte[] buf, int len) {
        int end = Math.min(maxSize, len);
        if (minSize >= end) return end;

        long hash = 0;
        for (int i = minSize - 48; i < minSize; i++) {
            hash = (hash << 1) + GEAR[buf[i] & 0xff];
        }
        for (int i = minSize; i < end; i++) {
            hash = (hash << 1) + GEAR[buf[i] & 0xff];
            hash -= GEAR[buf[i - 48] & 0xff] << 48;
            if ((hash & mask) == 0) return i + 1;
        }
        return end;
    }

    /** Hash a byte array with SHA-256 (used for chunk identity and verification). */
    public static String hashOf(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
