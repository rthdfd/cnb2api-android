package com.cnb2api.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact Android implementation of ToolForge's prompt fallback.
 *
 * It follows ToolForge's XYML envelope, accepts QNML/XML/JSON fallbacks, and
 * never executes a tool. The caller receives standard OpenAI tool_calls.
 */
public final class ToolForge {
    private static final Pattern INVOKE = Pattern.compile(
            "(?is)<\\|(XYML|QNML)\\|invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</\\|\\1\\|invoke\\s*>");
    private static final Pattern PARAMETER = Pattern.compile(
            "(?is)<\\|(XYML|QNML)\\|parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)</\\|\\1\\|parameter\\s*>");
    private static final Pattern XML_CALL = Pattern.compile(
            "(?is)<tool_call(?:\\s+name=\"([^\"]+)\")?\\s*>(.*?)</tool_call\\s*>");
    private static final Pattern XML_NAME = Pattern.compile("(?is)<name\\s*>(.*?)</name\\s*>");
    private static final Pattern XML_ARGUMENT = Pattern.compile(
            "(?is)<parameter\\s+name=\"([^\"]+)\"\\s*>(.*?)</parameter\\s*>");

    private ToolForge() {}

    public static JSONArray injectMessages(JSONArray source, JSONArray tools) throws JSONException {
        String instructions = buildInstructions(tools);
        JSONArray history = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            String content = contentText(message.opt("content"));

            if ("tool".equals(role) || "toolResult".equals(role)) {
                JSONObject converted = new JSONObject();
                converted.put("role", "user");
                String id = message.optString("tool_call_id", "unknown");
                String name = message.optString("name", "");
                String header = "[Tool Result id=" + id + (name.isEmpty() ? "" : " name=" + name) + "]";
                converted.put("content", header + "\n" + content);
                history.put(converted);
                continue;
            }

            if ("assistant".equals(role) && message.optJSONArray("tool_calls") != null) {
                StringBuilder assistant = new StringBuilder();
                if (!content.trim().isEmpty()) assistant.append(content.trim());
                JSONArray calls = message.optJSONArray("tool_calls");
                for (int c = 0; c < calls.length(); c++) {
                    JSONObject call = calls.optJSONObject(c);
                    if (call == null) continue;
                    JSONObject fn = call.optJSONObject("function");
                    String name = fn != null ? fn.optString("name", "") : call.optString("name", "");
                    JSONObject args = parseArguments(fn != null ? fn.opt("arguments") : call.opt("arguments"));
                    String rendered = renderToolCall(name, args);
                    if (!rendered.isEmpty()) {
                        if (assistant.length() > 0) assistant.append('\n');
                        assistant.append(rendered);
                    }
                }
                JSONObject converted = new JSONObject();
                converted.put("role", "assistant");
                converted.put("content", assistant.toString());
                history.put(converted);
                continue;
            }

            JSONObject converted = new JSONObject();
            converted.put("role", "developer".equals(role) ? "system" : role);
            converted.put("content", content);
            history.put(converted);
        }

