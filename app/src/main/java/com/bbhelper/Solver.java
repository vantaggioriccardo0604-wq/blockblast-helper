package com.bbhelper;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Trova ordine e posizioni migliori per i pezzi disponibili. Griglia = bitmask a 64 bit.
 *
 * Due fasi:
 *  1. prova tutti gli ordini e tutte le posizioni, con una valutazione veloce (linee, combo,
 *     spazio libero, buchi);
 *  2. sulle migliori combinazioni calcola la "sopravvivenza": quanti dei pezzi che Block Blast
 *     puo' dare dopo entrerebbero ancora (3x3, pezzi da 5, L...), e scarta le mosse che
 *     lasciano la griglia senza spazio per quelli.
 */
public final class Solver {

    public static final class Step {
        public final int slot;      // 0..2 (posto nel vassoio)
        public final int row, col;  // angolo in alto a sinistra del pezzo sulla griglia
        public final long cells;    // celle occupate dal pezzo
        public final int lines;     // linee completate da questa mossa
        Step(int slot, int row, int col, long cells, int lines) {
            this.slot = slot; this.row = row; this.col = col; this.cells = cells; this.lines = lines;
        }
    }

    public static final class Plan {
        public final List<Step> steps = new ArrayList<>();
        public int totalLines;
        public boolean allPlaced;
        /** % di terne casuali che entrerebbero ancora dopo queste mosse (-1 = non calcolato). */
        public int safety = -1;
    }

    private static final long[] ROW = new long[8], COL = new long[8];
    private static final long[][] FUTURE;      // per ogni forma futura: tutte le posizioni
    private static final double[] FUTURE_W;    // peso (quanto spesso esce / quanto e' difficile)

    static {
        for (int i = 0; i < 8; i++)
            for (int j = 0; j < 8; j++) {
                ROW[i] |= 1L << (i * 8 + j);
                COL[j] |= 1L << (i * 8 + j);
            }
        // pezzi tipici di Block Blast, con un peso
        String[][] shapes = {
                {"#", "0.4"},
                {"##", "0.8"}, {"#/#", "0.8"},
                {"###", "0.9"}, {"#/#/#", "0.9"},
                {"####", "1.0"}, {"#/#/#/#", "1.0"},
                {"#####", "1.3"}, {"#/#/#/#/#", "1.3"},
                {"##/##", "1.0"},
                {"###/###/###", "1.6"},
                {"###/###", "1.0"}, {"##/##/##", "1.0"},
                {"#./##", "0.6"}, {".#/##", "0.6"}, {"##/#.", "0.6"}, {"##/.#", "0.6"},
                {"#./#./##", "0.5"}, {".#/.#/##", "0.5"}, {"##/#./#.", "0.5"}, {"##/.#/.#", "0.5"},
                {"#../###", "0.5"}, {"..#/###", "0.5"}, {"###/#..", "0.5"}, {"###/..#", "0.5"},
                {"#../#../###", "0.8"}, {"..#/..#/###", "0.8"}, {"###/#../#..", "0.8"}, {"###/..#/..#", "0.8"},
                {"###/.#.", "0.5"}, {".#./###", "0.5"}, {"#./##/#.", "0.5"}, {".#/##/.#", "0.5"},
                {"##./.##", "0.5"}, {".##/##.", "0.5"}, {"#./##/.#", "0.5"}, {".#/##/#.", "0.5"},
        };
        FUTURE = new long[shapes.length][];
        FUTURE_W = new double[shapes.length];
        for (int s = 0; s < shapes.length; s++) {
            FUTURE[s] = allPlacements(parse(shapes[s][0]));
            FUTURE_W[s] = Double.parseDouble(shapes[s][1]);
        }
    }

    static boolean[][] parse(String key) {
        String[] rows = key.split("/");
        boolean[][] c = new boolean[rows.length][rows[0].length()];
        for (int i = 0; i < rows.length; i++)
            for (int j = 0; j < rows[i].length(); j++) c[i][j] = rows[i].charAt(j) == '#';
        return c;
    }

