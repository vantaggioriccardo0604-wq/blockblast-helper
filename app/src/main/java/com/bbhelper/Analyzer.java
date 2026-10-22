package com.bbhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Riconosce la griglia 8x8 e i pezzi disponibili da uno screenshot.
 * Nessuna dipendenza Android: lavora su un array di pixel ARGB.
 */
public final class Analyzer {

    public static final class Piece {
        public final boolean[][] cells; // [riga][colonna]
        public final int rows, cols;
        public final int left, top, right, bottom; // riquadro sullo schermo
        Piece(boolean[][] cells, int left, int top, int right, int bottom) {
            this.cells = cells;
            this.rows = cells.length;
            this.cols = cells[0].length;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int count() {
            int n = 0;
            for (boolean[] r : cells) for (boolean b : r) if (b) n++;
            return n;
        }
        public String key() {
            StringBuilder sb = new StringBuilder();
            for (boolean[] r : cells) {
                for (boolean b : r) sb.append(b ? '#' : '.');
                sb.append('/');
            }
            return sb.toString();
        }
    }

    public static final class Result {
        public boolean ok;
        public String error;
        public float boardLeft, boardTop, cellSize; // area interna della griglia
        public int outerLeft, outerTop, outerRight, outerBottom;
        public final boolean[] grid = new boolean[64];
        /** Esattamente 3 posizioni (sinistra, centro, destra); null se il posto e' vuoto. */
        public final Piece[] slots = new Piece[3];

        public String signature() {
            StringBuilder sb = new StringBuilder();
            for (boolean b : grid) sb.append(b ? '1' : '0');
            for (Piece p : slots) sb.append('|').append(p == null ? "-" : p.key());
            return sb.toString();
        }
    }

    private Analyzer() {}

