package com.neymis.codex;

import android.app.Application;

public class CodexApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(getApplicationContext());
    }
}
