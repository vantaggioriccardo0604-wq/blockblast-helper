package com.bbhelper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostica: tempi reali sul telefono (cattura, riconoscimento, calcolo) e ultimi errori.
 * Tutto in memoria; la schermata dell'app la legge e il rapporto la copia in testo.
 */
public final class Diag {

    private Diag() {}

    private static final Object LOCK = new Object();
    private static double grabAvg = -1, analyzeAvg = -1, solveAvg = -1;
    private static long grabMax, analyzeMax, solveMax;
    private static long frames, framesOk, solves, instant;
    private static String lastState = "-";
    private static final ArrayDeque<String> errors = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.ITALY);

    private static double avg(double old, long v) {
        return old < 0 ? v : old * 0.9 + v * 0.1; // media mobile
    }

    public static void grabMs(long ms) {
        synchronized (LOCK) { grabAvg = avg(grabAvg, ms); grabMax = Math.max(grabMax, ms); }
    }

    public static void analyzeMs(long ms, boolean ok, String state) {
        synchronized (LOCK) {
            analyzeAvg = avg(analyzeAvg, ms);
            analyzeMax = Math.max(analyzeMax, ms);
            frames++;
            if (ok) framesOk++;
            lastState = state;
        }
    }

    public static void solveMs(long ms, boolean wasInstant) {
        synchronized (LOCK) {
            if (wasInstant) { instant++; return; }
            solves++;
            solveAvg = avg(solveAvg, ms);
            solveMax = Math.max(solveMax, ms);
        }
    }

    public static void error(String where, Throwable t) {
        synchronized (LOCK) {
            StackTraceElement[] st = t.getStackTrace();
            String at = st.length > 0 ? " @" + st[0].getClassName().replace("com.bbhelper.", "")
                    + "." + st[0].getMethodName() + ":" + st[0].getLineNumber() : "";
            errors.addFirst(FMT.format(new Date()) + " " + where + ": " + t + at);
            while (errors.size() > 10) errors.removeLast();
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            grabAvg = analyzeAvg = solveAvg = -1;
            grabMax = analyzeMax = solveMax = 0;
            frames = framesOk = solves = instant = 0;
            errors.clear();
            lastState = "-";
        }
    }

    private static String ms(double v) {
        return v < 0 ? "-" : Math.round(v) + " ms";
    }

    /** Riassunto leggibile per la schermata dell'app. */
    public static String summary() {
        synchronized (LOCK) {
            long tot = solves + instant;
            return I18n.t("Cattura schermo: ", "Screen capture: ") + ms(grabAvg) + " (max " + grabMax + ")\n"
                    + I18n.t("Riconoscimento: ", "Recognition: ") + ms(analyzeAvg) + " (max " + analyzeMax + ")\n"
                    + I18n.t("Calcolo mosse: ", "Move search: ") + ms(solveAvg) + " (max " + solveMax + ")\n"
                    + I18n.t("Mosse già pronte (istantanee): ", "Moves ready in advance (instant): ") + (tot == 0 ? "-" : (instant * 100 / tot) + "%") + "\n"
                    + I18n.t("Fotogrammi con griglia: ", "Frames with board: ") + (frames == 0 ? "-" : (framesOk * 100 / frames) + "%")
                    + I18n.t(" di ", " of ") + frames + "\n"
                    + I18n.t("Ultimo stato: ", "Last state: ") + lastState + "\n"
                    + I18n.t("Errori recenti: ", "Recent errors: ") + (errors.isEmpty() ? I18n.t("nessuno", "none") : errors.size());
        }
    }

    /** Rapporto completo da condividere. */
    public static String report(String header) {
        StringBuilder sb = new StringBuilder();
        sb.append("BB Helper report\n").append(header).append("\n\n").append(summary()).append("\n");
        synchronized (LOCK) {
            for (String e : errors) sb.append("  ").append(e).append('\n');
        }
        return sb.toString();
    }
}