    static int r(int c) { return (c >> 16) & 0xff; }
    static int g(int c) { return (c >> 8) & 0xff; }
    static int b(int c) { return c & 0xff; }
    static int lum(int c) { return (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000; }
    static int dist(int a, int c) {
        return Math.abs(r(a) - r(c)) + Math.abs(g(a) - g(c)) + Math.abs(b(a) - b(c));
    }
    static float sat(int c) {
        int mx = Math.max(r(c), Math.max(g(c), b(c)));
        int mn = Math.min(r(c), Math.min(g(c), b(c)));
        return mx == 0 ? 0f : (mx - mn) / (float) mx;
    }

    public static Result analyze(int[] px, int w, int h) {
        return analyze(px, w, h, null);
    }

    /** ignore = {l,t,r,b} zona da ignorare (es. la bolla flottante), oppure null. */
    public static Result analyze(int[] px, int w, int h, int[] ignore) {
        Result res = new Result();
        if (!findBoard(px, w, h, res)) {
            res.error = "Griglia non trovata";
            return res;
        }
        readGrid(px, w, h, res, ignore);
        readPieces(px, w, h, res, ignore);
        res.ok = true;
        return res;
    }

    // ---------------------------------------------------------------- griglia

    private static boolean findBoard(int[] px, int w, int h, Result res) {
        int step = Math.max(1, w / 360);
        boolean[] boardRow = new boolean[h];
        for (int y = 0; y < h; y++) {
            int e1 = px[y * w + 1], e2 = px[y * w + w - 2];
            int cnt = 0, tot = 0;
            for (int x = 0; x < w; x += step) {
                int c = px[y * w + x];
                tot++;
                if (dist(c, e1) > 36 && dist(c, e2) > 36) cnt++;
            }
            boardRow[y] = cnt >= tot * 0.72;
        }
        // run piu' lunga di righe "griglia" (tollera piccoli buchi)
        int bestStart = -1, bestLen = 0, start = -1, gap = 0;
        int maxGap = Math.max(4, w / 55);
        for (int y = 0; y <= h; y++) {
            boolean v = y < h && boardRow[y];
            if (v) {
                if (start < 0) start = y;
                gap = 0;
            } else if (start >= 0) {
                gap++;
                if (gap > maxGap || y == h) {
                    int len = y - gap + 1 - start;
                    if (len > bestLen) { bestLen = len; bestStart = start; }
                    start = -1; gap = 0;
                }
            }
        }
        if (bestLen < w * 0.5) return false;
        int top = bestStart, bottom = bestStart + bestLen - 1;

        // estremi orizzontali: colonne diverse dallo sfondo nella maggior parte delle righe
        int samples = 24;
        int[] colHits = new int[w];
        for (int s = 0; s < samples; s++) {
            int y = top + (bottom - top) * (s + 1) / (samples + 1);
            int e1 = px[y * w + 1], e2 = px[y * w + w - 2];
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                if (dist(c, e1) > 36 && dist(c, e2) > 36) colHits[x]++;
            }
        }
        int left = -1, right = -1;
        for (int x = 0; x < w; x++) if (colHits[x] >= samples * 0.8) { left = x; break; }
        for (int x = w - 1; x >= 0; x--) if (colHits[x] >= samples * 0.8) { right = x; break; }
        if (left < 0 || right <= left) return false;
        int ow = right - left + 1, oh = bottom - top + 1;
        if (Math.abs(ow - oh) > ow * 0.12) return false; // deve essere quasi quadrata
        if (ow < w * 0.6) return false; // troppo piccola: e' una miniatura (es. app recenti)

        res.outerLeft = left; res.outerTop = top; res.outerRight = right; res.outerBottom = bottom;

        // allinea la griglia 8x8 ai bordi delle celle (righe della griglia / bordi dei blocchi)
        float[] profX = new float[w], profY = new float[h];
        for (int s = 0; s < 40; s++) {
            int y = top + 2 + (oh - 4) * s / 40;
            for (int x = Math.max(1, left - 8); x < Math.min(w - 1, right + 8); x++)
                profX[x] += Math.abs(lum(px[y * w + x + 1]) - lum(px[y * w + x - 1]));
            int x = left + 2 + (ow - 4) * s / 40;
            for (int yy = Math.max(1, top - 8); yy < Math.min(h - 1, bottom + 8); yy++)
                profY[yy] += Math.abs(lum(px[(yy + 1) * w + x]) - lum(px[(yy - 1) * w + x]));
        }
        float bestS = ow / 8f, bestX = left, bestScore = -1;
        for (float cs = ow / 8f * 0.86f; cs <= ow / 8f * 1.01f; cs += 0.25f) {
            for (int x0 = left - 3; x0 <= left + ow * 0.14f; x0++) {
                if (x0 + 8 * cs > right + 3) break;
                float sc = 0;
                for (int k = 0; k <= 8; k++) sc += peak(profX, Math.round(x0 + k * cs));
                if (sc > bestScore) { bestScore = sc; bestS = cs; bestX = x0; }
            }
        }
        float bestY = top; bestScore = -1;
        for (int y0 = top - 3; y0 <= top + oh * 0.14f; y0++) {
            if (y0 + 8 * bestS > bottom + 3 + oh * 0.03f) break;
            float sc = 0;
            for (int k = 0; k <= 8; k++) sc += peak(profY, Math.round(y0 + k * bestS));
            if (sc > bestScore) { bestScore = sc; bestY = y0; }
        }
        res.boardLeft = bestX;
        res.boardTop = bestY;
        res.cellSize = bestS;
        return true;
    }

    private static float peak(float[] prof, int i) {
        float m = 0;
        for (int d = -1; d <= 1; d++)
            if (i + d >= 0 && i + d < prof.length) m = Math.max(m, prof[i + d]);
        return m;
    }

    private static int frameDepth(int[] px, int w, int x0, int y0, int dx, int dy, int max, int fallback) {
        int x = x0 + dx * 2, y = y0 + dy * 2;
        int ref = px[y * w + x];
        for (int d = 2; d <= max; d++) {
            int c = px[(y0 + dy * d) * w + (x0 + dx * d)];
            if (dist(c, ref) > 30) {
                // conferma che il nuovo colore sia stabile per qualche pixel
                int c2 = px[(y0 + dy * Math.min(d + 2, max + 2)) * w + (x0 + dx * Math.min(d + 2, max + 2))];
                if (dist(c2, ref) > 30) return d;
            }
            ref = c; // segue le sfumature della cornice
        }
        return fallback;
    }

