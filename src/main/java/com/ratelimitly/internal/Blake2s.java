package com.ratelimitly.internal;

public final class Blake2s {
    private static final int BLOCK_BYTES = 64;
    private static final int[] IV = {
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    };
    private static final byte[][] SIGMA = {
        { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,15 },
        {14,10, 4, 8, 9,15,13, 6, 1,12, 0, 2,11, 7, 5, 3 },
        {11, 8,12, 0, 5, 2,15,13,10,14, 3, 6, 7, 1, 9, 4 },
        { 7, 9, 3, 1,13,12,11,14, 2, 6, 5,10, 4, 0,15, 8 },
        { 9, 0, 5, 7, 2, 4,10,15,14, 1,11,12, 6, 8, 3,13 },
        { 2,12, 6,10, 0,11, 8, 3, 4,13, 7, 5,15,14, 1, 9 },
        {12, 5, 1,15,14,13, 4,10, 0, 7, 6, 3, 9, 2, 8,11 },
        {13,11, 7,14,12, 1, 3, 9, 5, 0,15, 4, 8, 6, 2,10 },
        { 6,15,14, 9,11, 3, 0, 8,12, 2,13, 7, 1, 4,10, 5 },
        {10, 2, 8, 4, 7, 6, 1, 5,15,11, 9,14, 3,12,13, 0 }
    };

    private Blake2s() {
    }

    public static byte[] digest(byte[] input) {
        int[] h = IV.clone();
        h[0] ^= 0x01010020;

        long counter = 0;
        int offset = 0;
        while (input.length - offset > BLOCK_BYTES) {
            counter += BLOCK_BYTES;
            compress(h, input, offset, counter, false);
            offset += BLOCK_BYTES;
        }

        byte[] block = new byte[BLOCK_BYTES];
        int remaining = input.length - offset;
        System.arraycopy(input, offset, block, 0, remaining);
        counter += remaining;
        compress(h, block, 0, counter, true);

        byte[] out = new byte[32];
        for (int i = 0; i < h.length; i++) {
            writeIntLe(out, i * 4, h[i]);
        }
        return out;
    }

    private static void compress(int[] h, byte[] block, int offset, long counter, boolean last) {
        int[] m = new int[16];
        for (int i = 0; i < 16; i++) {
            m[i] = readIntLe(block, offset + (i * 4));
        }

        int[] v = new int[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= (int) counter;
        v[13] ^= (int) (counter >>> 32);
        if (last) {
            v[14] = ~v[14];
        }

        for (int round = 0; round < 10; round++) {
            byte[] s = SIGMA[round];
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }

        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void g(int[] v, int a, int b, int c, int d, int x, int y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 12);
        v[a] = v[a] + v[b] + y;
        v[d] = Integer.rotateRight(v[d] ^ v[a], 8);
        v[c] = v[c] + v[d];
        v[b] = Integer.rotateRight(v[b] ^ v[c], 7);
    }

    private static int readIntLe(byte[] input, int offset) {
        return (input[offset] & 0xFF)
            | ((input[offset + 1] & 0xFF) << 8)
            | ((input[offset + 2] & 0xFF) << 16)
            | ((input[offset + 3] & 0xFF) << 24);
    }

    private static void writeIntLe(byte[] output, int offset, int value) {
        output[offset] = (byte) (value & 0xFF);
        output[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        output[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        output[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
