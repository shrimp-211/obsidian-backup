package com.obsidian.backup.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Reed-Solomon (8+2) erasure coding over GF(2^8), mirroring the Rust sidecar's
 * erasure module. Objects are split into 8 data shards + 2 parity shards;
 * any 2 lost shards can be reconstructed. Used by {@code verify repair}.
 */
public final class ReedSolomon {

    public static final int DATA_SHARDS = 8;
    public static final int PARITY_SHARDS = 2;
    public static final int TOTAL_SHARDS = 10;

    // GF(2^8) with generator polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D).
    private static final int[] EXP = new int[510];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 510; i++) EXP[i] = EXP[i - 255];
    }

    private ReedSolomon() {}

    // ---- GF arithmetic ----

    private static int mul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    private static int div(int a, int b) {
        if (b == 0) throw new ArithmeticException("div by zero");
        if (a == 0) return 0;
        return EXP[(LOG[a] - LOG[b] + 255) % 255];
    }

    // ---- encode ----

    /** Split data into 8 equal shards and derive 2 parity shards. */
    public static byte[][] encode(byte[] data) {
        int shardSize = (data.length + DATA_SHARDS - 1) / DATA_SHARDS;
        if (shardSize == 0) shardSize = 1;
        byte[][] shards = new byte[TOTAL_SHARDS][shardSize];
        for (int i = 0; i < DATA_SHARDS; i++) {
            System.arraycopy(data, i * shardSize, shards[i], 0,
                Math.min(shardSize, Math.max(0, data.length - i * shardSize)));
        }
        // Parity shards: P_j = sum_i data_i * (i+1)^j  (Vandermonde).
        for (int j = 0; j < PARITY_SHARDS; j++) {
            for (int k = 0; k < shardSize; k++) {
                int acc = 0;
                for (int i = 0; i < DATA_SHARDS; i++) {
                    acc ^= mul(shards[i][k] & 0xff, EXP[(i * (j + 1)) % 255]);
                }
                shards[DATA_SHARDS + j][k] = (byte) acc;
            }
        }
        return shards;
    }

    /** Reassemble the original data from the 8 data shards. */
    public static byte[] decodeData(byte[][] shards, int originalSize) {
        int shardSize = shards[0].length;
        byte[] data = new byte[originalSize];
        for (int i = 0; i < DATA_SHARDS; i++) {
            int copy = Math.min(shardSize, Math.max(0, originalSize - i * shardSize));
            System.arraycopy(shards[i], 0, data, i * shardSize, copy);
        }
        return data;
    }

    /**
     * Reconstruct missing shards given at least 8 present shards (data or parity).
     * {@code present[i]} is true if shard i is available. Missing shards are
     * filled in-place.
     */
    public static void reconstruct(byte[][] shards, boolean[] present) {
        int shardSize = shards[0].length;
        int missing = 0;
        for (boolean b : present) if (!b) missing++;
        if (missing == 0) return;
        if (missing > PARITY_SHARDS) throw new IllegalArgumentException("too many lost shards");

        // Collect indices of present shards.
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < TOTAL_SHARDS; i++) if (present[i]) idx.add(i);
        if (idx.size() < DATA_SHARDS) throw new IllegalArgumentException("not enough shards");

        // Reconstruct each missing shard byte-by-byte using the surviving shards.
        for (int miss = 0; miss < TOTAL_SHARDS; miss++) {
            if (present[miss]) continue;
            // Solve for the missing shard from DATA_SHARDS surviving shards via
            // Lagrange interpolation over the Vandermonde points 1..8 (data) and
            // coefficients for parity rows.
            byte[] rec = new byte[shardSize];
            for (int k = 0; k < shardSize; k++) {
                int acc = 0;
                for (int s = 0; s < DATA_SHARDS; s++) {
                    int srcIdx = idx.get(s);
                    int srcVal = shards[srcIdx][k] & 0xff;
                    int coef = coefficient(srcIdx, miss, idx);
                    acc ^= mul(srcVal, coef);
                }
                rec[k] = (byte) acc;
            }
            shards[miss] = rec;
            present[miss] = true;
            idx.add(miss);
        }
    }

    /** Compute the interpolation coefficient for reconstructing shard `miss`
     *  from shard `src`, given the surviving shard indices `survivors`. */
    private static int coefficient(int src, int miss, List<Integer> survivors) {
        int num = 1, den = 1;
        int xs = point(src), xm = point(miss);
        for (int other : survivors) {
            if (other == src) continue;
            int xo = point(other);
            num = mul(num, xm ^ xo);
            den = mul(den, xs ^ xo);
        }
        return div(num, den);
    }

    /** Vandermonde evaluation point for a shard index. */
    private static int point(int idx) {
        if (idx < DATA_SHARDS) return idx + 1;       // data points 1..8
        return DATA_SHARDS + (idx - DATA_SHARDS) + 1; // parity points 9..10
    }
}
