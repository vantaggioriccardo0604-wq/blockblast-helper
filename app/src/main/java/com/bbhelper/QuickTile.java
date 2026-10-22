package com.bbhelper;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Pulsante nelle impostazioni rapide (tendina): accende e spegne l'aiuto. */
public class QuickTile extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        if (A11yService.isOn()) {
            // modalita' automatica: il pulsante accende/spegne l'aiuto senza richieste
            android.content.SharedPreferences p = getSharedPreferences(HelperService.PREFS, MODE_PRIVATE);
            p.edit().putBoolean(HelperService.P_AUTO_OFF, !p.getBoolean(HelperService.P_AUTO_OFF, false)).apply();
            refresh();
            return;
        }
        if (HelperService.running) {
            startService(new Intent(this, HelperService.class).setAction(HelperService.ACTION_STOP));
            HelperService.running = false;
            refresh();
            return;
        }
        // serve il permesso di registrare lo schermo: lo chiede l'app, che poi si chiude da sola
        Intent i = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pi = PendingIntent.getActivity(this, 3, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pi);
        } else {
            startActivityAndCollapseCompat(i);
        }
    }

    @SuppressWarnings("deprecation")
    private void startActivityAndCollapseCompat(Intent i) {
        startActivityAndCollapse(i);
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = A11yService.isOn()
                ? !getSharedPreferences(HelperService.PREFS, MODE_PRIVATE).getBoolean(HelperService.P_AUTO_OFF, false)
                : HelperService.running;
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("BB Helper");
        t.updateTile();
    }
}
