package com.cnb2api.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

/** Minimal control-room UI: start/stop, settings, and a selectable live log. */
public final class MainActivity extends Activity {
    private final int bg = Color.rgb(8, 16, 24);
    private final int surface = Color.rgb(17, 26, 36);
    private final int surfaceHigh = Color.rgb(24, 37, 54);
    private final int ink = Color.rgb(245, 247, 250);
    private final int muted = Color.rgb(147, 164, 184);
    private final int cyan = Color.rgb(88, 214, 209);
    private final int orange = Color.rgb(255, 184, 107);
    private final int red = Color.rgb(255, 113, 134);
    private TextView status;
    private TextView logView;
    private ScrollView logScroll;
    private Button start;
    private Button stop;
    private BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerLogReceiver();
        logView.setText(LogBus.snapshot());
        updateStatus(ProxyService.isRunning());
        scrollToBottom();
    }

    @Override
    protected void onPause() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("CNB2API", 26, ink, true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settings = actionButton("设置", surfaceHigh, ink);
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(72), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView subtitle = text("手机上的 OpenAI 兼容网关 · ToolForge XYML fallback", 13, muted, false);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        start = actionButton("启动服务", cyan, bg);
        stop = actionButton("停止服务", surfaceHigh, muted);
        start.setOnClickListener(v -> startServiceNow());
        stop.setOnClickListener(v -> stopServiceNow());
        controls.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));
        View spacer = new View(this);
        controls.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(controls);

        status = text("● 服务已停止", 13, red, true);
        status.setPadding(0, dp(14), 0, dp(12));
        root.addView(status);

        TextView logTitle = text("实时运行日志", 14, ink, true);
        root.addView(logTitle, new LinearLayout.LayoutParams(-1, dp(28)));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackground(panel(surface));
        logView = text("", 12, Color.rgb(190, 211, 224), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(14), dp(14), dp(14), dp(14));
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("cnb2api log", LogBus.snapshot()));
            Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show();
            return true;
        });
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView hint = text("长按日志可复制全部内容 · 设置修改后重新启动服务", 11, muted, false);
        hint.setPadding(0, dp(10), 0, 0);
        root.addView(hint);
        setContentView(root);
        updateStatus(ProxyService.isRunning());
    }

    private void registerLogReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (LogBus.ACTION_LOG.equals(intent.getAction())) {
                    String line = intent.getStringExtra(LogBus.EXTRA_LINE);
                    if (line != null) {
                        logView.append(line + "\n");
                        scrollToBottom();
                    }
                } else if (LogBus.ACTION_STATUS.equals(intent.getAction())) {
                    updateStatus(intent.getBooleanExtra(LogBus.EXTRA_RUNNING, false));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(LogBus.ACTION_LOG);
        filter.addAction(LogBus.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void startServiceNow() {
        AppSettings settings = AppSettings.load(this);
        settings.normalize();
        LogBus.log(this, "[APP] start requested");
        Intent intent = new Intent(this, ProxyService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        updateStatus(true);
    }

    private void stopServiceNow() {
        LogBus.log(this, "[APP] stop requested");
        stopService(new Intent(this, ProxyService.class));
        updateStatus(false);
    }

    private void updateStatus(boolean isRunning) {
        if (status == null) return;
        status.setText(isRunning ? "● 服务运行中  ·  http://" + localIp() + ":" + AppSettings.load(this).port + "/v1"
                : "● 服务已停止");
        status.setTextColor(isRunning ? cyan : red);
        if (start != null) start.setEnabled(!isRunning);
        if (stop != null) stop.setEnabled(isRunning);
    }

    private void showSettings() {
        AppSettings settings = AppSettings.load(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(4), dp(22), 0);
        EditText host = field("监听地址", settings.listenHost, InputType.TYPE_CLASS_TEXT);
        EditText port = field("端口", String.valueOf(settings.port), InputType.TYPE_CLASS_NUMBER);
        EditText key = field("API Key（留空为不鉴权）", settings.apiKey,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = field("默认模型", settings.model, InputType.TYPE_CLASS_TEXT);
        EditText min = field("凭证池最小数", String.valueOf(settings.poolMin), InputType.TYPE_CLASS_NUMBER);
        EditText max = field("凭证池最大数", String.valueOf(settings.poolMax), InputType.TYPE_CLASS_NUMBER);
        EditText ttl = field("凭证 TTL（分钟）", String.valueOf(settings.ttlMinutes), InputType.TYPE_CLASS_NUMBER);
        form.addView(host); form.addView(port); form.addView(key); form.addView(model);
        form.addView(min); form.addView(max); form.addView(ttl);
        CheckBox prompt = new CheckBox(this);
        prompt.setText("启用 ToolForge XYML 工具调用回退（推荐）");
        prompt.setTextColor(ink);
        prompt.setChecked(settings.forcePromptTools);
        form.addView(prompt);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("服务设置")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            settings.listenHost = host.getText().toString().trim();
            settings.port = number(port, settings.port);
            settings.apiKey = key.getText().toString();
            settings.model = model.getText().toString().trim();
            settings.poolMin = number(min, settings.poolMin);
            settings.poolMax = number(max, settings.poolMax);
            settings.ttlMinutes = number(ttl, settings.ttlMinutes);
            settings.forcePromptTools = prompt.isChecked();
            settings.normalize();
            settings.save(this);
            LogBus.log(this, "[APP] settings saved; restart service to apply");
            dialog.dismiss();
            updateStatus(ProxyService.isRunning());
        }));
        dialog.show();
    }

    private EditText field(String hint, String value, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(ink);
        field.setHintTextColor(muted);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setPadding(0, dp(8), 0, dp(8));
        return field;
    }

    private int number(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private Button actionButton(String label, int color, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(panel(color));
        return button;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable panel(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), Color.rgb(38, 56, 75));
        return drawable;
    }

    private void scrollToBottom() {
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String localIp() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "手机IP";
    }
}
