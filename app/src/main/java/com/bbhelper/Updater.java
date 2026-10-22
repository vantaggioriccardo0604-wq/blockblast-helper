package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aggiornamenti dentro l'app: controlla l'ultima versione su GitHub, la scarica e la installa
 * (Android chiede comunque conferma all'utente).
 */
public final class Updater {

    public static final String REPO = "vantaggioriccardo0604-wq/blockblast-helper";
    public static final String REPO_URL = "https://github.com/" + REPO;

    public interface Listener {
        /** Chiamato sul thread principale con un messaggio da mostrare. */
        void onStatus(String message, boolean busy);
    }

    private Updater() {}

    /** Confronta "1.0.18" con "1.0.9" numero per numero. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Controlla e, se c'e' una versione nuova, la scarica e avvia l'installazione. */
    public static void checkAndInstall(Activity act, Listener l) {
        Handler ui = new Handler(Looper.getMainLooper());
        l.onStatus(t("Controllo aggiornamenti…", "Checking for updates…"), true);
        new Thread(() -> {
            try {
                JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + REPO + "/releases/latest"));
                Matcher m = Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(rel.optString("name", ""));
                String latest = m.find() ? m.group(1) : "0";
                String mine = currentVersion(act);
                if (compare(latest, mine) <= 0) {
                    ui.post(() -> l.onStatus(t("Hai già l'ultima versione (" + mine + ") ✓",
                            "You already have the latest version (" + mine + ") ✓"), false));
                    return;
                }
                String apk = null;
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) apk = a.getString("browser_download_url");
                }
                if (apk == null) throw new Exception("APK not found");
                if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                    ui.post(() -> {
                        l.onStatus(t("Consenti a BB Helper di installare app, poi tocca di nuovo \"Aggiorna\"",
                                "Allow BB Helper to install apps, then tap \"Update\" again"), false);
                        act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName())));
                    });
                    return;
                }
                ui.post(() -> l.onStatus(t("Scarico la versione " + latest + "…", "Downloading version " + latest + "…"), true));
                install(act, apk);
                ui.post(() -> l.onStatus(t("Conferma l'installazione della versione " + latest,
                        "Confirm the installation of version " + latest), false));
            } catch (Exception e) {
                Diag.error("update", e);
                ui.post(() -> l.onStatus(t("Impossibile controllare gli aggiornamenti. Riprova più tardi o scarica da GitHub.",
                        "Couldn't check for updates. Try later or download from GitHub."), false));
            }
        }).start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Scarica l'APK direttamente in una sessione di installazione di Android. */
    private static void install(Context ctx, String url) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("app.apk", 0, -1)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            } finally {
                c.disconnect();
            }
            Intent done = new Intent(ctx, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi2 = PendingIntent.getBroadcast(ctx, id, done, flags);
            s.commit(pi2.getIntentSender());
        }
    }

    /** Riceve l'esito dell'installazione: se serve la conferma dell'utente, la mostra. */
    public static class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(ctx, t("Aggiornamento non riuscito: ", "Update failed: ") + msg, Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Apre su GitHub una nuova segnalazione gia' compilata con il rapporto. */
    public static void openIssue(Context ctx, String report) {
        String body = report.length() > 5500 ? report.substring(0, 5500) + "\n…" : report;
        body = t("**Cosa è successo?**\n\n(scrivi qui)\n\n**Rapporto automatico**\n```\n",
                "**What happened?**\n\n(write here)\n\n**Automatic report**\n```\n") + body + "\n```\n";
        Uri uri = Uri.parse(REPO_URL + "/issues/new").buildUpon()
                .appendQueryParameter("labels", "bug")
                .appendQueryParameter("title", t("Problema: ", "Problem: "))
                .appendQueryParameter("body", body).build();
        ctx.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
