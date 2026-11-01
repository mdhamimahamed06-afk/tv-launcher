package com.viral.launcher;

import java.io.UnsupportedEncodingException;

/** Tiny QR code generator (byte mode, error level L, versions 1-5). */
final class Qr {

    private static final int[] DATA_CW = {0, 19, 34, 55, 80, 108};
    private static final int[] ECC_CW = {0, 7, 10, 15, 20, 26};
    private static final int[][] ALIGN = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}};

    final int size;
    final boolean[][] mod;
    private final boolean[][] fn;

    /** Returns null if the text is too long. */
    static Qr encode(String text) {
        byte[] b;
        try {
            b = text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        for (int v = 1; v <= 5; v++) {
            if (b.length <= DATA_CW[v] - 2) return new Qr(v, b);
        }
        return null;
    }

    private Qr(int ver, byte[] data) {
        size = 17 + ver * 4;
        mod = new boolean[size][size];
        fn = new boolean[size][size];

        // --- build data codewords
        int cap = DATA_CW[ver] * 8;
        boolean[] bits = new boolean[cap + 16];
        int[] pos = {0};
        put(bits, pos, 4, 4);          // byte mode
        put(bits, pos, data.length, 8);
        for (byte x : data) put(bits, pos, x & 0xFF, 8);
        int term = Math.min(4, cap - pos[0]);
        put(bits, pos, 0, term);
        while (pos[0] % 8 != 0) put(bits, pos, 0, 1);
        int pad = 0xEC;
        while (pos[0] < cap) {
            put(bits, pos, pad, 8);
            pad = pad == 0xEC ? 0x11 : 0xEC;
        }
        byte[] cw = new byte[DATA_CW[ver] + ECC_CW[ver]];
        for (int i = 0; i < DATA_CW[ver]; i++) {
            int v = 0;
            for (int k = 0; k < 8; k++) v = (v << 1) | (bits[i * 8 + k] ? 1 : 0);
            cw[i] = (byte) v;
        }
        byte[] gen = generator(ECC_CW[ver]);
        byte[] rem = remainder(java.util.Arrays.copyOf(cw, DATA_CW[ver]), gen);
        System.arraycopy(rem, 0, cw, DATA_CW[ver], rem.length);

        // --- function patterns
        for (int i = 0; i < size; i++) {
            setFn(6, i, i % 2 == 0);
            setFn(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] ap = ALIGN[ver];
        for (int i = 0; i < ap.length; i++) {
            for (int j = 0; j < ap.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == ap.length - 1) || (i == ap.length - 1 && j == 0))
                    continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        formatBits(0);

        // --- place data
        int idx = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && idx < cw.length * 8) {
                        mod[y][x] = ((cw[idx >>> 3] >>> (7 - (idx & 7))) & 1) != 0;
                        idx++;
                    }
                }
            }
        }

        // --- choose best mask
        int best = 0, bestPen = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            formatBits(m);
            int p = penalty();
            if (p < bestPen) {
                bestPen = p;
                best = m;
            }
            applyMask(m);
        }
        applyMask(best);
        formatBits(best);
    }

    private static void put(boolean[] bits, int[] pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[pos[0]++] = ((val >>> i) & 1) != 0;
    }

    private void setFn(int x, int y, boolean dark) {
        if (x < 0 || y < 0 || x >= size || y >= size) return;
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(cx + dx, cy + dy, d != 2 && d != 4);
            }
        }
    }

    private void formatBits(int mask) {
        int data = (1 << 3) | mask; // error level L = 01
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
        setFn(8, 7, bit(bits, 6));
        setFn(8, 8, bit(bits, 7));
        setFn(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
        setFn(8, size - 8, true);
    }

    private static boolean bit(int v, int i) {
        return ((v >>> i) & 1) != 0;
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!fn[y][x] && inv) mod[y][x] = !mod[y][x];
            }
        }
    }

    private int penalty() {
        int result = 0;
        // rule 1: runs of 5+ same colour
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? mod[a][b] : mod[b][a];
                    boolean prev = pass == 0 ? mod[a][b - 1] : mod[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) result += 3;
                        else if (run > 5) result++;
                    } else {
                        run = 1;
                    }
                }
            }
        }
        // rule 2: 2x2 blocks
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = mod[y][x];
                if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) result += 3;
            }
        // rule 3: finder-like patterns
        int[] p1 = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        int[] p2 = {0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1};
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                for (int b = 0; b + 11 <= size; b++) {
                    boolean m1 = true, m2 = true;
                    for (int k = 0; k < 11; k++) {
                        boolean v = pass == 0 ? mod[a][b + k] : mod[b + k][a];
                        int iv = v ? 1 : 0;
                        if (iv != p1[k]) m1 = false;
                        if (iv != p2[k]) m2 = false;
                    }
                    if (m1) result += 40;
                    if (m2) result += 40;
                }
            }
        }
        // rule 4: dark ratio
        int dark = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) if (mod[y][x]) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(k, 0) * 10;
        return result;
    }

    private static byte[] generator(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] remainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(div[i] & 0xFF, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }
}
