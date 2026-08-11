package com.cnb2api.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small dependency-free HTTP/OpenAI gateway embedded in the Android service. */
public final class EmbeddedProxyServer {
    private static final String CNB_CHAT = "https://cnb.cool/ai/chat/completions";
    private final AppSettings settings;
    private final LogSink logger;
    private final CsrfPool pool;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile boolean stopRequested;
    private ServerSocket listener;
    private Thread acceptThread;

    public EmbeddedProxyServer(AppSettings settings, LogSink logger) {
        this.settings = settings;
        this.logger = logger;
        this.pool = new CsrfPool(settings, logger);
    }

    public void start() throws IOException {
        if (running) return;
        if (stopRequested) throw new IOException("service start cancelled");
        if (!pool.start()) throw new IOException("unable to acquire CNB CSRF credentials");
        if (stopRequested) {
            pool.close();
            throw new IOException("service start cancelled");
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(settings.listenHost, settings.port), 64);
        running = true;
        acceptThread = new Thread(() -> {
            logger.log("[HTTP] listening on " + settings.listenHost + ":" + settings.port);
            while (running) {
                try {
                    Socket socket = listener.accept();
                    clients.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) logger.log("[HTTP] accept error: " + message(e));
                }
            }
        }, "cnb2api-accept");
        acceptThread.start();
    }

    public void stop() {
        stopRequested = true;
        running = false;
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) {}
            listener = null;
        }
        clients.shutdownNow();
        pool.close();
        logger.log("[HTTP] service stopped");
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(20_000);
            BufferedInputStream input = new BufferedInputStream(client.getInputStream());
            BufferedOutputStream output = new BufferedOutputStream(client.getOutputStream());
            Request request = readRequest(input);
            if (request == null) return;
            logger.log("[HTTP] " + request.method + " " + request.path + " from "
                    + client.getInetAddress().getHostAddress());
            if ("OPTIONS".equals(request.method)) {
                writeBytes(output, 204, "application/json", new byte[0]);
                return;
            }
            if (!authorized(request)) {
                writeJson(output, 401, new JSONObject()
                        .put("error", new JSONObject().put("message", "invalid api key").put("type", "auth_error")));
                logger.log("[AUTH] rejected request from " + client.getInetAddress().getHostAddress());
                return;
            }
            if ("GET".equals(request.method) && "/healthz".equals(request.path)) {
                writeJson(output, 200, new JSONObject().put("status", "ok").put("poolSize", pool.size()));
                return;
            }
            if ("GET".equals(request.method) && "/pool".equals(request.path)) {
                writeJson(output, 200, new JSONObject().put("pool", pool.stats()));
                return;
            }
            if ("GET".equals(request.method) && "/v1/models".equals(request.path)) {
                writeJson(output, 200, models());
                return;
            }
            if ("POST".equals(request.method) && "/v1/chat/completions".equals(request.path)) {
                handleChat(request, output);
                return;
            }
            writeJson(output, 404, new JSONObject().put("error", "not found"));
        } catch (Exception e) {
            logger.log("[HTTP] request failed: " + message(e));
            try {
                // The client may have disconnected; a failed write is intentionally ignored.
                if (socket.isConnected() && !socket.isClosed()) {
                    OutputStream output = socket.getOutputStream();
                    writeJson(output, 500, new JSONObject().put("error", e.getMessage()));
                }
            } catch (Exception ignored) {}
        }
    }

    private void handleChat(Request request, OutputStream output) throws Exception {
        long started = System.currentTimeMillis();
        JSONObject body;
        try {
            body = new JSONObject(request.body);
        } catch (JSONException e) {
            writeJson(output, 400, new JSONObject().put("error", "invalid json: " + e.getMessage()));
            return;
        }
        JSONArray messages = body.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            writeJson(output, 400, new JSONObject().put("error", "messages is required"));
            return;
        }
        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) tools = new JSONArray();
        boolean stream = body.optBoolean("stream", false);
        boolean promptTools = settings.forcePromptTools && tools.length() > 0;
        String requestedModel = body.optString("model", settings.model);
        String model = resolveModel(requestedModel);
        logger.log("[REQ] model=" + requestedModel + " stream=" + stream + " msgs=" + messages.length()
                + " chars=" + messageChars(messages) + " tools=" + tools.length()
                + " fc=" + (promptTools ? "XYML" : "off"));

        JSONArray upstreamMessages = promptTools
                ? ToolForge.injectMessages(messages, tools)
                : convertMessages(messages);
        JSONObject upstreamRequest = new JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("messages", upstreamMessages)
                .put("maxTokens", 60000);
        if (body.has("temperature")) upstreamRequest.put("temperature", body.opt("temperature"));
        if (body.has("top_p")) upstreamRequest.put("top_p", body.opt("top_p"));
        if (body.has("enable_thinking")) upstreamRequest.put("enable_thinking", body.opt("enable_thinking"));

        if (!promptTools && tools.length() > 0) {
            logger.log("[TOOL] tools present but XYML fallback disabled; CNB native tools are not sent");
        }
        UpstreamResult upstream;
        try {
            upstream = chatUpstream(upstreamRequest);
        } catch (Exception e) {
            logger.log("[UP] request failed: " + message(e));
            writeJson(output, 502, new JSONObject()
                    .put("error", new JSONObject().put("message", message(e)).put("type", "upstream_error")));
            return;
        }
        String upstreamText = upstream.content.toString();
        JSONArray calls = promptTools ? ToolForge.parseToolCalls(upstreamText, tools) : new JSONArray();
        String content = calls.length() > 0 ? ToolForge.stripProtocolMarkup(upstreamText) : upstreamText;
        if (calls.length() > 0) logger.log("[TOOL] parsed " + calls.length() + " tool call(s): " + callNames(calls));
        logger.log("[RESP] status=200 elapsed=" + (System.currentTimeMillis() - started) + "ms"
                + " chars=" + content.length() + " tool_calls=" + calls.length());

        if (stream) writeStream(output, upstream, model, content, calls);
        else writeJson(output, 200, completion(upstream, model, content, calls));
    }

    private UpstreamResult chatUpstream(JSONObject request) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            CsrfPool.CsrfToken token = pool.acquire();
            HttpURLConnection connection = null;
            boolean reported = false;
            try {
                connection = (HttpURLConnection) new URL(CNB_CHAT).openConnection();
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(180_000);
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "text/event-stream, application/json, text/plain, */*");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
                connection.setRequestProperty("Origin", "https://cnb.cool");
                connection.setRequestProperty("Referer", "https://cnb.cool/");
                connection.setRequestProperty("Csrftoken", token.token);
                connection.setRequestProperty("Cookie", "csrfkey=" + token.key);
                byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
                int status = connection.getResponseCode();
                logger.log("[UP] attempt=" + attempt + " http=" + status + " bytes=" + payload.length);
                if (status == 200) {
                    UpstreamResult result = readSse(connection.getInputStream());
                    pool.report(token, true);
                    reported = true;
                    return result;
                }
                String error = readError(connection);
                boolean csrf = (status == 401 || status == 403)
                        && error.toLowerCase(Locale.US).contains("csrf");
                pool.report(token, !csrf);
                reported = true;
                last = new IOException("upstream http " + status + ": " + trim(error));
                if (!csrf) throw last;
            } catch (Exception e) {
                last = e;
                if (!reported) pool.report(token, false);
                if (attempt == 3) throw e;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IOException("upstream request failed") : last;
    }

    private UpstreamResult readSse(InputStream input) throws IOException {
        UpstreamResult result = new UpstreamResult();
        StringBuilder event = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumeSseLine(line, event, result);
            }
        }
        if (event.length() > 0) consumePayload(event.toString(), result);
        return result;
    }

    private void consumeSseLine(String line, StringBuilder event, UpstreamResult result) {
        if (line.startsWith("data:")) {
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) {
                if (event.length() > 0) {
                    consumePayload(event.toString(), result);
                    event.setLength(0);
                }
            } else {
                if (event.length() > 0) event.append('\n');
                event.append(payload);
            }
        } else if (line.isEmpty() && event.length() > 0) {
            consumePayload(event.toString(), result);
            event.setLength(0);
        }
    }

    private void consumePayload(String payload, UpstreamResult result) {
        try {
            JSONObject object = new JSONObject(payload);
            result.id = object.optString("id", result.id);
            result.model = object.optString("model", result.model);
            result.created = object.optLong("created", result.created);
            if (object.has("usage")) result.usage = object.optJSONObject("usage");
            JSONArray choices = object.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return;
            JSONObject choice = choices.optJSONObject(0);
            JSONObject delta = choice == null ? null : choice.optJSONObject("delta");
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            JSONObject text = delta != null ? delta : message;
            if (text != null) {
                result.content.append(text.optString("content", ""));
                result.reasoning.append(text.optString("reasoning_content", ""));
            }
        } catch (JSONException ignored) {
            logger.log("[UP] skipped malformed SSE payload len=" + payload.length());
        }
    }

    private void writeStream(OutputStream output, UpstreamResult upstream, String model,
                             String content, JSONArray calls) throws IOException, JSONException {
        BufferedOutputStream out = output instanceof BufferedOutputStream
                ? (BufferedOutputStream) output : new BufferedOutputStream(output);
        writeHead(out, 200, "text/event-stream; charset=utf-8", -1);
        String id = upstream.id.isEmpty() ? "chatcmpl_" + shortId() : upstream.id;
        long created = upstream.created == 0 ? System.currentTimeMillis() / 1000 : upstream.created;
        if (calls.length() > 0) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                JSONObject function = call.optJSONObject("function");
                JSONObject delta = new JSONObject()
                        .put("tool_calls", new JSONArray().put(new JSONObject()
                                .put("index", i)
                                .put("id", call.optString("id"))
                                .put("type", "function")
                                .put("function", new JSONObject()
                                        .put("name", function.optString("name"))
                                        .put("arguments", function.optString("arguments")))));
                writeSse(out, chunk(id, model, created, delta, null));
            }
            writeSse(out, chunk(id, model, created, new JSONObject(), "tool_calls"));
        } else {
            String reasoning = upstream.reasoning.toString();
            for (String piece : split(reasoning, 120)) {
                writeSse(out, chunk(id, model, created, new JSONObject().put("reasoning_content", piece), null));
            }
            for (String piece : split(content, 120)) {
                writeSse(out, chunk(id, model, created, new JSONObject().put("content", piece), null));
            }
            writeSse(out, chunk(id, model, created, new JSONObject(), "stop"));
        }
        writeRaw(out, "data: [DONE]\n\n");
        out.flush();
    }

    private JSONObject completion(UpstreamResult upstream, String model, String content,
                                  JSONArray calls) throws JSONException {
        JSONObject message = new JSONObject().put("role", "assistant");
        if (content.isEmpty()) message.put("content", JSONObject.NULL);
        else message.put("content", content);
        if (!upstream.reasoning.toString().isEmpty()) message.put("reasoning_content", upstream.reasoning.toString());
        if (calls.length() > 0) message.put("tool_calls", calls);
        JSONObject choice = new JSONObject().put("index", 0).put("message", message)
                .put("finish_reason", calls.length() > 0 ? "tool_calls" : "stop");
        JSONObject response = new JSONObject()
                .put("id", upstream.id.isEmpty() ? "chatcmpl_" + shortId() : upstream.id)
                .put("object", "chat.completion")
                .put("created", upstream.created == 0 ? System.currentTimeMillis() / 1000 : upstream.created)
                .put("model", upstream.model.isEmpty() ? model : upstream.model)
                .put("choices", new JSONArray().put(choice));
        if (upstream.usage != null) response.put("usage", upstream.usage);
        return response;
    }

    private JSONObject chunk(String id, String model, long created, JSONObject delta, String finish)
            throws JSONException {
        JSONObject choice = new JSONObject().put("index", 0).put("delta", delta);
        if (finish != null) choice.put("finish_reason", finish);
        return new JSONObject().put("id", id).put("object", "chat.completion.chunk")
                .put("created", created).put("model", model)
                .put("choices", new JSONArray().put(choice));
    }

    private JSONArray convertMessages(JSONArray messages) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject source = messages.optJSONObject(i);
            if (source == null) continue;
            String role = source.optString("role", "user");
            if ("tool".equals(role) || "toolResult".equals(role)) role = "user";
            if (!"system".equals(role) && !"user".equals(role) && !"assistant".equals(role)) role = "user";
            result.put(new JSONObject().put("role", role).put("content", text(source.opt("content"))));
        }
        return result;
    }

    private JSONObject models() throws JSONException {
        JSONArray data = new JSONArray()
                .put(new JSONObject().put("id", "deepseek-v4-flash").put("object", "model").put("owned_by", "cnb"))
                .put(new JSONObject().put("id", "deepseek-v4-pro").put("object", "model").put("owned_by", "cnb"));
        return new JSONObject().put("object", "list").put("data", data);
    }

    private String resolveModel(String requested) {
        if ("deepseek-v4-pro".equals(requested) || "deepseek-v4-flash".equals(requested)) return requested;
        return settings.model;
    }

    private boolean authorized(Request request) {
        if (settings.apiKey == null || settings.apiKey.isEmpty()) return true;
        String value = request.headers.get("authorization");
        if (value == null) return false;
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) value = value.substring(7);
        return settings.apiKey.equals(value.trim());
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("invalid request line");
        Request request = new Request(parts[0], parts[1]);
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) request.headers.put(line.substring(0, separator).trim().toLowerCase(Locale.US),
                    line.substring(separator + 1).trim());
        }
        int length;
        try { length = Integer.parseInt(request.headers.getOrDefault("content-length", "0")); }
        catch (NumberFormatException e) { throw new IOException("invalid content length"); }
        if (length > 64 * 1024 * 1024) throw new IOException("request body too large");
        byte[] body = readExact(input, length);
        request.body = new String(body, StandardCharsets.UTF_8);
        return request;
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) >= 0) {
            if (current == '\n') break;
            if (current != '\r') out.write(current);
            if (out.size() > 16 * 1024) throw new IOException("header line too long");
        }
        return current < 0 && out.size() == 0 ? null : out.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new IOException("unexpected end of request body");
            offset += count;
        }
        return data;
    }

    private static void writeJson(OutputStream output, int status, JSONObject body) throws IOException {
        writeBytes(output, status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(OutputStream output, int status, String contentType, byte[] body) throws IOException {
        BufferedOutputStream out = output instanceof BufferedOutputStream
                ? (BufferedOutputStream) output : new BufferedOutputStream(output);
        writeHead(out, status, contentType, body.length);
        out.write(body);
        out.flush();
    }

    private static void writeHead(OutputStream output, int status, String contentType, int length) throws IOException {
        String text = "HTTP/1.1 " + status + " " + statusText(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + (length >= 0 ? "Content-Length: " + length + "\r\n" : "")
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type, X-ToolForge-FC-Mode\r\n\r\n";
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSse(OutputStream output, JSONObject value) throws IOException {
        writeRaw(output, "data: " + value + "\n\n");
    }

    private static void writeRaw(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static String readError(HttpURLConnection connection) {
        try {
            InputStream input = connection.getErrorStream();
            return input == null ? "" : new String(readExact(input, input.available()), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder out = new StringBuilder();
            JSONArray parts = (JSONArray) value;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) out.append(part.optString("text"));
            }
            return out.toString();
        }
        return String.valueOf(value);
    }

    private static int messageChars(JSONArray messages) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null) total += text(message.opt("content")).length();
        }
        return total;
    }

    private static String callNames(JSONArray calls) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            JSONObject function = call == null ? null : call.optJSONObject("function");
            String name = function == null ? "?" : function.optString("name", "?");
            if (names.length() > 0) names.append(", ");
            names.append(name);
        }
        return names.toString();
    }

    private static String[] split(String value, int max) {
        if (value == null || value.isEmpty()) return new String[0];
        int count = (value.length() + max - 1) / max;
        String[] result = new String[count];
        for (int i = 0; i < count; i++) result[i] = value.substring(i * max, Math.min(value.length(), (i + 1) * max));
        return result;
    }

    private static String trim(String value) {
        value = value == null ? "" : value.trim();
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            case 500: return "Internal Server Error";
            default: return "Error";
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> headers = new java.util.HashMap<>();
        String body = "";

        Request(String method, String path) {
            this.method = method;
            int query = path.indexOf('?');
            this.path = query >= 0 ? path.substring(0, query) : path;
        }
    }

    private static final class UpstreamResult {
        String id = "";
        String model = "";
        long created;
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        JSONObject usage;
    }
}