    private static int median(int[] a) {
        int[] c = a.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static int patchMean(int[] px, int w, int cx, int cy, int half) {
        long sr = 0, sg = 0, sb = 0; int n = 0;
        for (int y = cy - half; y <= cy + half; y++)
            for (int x = cx - half; x <= cx + half; x++) {
                int c = px[y * w + x];
                sr += r(c); sg += g(c); sb += b(c); n++;
            }
        return 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8) | (int) (sb / n);
    }

    private static boolean covered(int[] ig, int l, int t, int r, int b) {
        return ig != null && l <= ig[2] && r >= ig[0] && t <= ig[3] && b >= ig[1];
    }

    private static int bandLum(int[] px, int w, Result res, int row, int col,
                               float x0, float x1, float y0, float y1) {
        float cs = res.cellSize;
        int xa = (int) (res.boardLeft + (col + x0) * cs), xb = (int) (res.boardLeft + (col + x1) * cs);
        int ya = (int) (res.boardTop + (row + y0) * cs), yb = (int) (res.boardTop + (row + y1) * cs);
        if (xb <= xa) xb = xa + 1;
        if (yb <= ya) yb = ya + 1;
        long s = 0; int n = 0;
        for (int y = ya; y < yb; y++)
            for (int x = xa; x < xb; x++) { s += lum(px[y * w + x]); n++; }
        return (int) (s / n);
    }

    /** Quanto il bordo della cella e' "in rilievo": alto per i blocchi, ~0 per le celle vuote. */
    private static int bevel(int[] px, int w, Result res, int row, int col) {
        int t = bandLum(px, w, res, row, col, 0.35f, 0.65f, 0.05f, 0.11f);
        int b = bandLum(px, w, res, row, col, 0.35f, 0.65f, 0.89f, 0.95f);
        int l = bandLum(px, w, res, row, col, 0.05f, 0.11f, 0.35f, 0.65f);
        int r = bandLum(px, w, res, row, col, 0.89f, 0.95f, 0.35f, 0.65f);
        return Math.abs(t - b) + Math.abs(l - r);
    }

    private static void readGrid(int[] px, int w, int h, Result res, int[] ignore) {
        int[] means = new int[64];
        boolean[] skip = new boolean[64];
        int half = Math.max(1, (int) (res.cellSize * 0.18f));
        for (int i = 0; i < 64; i++) {
            int row = i / 8, col = i % 8;
            int cx = (int) (res.boardLeft + (col + 0.5f) * res.cellSize);
            int cy = (int) (res.boardTop + (row + 0.5f) * res.cellSize);
            skip[i] = covered(ignore, cx - half, cy - half, cx + half, cy + half);
            means[i] = patchMean(px, w, cx, cy, half);
        }
        // Colore delle celle vuote. I blocchi hanno sempre il bordo in rilievo (chiaro sopra,
        // scuro sotto), le celle vuote sono piatte: si usa il gruppo piu' numeroso di celle
        // piatte dello stesso colore. Funziona sia con celle vuote scure che chiare.
        int[] tex = new int[64];
        for (int i = 0; i < 64; i++) tex[i] = bevel(px, w, res, i / 8, i % 8);
        int ref = -1, refCount = 0;
        for (int i = 0; i < 64; i++) {
            if (skip[i] || tex[i] >= 18) continue;
            int similar = 0;
            for (int j = 0; j < 64; j++)
                if (!skip[j] && tex[j] < 18 && dist(means[i], means[j]) < 30) similar++;
            if (similar > refCount) { refCount = similar; ref = i; }
        }
        if (ref < 0) {
            // nessuna cella piatta (griglia quasi piena): ripiego sul gruppo piu' scuro
            int refL = 999, fallback = -1, fallbackL = 999;
            for (int i = 0; i < 64; i++) {
                if (skip[i]) continue;
                int l = lum(means[i]);
                if (l < fallbackL) { fallbackL = l; fallback = i; }
                int similar = 0;
                for (int j = 0; j < 64; j++) if (!skip[j] && dist(means[i], means[j]) < 30) similar++;
                if (similar >= 2 && l < refL) { refL = l; ref = i; }
            }
            if (ref < 0) ref = fallback;
        }
        if (ref < 0) return;
        long sr = 0, sg = 0, sb = 0; int n = 0;
        for (int i = 0; i < 64; i++) {
            int m = means[i];
            if (!skip[i] && dist(m, means[ref]) < 30) { sr += r(m); sg += g(m); sb += b(m); n++; }
        }
        int empty = 0xff000000 | ((int) (sr / n) << 16) | ((int) (sg / n) << 8) | (int) (sb / n);
        // le celle coperte dalla bolla si considerano vuote
        for (int i = 0; i < 64; i++) res.grid[i] = !skip[i] && dist(means[i], empty) > 54;
    }

