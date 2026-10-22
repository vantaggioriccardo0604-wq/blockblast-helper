package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;

/**
 * Modalita' "registrazione dello schermo": Android chiede il permesso a ogni avvio.
 * (L'alternativa senza richieste e' {@link A11yService}.)
 */
public class HelperService extends Service implements Engine.Host {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.bbhelper.STOP";
    private static final String CHANNEL = "helper";
    public static final String PREFS = "bbhelper";
    public static final String P_STEP = "step_mode";
    public static final String P_OPACITY = "opacity";   // 30..80 (%)
    public static final String P_SAVER = "battery_saver";
    public static final String P_SCORE = "score_mode";
    public static final String P_AUTO_OFF = "auto_off"; // modalita' automatica spenta dal pulsante rapido
    public static final String P_GAME_PKG = "game_pkg";
    public static final String S_PLACED = "st_placed", S_LINES = "st_lines", S_FOLLOWED = "st_followed",
            S_GAMES = "st_games", S_TRIPLES = "st_triples", S_RESET = "st_reset";
    public static final String LOG_FILE = "pezzi.csv";

    public static volatile boolean running = false;

    private Engine engine;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private byte[] rowBuf;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // gia' attivo

        startInForeground();

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            Toast.makeText(this, t("Permesso di cattura non valido, riprova", "Screen capture permission not valid, try again"), Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                main.post(HelperService.this::stopSelf);
            }
        }, main);

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        engine = new Engine(this, wm, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, this);
        reader = ImageReader.newInstance(engine.capW, engine.capH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("bbhelper", engine.capW, engine.capH, engine.densityDpi(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        engine.start(this::grab);
        running = true;
        refreshTile(this);
        Toast.makeText(this, t("Aiuto attivo: apri Block Blast", "Helper on: open Block Blast"), Toast.LENGTH_SHORT).show();
        return START_NOT_STICKY;
    }

    @Override
    public void onCloseRequested() {
        Toast.makeText(this, t("Aiuto chiuso", "Helper closed"), Toast.LENGTH_SHORT).show();
        stopSelf();
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, t("Aiuto BB Helper", "BB Helper"),
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, HelperService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pOpen = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(t("BB Helper attivo", "BB Helper is on"))
                .setContentText(t("Tocca la bolla per mettere in pausa", "Tap the bubble to pause"))
                .setContentIntent(pOpen)
                .addAction(new Notification.Action.Builder(null, t("Ferma", "Stop"), pStop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    private boolean grab(int[] pixels, int capW, int capH) {
        Image img = reader.acquireLatestImage();
        if (img == null) return false;
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int ps = p.getPixelStride(), rs = p.getRowStride();
            int w = Math.min(capW, img.getWidth()), h = Math.min(capH, img.getHeight());
            if (rowBuf == null || rowBuf.length < rs) rowBuf = new byte[rs];
            for (int y = 0; y < h; y++) {
                buf.position(y * rs);
                buf.get(rowBuf, 0, Math.min(rs, buf.remaining()));
                int o = y * capW;
                for (int x = 0, i = 0; x < w; x++, i += ps) {
                    pixels[o + x] = 0xff000000 | ((rowBuf[i] & 0xff) << 16)
                            | ((rowBuf[i + 1] & 0xff) << 8) | (rowBuf[i + 2] & 0xff);
                }
            }
            return true;
        } finally {
            img.close();
        }
    }

    static void refreshTile(Context c) {
        try {
            android.service.quicksettings.TileService.requestListeningState(c,
                    new ComponentName(c, QuickTile.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        running = false;
        if (engine != null) engine.stop();
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        projection = null;
        refreshTile(this);
        super.onDestroy();
    }
}
