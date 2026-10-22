package com.bbhelper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.file.Files;

/** Mostra l'ultimo errore (crash) con i pulsanti per copiarlo o segnalarlo. Usa solo viste semplici. */
public class CrashActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String text;
        try {
            text = new String(Files.readAllBytes(new File(getFilesDir(), App.CRASH_FILE).toPath()), "UTF-8");
        } catch (Exception e) {
            text = "(nessun dettaglio / no details)";
        }
        final String report = text;
        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(20 * d);
        col.setPadding(p, Math.round(48 * d), p, p);
        col.setBackgroundColor(Color.rgb(18, 18, 26));

        TextView title = new TextView(this);
        title.setText("BB Helper si è chiusa per un errore\nBB Helper stopped because of an error");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        Button copy = new Button(this);
        copy.setText("Copia errore / Copy error");
        copy.setAllCaps(false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("BB Helper crash", report));
            Toast.makeText(this, "Copiato / Copied", Toast.LENGTH_SHORT).show();
        });
        col.addView(copy);

        Button share = new Button(this);
        share.setText("Condividi / Share");
        share.setAllCaps(false);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "BB Helper crash")));
        col.addView(share);

        Button issue = new Button(this);
        issue.setText("Segnala su GitHub / Report on GitHub");
        issue.setAllCaps(false);
        issue.setOnClickListener(v -> {
            try { Updater.openIssue(this, report); } catch (Exception ignored) {}
        });
        col.addView(issue);

        TextView body = new TextView(this);
        body.setText(report);
        body.setTextColor(Color.rgb(220, 220, 230));
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(0, Math.round(16 * d), 0, 0);
        col.addView(body);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.rgb(18, 18, 26));
        sv.addView(col);
        setContentView(sv);
    }
}
