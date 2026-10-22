package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Disegna i suggerimenti sopra il gioco.
 * Tocca solo i bordi delle celle e un piccolo numero nell'angolo: il centro delle celle
 * resta libero, cosi' la cattura successiva non scambia i suggerimenti per blocchi.
 */
public class HintView extends View {

    static final int[] COLORS = {
            Color.rgb(0, 230, 118),   // 1 verde
            Color.rgb(255, 171, 0),   // 2 arancio
            Color.rgb(213, 0, 249),   // 3 viola
    };

    private Analyzer.Result res;
    private Solver.Plan plan;
    private float scale = 1f;
    private final int[] loc = new int[2];
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public HintView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        banner.setColor(Color.argb(200, 20, 20, 28));
    }

    public void show(Analyzer.Result r, Solver.Plan p, float captureToScreen) {
        res = r;
        plan = p;
        scale = captureToScreen;
        invalidate();
    }

    public void clear() {
        if (res == null && plan == null) return;
        res = null;
        plan = null;
        invalidate();
    }

    private boolean stepMode = true;

    public void setStepMode(boolean on) {
        if (stepMode != on) { stepMode = on; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (res == null) return;
        getLocationOnScreen(loc);
        float cell = res.cellSize * scale;
        float bx = res.boardLeft * scale - loc[0];
        float by = res.boardTop * scale - loc[1];
        int shown = plan == null ? 0 : (stepMode ? Math.min(1, plan.steps.size()) : plan.steps.size());

        // riga di stato sopra la griglia
        String msg;
        if (plan == null || plan.steps.isEmpty()) {
            msg = plan == null ? t("Nessun pezzo trovato", "No pieces found")
                    : t("Nessuna mossa possibile", "No move possible");
        } else if (stepMode) {
            Solver.Step st = plan.steps.get(0);
            StringBuilder sb = new StringBuilder(t("Prossima mossa", "Next move"));
            if (st.lines > 0) sb.append(t("  ·  chiude ", "  ·  clears ")).append(st.lines)
                    .append(st.lines == 1 ? t(" linea", " line") : t(" linee", " lines"));
            int left = plan.steps.size() - 1;
            if (left > 0) sb.append(t("  ·  poi altr" + (left == 1 ? "a 1" : "e " + left),
                    "  ·  then " + left + " more"));
            if (!plan.allPlaced) sb.append(t("  ·  non entrano tutti", "  ·  not all fit"));
            msg = sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < plan.steps.size(); i++) sb.append(i == 0 ? "" : "  →  ").append(i + 1);
            if (plan.totalLines > 0) sb.append("   ·   ").append(plan.totalLines)
                    .append(plan.totalLines == 1 ? t(" linea", " line") : t(" linee", " lines"));
            if (!plan.allPlaced) sb.append(t("   ·   attenzione: non entrano tutti", "   ·   warning: not all fit"));
            msg = sb.toString();
        }
        // probabilita' di sopravvivenza al turno dopo: pallino colorato + percentuale
        int safety = plan == null ? -1 : plan.safety;
        if (safety >= 0) msg = safety + "%   ·   " + msg;
        // larghezza limitata (max ~60% della griglia) per non confondere il riconoscimento
        text.setTextSize(cell * 0.30f);
        float dotSpace = safety >= 0 ? cell * 0.42f : 0f;
        float raw = text.measureText(msg) + dotSpace;
        if (raw > cell * 4.2f) text.setTextSize(cell * 0.30f * cell * 4.2f / raw);
        float textW = text.measureText(msg);
        float tw = textW + dotSpace + cell * 0.6f;
        float cx = bx + cell * 4f;
        float ty = res.outerTop * scale - loc[1] - cell * 0.95f;
        rect.set(cx - tw / 2, ty, cx + tw / 2, ty + cell * 0.48f);
        canvas.drawRoundRect(rect, cell * 0.24f, cell * 0.24f, banner);
        float textLeft = cx - tw / 2 + cell * 0.3f + dotSpace;
        if (safety >= 0) {
            badge.setColor(safety >= 85 ? Color.rgb(0, 230, 118)
                    : safety >= 60 ? Color.rgb(255, 214, 0) : Color.rgb(255, 82, 82));
            canvas.drawCircle(textLeft - dotSpace / 2f, ty + cell * 0.24f, cell * 0.12f, badge);
        }
        text.setColor(Color.WHITE);
        canvas.drawText(msg, textLeft + textW / 2f, ty + cell * 0.345f, text);

        if (plan == null) return;
        ring.setStrokeWidth(cell * 0.06f);
        float boardH = (res.outerBottom - res.outerTop) * scale;
        for (int i = shown - 1; i >= 0; i--) {
            Solver.Step st = plan.steps.get(i);
            int color = COLORS[i % COLORS.length];
            ring.setColor(color);
            int first = -1;
            for (int k = 0; k < 64; k++) {
                if (((st.cells >>> k) & 1L) == 0) continue;
                if (first < 0) first = k;
                float x = bx + (k % 8) * cell, y = by + (k / 8) * cell;
                float in = cell * 0.22f; // lontano dal bordo (rilievo) e dal centro della cella
                rect.set(x + in, y + in, x + cell - in, y + cell - in);
                canvas.drawRoundRect(rect, cell * 0.10f, cell * 0.10f, ring);
            }
            badge.setColor(color);
            if (first >= 0) {
                float x = bx + (first % 8) * cell + cell * 0.20f;
                float y = by + (first / 8) * cell + cell * 0.20f;
                canvas.drawCircle(x, y, cell * 0.11f, badge);
                text.setColor(Color.BLACK);
                text.setTextSize(cell * 0.16f);
                canvas.drawText(String.valueOf(i + 1), x, y + cell * 0.06f, text);
            }
            // numero anche sopra il pezzo da prendere nel vassoio (in una fascia che il
            // riconoscimento dei pezzi non guarda)
            Analyzer.Piece pc = res.slots[st.slot];
            if (pc != null) {
                float px = (pc.left + pc.right) / 2f * scale - loc[0];
                float py = res.outerBottom * scale - loc[1] + boardH * 0.062f;
                float r = boardH * 0.03f;
                canvas.drawCircle(px, py, r, badge);
                text.setColor(Color.BLACK);
                text.setTextSize(r * 1.25f);
                canvas.drawText(String.valueOf(i + 1), px, py + r * 0.45f, text);
            }
        }
    }
}
