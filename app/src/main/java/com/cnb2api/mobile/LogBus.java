package com.cnb2api.mobile;

import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Small process-local log bus. The service owns the log; the activity only renders it. */
public final class LogBus {
    public static final String ACTION_LOG = "com.cnb2api.mobile.LOG";
    public static final String ACTION_STATUS = "com.cnb2api.mobile.STATUS";
    public static final String EXTRA_LINE = "line";
    public static final String EXTRA_RUNNING = "running";
    private static final int MAX_LINES = 1200;
    private static final List<String> lines = new ArrayList<>();
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogBus() {}

    public static void clear() {
        synchronized (lines) {
            lines.clear();
        }
    }

    public static void log(Context context, String message) {
        String line = FORMAT.format(new Date()) + "  " + message;
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > MAX_LINES) lines.remove(0);
        }
        Intent intent = new Intent(ACTION_LOG).setPackage(context.getPackageName());
        intent.putExtra(EXTRA_LINE, line);
        context.sendBroadcast(intent);
    }

    public static void status(Context context, boolean running) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(context.getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        context.sendBroadcast(intent);
    }

    public static String snapshot() {
        synchronized (lines) {
            StringBuilder out = new StringBuilder();
            for (String line : lines) out.append(line).append('\n');
            return out.toString();
        }
    }
}
