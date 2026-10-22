package com.bbhelper;

import android.app.Application;
import android.content.Intent;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Se l'app va in crash, invece di chiudersi in silenzio salva l'errore e lo mostra
 * in una schermata apposita (CrashActivity), da cui si puo' copiare o segnalare.
 */
public class App extends Application {

    public static final String CRASH_FILE = "ultimo_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String text = "BB Helper " + Updater.currentVersion(this) + " · Android "
                        + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MANUFACTURER + " "
                        + android.os.Build.MODEL + "\nThread: " + thread.getName() + "\n\n" + sw;
                try (FileWriter fw = new FileWriter(new java.io.File(getFilesDir(), CRASH_FILE))) {
                    fw.write(text);
                }
                Intent i = new Intent(this, CrashActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            } catch (Throwable ignored) {
                if (previous != null) previous.uncaughtException(thread, ex);
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });
    }
}