        if (history.length() > 0 && "system".equals(history.optJSONObject(0).optString("role"))) {
            JSONObject first = history.optJSONObject(0);
            first.put("content", first.optString("content", "").trim() + "\n\n" + instructions);
            return history;
        }
        JSONArray result = new JSONArray();
        result.put(new JSONObject().put("role", "system").put("content", instructions));
        for (int i = 0; i < history.length(); i++) result.put(history.get(i));
        return result;
    }

    public static String buildInstructions(JSONArray tools) throws JSONException {
        Set<String> names = allowedNames(tools);
        StringBuilder schemas = new StringBuilder();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject raw = tools.optJSONObject(i);
            if (raw == null) continue;
            JSONObject tool = raw.optJSONObject("function");
            if (tool == null) tool = raw;
            String name = tool.optString("name", "").trim();
            if (name.isEmpty()) continue;
            JSONObject parameters = tool.optJSONObject("parameters");
            schemas.append("Action name: ").append(name).append('\n')
                    .append("Description: ").append(clip(tool.optString("description", ""), 240)).append('\n')
                    .append("Parameters: ").append(parameters == null ? "{}" : parameters).append("\n\n");
        }
        String exampleName = names.isEmpty() ? "TOOL_NAME" : names.iterator().next();
        String example = renderToolCall(exampleName, new JSONObject().put("ARG", "value"));
        return "=== XYML TOOL CALL PROTOCOL ===\n"
                + "You have access to these tools:\n\n" + schemas
                + "Default protocol for new tool calls: XYML\n"
                + "Accepted parse protocols by this client: XYML, QNML\n"
                + "Available action names: " + join(names) + "\n\n"
                + "FORMAT:\n" + example + "\n\n"
                + "RULES:\n"
                + "1. If a tool is needed, output a parseable XYML tool-call block. If no tool is needed, answer normally.\n"
                + "2. Use exact action names and parameter names from the schema.\n"
                + "3. Put strings in plain text or CDATA; objects and arrays may use JSON.\n"
                + "4. Never invent a tool or leave a required parameter empty.\n"
                + "5. After a tool result, call another tool only if needed; otherwise answer normally.\n\n"
                + "CORRECT EXAMPLE:\n" + example + "\n"
                + "Remember: preferred form is <|XYML|tool_calls>...</|XYML|tool_calls>.\n"
                + "=== END XYML TOOL INSTRUCTIONS ===";
    }

    public static JSONArray parseToolCalls(String text, JSONArray tools) {
        JSONArray result = new JSONArray();
        Set<String> allowed = allowedNames(tools);
        LinkedHashMap<String, JSONObject> unique = new LinkedHashMap<>();

        Matcher invoke = INVOKE.matcher(text == null ? "" : text);
        while (invoke.find()) {
            String name = invoke.group(2).trim();
            if (!allowed.isEmpty() && !allowed.contains(name)) continue;
            JSONObject args = new JSONObject();
            Matcher parameter = PARAMETER.matcher(invoke.group(3));
            while (parameter.find()) putValue(args, parameter.group(2), parameter.group(3));
            addCall(unique, name, args);
        }

        Matcher xml = XML_CALL.matcher(text == null ? "" : text);
        while (xml.find()) {
            String name = xml.group(1);
            String body = xml.group(2);
            if (name == null) {
                Matcher n = XML_NAME.matcher(body);
                if (n.find()) name = n.group(1).trim();
            }
            if (name == null || name.isEmpty() || (!allowed.isEmpty() && !allowed.contains(name))) continue;
            JSONObject args = new JSONObject();
            Matcher parameter = XML_ARGUMENT.matcher(body);
            while (parameter.find()) putValue(args, parameter.group(1), parameter.group(2));
            addCall(unique, name, args);
        }

        parseJsonFragments(text == null ? "" : text, allowed, unique);
        for (JSONObject call : unique.values()) result.put(call);
        return result;
    }

    public static String stripProtocolMarkup(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("(?is)<\\|(?:XYML|QNML)\\|tool_calls>.*?</\\|(?:XYML|QNML)\\|tool_calls>", "");
        cleaned = cleaned.replaceAll("(?is)<tool_call.*?</tool_call>", "");
        return cleaned.trim();
    }

    private static void parseJsonFragments(String text, Set<String> allowed,
                                           LinkedHashMap<String, JSONObject> unique) {
        int start = -1;
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') quoted = false;
                continue;
            }
            if (ch == '"') quoted = true;
            else if (ch == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    try {
                        JSONObject object = new JSONObject(text.substring(start, i + 1));
                        parseJsonObject(object, allowed, unique);
                    } catch (JSONException ignored) {
                        // The model may include a JSON-like fragment in prose.
                    }
                    start = -1;
                }
            }
        }
    }

    private static void parseJsonObject(JSONObject object, Set<String> allowed,
                                        LinkedHashMap<String, JSONObject> unique) {
        JSONArray calls = object.optJSONArray("tool_calls");
        if (calls != null) {
            for (int i = 0; i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call == null) continue;
                JSONObject fn = call.optJSONObject("function");
                String name = fn != null ? fn.optString("name", "") : call.optString("name", "");
                if (name.isEmpty() || (!allowed.isEmpty() && !allowed.contains(name))) continue;
                addCall(unique, name, parseArguments(fn != null ? fn.opt("arguments") : call.opt("arguments")));
            }
        }
        String name = object.optString("name", "");
        if (!name.isEmpty() && (allowed.isEmpty() || allowed.contains(name))) {
            Object arguments = object.has("arguments") ? object.opt("arguments") : object.opt("input");
            addCall(unique, name, parseArguments(arguments));
        }
    }

    private static JSONObject parseArguments(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw != null && raw != JSONObject.NULL) {
            try {
                if (raw.toString().trim().startsWith("{")) return new JSONObject(raw.toString());
                if (raw.toString().trim().startsWith("[")) {
                    return new JSONObject().put("value", new JSONArray(raw.toString()));
                }
            } catch (JSONException ignored) {}
            try {
                return new JSONObject().put("value", new JSONObject(raw.toString()));
            } catch (JSONException ignored) {
                try {
                    return new JSONObject().put("value", raw.toString());
                } catch (JSONException ignoredAgain) {
                    return new JSONObject();
                }
            }
        }
        return new JSONObject();
    }

    private static void putValue(JSONObject object, String key, String raw) {
        String value = raw.trim();
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>") ) {
            value = value.substring(9, value.length() - 3);
        }
        try {
            if (value.startsWith("{") && value.endsWith("}")) object.put(key, new JSONObject(value));
            else if (value.startsWith("[") && value.endsWith("]")) object.put(key, new JSONArray(value));
            else object.put(key, value);
        } catch (JSONException ignored) {
            try { object.put(key, value); } catch (JSONException ignoredAgain) {}
        }
    }

    private static void addCall(LinkedHashMap<String, JSONObject> unique, String name, JSONObject args) {
        if (name == null || name.trim().isEmpty()) return;
        String key = name + "\u0000" + args;
        if (unique.containsKey(key)) return;
        try {
            unique.put(key, new JSONObject()
                    .put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .put("type", "function")
                    .put("function", new JSONObject()
                            .put("name", name)
                            .put("arguments", args.toString())));
        } catch (JSONException ignored) {}
    }

    private static Set<String> allowedNames(JSONArray tools) {
        Set<String> result = new HashSet<>();
        if (tools == null) return result;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject raw = tools.optJSONObject(i);
            if (raw == null) continue;
            JSONObject function = raw.optJSONObject("function");
            result.add((function == null ? raw : function).optString("name", "").trim());
        }
        result.remove("");
        return result;
    }

    private static String renderToolCall(String name, JSONObject arguments) {
        if (name == null || name.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder("<|XYML|tool_calls>\n  <|XYML|invoke name=\"")
                .append(escape(name)).append("\">\n");
        java.util.Iterator<String> keys = arguments.keys();
        java.util.ArrayList<String> sorted = new java.util.ArrayList<>();
        while (keys.hasNext()) sorted.add(keys.next());
        java.util.Collections.sort(sorted);
        for (String key : sorted) {
            out.append("    <|XYML|parameter name=\"").append(escape(key)).append("\">")
                    .append(escapeValue(arguments.opt(key)))
                    .append("</|XYML|parameter>\n");
        }
        return out.append("  </|XYML|invoke>\n</|XYML|tool_calls>").toString();
    }

    private static String contentText(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof String) return (String) value;
        if (value instanceof JSONArray) {
            StringBuilder out = new StringBuilder();
            JSONArray parts = (JSONArray) value;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type", ""))) {
                    if (out.length() > 0) out.append('\n');
                    out.append(part.optString("text", ""));
                }
            }
            return out.toString();
        }
        return String.valueOf(value);
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').trim();
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONObject || value instanceof JSONArray) return escape(value.toString());
        return escape(String.valueOf(value));
    }
}
