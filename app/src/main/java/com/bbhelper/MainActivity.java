package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    public static final String EXTRA_AUTOSTART = "autostart";
    public static final String P_ONBOARDED = "onboarded_v1";
    private static final int REQ_CAPTURE = 7;
    private static final int REQ_NOTIF = 8;

    static final int BG = Color.rgb(18, 18, 26);
    static final int TEXT2 = Color.rgb(200, 200, 215);
    static final int MUTED = Color.rgb(150, 150, 170);
    static final int GREEN = Color.rgb(0, 200, 110);
    static final int GREY = Color.rgb(60, 60, 75);
    static final int BLUE = Color.rgb(60, 90, 160);
    static final int CARD = Color.rgb(28, 30, 44);

    private Button startBtn;
    private TextView status, statsView, autoInfo, diagView, updateStatus;
    private SharedPreferences prefs;
    private LinearLayout col;   // contenitore in cui si aggiungono gli elementi (pagina o scheda)
    private LinearLayout root;  // la pagina
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(HelperService.PREFS, MODE_PRIVATE);
        Lang.load(this);
        if (!prefs.getBoolean(P_ONBOARDED, false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        d = getResources().getDisplayMetrics().density;
        int pad = dp(24);

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(48), pad, pad);
        col.setBackgroundColor(BG);

        root = col;
        // intestazione: icona + nome
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        head.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("BB Helper", 30, Color.WHITE, true);
        title.setPadding(dp(14), 0, 0, 0);
        head.addView(title);
        col.addView(head);
        TextView sub = text(t("Ti mostra dove mettere i pezzi in Block Blast, direttamente sopra il gioco.",
                "Shows you where to place the pieces in Block Blast, right on top of the game."), 16,
                Color.rgb(170, 170, 190), false);
        sub.setPadding(0, dp(6), 0, dp(4));
        col.addView(sub);
        TextView unofficial = text(t("Progetto non ufficiale, non affiliato con gli sviluppatori di Block Blast.",
                "Unofficial project, not affiliated with the developers of Block Blast."), 12, MUTED, false);
        unofficial.setPadding(0, 0, 0, dp(22));
        col.addView(unofficial);

        startBtn = button(t("Avvia aiuto", "Start helper"), GREEN, 56, 0);
        startBtn.setOnClickListener(v -> start());
        Button stop = button(t("Ferma", "Stop"), GREY, 56, 12);
        stop.setOnClickListener(v -> {
            startService(new Intent(this, HelperService.class).setAction(HelperService.ACTION_STOP));
            if (A11yService.isOn()) prefs.edit().putBoolean(HelperService.P_AUTO_OFF, true).apply();
            Toast.makeText(this, t("Aiuto fermato", "Helper stopped"), Toast.LENGTH_SHORT).show();
            refresh();
        });
        status = text("", 15, Color.rgb(120, 220, 160), false);
        status.setPadding(0, dp(12), 0, 0);
        col.addView(status);

        // ---- modalita' automatica
        heading(t("Modalità automatica (consigliata)", "Automatic mode (recommended)"));
        autoInfo = text("", 15, TEXT2, false);
        autoInfo.setLineSpacing(0, 1.2f);
        col.addView(autoInfo);
        Button autoBtn = button(t("Apri impostazioni Accessibilità", "Open Accessibility settings"),
                Color.rgb(120, 70, 200), 52, 12);
        autoBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        Button forget = button(t("Reimposta app del gioco", "Reset game app"), GREY, 48, 8);
        forget.setOnClickListener(v -> {
            prefs.edit().remove(HelperService.P_GAME_PKG).apply();
            Toast.makeText(this, t("Apri Block Blast: lo riconoscerà di nuovo",
                    "Open Block Blast: it will be recognized again"), Toast.LENGTH_SHORT).show();
            refresh();
        });

        // ---- impostazioni
        heading(t("Impostazioni", "Settings"));
        col.addView(makeSwitch(t("Una mossa alla volta", "One move at a time"),
                t("Mostra solo la prossima mossa; dopo averla fatta compare la successiva",
                        "Shows only the next move; after you play it, the following one appears"),
                HelperService.P_STEP, true));
        col.addView(makeSwitch(t("Risparmio batteria", "Battery saver"),
                t("Controlla lo schermo meno spesso quando la partita è ferma",
                        "Checks the screen less often when the game is idle"),
                HelperService.P_SAVER, true));
        TextView opLabel = text("", 16, Color.WHITE, false);
        opLabel.setPadding(0, dp(14), 0, 0);
        col.addView(opLabel);
        SeekBar op = new SeekBar(this);
        op.setMax(50); // 30%..80%
        int cur = Math.max(30, Math.min(80, prefs.getInt(HelperService.P_OPACITY, 80)));
        op.setProgress(cur - 30);
        opLabel.setText(t("Visibilità suggerimenti: ", "Hint visibility: ") + cur + "%");
        op.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean user) {
                opLabel.setText(t("Visibilità suggerimenti: ", "Hint visibility: ") + (v + 30) + "%");
                prefs.edit().putInt(HelperService.P_OPACITY, v + 30).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        col.addView(op);

        // lingua
        TextView langLabel = text(t("Lingua", "Language"), 16, Color.WHITE, false);
        langLabel.setPadding(0, dp(14), 0, dp(4));
        col.addView(langLabel);
        android.widget.RadioGroup langs = new android.widget.RadioGroup(this);
        langs.setOrientation(LinearLayout.HORIZONTAL);
        String[][] opts = {{"auto", t("Automatica", "Automatic")}, {"it", "Italiano"}, {"en", "English"}};
        for (String[] o : opts) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(o[1]);
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(15);
            rb.setTag(o[0]);
            rb.setId(View.generateViewId());
            langs.addView(rb, new android.widget.RadioGroup.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (o[0].equals(I18n.mode())) rb.setChecked(true);
        }
        langs.setOnCheckedChangeListener((g, id) -> {
            String m = (String) g.findViewById(id).getTag();
            if (m.equals(I18n.mode())) return;
            Lang.set(this, m);
            recreate(); // ridisegna la schermata nella nuova lingua
        });
        col.addView(langs);

        // ---- statistiche
        heading(t("Statistiche", "Statistics"));
        statsView = text("", 15, TEXT2, false);
        statsView.setLineSpacing(0, 1.25f);
        col.addView(statsView);
        Button dash = button(t("Apri dashboard 📊", "Open dashboard 📊"), Color.rgb(0, 160, 95), 52, 14);
        dash.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));

        // ---- aggiornamenti e problemi
        heading(t("Aggiornamenti e problemi", "Updates and problems"));
        updateStatus = text(t("Versione installata: ", "Installed version: ") + Updater.currentVersion(this),
                15, TEXT2, false);
        col.addView(updateStatus);
        Button upd = button(t("Aggiorna", "Update"), GREEN, 52, 12);
        upd.setOnClickListener(v -> Updater.checkAndInstall(this, (msg, busy) -> {
            updateStatus.setText(msg);
            upd.setEnabled(!busy);
            upd.setAlpha(busy ? 0.5f : 1f);
        }));
        Button issue = button(t("Segnala un problema su GitHub", "Report a problem on GitHub"), BLUE, 52, 10);
        issue.setOnClickListener(v -> Updater.openIssue(this, Diag.report(deviceHeader())));
        Button rep = button(t("Invia rapporto (altre app)", "Send report (other apps)"), GREY, 52, 10);
        rep.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, t("Rapporto BB Helper", "BB Helper report"))
                    .putExtra(Intent.EXTRA_TEXT, Diag.report(deviceHeader()));
            startActivity(Intent.createChooser(send, t("Invia rapporto", "Send report")));
        });
        Button site = button(t("Pagina del progetto", "Project page"), GREY, 48, 10);
        site.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.REPO_URL))));

        // ---- diagnostica
        heading(t("Diagnostica", "Diagnostics"));
        diagView = text("", 14, TEXT2, false);
        diagView.setTypeface(Typeface.MONOSPACE);
        diagView.setLineSpacing(0, 1.2f);
        col.addView(diagView);

        // ---- guida
        heading(t("Come funziona", "How it works"));
        TextView help = text(t(
                "1. Tocca \"Avvia aiuto\" e consenti \"Mostra sopra altre app\" se richiesto.\n"
                        + "2. Alla richiesta di registrazione dello schermo scegli \"Schermo intero\".\n"
                        + "3. Apri Block Blast: sopra la griglia compaiono i riquadri colorati.\n\n"
                        + "Il numero sopra un pezzo in basso indica quale prendere; i riquadri dello "
                        + "stesso colore sulla griglia indicano dove metterlo. Con \"Una mossa alla volta\" "
                        + "vedi solo la prossima; altrimenti verde 1 → arancio 2 → viola 3.\n\n"
                        + "Il pallino colorato in alto indica quanto è probabile che i prossimi 3 pezzi "
                        + "entrino ancora: verde sicuro, giallo attenzione, rosso pericolo.\n\n"
                        + "La bolla flottante:\n"
                        + "• tocca = pausa / riprendi\n"
                        + "• doppio tocco = modalità punteggio ★ (combo) / sopravvivenza ✓\n"
                        + "• trascina = spostala\n"
                        + "• tieni premuto = chiudi l'aiuto\n\n"
                        + "Colore della bolla: blu = cerca la griglia, arancio = sta calcolando, "
                        + "verde = suggerimento pronto.\n\n"
                        + "Avvio rapido: aggiungi il pulsante \"BB Helper\" nelle impostazioni rapide "
                        + "(tendina delle notifiche → matita/modifica).\n\n"
                        + "Privacy: l'app guarda lo schermo solo per riconoscere la partita. Le immagini "
                        + "non vengono salvate e nessun dato esce dal telefono.",
                "1. Tap \"Start helper\" and allow \"Display over other apps\" if asked.\n"
                        + "2. When asked to record the screen, choose \"Entire screen\".\n"
                        + "3. Open Block Blast: colored boxes appear on top of the board.\n\n"
                        + "The number above a piece at the bottom tells you which one to take; the boxes "
                        + "of the same color on the board show where to put it. With \"One move at a time\" "
                        + "you only see the next one; otherwise green 1 → orange 2 → purple 3.\n\n"
                        + "The colored dot at the top shows how likely the next 3 pieces are to still "
                        + "fit: green safe, yellow careful, red danger.\n\n"
                        + "The floating bubble:\n"
                        + "• tap = pause / resume\n"
                        + "• double tap = score mode ★ (combos) / survival ✓\n"
                        + "• drag = move it\n"
                        + "• long press = close the helper\n\n"
                        + "Bubble color: blue = looking for the board, orange = thinking, "
                        + "green = hint ready.\n\n"
                        + "Quick start: add the \"BB Helper\" tile to Quick Settings "
                        + "(notification shade → pencil/edit).\n\n"
                        + "Privacy: the app looks at the screen only to recognize the game. Images are "
                        + "never saved and no data leaves your phone."), 15, TEXT2, false);
        help.setLineSpacing(0, 1.2f);
        col.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(col);
        setContentView(sv);
        getWindow().setStatusBarColor(BG);
        handleAutostart(getIntent());
    }

    // ------------------------------------------------------------------ mattoncini

    private int dp(float v) { return Math.round(v * d); }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    /** Inizia una nuova scheda con titolo: gli elementi successivi finiscono dentro di lei. */
    private TextView heading(String s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        root.addView(card, lp);
        col = card;
        TextView h = text(s, 19, Color.WHITE, true);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);
        return h;
    }

    /** Pulsante gia' aggiunto alla colonna. */
    private Button button(String label, int color, int heightDp, int topMarginDp) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(17);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(16 * d);
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(topMarginDp);
        col.addView(btn, lp);
        return btn;
    }

    private LinearLayout makeSwitch(String title, String desc, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(Color.WHITE);
        sw.setTextSize(16);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean(key, on).apply());
        row.addView(sw);
        row.addView(text(desc, 13, MUTED, false));
        return row;
    }

    private String deviceHeader() {
        return t("Versione app: ", "App version: ") + Updater.currentVersion(this)
                + "\n" + t("Telefono: ", "Phone: ") + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\n" + t("Modalità: ", "Mode: ") + (A11yService.isOn() ? t("automatica (Accessibilità)", "automatic (Accessibility)")
                : HelperService.running ? t("registrazione schermo", "screen recording") : t("spenta", "off"))
                + "\n" + t("Schermo: ", "Screen: ") + getResources().getDisplayMetrics().widthPixels + "x"
                + getResources().getDisplayMetrics().heightPixels;
    }

    // ------------------------------------------------------------------ azioni

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutostart(intent);
    }

    private void handleAutostart(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            intent.removeExtra(EXTRA_AUTOSTART);
            start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        diagView.setText(Diag.summary());
        long placed = prefs.getLong(HelperService.S_PLACED, 0);
        long followed = prefs.getLong(HelperService.S_FOLLOWED, 0);
        statsView.setText(t("Partite: ", "Games: ") + prefs.getLong(HelperService.S_GAMES, 0)
                + "\n" + t("Pezzi piazzati: ", "Pieces placed: ") + placed
                + "\n" + t("Linee fatte: ", "Lines cleared: ") + prefs.getLong(HelperService.S_LINES, 0)
                + "\n" + t("Mosse come suggerito: ", "Moves as suggested: ")
                + (placed == 0 ? "-" : (followed * 100 / placed) + "%")
                + "\n" + t("Terne di pezzi registrate: ", "Piece sets recorded: ") + prefs.getLong(HelperService.S_TRIPLES, 0));
        boolean auto = A11yService.isOn();
        String game = prefs.getString(HelperService.P_GAME_PKG, null);
        if (auto) {
            autoInfo.setText(t("● Attiva. L'aiuto compare da solo quando apri Block Blast, senza richieste.",
                    "● On. The helper appears by itself when you open Block Blast, no prompts.")
                    + (game == null ? t("\nApri Block Blast una volta perché la riconosca.", "\nOpen Block Blast once so it can recognize it.")
                    : t("\nGioco riconosciuto ✓", "\nGame recognized ✓")));
            autoInfo.setTextColor(Color.rgb(120, 220, 160));
        } else if (Build.VERSION.SDK_INT < 30) {
            autoInfo.setText(t("Richiede Android 11 o più recente.", "Requires Android 11 or newer."));
            autoInfo.setTextColor(TEXT2);
        } else {
            autoInfo.setText(t(
                    "Si attiva una volta sola e poi niente più richieste di registrare lo schermo.\n\n"
                            + "1. Tocca il pulsante qui sotto.\n"
                            + "2. Cerca \"BB Helper (modalità automatica)\" (a volte è in \"App installate\" o \"Servizi scaricati\") e attivalo.\n\n"
                            + "Se l'interruttore è grigio o dice \"impostazione limitata\": vai in Impostazioni → App → BB Helper → "
                            + "⋮ in alto a destra → \"Consenti impostazioni limitate\", poi riprova.",
                    "Turn it on once and you'll never be asked to record the screen again.\n\n"
                            + "1. Tap the button below.\n"
                            + "2. Find \"BB Helper (automatic mode)\" (sometimes under \"Installed apps\" or \"Downloaded services\") and turn it on.\n\n"
                            + "If the switch is grey or says \"restricted setting\": go to Settings → Apps → BB Helper → "
                            + "⋮ top right → \"Allow restricted settings\", then try again."));
            autoInfo.setTextColor(TEXT2);
        }
        boolean on = auto ? !prefs.getBoolean(HelperService.P_AUTO_OFF, false) : HelperService.running;
        status.setText(on ? (auto ? t("● Aiuto attivo (automatico)", "● Helper on (automatic)")
                : t("● Aiuto attivo", "● Helper on")) : "");
        startBtn.setAlpha(on ? 0.5f : 1f);
    }

    private void start() {
        if (A11yService.isOn()) {
            prefs.edit().putBoolean(HelperService.P_AUTO_OFF, false).apply();
            Toast.makeText(this, t("Modalità automatica attiva: apri Block Blast",
                    "Automatic mode on: open Block Blast"), Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        if (HelperService.running) {
            Toast.makeText(this, t("È già attivo: apri Block Blast", "Already on: open Block Blast"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, t("Attiva \"Mostra sopra altre app\" per BB Helper, poi torna qui",
                    "Enable \"Display over other apps\" for BB Helper, then come back"), Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return; // riprende in onRequestPermissionsResult
        }
        askCapture();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_NOTIF) askCapture(); // la notifica e' facoltativa
    }

    private void askCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, t("Serve il permesso di registrare lo schermo", "Screen recording permission is needed"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, HelperService.class)
                .putExtra(HelperService.EXTRA_CODE, result)
                .putExtra(HelperService.EXTRA_DATA, data);
        startForegroundService(svc);
        // torna alla schermata home: l'utente apre il gioco
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }
}
