package com.bbhelper;

import android.content.Context;

/** Salva e carica la lingua scelta nelle impostazioni ("auto", "it", "en"). */
public final class Lang {

    public static final String P_LANG = "lang";

    private Lang() {}

    /** Da chiamare all'avvio di ogni schermata o servizio. */
    public static void load(Context c) {
        I18n.setMode(c.getSharedPreferences(HelperService.PREFS, Context.MODE_PRIVATE).getString(P_LANG, "auto"));
    }

    public static void set(Context c, String mode) {
        I18n.setMode(mode);
        c.getSharedPreferences(HelperService.PREFS, Context.MODE_PRIVATE).edit().putString(P_LANG, mode).apply();
    }
}
