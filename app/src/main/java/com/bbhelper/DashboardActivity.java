package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Dashboard dentro l'app: grafici calcolati dal registro raccolto mentre giochi. */
public class DashboardActivity extends Activity {

    static final int BG = Color.rgb(18, 18, 26);
    static final int CARD = Color.rgb(30, 30, 42);
    static final int TEXT = Color.WHITE;
    static final int MUTED = Color.rgb(150, 150, 170);
    static final int ACCENT = Color.rgb(0, 200, 110);
    static final int BLUE = Color.rgb(90, 150, 255);
    static final int ORANGE = Color.rgb(255, 171, 0);
    static final int RED = Color.rgb(255, 82, 82);

    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // i dati crescono mentre giochi
    }

    private void render() {
        List<String> lines = new ArrayList<>();
        try {
            File f = new File(getFilesDir(), HelperService.LOG_FILE);
            if (f.exists()) lines = Files.readAllLines(f.toPath());
        } catch (Exception ignored) {}
        DashData data = DashData.parse(lines);
        android.content.SharedPreferences prefs = getSharedPreferences(HelperService.PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(40), pad, dp(32));
        col.setBackgroundColor(BG);

        TextView title = text("Dashboard", 28, TEXT, true);
        col.addView(title);
        TextView sub = text(tr("Calcolata dai dati raccolti mentre giochi. Si aggiorna da sola.", "Built from the data collected while you play. Updates by itself."), 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        // ---- numeri principali (statistiche complessive salvate dall'app)
        long placed = prefs.getLong(HelperService.S_PLACED, 0);
        long followed = prefs.getLong(HelperService.S_FOLLOWED, 0);
        LinearLayout r1 = row(), r2 = row(), r3 = row();
        r1.addView(kpi(String.valueOf(prefs.getLong(HelperService.S_GAMES, 0)), tr("partite", "games")));
        r1.addView(kpi(String.valueOf(placed), tr("pezzi piazzati", "pieces placed")));
        r2.addView(kpi(String.valueOf(prefs.getLong(HelperService.S_LINES, 0)), tr("linee fatte", "lines cleared")));
        r2.addView(kpi(placed == 0 ? "-" : (followed * 100 / placed) + "%", tr("mosse come suggerito", "moves as suggested")));
        r3.addView(kpi(String.valueOf(data.triples), tr("terne registrate", "piece sets recorded")));
        int last = data.safety.isEmpty() ? -1 : data.safety.get(data.safety.size() - 1);
        r3.addView(kpi(last < 0 ? "-" : last + "%", tr("sopravvivenza attuale", "current survival")));
        col.addView(r1);
        col.addView(r2);
        col.addView(r3);

        // ---- pezzi piu' frequenti
        LinearLayout c1 = card(tr("Pezzi che escono più spesso", "Most frequent pieces"),
                data.triples == 0 ? null : tr("Su " + (data.triples * 3) + " pezzi visti", "Out of " + (data.triples * 3) + " pieces seen"));
        if (data.shapes.isEmpty()) c1.addView(empty());
        else c1.addView(new ShapeBars(this, data.shapes.subList(0, Math.min(10, data.shapes.size())), data.triples * 3),
                new LinearLayout.LayoutParams(-1, dp(30) * Math.min(10, data.shapes.size()) + dp(4)));
        col.addView(c1);

        // ---- il gioco ti aiuta?
        LinearLayout c2 = card(tr("Il gioco ti aiuta?", "Does the game help you?"),
                tr("Con la griglia piena il gioco dà pezzi che entrano più spesso del caso? Sotto: grandezza media dei pezzi secondo quante caselle erano piene", "With a full board, does the game give pieces that fit more often than chance? Below: average piece size by how many cells were filled"));
        boolean any = false;
        for (int n : data.fullnessCount) if (n > 0) any = true;
        if (!any) c2.addView(empty());
        else {
            List<String> labels = new ArrayList<>();
            List<Double> vals = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                labels.add(data.fullnessLabels[i] + "\n(" + data.fullnessCount[i] + ")");
                vals.add(data.avgPieceSize[i]);
            }
            c2.addView(new Bars(this, labels, vals, BLUE, "%.1f"), new LinearLayout.LayoutParams(-1, dp(170)));
            TextView v = text(data.helpVerdict(), 14, TEXT, false);
            v.setPadding(0, dp(8), 0, 0);
            c2.addView(v);
        }
        col.addView(c2);

        // ---- sopravvivenza nel tempo
        LinearLayout c3 = card(tr("Sopravvivenza nel tempo", "Survival over time"),
                tr("Probabilità che i 3 pezzi successivi entrino, a ogni terna (ultime 120)", "Chance the next 3 pieces fit, at every new set (last 120)"));
        if (data.safety.size() < 2) c3.addView(empty());
        else {
            List<Integer> s = data.safety.subList(Math.max(0, data.safety.size() - 120), data.safety.size());
            c3.addView(new SafetyLine(this, s), new LinearLayout.LayoutParams(-1, dp(160)));
        }
        col.addView(c3);

        // ---- linee per mossa
        LinearLayout c4 = card(tr("Linee chiuse per mossa", "Lines cleared per move"), data.moves == 0 ? null : tr("Su " + data.moves + " mosse", "Out of " + data.moves + " moves"));
        if (data.moves == 0) c4.addView(empty());
        else {
            List<String> labels = java.util.Arrays.asList(tr("nessuna", "none"), "1", "2", "3+");
            List<Double> vals = new ArrayList<>();
            for (int n : data.linesPerMove) vals.add(100.0 * n / data.moves);
            c4.addView(new Bars(this, labels, vals, ACCENT, "%.0f%%"), new LinearLayout.LayoutParams(-1, dp(160)));
        }
        col.addView(c4);

        // ---- linee per partita
        LinearLayout c5 = card(tr("Linee per partita", "Lines per game"), tr("Ultime partite registrate", "Latest recorded games"));
        if (data.linesPerGame.isEmpty()) c5.addView(empty());
        else {
            List<Integer> g = data.linesPerGame.subList(Math.max(0, data.linesPerGame.size() - 12), data.linesPerGame.size());
            List<String> labels = new ArrayList<>();
            List<Double> vals = new ArrayList<>();
            int start = data.linesPerGame.size() - g.size() + 1;
            for (int i = 0; i < g.size(); i++) { labels.add("#" + (start + i)); vals.add((double) g.get(i)); }
            c5.addView(new Bars(this, labels, vals, ORANGE, "%.0f"), new LinearLayout.LayoutParams(-1, dp(160)));
        }
        col.addView(c5);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(col);
        setContentView(sv);
    }

    // ------------------------------------------------------------------ mattoncini

    static String tr(String it, String en) { return t(it, en); }

    int dp(float v) { return Math.round(v * d); }

    TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        r.setLayoutParams(lp);
        return r;
    }

    View kpi(String value, String label) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        k.setBackground(bg);
        k.addView(text(value, 26, TEXT, true));
        k.addView(text(label, 13, MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginEnd(dp(5));
        lp.setMarginStart(dp(5));
        k.setLayoutParams(lp);
        return k;
    }

    LinearLayout card(String title, String subtitle) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        c.addView(text(title, 18, TEXT, true));
        if (subtitle != null) {
            TextView s = text(subtitle, 13, MUTED, false);
            s.setPadding(0, dp(2), 0, dp(10));
            c.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        lp.setMarginStart(dp(5));
        lp.setMarginEnd(dp(5));
        c.setLayoutParams(lp);
        return c;
    }

    View empty() {
        TextView t = text(tr("Ancora pochi dati: gioca con l'aiuto attivo e torna qui.", "Not much data yet: play with the helper on and come back."), 14, MUTED, false);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    // ------------------------------------------------------------------ grafici

    /** Righe orizzontali: disegno del pezzo + barra + percentuale. */
    static class ShapeBars extends View {
        final List<Map.Entry<String, Integer>> items;
        final int total;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        ShapeBars(Context c, List<Map.Entry<String, Integer>> items, int total) {
            super(c);
            this.items = new ArrayList<>(items);
            this.total = Math.max(1, total);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float dens = getResources().getDisplayMetrics().density;
            float rowH = 30 * dens, w = getWidth();
            int max = 1;
            for (Map.Entry<String, Integer> e : items) max = Math.max(max, e.getValue());
            float shapeW = 54 * dens, labelW = 46 * dens;
            for (int i = 0; i < items.size(); i++) {
                String key = items.get(i).getKey();
                int n = items.get(i).getValue();
                float top = i * rowH;
                // disegno della forma
                String[] rows = key.split("/");
                int nr = 0, nc = 0;
                for (String s : rows) if (!s.isEmpty()) { nr++; nc = Math.max(nc, s.length()); }
                float cell = Math.min((rowH - 6 * dens) / Math.max(1, nr), (shapeW - 4 * dens) / Math.max(1, nc));
                float ox = (shapeW - nc * cell) / 2f, oy = top + (rowH - nr * cell) / 2f;
                p.setColor(BLUE);
                int ri = 0;
                for (String s : rows) {
                    if (s.isEmpty()) continue;
                    for (int j = 0; j < s.length(); j++)
                        if (s.charAt(j) == '#') {
                            r.set(ox + j * cell + 0.6f, oy + ri * cell + 0.6f, ox + (j + 1) * cell - 0.6f, oy + (ri + 1) * cell - 0.6f);
                            cv.drawRoundRect(r, cell * 0.2f, cell * 0.2f, p);
                        }
                    ri++;
                }
                // barra
                float bx = shapeW + 8 * dens, bw = (w - bx - labelW) * n / max;
                p.setColor(Color.argb(60, 255, 255, 255));
                r.set(bx, top + rowH * 0.3f, w - labelW, top + rowH * 0.7f);
                cv.drawRoundRect(r, 6 * dens, 6 * dens, p);
                p.setColor(ACCENT);
                r.set(bx, top + rowH * 0.3f, bx + Math.max(4 * dens, bw), top + rowH * 0.7f);
                cv.drawRoundRect(r, 6 * dens, 6 * dens, p);
                // percentuale
                p.setColor(TEXT);
                p.setTextSize(13 * dens);
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(String.format("%.1f%%", 100f * n / total), w, top + rowH * 0.66f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    /** Barre verticali con etichette sotto e valore sopra. */
    static class Bars extends View {
        final List<String> labels;
        final List<Double> values;
        final int color;
        final String fmt;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Bars(Context c, List<String> labels, List<Double> values, int color, String fmt) {
            super(c);
            this.labels = labels;
            this.values = values;
            this.color = color;
            this.fmt = fmt;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float dens = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight();
            float labelH = 34 * dens, valueH = 18 * dens;
            double max = 0;
            for (double v : values) max = Math.max(max, v);
            if (max <= 0) max = 1;
            int n = values.size();
            float slot = w / n, bw = Math.min(slot * 0.6f, 44 * dens);
            float base = h - labelH, top = valueH;
            p.setColor(Color.argb(50, 255, 255, 255));
            cv.drawRect(0, base, w, base + 1 * dens, p);
            for (int i = 0; i < n; i++) {
                double v = values.get(i);
                float cx = slot * i + slot / 2f;
                float bh = (float) ((base - top) * v / max);
                p.setColor(color);
                r.set(cx - bw / 2, base - bh, cx + bw / 2, base);
                cv.drawRoundRect(r, 6 * dens, 6 * dens, p);
                if (bh > 6 * dens) cv.drawRect(cx - bw / 2, base - 6 * dens, cx + bw / 2, base, p);
                p.setColor(TEXT);
                p.setTextSize(12 * dens);
                p.setTextAlign(Paint.Align.CENTER);
                if (v > 0) cv.drawText(String.format(fmt, v), cx, base - bh - 5 * dens, p);
                p.setColor(MUTED);
                p.setTextSize(11 * dens);
                String[] parts = labels.get(i).split("\n");
                for (int k = 0; k < parts.length; k++)
                    cv.drawText(parts[k], cx, base + (14 + 13 * k) * dens, p);
            }
        }
    }

    /** Linea della sopravvivenza (0-100%) con fasce verde/gialla/rossa. */
    static class SafetyLine extends View {
        final List<Integer> vals;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        SafetyLine(Context c, List<Integer> vals) {
            super(c);
            this.vals = new ArrayList<>(vals);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float dens = getResources().getDisplayMetrics().density;
            float w = getWidth(), h = getHeight(), left = 30 * dens, bottom = h - 4 * dens, top = 6 * dens;
            float ph = bottom - top;
            // fasce di rischio
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(35, 0, 230, 118));
            cv.drawRect(left, top, w, top + ph * 0.15f, p);
            p.setColor(Color.argb(35, 255, 214, 0));
            cv.drawRect(left, top + ph * 0.15f, w, top + ph * 0.40f, p);
            p.setColor(Color.argb(35, 255, 82, 82));
            cv.drawRect(left, top + ph * 0.40f, w, bottom, p);
            p.setColor(MUTED);
            p.setTextSize(11 * dens);
            p.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("100%", left - 4 * dens, top + 10 * dens, p);
            cv.drawText("60%", left - 4 * dens, top + ph * 0.40f + 4 * dens, p);
            cv.drawText("0%", left - 4 * dens, bottom, p);
            // linea
            int n = vals.size();
            Path path = new Path();
            for (int i = 0; i < n; i++) {
                float x = left + (w - left) * i / Math.max(1, n - 1);
                float y = top + ph * (1 - vals.get(i) / 100f);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * dens);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(BLUE);
            cv.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
