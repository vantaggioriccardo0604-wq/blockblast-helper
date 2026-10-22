package com.bbhelper;

import java.util.Locale;

/** Testi in due lingue: italiano se il telefono e' in italiano, altrimenti inglese. */
public final class I18n {

    private I18n() {}

    public static boolean italian() {
        return "it".equals(Locale.getDefault().getLanguage());
    }

    /** Sceglie il testo nella lingua del telefono. */
    public static String t(String it, String en) {
        return italian() ? it : en;
    }
}
