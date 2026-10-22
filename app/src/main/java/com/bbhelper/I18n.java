package com.bbhelper;

import java.util.Locale;

/**
 * Testi in due lingue. Di base segue la lingua del telefono (italiano se il telefono e' in
 * italiano, altrimenti inglese); dalle impostazioni si puo' scegliere a mano (vedi {@link Lang}).
 * Nessuna dipendenza Android: la usano anche i test.
 */
public final class I18n {

    private static volatile String mode = "auto"; // "auto", "it", "en"

    private I18n() {}

    public static void setMode(String m) {
        mode = m == null ? "auto" : m;
    }

    public static String mode() {
        return mode;
    }

    public static boolean italian() {
        if ("it".equals(mode)) return true;
        if ("en".equals(mode)) return false;
        return "it".equals(Locale.getDefault().getLanguage());
    }

    /** Sceglie il testo nella lingua giusta. */
    public static String t(String it, String en) {
        return italian() ? it : en;
    }
}
