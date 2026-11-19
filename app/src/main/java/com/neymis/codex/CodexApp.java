package com.neymis.codex;

import com.neymis.core.CrashLog;
import android.app.Application;

public class CodexApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(getApplicationContext());
        CrashLog.export(getApplicationContext(), "NeymisCodex", "codex-crash.txt");
    }
}
