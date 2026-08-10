package com.ourlauncher;

import android.app.Application;

public class OurLauncherApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
    }
}