    // ------------------------------------------------------------------ pezzi

    private static void readPieces(int[] px, int w, int h, Result res, int[] ignore) {
        int boardH = res.outerBottom - res.outerTop;
        // la fascia subito sotto la griglia e' riservata ai numeri dei suggerimenti
        int y0 = res.outerBottom + Math.max(2, (int) (boardH * 0.105f));
        int y1 = Math.min(h - 1, res.outerBottom + (int) (boardH * 0.6f));
        if (y1 - y0 < 10) return;
        int ow = res.outerRight - res.outerLeft + 1;
        int rh = y1 - y0 + 1;
        boolean[] mask = new boolean[rh * w];
        int[] tmp = new int[w / 2 + 1];
        for (int y = y0; y <= y1; y++) {
            // sfondo della riga = mediana dei margini ai lati della griglia (li' non ci sono
            // pezzi); se i margini sono troppo stretti, mediana dell'intera riga
            int k = 0;
            for (int x = 0; x < res.outerLeft - 2; x++) tmp[k++] = px[y * w + x];
            for (int x = res.outerRight + 3; x < w && k < tmp.length; x++) tmp[k++] = px[y * w + x];
            if (k < 6) {
                k = 0;
                for (int x = 0; x < w; x += 2) tmp[k++] = px[y * w + x];
            }
            int bg = medianColor(tmp, k);
            int bgL = lum(bg);
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                if (dist(c, bg) > 40 && (lum(c) > bgL + 10 || sat(c) > 0.3f)
                        && !covered(ignore, x, y, x, y))
                    mask[(y - y0) * w + x] = true;
            }
        }
        List<int[]> boxes = new ArrayList<>(); // [l,t,r,b] per ogni posto
        for (int s = 0; s < 3; s++) {
            int xa = res.outerLeft + ow * s / 3, xb = res.outerLeft + ow * (s + 1) / 3;
            boxes.add(bbox(mask, w, rh, xa, xb, y0));
        }
        float cell = res.cellSize;
        // candidati per la dimensione del blocco nel vassoio (uguale per tutti i pezzi)
        List<Float> cands = new ArrayList<>();
        for (int[] bx : boxes) {
            if (bx == null) continue;
            int bw = bx[2] - bx[0] + 1, bh = bx[3] - bx[1] + 1;
            for (int k = 1; k <= 5; k++) {
                cands.add(bw / (float) k);
                cands.add(bh / (float) k);
            }
        }
        // Nel vassoio i blocchi sono ~45% di una casella della griglia (uguale in tutti i temi):
        // prima si cercano misure vicine a quella, poi (se nulla torna) un intervallo piu' largo.
        float bestU = pickUnit(cands, boxes, mask, w, y0, cell, 0.36f, 0.56f);
        if (bestU < 0) bestU = pickUnit(cands, boxes, mask, w, y0, cell, 0.28f, 1.05f);
        if (bestU < 0) return;
        for (int s = 0; s < 3; s++) {
            int[] bx = boxes.get(s);
            if (bx == null) continue;
            int cw = Math.round((bx[2] - bx[0] + 1) / bestU), ch = Math.round((bx[3] - bx[1] + 1) / bestU);
            float[][] f = coverage(mask, w, y0, bx, ch, cw);
            boolean[][] cells = new boolean[ch][cw];
            int n = 0;
            for (int i = 0; i < ch; i++) for (int j = 0; j < cw; j++) {
                cells[i][j] = f[i][j] > 0.5f;
                if (cells[i][j]) n++;
            }
            if (n == 0) continue;
            res.slots[s] = new Piece(cells, bx[0], bx[1], bx[2], bx[3]);
        }
    }