    static long[] allPlacements(boolean[][] cells) {
        int rows = cells.length, cols = cells[0].length;
        List<Long> list = new ArrayList<>();
        for (int r = 0; r + rows <= 8; r++)
            for (int c = 0; c + cols <= 8; c++) {
                long m = 0;
                for (int i = 0; i < rows; i++)
                    for (int j = 0; j < cols; j++)
                        if (cells[i][j]) m |= 1L << ((r + i) * 8 + (c + j));
                list.add(m);
            }
        long[] a = new long[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    // ------------------------------------------------------------------------

    private static final int TOP_K = 2000;

    // pesi della strategia (tarati con partite simulate)
    public static double W_EMPTY = 1, W_SINGLE = 14, W_EDGE = 3, W_POCKET = 0, W_LINE = 30,
            W_MULTI = 20, W_COMBO = 25, S_MISS = 100, S_SPOT = 2, W_WALL = 0,
            W_LOOK = 1, LOOK_FAIL = 150;
    public static int LOOK_N = 16, LOOK_SAMPLES = 10, SAFETY_SAMPLES = 60;
    // quando la griglia e' piena (poche celle libere) ogni mossa conta: si guarda piu' a fondo
    // (spento: nelle simulazioni non migliorava, rallentava soltanto)
    public static int CROWD_EMPTY = 0, CROWD_LOOK_N = 32, CROWD_SAMPLES = 24;
    /** Modalita' punteggio: quanto valgono in piu' linee, linee multiple e combo. */
    public static double SCORE_MULT = 3, SCORE_SETUP = 4;

    private static final class Leaf {
        double score;
        final long board;
        final int depth;
        final int[] seq, idx, lines;
        Leaf(double score, long board, int depth, int[] seq, int[] idx, int[] lines) {
            this.score = score; this.board = board; this.depth = depth;
            this.seq = seq.clone(); this.idx = idx.clone(); this.lines = lines.clone();
        }
    }

    private final long[][] placements;
    private final int[][] placeRow, placeCol;
    private final int[] slotOf;
    private final String[] keys;
    private final int n;
    private final double lineMult;
    private int lookN = LOOK_N, lookSamples = LOOK_SAMPLES;
    private final int[] curSeq, curIdx, curLines;
    private int maxDepth = 0;
    // migliori foglie per profondita' raggiunta (piu' pezzi piazzati = sempre meglio)
    private final PriorityQueue<Leaf> top = new PriorityQueue<>((a, b) -> Double.compare(a.score, b.score));

    private Solver(List<Analyzer.Piece> pieces, int[] slots, boolean scoreMode) {
        n = pieces.size();
        lineMult = scoreMode ? SCORE_MULT : 1.0;
        slotOf = slots;
        placements = new long[n][];
        placeRow = new int[n][];
        placeCol = new int[n][];
        keys = new String[n];
        curSeq = new int[n]; curIdx = new int[n]; curLines = new int[n];
        for (int p = 0; p < n; p++) {
            Analyzer.Piece pc = pieces.get(p);
            keys[p] = pc.key();
            placements[p] = allPlacements(pc.cells);
            int k = 0;
            placeRow[p] = new int[placements[p].length];
            placeCol[p] = new int[placements[p].length];
            for (int r = 0; r + pc.rows <= 8; r++)
                for (int c = 0; c + pc.cols <= 8; c++) { placeRow[p][k] = r; placeCol[p][k] = c; k++; }
        }
    }

    public static long toMask(boolean[] grid) {
        long m = 0;
        for (int i = 0; i < 64; i++) if (grid[i]) m |= 1L << i;
        return m;
    }

    /** Ritorna null se non ci sono pezzi. */
    public static Plan solve(boolean[] grid, Analyzer.Piece[] slots) {
        return solve(grid, slots, false);
    }

    public static Plan solve(boolean[] grid, Analyzer.Piece[] slots, boolean scoreMode) {
        List<Analyzer.Piece> pieces = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        for (int s = 0; s < slots.length; s++)
            if (slots[s] != null) { pieces.add(slots[s]); idx.add(s); }
        if (pieces.isEmpty()) return null;
        int[] so = new int[idx.size()];
        for (int i = 0; i < so.length; i++) so[i] = idx.get(i);
        return new Solver(pieces, so, scoreMode).run(toMask(grid));
    }

    private Plan run(long board) {
        if (64 - Long.bitCount(board) < CROWD_EMPTY) { lookN = CROWD_LOOK_N; lookSamples = CROWD_SAMPLES; }
        dfs(board, 0, 0, 0.0);

        // fase 2: valutazione approfondita delle migliori combinazioni
        List<Leaf> cands = new ArrayList<>();
        for (Leaf lf : top) {
            if (lf.depth < maxDepth) continue;
            lf.score += survival(lf.board);
            cands.add(lf);
        }
        cands.sort((a, b) -> Double.compare(b.score, a.score));

        // fase 3: per le migliori, simula il turno dopo con pezzi casuali
        if (W_LOOK > 0 && maxDepth == n) {
            int lim = Math.min(lookN, cands.size());
            for (int i = 0; i < lim; i++) {
                Leaf lf = cands.get(i);
                lf.score += W_LOOK * lookahead(lf.board);
            }
            cands.subList(0, lim).sort((a, b) -> Double.compare(b.score, a.score));
        }
        Leaf best = cands.isEmpty() ? null : cands.get(0);

        Plan plan = new Plan();
        plan.allPlaced = maxDepth == n;
        if (best == null) return plan;
        plan.safety = plan.allPlaced ? survivalPercent(best.board, SAFETY_SAMPLES) : 0;
        for (int d = 0; d < best.depth; d++) {
            int p = best.seq[d], k = best.idx[d];
            plan.steps.add(new Step(slotOf[p], placeRow[p][k], placeCol[p][k], placements[p][k], best.lines[d]));
            plan.totalLines += best.lines[d];
        }
        return plan;
    }

    private void dfs(long board, int depth, int usedMask, double acc) {
        boolean any = false;
        if (depth < n) {
            for (int p = 0; p < n; p++) {
                if ((usedMask & (1 << p)) != 0) continue;
                // pezzi identici: basta provarne uno
                boolean dup = false;
                for (int q = 0; q < p; q++)
                    if ((usedMask & (1 << q)) == 0 && keys[q].equals(keys[p])) { dup = true; break; }
                if (dup) continue;
                long[] pl = placements[p];
                for (int k = 0; k < pl.length; k++) {
                    long m = pl[k];
                    if ((board & m) != 0) continue;
                    any = true;
                    long nb = board | m;
                    long clear = 0; int lines = 0;
                    for (int i = 0; i < 8; i++) {
                        if ((nb & ROW[i]) == ROW[i]) { clear |= ROW[i]; lines++; }
                        if ((nb & COL[i]) == COL[i]) { clear |= COL[i]; lines++; }
                    }
                    nb &= ~clear;
                    curSeq[depth] = p; curIdx[depth] = k; curLines[depth] = lines;
                    // linee multiple e combo consecutive valgono di piu'
                    double gain = 0;
                    if (lines > 0) {
                        // in modalita' punteggio valgono di piu' solo linee multiple e combo
                        gain = W_LINE * lines + lineMult * W_MULTI * (lines - 1) * lines;
                        if (depth > 0 && curLines[depth - 1] > 0) gain += lineMult * W_COMBO;
                    }
                    dfs(nb, depth + 1, usedMask | (1 << p), acc + gain);
                }
            }
        }
        if (!any) offer(board, depth, acc + quick(board));
    }

    private void offer(long board, int depth, double score) {
        if (depth > maxDepth) {
            maxDepth = depth;
            top.clear(); // le foglie con meno pezzi piazzati non servono piu'
        } else if (depth < maxDepth) {
            return;
        }
        if (top.size() < TOP_K) {
            top.add(new Leaf(score, board, depth, curSeq, curIdx, curLines));
        } else if (score > top.peek().score) {
            top.poll();
            top.add(new Leaf(score, board, depth, curSeq, curIdx, curLines));
        }
    }

    private static boolean filled(long b, int r, int c) {
        return r < 0 || r > 7 || c < 0 || c > 7 || ((b >>> (r * 8 + c)) & 1L) != 0;
    }

    private static final long COL0 = 0x0101010101010101L, COL7 = 0x8080808080808080L;
    private static final long NOT_LAST_ROW = 0x00FFFFFFFFFFFFFFL;

    /**
     * Valutazione veloce della griglia, con operazioni sui bit (tutte le 64 caselle insieme).
     * Celle "isolate" = vuote con tutti e 4 i vicini pieni (i bordi contano come pieni);
     * "bordi" = coppie di caselle vicine una piena e una vuota.
     */
    private double quick(long b) {
        long e = ~b;
        long up = e << 8, down = e >>> 8;
        long left = (e << 1) & ~COL0, right = (e >>> 1) & ~COL7;
        int empty = Long.bitCount(e);
        int singles = Long.bitCount(e & ~(up | down | left | right));
        int edges = Long.bitCount((b ^ (b >>> 1)) & ~COL7) + Long.bitCount((b ^ (b >>> 8)) & NOT_LAST_ROW);
        double s = empty * W_EMPTY - singles * W_SINGLE - edges * W_EDGE;
        if (W_POCKET != 0 || W_WALL != 0) s += slowExtras(b);
        if (lineMult > 1) {
            // modalita' punteggio: righe/colonne quasi piene preparano chiusure multiple
            int setup = 0;
            for (int i = 0; i < 8; i++) {
                if (Long.bitCount(b & ROW[i]) >= 6) setup++;
                if (Long.bitCount(b & COL[i]) >= 6) setup++;
            }
            s += SCORE_SETUP * setup;
        }
        return s;
    }

    /** Termini usati solo per esperimenti di taratura (pesi a zero di default). */
    private double slowExtras(long b) {
        int pockets = 0, walls = 0;
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++) {
                if (filled(b, r, c)) continue;
                int around = (filled(b, r - 1, c) ? 1 : 0) + (filled(b, r + 1, c) ? 1 : 0)
                        + (filled(b, r, c - 1) ? 1 : 0) + (filled(b, r, c + 1) ? 1 : 0);
                if (around == 3) pockets++;
                if (r == 0 || r == 7) walls++;
                if (c == 0 || c == 7) walls++;
            }
        return -pockets * W_POCKET - walls * W_WALL;
    }

    /**
     * Valore atteso del turno successivo: estrae terne di pezzi casuali (sempre le stesse per la
     * stessa griglia, cosi' il suggerimento non cambia da un fotogramma all'altro) e le piazza
     * con una strategia veloce; se una terna non entra, forte penalita'.
     */
    private double lookahead(long b) {
        java.util.Random rnd = new java.util.Random(b * 0x9E3779B97F4A7C15L + 12345);
        double total = 0;
        int[] tri = new int[3];
        for (int sIdx = 0; sIdx < lookSamples; sIdx++) {
            for (int i = 0; i < 3; i++) tri[i] = rnd.nextInt(FUTURE.length);
            total += bestGreedy(b, tri);
        }
        return total / lookSamples;
    }

    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    private double bestGreedy(long start, int[] tri) {
        double best = -Double.MAX_VALUE;
        for (int[] ord : ORDERS) {
            long b = start;
            double acc = 0;
            int placed = 0;
            for (int o : ord) {
                long[] pl = FUTURE[tri[o]];
                double bestStep = -Double.MAX_VALUE;
                long bestBoard = 0;
                for (long m : pl) {
                    if ((b & m) != 0) continue;
                    long nb = b | m;
                    long clear = 0; int lines = 0;
                    for (int i = 0; i < 8; i++) {
                        if ((nb & ROW[i]) == ROW[i]) { clear |= ROW[i]; lines++; }
                        if ((nb & COL[i]) == COL[i]) { clear |= COL[i]; lines++; }
                    }
                    nb &= ~clear;
                    double v = (lines > 0 ? W_LINE * lines + lineMult * W_MULTI * (lines - 1) * lines : 0) + quick(nb);
                    if (v > bestStep) { bestStep = v; bestBoard = nb; }
                }
                if (bestStep == -Double.MAX_VALUE) break;
                b = bestBoard;
                acc += bestStep - quick(b); // solo il guadagno delle linee
                placed++;
            }
            double v = placed == 3 ? acc + quick(b) : -LOOK_FAIL * (3 - placed);
            if (v > best) best = v;
        }
        return best;
    }

    /**
     * Probabilita' (in %) che una terna di pezzi casuali entri tutta nella griglia, provando
     * ogni ordine e ogni posizione (con le linee che si chiudono). Stessa griglia = stesso risultato.
     */
    public static int survivalPercent(long b, int samples) {
        java.util.Random rnd = new java.util.Random(b * 0x2545F4914F6CDD1DL + 777);
        int ok = 0;
        int[] tri = new int[3];
        for (int s = 0; s < samples; s++) {
            for (int i = 0; i < 3; i++) tri[i] = rnd.nextInt(FUTURE.length);
            if (fitsAll(b, tri, 0)) ok++;
        }
        return Math.round(100f * ok / samples);
    }

    /** I pezzi (chiavi tipo "##/#./") entrano tutti nella griglia, in qualche ordine? */
    public static boolean fitsAll(long b, String[] keys) {
        long[][] pl = new long[keys.length][];
        for (int i = 0; i < keys.length; i++) pl[i] = placementsOf(keys[i]);
        return fitsKeys(b, pl, 0);
    }

    private static final java.util.Map<String, long[]> PL_CACHE = new java.util.HashMap<>();

    /** Tutte le posizioni di un pezzo dato come chiave (es. "##/#./"), con cache. */
    public static synchronized long[] placementsOf(String key) {
        long[] p = PL_CACHE.get(key);
        if (p == null) {
            p = allPlacements(parse(key.replaceAll("/+$", "")));
            PL_CACHE.put(key, p);
        }
        return p;
    }

    private static boolean fitsKeys(long b, long[][] pl, int usedMask) {
        if (usedMask == (1 << pl.length) - 1) return true;
        for (int i = 0; i < pl.length; i++) {
            if ((usedMask & (1 << i)) != 0) continue;
            for (long m : pl[i]) {
                if ((b & m) != 0) continue;
                long nb = b | m, clear = 0;
                for (int k = 0; k < 8; k++) {
                    if ((nb & ROW[k]) == ROW[k]) clear |= ROW[k];
                    if ((nb & COL[k]) == COL[k]) clear |= COL[k];
                }
                if (fitsKeys(nb & ~clear, pl, usedMask | (1 << i))) return true;
            }
        }
        return false;
    }

    private static boolean fitsAll(long b, int[] tri, int usedMask) {
        if (usedMask == 7) return true;
        for (int i = 0; i < 3; i++) {
            if ((usedMask & (1 << i)) != 0) continue;
            for (long m : FUTURE[tri[i]]) {
                if ((b & m) != 0) continue;
                long nb = b | m, clear = 0;
                for (int k = 0; k < 8; k++) {
                    if ((nb & ROW[k]) == ROW[k]) clear |= ROW[k];
                    if ((nb & COL[k]) == COL[k]) clear |= COL[k];
                }
                if (fitsAll(nb & ~clear, tri, usedMask | (1 << i))) return true;
            }
        }
        return false;
    }

    /** Quanto la griglia resta "giocabile" per i prossimi pezzi. */
    static double survival(long b) {
        double s = 0;
        for (int i = 0; i < FUTURE.length; i++) {
            int count = 0;
            for (long m : FUTURE[i]) {
                if ((b & m) == 0 && ++count >= 6) break;
            }
            if (count == 0) s -= S_MISS * FUTURE_W[i];          // questo pezzo non entrerebbe piu'
            else s += FUTURE_W[i] * S_SPOT * Math.min(count, 3); // piu' posti = piu' sicuro
        }
        return s;
    }
}
