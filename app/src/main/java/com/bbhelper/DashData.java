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
                    for (int i = 2; i < 5; i++) {
                        String k = f[i];
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
        for (int b = 0; b < 5; b++) {
            d.fullnessCount[b] = pieceCount[b] / 3;
            d.avgPieceSize[b] = pieceCount[b] == 0 ? 0 : sizeSum[b] / pieceCount[b];
            d.bigPieceRate[b] = pieceCount[b] == 0 ? 0 : 100.0 * bigCount[b] / pieceCount[b];
        }
        d.shapes.addAll(shapeCount.entrySet());
        d.shapes.sort((a, b) -> b.getValue() - a.getValue());
        return d;
    }

    /** Una frase semplice su "il gioco ti aiuta?", in base ai pezzi visti. */
    public String helpVerdict() {
        // confronta griglia quasi vuota (0-19) con griglia piena (30+)
        double emptyS = 0, fullS = 0;
        int emptyN = 0, fullN = 0;
        for (int b = 0; b < 5; b++) {
            if (fullnessCount[b] == 0) continue;
            if (b <= 1) { emptyS += avgPieceSize[b] * fullnessCount[b]; emptyN += fullnessCount[b]; }
            if (b >= 3) { fullS += avgPieceSize[b] * fullnessCount[b]; fullN += fullnessCount[b]; }
        }
        if (emptyN < 15 || fullN < 15) return I18n.t("Servono più terne con la griglia piena per capirlo.", "More piece sets with a full board are needed to tell.");
        double e = emptyS / emptyN, f = fullS / fullN;
        if (f < e - 0.3) return String.format(I18n.t("Sembra di sì: con la griglia piena i pezzi sono più piccoli (%.1f caselle contro %.1f).", "Looks like it: with a full board pieces are smaller (%.1f cells vs %.1f)."), f, e);
        if (f > e + 0.3) return String.format(I18n.t("Sembra di no: con la griglia piena i pezzi sono perfino più grandi (%.1f contro %.1f).", "Looks like it doesn't: with a full board pieces are even bigger (%.1f vs %.1f)."), f, e);
        return String.format(I18n.t("Non si vede differenza: i pezzi sono simili con griglia vuota o piena (%.1f contro %.1f).", "No visible difference: pieces are similar with an empty or full board (%.1f vs %.1f)."), e, f);
    }
}
