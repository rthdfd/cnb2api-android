package com.cnb2api.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent settings shared by the activity and the foreground service. */
public final class AppSettings {
    private static final String FILE = "cnb2api.settings";

    public String listenHost = "0.0.0.0";
    public int port = 7863;
    public String apiKey = "";
    public String model = "deepseek-v4-flash";
    public int poolMin = 2;
    public int poolMax = 8;
    public int ttlMinutes = 30;
    public boolean forcePromptTools = true;

    public static AppSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.listenHost = p.getString("listenHost", s.listenHost);
        s.port = p.getInt("port", s.port);
        s.apiKey = p.getString("apiKey", s.apiKey);
        s.model = p.getString("model", s.model);
        s.poolMin = p.getInt("poolMin", s.poolMin);
        s.poolMax = p.getInt("poolMax", s.poolMax);
        s.ttlMinutes = p.getInt("ttlMinutes", s.ttlMinutes);
        s.forcePromptTools = p.getBoolean("forcePromptTools", s.forcePromptTools);
        return s;
    }

    public void save(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("listenHost", listenHost)
                .putInt("port", port)
                .putString("apiKey", apiKey)
                .putString("model", model)
                .putInt("poolMin", poolMin)
                .putInt("poolMax", poolMax)
                .putInt("ttlMinutes", ttlMinutes)
                .putBoolean("forcePromptTools", forcePromptTools)
                .apply();
    }

    public void normalize() {
        if (listenHost == null || listenHost.trim().isEmpty()) listenHost = "0.0.0.0";
        port = Math.max(1024, Math.min(65535, port));
        if (model == null || model.trim().isEmpty()) model = "deepseek-v4-flash";
        poolMin = Math.max(1, Math.min(16, poolMin));
        poolMax = Math.max(poolMin, Math.min(32, poolMax));
        ttlMinutes = Math.max(1, Math.min(1440, ttlMinutes));
    }
}
