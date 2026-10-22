package com.bbhelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Segue la partita confrontando due situazioni stabili consecutive: capisce quale pezzo e'
 * stato piazzato e dove, quante linee ha chiuso, se era la mossa suggerita, e registra le
 * terne di pezzi che il gioco propone (per tarare la strategia sui pezzi veri).
 * Nessuna dipendenza Android.
 */
public final class Tracker {

    public long placed, lines, followed, games, triples;
    /**
     * Righe nuove per il registro:
     *  "ora;griglia_hex;pezzo1;pezzo2;pezzo3"  terna proposta dal gioco
     *  "M;ora;linee;seguita(0/1)"              mossa riconosciuta
     *  "G;ora"                                 nuova partita
     *  "S;ora;sopravvivenza%"                  sopravvivenza calcolata per una terna nuova
     */
    public final List<String> newLogLines = new ArrayList<>();

    private boolean hasPrev = false;
    private long prevBoard;
    private final Analyzer.Piece[] prevSlots = new Analyzer.Piece[3];
    private long expectedCells = 0;
    private int expectedSlot = -1;

    private static final long[] ROW = new long[8], COL = new long[8];
    static {
        for (int i = 0; i < 8; i++)
            for (int j = 0; j < 8; j++) {
                ROW[i] |= 1L << (i * 8 + j);
                COL[j] |= 1L << (i * 8 + j);
            }
    }

    /**
     * Da chiamare a ogni nuova situazione stabile, prima di mostrare il nuovo suggerimento.
     * @param planShown il suggerimento che era a schermo per la situazione precedente (o null)
     */
    public void onState(boolean[] grid, Analyzer.Piece[] slots, Solver.Plan planShown, long now) {
        long board = Solver.toMask(grid);
        if (hasPrev) {
            boolean matched = false;
            for (int s = 0; s < 3 && !matched; s++) {
                Analyzer.Piece p = prevSlots[s];
                if (p == null) continue;
                // il pezzo deve essere sparito dal suo posto, oppure il vassoio si e' riempito di nuovo
                boolean gone = slots[s] == null || !slots[s].key().equals(p.key()) || refilled(slots);
                if (!gone) continue;
                for (long m : Solver.allPlacements(p.cells)) {
                    if ((prevBoard & m) != 0) continue;
                    long nb = prevBoard | m, clear = 0;
                    int l = 0;
                    for (int i = 0; i < 8; i++) {
                        if ((nb & ROW[i]) == ROW[i]) { clear |= ROW[i]; l++; }
                        if ((nb & COL[i]) == COL[i]) { clear |= COL[i]; l++; }
                    }
                    nb &= ~clear;
                    if (nb == board) {
                        placed++;
                        lines += l;
                        boolean fol = s == expectedSlot && m == expectedCells;
                        if (fol) followed++;
                        newLogLines.add("M;" + now + ";" + l + ";" + (fol ? 1 : 0));
                        matched = true;
                        break;
                    }
                }
            }
            // griglia svuotata senza una mossa che lo spieghi: nuova partita
            if (!matched && board == 0 && Long.bitCount(prevBoard) >= 8) {
                games++;
                newLogLines.add("G;" + now);
            }
        } else if (board == 0) {
            games++;
            newLogLines.add("G;" + now);
        }

        // nuova terna proposta dal gioco
        if (refilled(slots) && (!hasPrev || countOf(prevSlots) < 3 || !sameKeys(slots, prevSlots))) {
            triples++;
            newLogLines.add(now + ";" + Long.toHexString(board) + ";"
                    + slots[0].key() + ";" + slots[1].key() + ";" + slots[2].key());
        }

        hasPrev = true;
        prevBoard = board;
        System.arraycopy(slots, 0, prevSlots, 0, 3);
        if (planShown != null && !planShown.steps.isEmpty()) {
            expectedSlot = planShown.steps.get(0).slot;
            expectedCells = planShown.steps.get(0).cells;
        } else {
            expectedSlot = -1;
            expectedCells = 0;
        }
    }

    /** Aggiorna la mossa attesa quando cambia il suggerimento per la situazione attuale. */
    public void setExpected(Solver.Plan plan) {
        if (plan != null && !plan.steps.isEmpty()) {
            expectedSlot = plan.steps.get(0).slot;
            expectedCells = plan.steps.get(0).cells;
        } else {
            expectedSlot = -1;
            expectedCells = 0;
        }
    }

    private static boolean refilled(Analyzer.Piece[] s) {
        return s[0] != null && s[1] != null && s[2] != null;
    }

    private static int countOf(Analyzer.Piece[] s) {
        int n = 0;
        for (Analyzer.Piece p : s) if (p != null) n++;
        return n;
    }

    private static boolean sameKeys(Analyzer.Piece[] a, Analyzer.Piece[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] == null || b[i] == null) return false;
            if (!a[i].key().equals(b[i].key())) return false;
        }
        return true;
    }
}
