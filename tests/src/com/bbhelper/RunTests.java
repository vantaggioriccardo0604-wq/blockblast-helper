package com.bbhelper;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Test automatici eseguiti su GitHub prima di ogni versione:
 *  1. riconoscimento di griglia e pezzi sugli screenshot reali (a meta' risoluzione, come l'app);
 *  2. riconoscimento delle mosse (Tracker) su una partita simulata;
 *  3. gara di partite simulate: turni sopravvissuti e tempi di calcolo.
 * Esce con errore se qualcosa peggiora: la versione non viene pubblicata.
 *
 * Uso: java com.bbhelper.RunTests <cartella tests> [--aggiorna]
 */
public final class RunTests {

    static final StringBuilder md = new StringBuilder();
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "tests");
        boolean update = args.length > 1 && args[1].equals("--aggiorna");
        md.append("## Test automatici\n\n");
        testScreens(dir, update);
        testTracker();
        testDashboard();
        testSimulation(dir, update);
        md.append(failures == 0 ? "\n**Tutti i test superati ✓**\n" : "\n**" + failures + " test falliti ✗**\n");
        System.out.println(md);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) try (FileWriter fw = new FileWriter(summary, true)) { fw.write(md.toString()); }
        try (FileWriter fw = new FileWriter("test_summary.md")) { fw.write(md.toString()); }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ screenshot

    static int[] halfRes(BufferedImage im, int[] wh) {
        int w = Math.round(im.getWidth() * (im.getWidth() >= 1000 ? 0.5f : 1f));
        int h = Math.round(im.getHeight() * (im.getWidth() >= 1000 ? 0.5f : 1f));
        BufferedImage sc = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sc.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        wh[0] = w; wh[1] = h;
        return sc.getRGB(0, 0, w, h, null, 0, w);
    }

    static String describe(Analyzer.Result r) {
        if (!r.ok) return "NESSUNA_GRIGLIA";
        StringBuilder sb = new StringBuilder();
        for (boolean b : r.grid) sb.append(b ? '#' : '.');
        for (Analyzer.Piece p : r.slots) sb.append(' ').append(p == null ? "-" : p.key());
        return sb.toString();
    }

    static void testScreens(File dir, boolean update) throws Exception {
        File[] files = new File(dir, "screens").listFiles((d, n) -> n.endsWith(".png"));
        Arrays.sort(files);
        File expFile = new File(dir, "atteso.txt");
        List<String> exp = expFile.exists() ? Files.readAllLines(expFile.toPath()) : new ArrayList<>();
        List<String> out = new ArrayList<>();
        md.append("### Riconoscimento sugli screenshot\n\n| Screenshot | Risultato | Tempo |\n|---|---|---|\n");
        for (File f : files) {
            int[] wh = new int[2];
            int[] px = halfRes(ImageIO.read(f), wh);
            long t0 = System.nanoTime();
            Analyzer.Result r = Analyzer.analyze(px, wh[0], wh[1]);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String got = f.getName() + "|" + describe(r);
            out.add(got);
            String want = exp.stream().filter(l -> l.startsWith(f.getName() + "|")).findFirst().orElse(null);
            String verdict;
            if (want == null) verdict = "nuovo (da confermare)";
            else if (want.equals(got)) verdict = "✓";
            else { verdict = "✗ diverso dall'atteso"; failures++; }
            md.append("| ").append(f.getName()).append(" | ").append(verdict).append(" | ").append(ms).append(" ms |\n");
        }
        if (update) Files.write(expFile.toPath(), out);
        md.append('\n');
    }

    // ------------------------------------------------------------------ mosse

    static final String[] SH = {"#", "##", "#/#", "###", "#/#/#", "####", "#/#/#/#", "#####", "#/#/#/#/#", "##/##",
            "###/###/###", "###/###", "##/##/##", "#./##", ".#/##", "##/#.", "##/.#", "#./#./##", ".#/.#/##",
            "##/#./#.", "##/.#/.#", "#../###", "..#/###", "###/#..", "###/..#", "#../#../###", "..#/..#/###",
            "###/#../#..", "###/..#/..#", "###/.#.", ".#./###", "#./##/#.", ".#/##/.#", "##./.##", ".##/##.",
            "#./##/.#", ".#/##/#."};

    static Analyzer.Piece piece(String k) {
        return new Analyzer.Piece(Solver.parse(k), 0, 0, 0, 0);
    }

    static long apply(long b, long cells) {
        b |= cells;
        long clear = 0;
        for (int i = 0; i < 8; i++) {
            long row = 0xFFL << (i * 8), col = 0x0101010101010101L << i;
            if ((b & row) == row) clear |= row;
            if ((b & col) == col) clear |= col;
        }
        return b & ~clear;
    }

    static void testTracker() {
        Random rnd = new Random(5);
        boolean[] grid = new boolean[64];
        Tracker tr = new Tracker();
        long expLines = 0, expPlaced = 0;
        Analyzer.Piece[] slots = new Analyzer.Piece[3];
        for (int s = 0; s < 3; s++) slots[s] = piece(SH[rnd.nextInt(SH.length)]);
        tr.onState(grid, slots, null, 0);
        for (int turn = 0; turn < 30; ) {
            Solver.Plan pl = Solver.solve(grid, slots);
            tr.setExpected(pl);
            if (pl.steps.isEmpty()) break;
            Solver.Step st = pl.steps.get(0);
            long before = Solver.toMask(grid);
            long b = apply(before, st.cells);
            expLines += st.lines;
            expPlaced++;
            for (int i = 0; i < 64; i++) grid[i] = ((b >>> i) & 1) != 0;
            slots = slots.clone();
            slots[st.slot] = null;
            if (slots[0] == null && slots[1] == null && slots[2] == null) {
                for (int s = 0; s < 3; s++) slots[s] = piece(SH[rnd.nextInt(SH.length)]);
                turn++;
            }
            tr.onState(grid, slots, null, turn);
        }
        boolean ok = tr.placed == expPlaced && tr.lines == expLines && tr.followed == expPlaced;
        if (!ok) failures++;
        md.append("### Riconoscimento delle mosse\n\n")
                .append(ok ? "✓ " : "✗ ").append("pezzi ").append(tr.placed).append("/").append(expPlaced)
                .append(", linee ").append(tr.lines).append("/").append(expLines)
                .append(", mosse seguite ").append(tr.followed).append("/").append(expPlaced).append("\n\n");
    }

    // ------------------------------------------------------------------ dashboard

    static void testDashboard() {
        List<String> log = java.util.Arrays.asList(
                "1700000000000;0;##/##/;#/;###/",          // terna con griglia vuota
                "1700000000001;ffffffffff;#/;#/;##/",      // terna con griglia piena (40 caselle)
                "G;1", "M;2;0;1", "M;3;2;1", "M;4;1;0", "S;5;92",
                "G;6", "M;7;3;1", "riga rovinata;;", "S;8;40");
        DashData d = DashData.parse(log);
        boolean ok = d.triples == 2 && d.moves == 4 && d.movesFollowed == 3 && d.games == 2
                && d.totalLines == 6 && d.linesPerGame.size() == 2 && d.linesPerGame.get(0) == 3
                && d.linesPerGame.get(1) == 3 && d.safety.size() == 2 && d.linesPerMove[3] == 1
                && d.fullnessCount[0] == 1 && d.fullnessCount[4] == 1
                && Math.abs(d.avgPieceSize[0] - 8.0 / 3) < 1e-9;
        if (!ok) failures++;
        md.append("### Dashboard\n\n").append(ok ? "✓ calcoli corretti su un registro di prova\n\n"
                : "✗ calcoli della dashboard sbagliati\n\n");
    }

    // ------------------------------------------------------------------ partite simulate

    static void testSimulation(File dir, boolean update) throws Exception {
        int games = 12, cap = 150;
        long totalTurns = 0;
        List<Long> times = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            Random rnd = new Random(9000 + g);
            boolean[] grid = new boolean[64];
            int turns = 0;
            while (turns < cap) {
                Analyzer.Piece[] slots = new Analyzer.Piece[3];
                for (int s = 0; s < 3; s++) slots[s] = piece(SH[rnd.nextInt(SH.length)]);
                long t0 = System.nanoTime();
                Solver.Plan pl = Solver.solve(grid, slots);
                times.add((System.nanoTime() - t0) / 1_000_000);
                if (!pl.allPlaced) break;
                long b = Solver.toMask(grid);
                for (Solver.Step st : pl.steps) b = apply(b, st.cells);
                for (int i = 0; i < 64; i++) grid[i] = ((b >>> i) & 1) != 0;
                turns++;
            }
            totalTurns += turns;
        }
        double avg = totalTurns / (double) games;
        long[] t = times.stream().mapToLong(Long::longValue).sorted().toArray();
        File base = new File(dir, "riferimento.txt");
        double ref = base.exists() ? Double.parseDouble(Files.readAllLines(base.toPath()).get(0).trim()) : -1;
        boolean ok = ref < 0 || avg >= ref * 0.9;
        if (!ok) failures++;
        if (update) Files.write(base.toPath(), List.of(String.valueOf(avg)));
        md.append("### Partite simulate (").append(games).append(" partite, massimo ").append(cap).append(" turni)\n\n")
                .append(ok ? "✓ " : "✗ peggiorata: ")
                .append(String.format("turni medi %.1f", avg))
                .append(ref >= 0 ? String.format(" (riferimento %.1f)", ref) : "")
                .append(String.format(" · calcolo: mediana %d ms, massimo %d ms (su server GitHub)\n",
                        t[t.length / 2], t[t.length - 1]));
    }
}
