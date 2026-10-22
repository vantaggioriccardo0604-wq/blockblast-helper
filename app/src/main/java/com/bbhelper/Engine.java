package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Il "cervello" dell'aiuto: sovrapposizione con i suggerimenti, bolla, ciclo di
 * riconoscimento, calcolo e statistiche. Non sa come viene catturato lo schermo:
 * glielo dice una {@link Frames} (registrazione dello schermo oppure Accessibilita').
 */
public final class Engine {

    /** Riempie {@code pixels} (capW x capH, ARGB) con l'ultima immagine dello schermo. */
    public interface Frames {
        boolean grab(int[] pixels, int w, int h);
    }

    /** Chi ospita il motore. */
    public interface Host {
        /** L'utente ha chiuso l'aiuto dalla bolla (pressione lunga). */
        void onCloseRequested();

        /** La griglia di gioco e' stata riconosciuta (serve per capire qual e' l'app del gioco). */
        default void onBoardSeen() {}

        /** Intervallo minimo tra due catture dello schermo (l'Accessibilita' ne impone uno). */
        default long minIntervalMs() { return 200; }
    }

    private static final long FAST_MS = 200;    // partita in movimento (minimo)
    private static final long SLOW_MS = 900;    // partita ferma, con risparmio batteria
    private static final long HIDDEN_MS = 1500; // aiuto nascosto (fuori dal gioco)

    private final Context ctx;
    private final WindowManager wm;
    private final int overlayType;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private Frames frames;

    public final int capW, capH;
    private int screenW, screenH;
    private float toScreen = 1f;
    private final int[] pixels;

    private HintView hints;
    private Bubble bubble;
    private WindowManager.LayoutParams bubbleLp, hintLp;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile boolean visible = true;
    private String lastSig = null, shownSig = null;
    private int stableCount = 0, failCount = 0, idleFrames = 0;
    private final Tracker tracker = new Tracker();
    private String lastTrackedSig = null;
    private volatile boolean forceResolve = false, resetStats = false;
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (HelperService.S_RESET.equals(key)) resetStats = true;
        if (HelperService.P_SCORE.equals(key)) forceResolve = true;
        applyPrefs();
    };

    // mossa successiva gia' pronta: griglia e vassoio attesi se l'utente fa la mossa suggerita
    private long predictedBoard;
    private String[] predictedKeys;
    private Solver.Plan predictedPlan;

    // la bolla non deve essere scambiata per un pezzo della partita
    private volatile int[] bubbleRect = null; // in coordinate di cattura
    private final int[] tmpLoc = new int[2];
    private final Runnable readBubble = () -> {
        if (bubble == null || bubble.getWidth() == 0 || bubble.getVisibility() != View.VISIBLE) {
            bubbleRect = null;
            return;
        }
        bubble.getLocationOnScreen(tmpLoc);
        float k = 1f / toScreen;
        int m = Math.round(bubble.getWidth() * 0.1f);
        bubbleRect = new int[]{
                Math.round((tmpLoc[0] - m) * k), Math.round((tmpLoc[1] - m) * k),
                Math.round((tmpLoc[0] + bubble.getWidth() + m) * k),
                Math.round((tmpLoc[1] + bubble.getHeight() + m) * k)};
    };

    public Engine(Context ctx, WindowManager wm, int overlayType, Host host) {
        this.ctx = ctx;
        this.wm = wm;
        this.overlayType = overlayType;
        this.host = host;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        float down = screenW >= 1000 ? 0.5f : 1f; // risoluzione ridotta: piu' veloce, stessa precisione
        capW = Math.round(screenW * down);
        capH = Math.round(screenH * down);
        toScreen = screenW / (float) capW;
        pixels = new int[capW * capH];
        prefs = ctx.getSharedPreferences(HelperService.PREFS, Context.MODE_PRIVATE);
    }

    public int densityDpi() {
        return ctx.getResources().getDisplayMetrics().densityDpi;
    }

    public void start(Frames f) {
        frames = f;
        workerThread = new HandlerThread("bbhelper-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        addOverlays();
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        loadStats();
        applyPrefs();
        running = true;
        worker.postDelayed(tick, 600);
    }

    public void stop() {
        running = false;
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        try { if (worker != null) worker.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { if (hints != null) wm.removeView(hints); } catch (Exception ignored) {}
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) {}
        hints = null;
        bubble = null;
        if (workerThread != null) workerThread.quitSafely();
    }

    /** Mostra/nasconde l'aiuto (modalita' automatica: visibile solo dentro il gioco). */
    public void setVisible(boolean v) {
        if (visible == v) return;
        visible = v;
        main.post(() -> {
            if (bubble == null) return;
            int vis = v ? View.VISIBLE : View.GONE;
            bubble.setVisibility(vis);
            hints.setVisibility(vis);
            if (!v) hints.clear();
        });
        if (!v) { shownSig = null; lastSig = null; }
    }

    private void addOverlays() {
        hints = new HintView(ctx);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        // da Android 12 i tocchi passano attraverso un overlay solo se e' almeno un po' trasparente
        lp.alpha = 0.8f; // aggiornato da applyPrefs()
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(hints, lp);
        hintLp = lp;

        bubble = new Bubble(ctx);
        int size = Math.round(52 * ctx.getResources().getDisplayMetrics().density);
        bubbleLp = new WindowManager.LayoutParams(size, size, overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.LEFT;
        // in alto a destra, all'altezza dell'ingranaggio: lontano dalla griglia
        bubbleLp.x = prefs.getInt("bubble_x", screenW - size - Math.round(screenW * 0.20f));
        bubbleLp.y = prefs.getInt("bubble_y", Math.round(screenH * 0.04f));
        wm.addView(bubble, bubbleLp);
        if (!visible) {
            bubble.setVisibility(View.GONE);
            hints.setVisibility(View.GONE);
        }
    }

    private void applyPrefs() {
        if (hints == null) return;
        hints.setStepMode(prefs.getBoolean(HelperService.P_STEP, true));
        // da Android 12 i tocchi passano attraverso l'overlay solo con opacita' <= 80%
        float a = Math.max(30, Math.min(80, prefs.getInt(HelperService.P_OPACITY, 80))) / 100f;
        if (hintLp != null && hintLp.alpha != a) {
            hintLp.alpha = a;
            try { wm.updateViewLayout(hints, hintLp); } catch (Exception ignored) {}
        }
        if (bubble != null) bubble.invalidate();
    }

    // ---------------------------------------------------------------- ciclo

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long next;
            if (!visible) {
                next = HIDDEN_MS;
            } else {
                if (!paused) {
                    try {
                        step();
                    } catch (Throwable th) {
                        // un fotogramma storto non deve fermare tutto, ma resta nel rapporto
                        Diag.error("loop", th);
                    }
                }
                boolean saver = prefs.getBoolean(HelperService.P_SAVER, true);
                next = saver && idleFrames > 8 ? SLOW_MS : Math.max(FAST_MS, host.minIntervalMs());
            }
            if (running) worker.postDelayed(this, next);
        }
    };

    private void step() {
        main.post(readBubble);
        long g0 = System.nanoTime();
        if (!frames.grab(pixels, capW, capH)) return;
        long g1 = System.nanoTime();
        Diag.grabMs((g1 - g0) / 1_000_000);
        Analyzer.Result res = Analyzer.analyze(pixels, capW, capH, bubbleRect);
        int nPieces = 0;
        for (Analyzer.Piece pc : res.slots) if (pc != null) nPieces++;
        Diag.analyzeMs((System.nanoTime() - g1) / 1_000_000, res.ok,
                res.ok ? t("griglia trovata, pezzi nel vassoio: ", "board found, pieces in tray: ") + nPieces
                        : t("griglia non trovata", "board not found"));
        if (!res.ok) {
            idleFrames++;
            lastSig = null;
            stableCount = 0;
            // nascondi solo se la griglia manca per piu' fotogrammi di fila (niente sfarfallio)
            if (++failCount >= 3 && shownSig != null) {
                shownSig = null;
                main.post(() -> {
                    if (hints == null) return;
                    hints.clear();
                    bubble.setState(Bubble.SEARCHING);
                });
            }
            return;
        }
        failCount = 0;
        host.onBoardSeen();
        String sig = res.signature();
        if (sig.equals(lastSig)) { stableCount++; idleFrames++; }
        else { stableCount = 0; lastSig = sig; idleFrames = 0; }

        if (resetStats) { resetStats = false; loadStats(); }
        if (forceResolve) { forceResolve = false; shownSig = null; }
        if (sig.equals(shownSig)) return; // suggerimento gia' a schermo

        // l'utente ha fatto la mossa suggerita: la successiva e' gia' calcolata, si mostra subito;
        // altrimenti si aspetta che la situazione sia uguale per 2 catture (es. pezzo in trascinamento)
        Solver.Plan ready = matchesPrediction(res) ? predictedPlan : null;
        if (ready == null && stableCount < 1) return;

        main.post(() -> { if (bubble != null) bubble.setState(Bubble.THINKING); });
        if (!sig.equals(lastTrackedSig)) {
            lastTrackedSig = sig;
            tracker.onState(res.grid, res.slots, null, System.currentTimeMillis());
            saveStats();
        }
        long t0 = System.nanoTime();
        Solver.Plan plan = ready != null ? ready : Solver.solve(res.grid, res.slots, scoreMode());
        Diag.solveMs(ready != null ? 0 : (System.nanoTime() - t0) / 1_000_000, ready != null);
        preparePrediction(res, plan);
        tracker.setExpected(plan);
        if (ready == null && plan != null && plan.safety >= 0
                && res.slots[0] != null && res.slots[1] != null && res.slots[2] != null) {
            tracker.newLogLines.add("S;" + System.currentTimeMillis() + ";" + plan.safety);
            saveStats();
        }
        shownSig = sig;
        final float sc = toScreen;
        main.post(() -> {
            if (paused || !visible || hints == null) return;
            hints.show(res, plan, sc);
            bubble.setState(Bubble.ACTIVE);
        });
    }

    /** La situazione attuale e' quella prevista dopo la prima mossa suggerita? */
    private boolean matchesPrediction(Analyzer.Result res) {
        if (predictedPlan == null || predictedKeys == null) return false;
        if (Solver.toMask(res.grid) != predictedBoard) return false;
        for (int i = 0; i < 3; i++) {
            String k = res.slots[i] == null ? null : res.slots[i].key();
            if (k == null ? predictedKeys[i] != null : !k.equals(predictedKeys[i])) return false;
        }
        return true;
    }

    /** Prepara la mossa successiva: griglia e vassoio dopo la prima mossa e il resto del piano. */
    private void preparePrediction(Analyzer.Result res, Solver.Plan plan) {
        predictedPlan = null;
        if (plan == null || plan.steps.size() < 2) return; // all'ultimo pezzo arrivano pezzi nuovi
        Solver.Step st = plan.steps.get(0);
        long b = Solver.toMask(res.grid) | st.cells, clear = 0;
        for (int i = 0; i < 8; i++) {
            long row = 0xFFL << (i * 8), col = 0x0101010101010101L << i;
            if ((b & row) == row) clear |= row;
            if ((b & col) == col) clear |= col;
        }
        predictedBoard = b & ~clear;
        predictedKeys = new String[3];
        for (int i = 0; i < 3; i++)
            predictedKeys[i] = (i == st.slot || res.slots[i] == null) ? null : res.slots[i].key();
        Solver.Plan rest = new Solver.Plan();
        rest.steps.addAll(plan.steps.subList(1, plan.steps.size()));
        rest.allPlaced = plan.allPlaced;
        rest.safety = plan.safety;
        rest.totalLines = plan.totalLines - st.lines;
        predictedPlan = rest;
    }

    // ---------------------------------------------------------------- statistiche

    private void loadStats() {
        tracker.placed = prefs.getLong(HelperService.S_PLACED, 0);
        tracker.lines = prefs.getLong(HelperService.S_LINES, 0);
        tracker.followed = prefs.getLong(HelperService.S_FOLLOWED, 0);
        tracker.games = prefs.getLong(HelperService.S_GAMES, 0);
        tracker.triples = prefs.getLong(HelperService.S_TRIPLES, 0);
    }

    private void saveStats() {
        prefs.edit()
                .putLong(HelperService.S_PLACED, tracker.placed).putLong(HelperService.S_LINES, tracker.lines)
                .putLong(HelperService.S_FOLLOWED, tracker.followed).putLong(HelperService.S_GAMES, tracker.games)
                .putLong(HelperService.S_TRIPLES, tracker.triples).apply();
        if (!tracker.newLogLines.isEmpty()) {
            try (java.io.FileWriter fw = new java.io.FileWriter(
                    new java.io.File(ctx.getFilesDir(), HelperService.LOG_FILE), true)) {
                for (String line : tracker.newLogLines) fw.write(line + "\n");
            } catch (Exception ignored) {}
            tracker.newLogLines.clear();
        }
    }

    // ---------------------------------------------------------------- comandi dalla bolla

    private boolean scoreMode() {
        return prefs.getBoolean(HelperService.P_SCORE, false);
    }

    private void toggleScoreMode() {
        boolean on = !scoreMode();
        prefs.edit().putBoolean(HelperService.P_SCORE, on).apply();
        Toast.makeText(ctx, on
                        ? t("Modalità punteggio: cerca combo e linee multiple", "Score mode: goes for combos and multi-line clears")
                        : t("Modalità sopravvivenza: dura il più a lungo possibile", "Survival mode: lasts as long as possible"),
                Toast.LENGTH_SHORT).show();
        if (bubble != null) bubble.invalidate();
    }

    private void togglePause() {
        paused = !paused;
        if (paused) {
            hints.clear();
            shownSig = null;
            lastSig = null;
            bubble.setState(Bubble.PAUSED);
        } else {
            bubble.setState(Bubble.SEARCHING);
        }
    }

    // ---------------------------------------------------------------- bolla

    /** Bolla: tocco = pausa, doppio tocco = punteggio/sopravvivenza, trascina = sposta, pressione lunga = chiudi. */
    class Bubble extends View {
        static final int SEARCHING = 0, THINKING = 1, ACTIVE = 2, PAUSED = 3;
        private int state = SEARCHING;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private int startX, startY;
        private boolean moved, longFired, pendingSingle;
        private final Runnable singleTap = () -> { pendingSingle = false; togglePause(); };
        private final Runnable longPress = () -> {
            longFired = true;
            host.onCloseRequested();
        };

        Bubble(Context c) {
            super(c);
            fill.setColor(Color.argb(230, 25, 25, 35));
            ringP.setStyle(Paint.Style.STROKE);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setFakeBoldText(true);
        }

        void setState(int s) {
            if (state != s) { state = s; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), r = w / 2f;
            canvas.drawCircle(r, r, r * 0.92f, fill);
            int color;
            String txt;
            switch (state) {
                case ACTIVE: color = Color.rgb(0, 230, 118); txt = scoreMode() ? "★" : "✓"; break;
                case THINKING: color = Color.rgb(255, 171, 0); txt = "…"; break;
                case PAUSED: color = Color.GRAY; txt = "II"; break;
                default: color = Color.rgb(100, 160, 255); txt = "BB"; break;
            }
            ringP.setColor(color);
            ringP.setStrokeWidth(w * 0.08f);
            canvas.drawCircle(r, r, r * 0.80f, ringP);
            label.setTextSize(w * 0.34f);
            canvas.drawText(txt, r, r + w * 0.12f, label);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY();
                    startX = bubbleLp.x; startY = bubbleLp.y;
                    moved = false; longFired = false;
                    postDelayed(longPress, 800);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > getWidth() * 0.2f) {
                        moved = true;
                        removeCallbacks(longPress);
                    }
                    if (moved) {
                        bubbleLp.x = startX + Math.round(dx);
                        bubbleLp.y = startY + Math.round(dy);
                        wm.updateViewLayout(this, bubbleLp);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    removeCallbacks(longPress);
                    if (moved) {
                        // ricorda dove l'hai lasciata
                        prefs.edit().putInt("bubble_x", bubbleLp.x).putInt("bubble_y", bubbleLp.y).apply();
                    } else if (!longFired) {
                        if (pendingSingle) {          // secondo tocco: doppio tocco
                            removeCallbacks(singleTap);
                            pendingSingle = false;
                            toggleScoreMode();
                        } else {                      // aspetta un attimo un eventuale secondo tocco
                            pendingSingle = true;
                            postDelayed(singleTap, 280);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPress);
                    return true;
            }
            return false;
        }
    }
}
