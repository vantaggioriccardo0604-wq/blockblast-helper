package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Prima apertura: 3 schermate che spiegano cosa fa l'app e chiedono un consenso esplicito
 * all'uso dello schermo (nessun dato esce dal telefono).
 */
public class OnboardingActivity extends Activity {

    private int page = 0;
    private float d;

    private static final String[][] PAGES = {
            {"👋", "Benvenuto in BB Helper", "Welcome to BB Helper",
                    "Mentre giochi a Block Blast, BB Helper ti mostra dove mettere i pezzi, con riquadri colorati "
                            + "direttamente sopra la griglia.\n\nCalcola la mossa migliore pensando anche ai pezzi che "
                            + "potrebbero arrivare dopo, e ti dice con un pallino colorato quanto è rischiosa la situazione.",
                    "While you play Block Blast, BB Helper shows you where to place the pieces, with colored boxes "
                            + "right on top of the board.\n\nIt picks the best move while thinking about the pieces that "
                            + "might come next, and a colored dot tells you how risky things are."},
            {"🔒", "La tua privacy", "Your privacy",
                    "Per riconoscere la partita, BB Helper deve guardare lo schermo mentre giochi.\n\n"
                            + "• Le immagini vengono analizzate sul momento e subito scartate: non vengono mai salvate.\n"
                            + "• Nessun dato esce dal telefono: l'app usa internet solo se tocchi tu \"Aggiorna\".\n"
                            + "• Sul telefono restano solo le statistiche di gioco (pezzi, mosse, linee). Puoi "
                            + "cancellarle quando vuoi da Impostazioni → App → BB Helper → Spazio → Cancella dati.\n"
                            + "• Con la modalità automatica (Accessibilità) l'app non legge testi, password o "
                            + "contenuti delle altre app: si attiva solo dentro il gioco.",
                    "To recognize the game, BB Helper has to look at the screen while you play.\n\n"
                            + "• Images are analyzed on the spot and thrown away right after: they are never saved.\n"
                            + "• No data leaves your phone: the app only uses the internet when you tap \"Update\".\n"
                            + "• Only game statistics (pieces, moves, lines) stay on the phone. You can delete them "
                            + "any time from Settings → Apps → BB Helper → Storage → Clear data.\n"
                            + "• In automatic mode (Accessibility) the app does not read texts, passwords or the content "
                            + "of other apps: it only turns on inside the game."},
            {"🚀", "Come iniziare", "Getting started",
                    "Hai due modi per usarla:\n\n"
                            + "• Modalità automatica (consigliata): la attivi una volta nelle impostazioni Accessibilità "
                            + "e da lì in poi l'aiuto compare da solo quando apri il gioco.\n\n"
                            + "• Avvia aiuto: ogni volta Android ti chiede il permesso di registrare lo schermo.\n\n"
                            + "BB Helper è un progetto non ufficiale e gratuito, non affiliato con gli sviluppatori "
                            + "di Block Blast.",
                    "There are two ways to use it:\n\n"
                            + "• Automatic mode (recommended): turn it on once in Accessibility settings and from then "
                            + "on the helper appears by itself when you open the game.\n\n"
                            + "• Start helper: Android asks for screen recording permission every time.\n\n"
                            + "BB Helper is a free, unofficial project, not affiliated with the developers of "
                            + "Block Blast."},
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(MainActivity.BG);
        render();
    }

    @Override
    public void onBackPressed() {
        if (page > 0) { page--; render(); }
        else super.onBackPressed();
    }

    private int dp(float v) { return Math.round(v * d); }

    private void render() {
        String[] p = PAGES[page];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(72), dp(28), dp(28));
        col.setBackgroundColor(MainActivity.BG);

        TextView dots = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PAGES.length; i++) sb.append(i == page ? "●  " : "○  ");
        dots.setText(sb.toString().trim());
        dots.setTextColor(MainActivity.GREEN);
        dots.setTextSize(14);
        col.addView(dots);

        TextView icon = new TextView(this);
        icon.setText(p[0]);
        icon.setTextSize(56);
        icon.setPadding(0, dp(28), 0, dp(8));
        col.addView(icon);

        TextView title = new TextView(this);
        title.setText(t(p[1], p[2]));
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView body = new TextView(this);
        body.setText(t(p[3], p[4]));
        body.setTextColor(MainActivity.TEXT2);
        body.setTextSize(17);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, dp(16), 0, dp(24));
        col.addView(body);

        Button next = new Button(this);
        next.setAllCaps(false);
        next.setTextColor(Color.WHITE);
        next.setTextSize(18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MainActivity.GREEN);
        bg.setCornerRadius(16 * d);
        next.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56));

        if (page == 1) {
            // consenso esplicito: separato e obbligatorio per andare avanti
            CheckBox ok = new CheckBox(this);
            ok.setText(t("Ho capito: BB Helper guarda lo schermo solo per riconoscere la partita, "
                            + "e accetto che lo faccia.",
                    "I understand: BB Helper looks at the screen only to recognize the game, and I agree."));
            ok.setTextColor(Color.WHITE);
            ok.setTextSize(15);
            ok.setPadding(dp(4), dp(8), 0, dp(8));
            col.addView(ok);
            next.setEnabled(false);
            next.setAlpha(0.4f);
            ok.setOnCheckedChangeListener((v, on) -> { next.setEnabled(on); next.setAlpha(on ? 1f : 0.4f); });
            lp.topMargin = dp(16);
        }
        boolean last = page == PAGES.length - 1;
        next.setText(last ? t("Inizia", "Let's go") : t("Avanti", "Next"));
        next.setOnClickListener(v -> {
            if (last) {
                getSharedPreferences(HelperService.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(MainActivity.P_ONBOARDED, true).apply();
                finish();
            } else {
                page++;
                render();
            }
        });
        col.addView(next, lp);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(MainActivity.BG);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
    }
}
