package com.bbhelper;

import static com.bbhelper.I18n.t;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Modalita' automatica: servizio di Accessibilita'. Si attiva una volta sola nelle
 * Impostazioni; poi legge lo schermo senza richieste e mostra l'aiuto solo dentro il gioco.
 * Richiede Android 11+ (catture dello schermo per l'Accessibilita').
 */
public class A11yService extends AccessibilityService implements Engine.Host {

    public static volatile A11yService instance = null;

    private Engine engine;
    private SharedPreferences prefs;
    private volatile String currentPkg = null;
    private boolean dismissed = false; // chiuso dalla bolla: riappare al prossimo ingresso nel gioco
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (sp, key) -> {
        if (HelperService.P_AUTO_OFF.equals(key)) updateVisibility();
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, t("La modalità automatica richiede Android 11 o più recente",
                    "Automatic mode needs Android 11 or newer"), Toast.LENGTH_LONG).show();
            return;
        }
        instance = this;
        prefs = getSharedPreferences(HelperService.PREFS, MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(listener);
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        engine = new Engine(this, wm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, this);
        engine.setVisible(false);
        engine.start(this::grab);
        HelperService.refreshTile(this);
    }

    /** Il pulsante rapido e l'app usano questo per sapere se la modalita' automatica e' attiva. */
    public static boolean isOn() {
        return instance != null && instance.engine != null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        // contano solo il gioco, la schermata home e le app vere (con un'icona): tendina, tastiera,
        // pubblicita', finestre di sistema e la nostra app non cambiano "dove sei"
        String game = prefs.getString(HelperService.P_GAME_PKG, null);
        if (!pkg.equals(game) && !isHome(pkg) && !isRealApp(pkg)) return;
        if (pkg.equals(getPackageName())) return;
        if (!pkg.equals(currentPkg)) dismissed = false;
        currentPkg = pkg;
        updateVisibility();
    }

    private String homePkg = null;
    private final java.util.HashMap<String, Boolean> appCache = new java.util.HashMap<>();

    private boolean isHome(String pkg) {
        if (homePkg == null) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            homePkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
        }
        return pkg.equals(homePkg);
    }

    private boolean isRealApp(String pkg) {
        Boolean v = appCache.get(pkg);
        if (v == null) {
            v = !pkg.equals("android") && !pkg.equals("com.android.systemui")
                    && getPackageManager().getLaunchIntentForPackage(pkg) != null;
            appCache.put(pkg, v);
        }
        return v;
    }

    private void updateVisibility() {
        if (engine == null) return;
        String game = prefs.getString(HelperService.P_GAME_PKG, null);
        boolean off = prefs.getBoolean(HelperService.P_AUTO_OFF, false);
        // finche' non sa qual e' il gioco, prova ovunque (la bolla compare)
        boolean inGame = game == null || game.equals(currentPkg);
        engine.setVisible(!off && !dismissed && inGame);
    }

    @Override
    public void onBoardSeen() {
        // la prima volta che vede la griglia impara qual e' l'app del gioco
        if (prefs.getString(HelperService.P_GAME_PKG, null) == null && currentPkg != null) {
            prefs.edit().putString(HelperService.P_GAME_PKG, currentPkg).apply();
        }
    }

    @Override
    public long minIntervalMs() {
        return 340; // le catture dell'Accessibilita' hanno un intervallo minimo di ~1/3 di secondo
    }

    @Override
    public void onCloseRequested() {
        dismissed = true;
        Toast.makeText(this, t("Aiuto nascosto: riappare quando rientri nel gioco",
                "Helper hidden: it comes back when you reopen the game"), Toast.LENGTH_SHORT).show();
        updateVisibility();
    }

    private boolean grab(int[] pixels, int capW, int capH) {
        if (Build.VERSION.SDK_INT < 30) return false;
        final Bitmap[] out = new Bitmap[1];
        CountDownLatch done = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    HardwareBuffer hb = r.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            if (sw != null) {
                                if (sw.getWidth() != capW || sw.getHeight() != capH) {
                                    Bitmap sc = Bitmap.createScaledBitmap(sw, capW, capH, true);
                                    if (sc != sw) sw.recycle();
                                    sw = sc;
                                }
                                out[0] = sw;
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        hb.close();
                        done.countDown();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    done.countDown();
                }
            });
            if (!done.await(2, TimeUnit.SECONDS) || out[0] == null) return false;
            out[0].getPixels(pixels, 0, capW, 0, 0, capW, capH);
            out[0].recycle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (engine != null) engine.stop();
        engine = null;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(listener);
        instance = null;
        HelperService.refreshTile(this);
        super.onDestroy();
    }
}
