package com.cnb2api.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android counterpart of cnb2api's independent CSRF credential pool. */
public final class CsrfPool {
    private static final String HOME = "https://cnb.cool/";
    private static final Pattern TOKEN = Pattern.compile(
            "window\\.csrftoken\\s*=\\s*\"([0-9a-fA-F]{32,64})\"");
    private static final Pattern KEY = Pattern.compile("csrfkey=([0-9a-fA-F]{32,64})");

    private final int minSize;
    private final int maxSize;
    private final long ttlMs;
    private final int timeoutMs = 15000;
    private final LogSink logger;
    private final List<CsrfToken> tokens = new ArrayList<>();
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    public CsrfPool(AppSettings settings, LogSink logger) {
        this.minSize = settings.poolMin;
        this.maxSize = settings.poolMax;
        this.ttlMs = settings.ttlMinutes * 60_000L;
        this.logger = logger;
    }

    public boolean start() {
        logger.log("[POOL] fetching " + minSize + " independent CNB session token(s)");
        CountDownLatch done = new CountDownLatch(minSize);
        ExecutorService workers = Executors.newFixedThreadPool(Math.max(1, Math.min(minSize, 4)));
        for (int i = 0; i < minSize; i++) {
            workers.execute(() -> {
                try {
                    CsrfToken token = fetch();
                    synchronized (tokens) {
                        if (token != null) tokens.add(token);
                    }
                    if (token != null) logger.log("[POOL] token acquired (size=" + size() + ")");
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownNow();
        }
        if (size() == 0) {
            logger.log("[POOL] failed: no usable CSRF token");
            return false;
        }
        maintenance.scheduleAtFixedRate(this::maintain, 60, 60, TimeUnit.SECONDS);
        logger.log("[POOL] ready: " + size() + " token(s), max=" + maxSize);
        return true;
    }

    public CsrfToken acquire() throws IOException {
        synchronized (tokens) {
            CsrfToken best = null;
            for (CsrfToken candidate : tokens) {
                if (!usable(candidate)) continue;
                if (candidate.inUse == 0) {
                    candidate.inUse++;
                    return candidate;
                }
                if (best == null || candidate.inUse < best.inUse) best = candidate;
            }
            if (best != null && tokens.size() >= maxSize) {
                best.inUse++;
                return best;
            }
        }

        if (size() < maxSize) {
            logger.log("[POOL] expanding token pool for concurrent request");
            CsrfToken created = fetch();
            if (created != null) {
                synchronized (tokens) {
                    created.inUse = 1;
                    tokens.add(created);
                }
                return created;
            }
        }
        synchronized (tokens) {
            CsrfToken best = null;
            for (CsrfToken candidate : tokens) {
                if (usable(candidate) && (best == null || candidate.inUse < best.inUse)) best = candidate;
            }
            if (best != null) {
                best.inUse++;
                return best;
            }
        }
        throw new IOException("no valid csrf token available");
    }

    public void report(CsrfToken token, boolean ok) {
        if (token == null) return;
        synchronized (tokens) {
            if (ok) {
                token.errCount = 0;
                token.valid = true;
            } else {
                token.errCount++;
                if (token.errCount >= 3) token.valid = false;
            }
            if (token.inUse > 0) token.inUse--;
        }
    }

    public int size() {
        synchronized (tokens) {
            return tokens.size();
        }
    }

    public JSONArray stats() {
        JSONArray result = new JSONArray();
        synchronized (tokens) {
            for (CsrfToken token : tokens) {
                try {
                    result.put(new JSONObject()
                            .put("csrfkey", prefix(token.key))
                            .put("token", prefix(token.token))
                            .put("valid", token.valid)
                            .put("err_cnt", token.errCount)
                            .put("in_use", token.inUse)
                            .put("ttl_left", Math.max(0, (ttlMs - (System.currentTimeMillis() - token.created)) / 1000) + "s"));
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    public void close() {
        closed = true;
        maintenance.shutdownNow();
        synchronized (tokens) {
            tokens.clear();
        }
    }

    private void maintain() {
        if (closed) return;
        synchronized (tokens) {
            tokens.removeIf(token -> !usable(token));
        }
        while (!closed && size() < minSize) {
            CsrfToken token = fetch();
            if (token == null) break;
            synchronized (tokens) {
                if (tokens.size() < maxSize) tokens.add(token);
            }
        }
        logger.log("[POOL] maintenance: size=" + size());
    }

    private boolean usable(CsrfToken token) {
        return token.valid && token.errCount < 3
                && System.currentTimeMillis() - token.created < ttlMs;
    }

    private CsrfToken fetch() {
        HttpURLConnection connection = null;
        try {
            URL current = new URL(HOME);
            List<String> cookieHeaders = new ArrayList<>();
            int status = 0;
            String body = "";
            for (int redirect = 0; redirect < 10; redirect++) {
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", userAgent());
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                if (!cookieHeaders.isEmpty()) connection.setRequestProperty("Cookie", joinCookies(cookieHeaders));
                status = connection.getResponseCode();
                addCookies(cookieHeaders, connection.getHeaderFields().get("Set-Cookie"));
                addCookies(cookieHeaders, connection.getHeaderFields().get("set-cookie"));
                if (status < 300 || status >= 400) {
                    body = read(connection.getInputStream(), 8 * 1024 * 1024);
                    break;
                }
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                connection = null;
                if (location == null || location.isEmpty()) break;
                current = new URL(current, location);
            }
            Matcher tokenMatch = TOKEN.matcher(body);
            String token = tokenMatch.find() ? tokenMatch.group(1) : "";
            String key = findCookie(cookieHeaders);
            if (status != 200 || token.isEmpty() || key.isEmpty()) {
                logger.log("[POOL] token fetch rejected: http=" + status
                        + " token=" + !token.isEmpty() + " cookie=" + !key.isEmpty());
                return null;
            }
            return new CsrfToken(key, token);
        } catch (Exception e) {
            logger.log("[POOL] token fetch error: " + safeMessage(e));
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String findCookie(List<String> cookies) {
        if (cookies == null) return "";
        for (String cookie : cookies) {
            Matcher match = KEY.matcher(cookie == null ? "" : cookie);
            if (match.find()) return match.group(1);
        }
        return "";
    }

    private static void addCookies(List<String> target, List<String> source) {
        if (source != null) target.addAll(source);
    }

    private static String joinCookies(List<String> cookies) {
        StringBuilder out = new StringBuilder();
        for (String cookie : cookies) {
            Matcher match = KEY.matcher(cookie == null ? "" : cookie);
            if (!match.find()) continue;
            if (out.length() > 0) out.append("; ");
            out.append("csrfkey=").append(match.group(1));
        }
        return out.toString();
    }

    private static String read(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                total += count;
                if (total > maxBytes) throw new IOException("response too large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String prefix(String value) {
        if (value == null) return "";
        return value.length() <= 8 ? value : value.substring(0, 8) + "...";
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static String userAgent() {
        return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36";
    }

    public static final class CsrfToken {
        public final String key;
        public final String token;
        public final long created = System.currentTimeMillis();
        public int inUse;
        public int errCount;
        public boolean valid = true;

        private CsrfToken(String key, String token) {
            this.key = key;
            this.token = token;
        }
    }
}
