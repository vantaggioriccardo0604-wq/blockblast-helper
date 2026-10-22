package com.bbhelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Calcola i numeri della dashboard dal registro dell'app (pezzi.csv).
 * Nessuna dipendenza Android: si puo' provare anche fuori dal telefono.
 */
public final class DashData {

    public int triples, moves, movesFollowed, games, totalLines;
    /** Forme dei pezzi (chiave "##/#./") con quante volte sono uscite, dalla piu' frequente. */
    public final List<Map.Entry<String, Integer>> shapes = new ArrayList<>();
    /** Mosse per numero di linee chiuse: [0], [1], [2], [3 o piu']. */
    public final int[] linesPerMove = new int[4];
    /** Linee fatte in ogni partita (in ordine). */
    public final List<Integer> linesPerGame = new ArrayList<>();
    /** Sopravvivenza (%) calcolata a ogni terna nuova, in ordine. */
    public final List<Integer> safety = new ArrayList<>();
    /** Grandezza media dei pezzi (caselle) secondo quanto era piena la griglia. */
    public final String[] fullnessLabels = {"0-9", "10-19", "20-29", "30-39", "40+"};
    public final double[] avgPieceSize = new double[5];
    public final int[] fullnessCount = new int[5];
    /** % di pezzi "grandi" (5+ caselle) secondo quanto era piena la griglia. */
    public final double[] bigPieceRate = new double[5];
    /** Terne con la griglia gia' piena (25+ caselle): griglia e pezzi, per "il gioco ti aiuta?". */
    public final List<long[]> crowdedBoards = new ArrayList<>();
    public final List<String[]> crowdedPieces = new ArrayList<>();
    /** Tutti i pezzi visti (con ripetizioni): estrarne a caso = estrarre con la frequenza vera. */
    public final List<String> allPieces = new ArrayList<>();

    public static DashData parse(List<String> lines) {
        DashData d = new DashData();
        Map<String, Integer> shapeCount = new HashMap<>();
        double[] sizeSum = new double[5];
        int[] bigCount = new int[5];
        int[] pieceCount = new int[5];
        int gameLines = 0;
        boolean inGame = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] f = line.split(";");
            try {
                if (f[0].equals("M") && f.length >= 4) {
                    d.moves++;
                    int l = Integer.parseInt(f[2]);
                    d.totalLines += l;
                    d.linesPerMove[Math.min(3, l)]++;
                    if (f[3].equals("1")) d.movesFollowed++;
                    gameLines += l;
                    inGame = true;
                } else if (f[0].equals("G")) {
                    if (inGame) d.linesPerGame.add(gameLines);
                    d.games++;
                    gameLines = 0;
                    inGame = true;
                } else if (f[0].equals("S") && f.length >= 3) {
                    d.safety.add(Integer.parseInt(f[2]));
                } else if (f.length >= 5 && Character.isDigit(f[0].charAt(0))) {
                    // terna: ora;griglia;pezzo1;pezzo2;pezzo3
                    d.triples++;
                    long board = Long.parseUnsignedLong(f[1], 16);
                    int bin = Math.min(4, Long.bitCount(board) / 10);
                    if (Long.bitCount(board) >= 28) {
                        d.crowdedBoards.add(new long[]{board});
                        d.crowdedPieces.add(new String[]{f[2], f[3], f[4]});
                    }
                    for (int i = 2; i < 5; i++) {
                        String k = f[i];
                        if (validKey(k)) d.allPieces.add(k);
                        shapeCount.merge(k, 1, Integer::sum);
                        int cells = 0;
                        for (char ch : k.toCharArray()) if (ch == '#') cells++;
                        sizeSum[bin] += cells;
                        pieceCount[bin]++;
                        if (cells >= 5) bigCount[bin]++;
                    }
                }
            } catch (Exception ignored) {
                // riga rovinata: si salta
            }
        }
        if (inGame && d.games > 0) d.linesPerGame.add(gameLines);
        // le terne lette male non contano per "il gioco ti aiuta?"
        for (int i = d.crowdedPieces.size() - 1; i >= 0; i--) {
            String[] k = d.crowdedPieces.get(i);
            if (!validKey(k[0]) || !validKey(k[1]) || !validKey(k[2])) {
                d.crowdedPieces.remove(i);
                d.crowdedBoards.remove(i);
            }
        }
        for (int b = 0; b < 5; b++) {
            d.fullnessCount[b] = pieceCount[b] / 3;
            d.avgPieceSize[b] = pieceCount[b] == 0 ? 0 : sizeSum[b] / pieceCount[b];
            d.bigPieceRate[b] = pieceCount[b] == 0 ? 0 : 100.0 * bigCount[b] / pieceCount[b];
        }
        d.shapes.addAll(shapeCount.entrySet());
        d.shapes.sort((a, b) -> b.getValue() - a.getValue());
        return d;
    }

    /** Forma valida: righe della stessa lunghezza, nessuna riga o colonna vuota. */
    static boolean validKey(String k) {
        String[] rows = k.replaceAll("/+$", "").split("/");
        if (rows.length == 0 || rows[0].isEmpty()) return false;
        for (String r : rows) if (r.length() != rows[0].length() || r.indexOf('#') < 0) return false;
        for (int j = 0; j < rows[0].length(); j++) {
            boolean any = false;
            for (String r : rows) if (r.charAt(j) == '#') any = true;
            if (!any) return false;
        }
        return true;
    }

    /**
     * "Il gioco ti aiuta?": con la griglia piena, quante terne del gioco entravano davvero,
     * contro quante ne entrerebbero estraendo a caso gli stessi pezzi (con la loro frequenza).
     */
    public String helpVerdict() {
        int n = Math.min(150, crowdedBoards.size());
        if (n < 10 || allPieces.size() < 60) return I18n.t("Servono più terne con la griglia piena per capirlo.",
                "More piece sets with a full board are needed to tell.");
        java.util.Random rnd = new java.util.Random(42);
        int fit = 0, rfit = 0, rtot = 0;
        for (int i = 0; i < n; i++) {
            long b = crowdedBoards.get(i)[0];
            if (Solver.fitsAll(b, crowdedPieces.get(i))) fit++;
            for (int s = 0; s < 30; s++) {
                String[] r = {allPieces.get(rnd.nextInt(allPieces.size())), allPieces.get(rnd.nextInt(allPieces.size())),
                        allPieces.get(rnd.nextInt(allPieces.size()))};
                rtot++;
                if (Solver.fitsAll(b, r)) rfit++;
            }
        }
        int real = Math.round(100f * fit / n), random = Math.round(100f * rfit / rtot);
        if (real >= random + 5) return String.format(I18n.t(
                "Sì! Con la griglia piena è entrato il %d%% delle terne; con gli stessi pezzi a caso sarebbe il %d%% (%d terne).",
                "Yes! With a full board %d%% of the sets fit; with the same pieces drawn at random it would be %d%% (%d sets)."),
                real, random, n);
        return String.format(I18n.t(
                "Non si vede un aiuto: con la griglia piena è entrato il %d%% delle terne, a caso sarebbe il %d%% (%d terne).",
                "No sign of help: with a full board %d%% of the sets fit, at random it would be %d%% (%d sets)."), real, random, n);
    }
}
