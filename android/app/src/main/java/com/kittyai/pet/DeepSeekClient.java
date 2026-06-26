package com.kittyai.pet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DeepSeekClient {
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";

    private final AppPrefs prefs;
    private final SecretStore secrets;

    public DeepSeekClient(Context context) {
        prefs = new AppPrefs(context);
        secrets = new SecretStore(context);
    }

    public synchronized String chat(String userText) throws Exception {
        String apiKey = secrets.getApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("请先在设置中填写 DeepSeek API 密钥。");
        }

        List<ChatMessage> history = new ArrayList<>(prefs.getHistory());
        JSONArray messages = new JSONArray();
        messages.put(message("system", buildSystemPrompt()));
        for (ChatMessage item : history) {
            messages.put(message(item.role, item.content));
        }
        messages.put(message("user", userText));

        String answer = callModel(apiKey, messages, MODEL, 0.9, 500);

        history.add(new ChatMessage("user", userText));
        history.add(new ChatMessage("assistant", answer));
        prefs.saveHistory(history);
        updateLongMemoryIfNeeded(apiKey, userText, answer);
        updatePersonalityIfNeeded(apiKey, userText, answer);
        return answer;
    }

    private String buildSystemPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("[核心人格]\n").append(prefs.getPersonality());
        String personalityDelta = prefs.getPersonalityDeltaPrompt();
        if (!personalityDelta.isEmpty()) {
            builder.append("\n\n[性格微调]\n").append(personalityDelta);
        }
        String longMemory = prefs.getLongMemoryPrompt();
        if (!longMemory.isEmpty()) {
            builder.append("\n\n[长期记忆]\n").append(longMemory);
        }
        return builder.toString();
    }

    private void updateLongMemoryIfNeeded(String apiKey, String userText, String answer) {
        if (!containsAny(userText, new String[]{
                "记住", "以后", "从现在开始", "我的偏好", "我喜欢", "我不喜欢",
                "我经常", "我的项目", "我的论文", "我的习惯", "下次你"
        })) return;
        JSONObject extracted = null;
        try {
            JSONArray messages = new JSONArray();
            messages.put(message("system",
                    "你是一个长期记忆提取器。只保存未来多次对话仍然有用的信息。" +
                    "不要保存临时请求、闲聊、密码、API Key 或敏感隐私。" +
                    "如果值得保存，输出严格 JSON：" +
                    "{\"should_save\":true,\"category\":\"user_profile | preferences | research_context | personal_projects | important_facts\",\"memory\":\"一句简短的长期记忆\"}。" +
                    "如果不值得保存，输出 {\"should_save\":false,\"category\":null,\"memory\":null}。不要输出 JSON 以外内容。"));
            messages.put(message("user", "用户：" + userText + "\n助手：" + answer));
            extracted = extractJsonObject(callModel(apiKey, messages, MODEL, 0.1, 220));
        } catch (Exception ignored) {
        }
        if (extracted == null) extracted = fallbackLongMemory(userText);
        if (extracted == null || !extracted.optBoolean("should_save")) return;
        String category = extracted.optString("category");
        String memory = extracted.optString("memory").trim();
        if (!memory.isEmpty()) prefs.addLongMemory(category, memory);
    }

    private void updatePersonalityIfNeeded(String apiKey, String userText, String answer) {
        if (!containsAny(userText, new String[]{
                "以后你说话", "以后回答", "说话风格", "回答风格", "你以后",
                "别这么", "不要总是", "少一点", "多一点", "直接点", "口语化",
                "正式点", "随意点", "温柔点", "严谨点", "科研问题", "日常聊天"
        })) return;
        JSONObject extracted = null;
        try {
            JSONArray messages = new JSONArray();
            messages.put(message("system",
                    "你是一个性格偏好提取器。判断用户是否表达了希望助手长期改变说话方式、回答风格或场景规则的偏好。" +
                    "只保存未来多次对话仍然有用的增量规则，不修改核心人格，不保存一次性请求。" +
                    "如果值得保存，输出严格 JSON：" +
                    "{\"should_save\":true,\"category\":\"tone_preferences | context_rules | forbidden_styles\",\"memory\":\"一句简短的风格偏好\"}。" +
                    "如果不值得保存，输出 {\"should_save\":false,\"category\":null,\"memory\":null}。不要输出 JSON 以外内容。"));
            messages.put(message("user", "用户：" + userText + "\n助手：" + answer));
            extracted = extractJsonObject(callModel(apiKey, messages, MODEL, 0.1, 220));
        } catch (Exception ignored) {
        }
        if (extracted == null) extracted = fallbackPersonality(userText);
        if (extracted == null || !extracted.optBoolean("should_save")) return;
        String category = extracted.optString("category");
        String memory = extracted.optString("memory").trim();
        if (!memory.isEmpty() && !isDangerousStyleRule(memory)) {
            prefs.addPersonalityDelta(category, memory);
        }
    }

    private String callModel(String apiKey, JSONArray messages, String model, double temperature, int maxTokens) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("thinking", new JSONObject().put("type", "disabled"));
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);

        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        try (OutputStream output = connection.getOutputStream()) {
            output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IllegalStateException("DeepSeek 请求失败（" + status + "）："
                    + truncate(response, 240));
        }

        JSONObject result = new JSONObject(response);
        return result.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim();
    }

    private static JSONObject message(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONObject extractJsonObject(String text) {
        if (text == null) return null;
        String raw = text.trim();
        if (raw.startsWith("```")) {
            raw = raw.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = raw.indexOf('{');
        while (start >= 0) {
            for (int end = raw.length(); end > start; end--) {
                String candidate = raw.substring(start, end).trim();
                if (!candidate.endsWith("}")) continue;
                try {
                    return new JSONObject(candidate);
                } catch (JSONException ignored) {
                }
            }
            start = raw.indexOf('{', start + 1);
        }
        return null;
    }

    private static JSONObject fallbackLongMemory(String userText) {
        String memory = null;
        if (userText.contains("记住")) {
            memory = after(userText, "记住");
        } else if (userText.contains("我喜欢")) {
            memory = "用户喜欢" + after(userText, "我喜欢");
        } else if (userText.contains("以后")) {
            memory = userText;
        }
        if (memory == null || memory.trim().isEmpty()) return null;
        try {
            return new JSONObject()
                    .put("should_save", true)
                    .put("category", "preferences")
                    .put("memory", truncate(memory.trim(), 120));
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static JSONObject fallbackPersonality(String userText) {
        String memory;
        String category = "tone_preferences";
        if (userText.contains("直接点")) {
            memory = "用户偏好回答更直接，减少铺垫。";
        } else if (userText.contains("口语化") || userText.contains("随意点")) {
            memory = "用户偏好日常聊天更口语化，正式场景保持专业。";
        } else if (userText.contains("正式点") || userText.contains("严谨点")) {
            memory = "用户偏好正式或科研场景保持更严谨的表达。";
            category = "context_rules";
        } else if (userText.contains("不要总是") || userText.contains("别这么")) {
            memory = truncate(userText, 120);
            category = "forbidden_styles";
        } else {
            memory = truncate(userText, 120);
        }
        try {
            return new JSONObject()
                    .put("should_save", true)
                    .put("category", category)
                    .put("memory", memory);
        } catch (JSONException ignored) {
            return null;
        }
    }

    private static boolean containsAny(String text, String[] needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static String after(String text, String marker) {
        int index = text.indexOf(marker);
        if (index < 0) return text;
        return text.substring(index + marker.length()).replaceAll("^[：:，,。\\s]+", "");
    }

    private static boolean isDangerousStyleRule(String text) {
        return containsAny(text, new String[]{"攻击用户", "羞辱用户", "泄露隐私", "违法", "暴力威胁"});
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    private static String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }
}
