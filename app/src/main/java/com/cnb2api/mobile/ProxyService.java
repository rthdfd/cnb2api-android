package com.cnb2api.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Keeps the local gateway alive after the activity is backgrounded. */
public final class ProxyService extends Service {
    public static final String ACTION_STOP = "com.cnb2api.mobile.STOP";
    private static final String CHANNEL = "cnb2api.service";
    private static volatile EmbeddedProxyServer server;
    private static volatile boolean running;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        startForeground(1, notification("准备启动服务"));
        if (server == null) startServerAsync();
        return START_STICKY;
    }

    private void startServerAsync() {
        AppSettings settings = AppSettings.load(this);
        settings.normalize();
        LogSink sink = message -> LogBus.log(this, message);
        EmbeddedProxyServer candidate = new EmbeddedProxyServer(settings, sink);
        server = candidate;
        new Thread(() -> {
            try {
                candidate.start();
                running = true;
                LogBus.status(this, true);
                updateNotification("服务运行中 :" + settings.port);
                LogBus.log(this, "[APP] local endpoint: http://<phone-ip>:" + settings.port + "/v1");
            } catch (Exception e) {
                running = false;
                server = null;
                LogBus.log(this, "[APP] startup failed: " + safeMessage(e));
                LogBus.status(this, false);
                stopSelf();
            }
        }, "cnb2api-start").start();
    }

    private void stopServer() {
        EmbeddedProxyServer active = server;
        server = null;
        running = false;
        if (active != null) active.stop();
        LogBus.status(this, false);
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // START_STICKY keeps the API service available when the UI task is swiped away.
        super.onTaskRemoved(rootIntent);
    }

    public static boolean isRunning() {
        return running;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "cnb2api service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Local OpenAI compatible gateway");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setContentTitle("cnb2api")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(1, notification(text));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