    private static float pickUnit(List<Float> cands, List<int[]> boxes, boolean[] mask, int w, int y0,
                                  float cell, float lo, float hi) {
        float bestU = -1, bestScore = Float.MAX_VALUE;
        for (float u : cands) {
            if (u < cell * lo || u > cell * hi) continue;
            float score = 0; boolean valid = true;
            for (int[] bx : boxes) {
                if (bx == null) continue;
                float fw = (bx[2] - bx[0] + 1) / u, fh = (bx[3] - bx[1] + 1) / u;
                int cw = Math.round(fw), ch = Math.round(fh);
                if (cw < 1 || ch < 1 || cw > 5 || ch > 5) { valid = false; break; }
                score += Math.abs(fw - cw) + Math.abs(fh - ch);
                float[][] f = coverage(mask, w, y0, bx, ch, cw);
                for (float[] row : f) for (float v : row) score += 2f * Math.min(v, 1 - v);
            }
            if (!valid) continue;
            score += 0.5f * Math.abs(u / cell - 0.45f); // vicino alla proporzione tipica
            if (score < bestScore) { bestScore = score; bestU = u; }
        }
        return bestU;
    }

    private static int medianColor(int[] a, int n) {
        int[] rr = new int[n], gg = new int[n], bb = new int[n];
        for (int i = 0; i < n; i++) { rr[i] = r(a[i]); gg[i] = g(a[i]); bb[i] = b(a[i]); }
        Arrays.sort(rr); Arrays.sort(gg); Arrays.sort(bb);
        return 0xff000000 | (rr[n / 2] << 16) | (gg[n / 2] << 8) | bb[n / 2];
    }

    private static int[] bbox(boolean[] mask, int w, int rh, int xa, int xb, int y0) {
        int[] rowCnt = new int[rh];
        int[] colCnt = new int[xb - xa];
        int total = 0;
        for (int y = 0; y < rh; y++)
            for (int x = xa; x < xb; x++)
                if (mask[y * w + x]) { rowCnt[y]++; colCnt[x - xa]++; total++; }
        if (total < 40) return null;
        int minHits = 3;
        int t = -1, bt = -1, l = -1, r = -1;
        for (int y = 0; y < rh; y++) if (rowCnt[y] >= minHits) { t = y; break; }
        for (int y = rh - 1; y >= 0; y--) if (rowCnt[y] >= minHits) { bt = y; break; }
        for (int x = 0; x < colCnt.length; x++) if (colCnt[x] >= minHits) { l = x; break; }
        for (int x = colCnt.length - 1; x >= 0; x--) if (colCnt[x] >= minHits) { r = x; break; }
        if (t < 0 || l < 0 || bt - t < 4 || r - l < 4) return null;
        return new int[]{xa + l, y0 + t, xa + r, y0 + bt};
    }

    private static float[][] coverage(boolean[] mask, int w, int y0, int[] bx, int rows, int cols) {
        float[][] f = new float[rows][cols];
        float cw = (bx[2] - bx[0] + 1) / (float) cols, ch = (bx[3] - bx[1] + 1) / (float) rows;
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                int cx = (int) (bx[0] + (j + 0.5f) * cw), cy = (int) (bx[1] + (i + 0.5f) * ch);
                int hx = Math.max(1, (int) (cw * 0.25f)), hy = Math.max(1, (int) (ch * 0.25f));
                int hit = 0, n = 0;
                for (int y = cy - hy; y <= cy + hy; y++)
                    for (int x = cx - hx; x <= cx + hx; x++) {
                        n++;
                        if (mask[(y - y0) * w + x]) hit++;
                    }
                f[i][j] = hit / (float) n;
            }
        return f;
    }
}
