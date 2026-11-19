package com.neymis.codex;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CrashLog {

    private CrashLog() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), "last_crash.txt");
    }

    static String read(Context context) {
        try {
            File file = file(context);
            if (!file.exists()) return "";
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static void clear(Context context) {
        try {
            file(context).delete();
        } catch (Exception ignored) {
        }
    }

    static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    StringWriter writer = new StringWriter();
                    error.printStackTrace(new PrintWriter(writer));
                    String header = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                            .format(new Date()) + "  [" + thread.getName() + "]\n\n";
                    FileOutputStream out = new FileOutputStream(file(context));
                    out.write((header + writer.toString()).getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }
}
