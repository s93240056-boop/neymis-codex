package com.neymis.codex;

import org.json.JSONObject;

final class Message {

    static final String USER = "user";
    static final String ASSISTANT = "assistant";

    String role;
    String text;
    String reasoning = "";
    long time;

    // Статистика ответа
    int tokens;
    long elapsedMs;

    // Ошибка последнего ответа (не сохраняется между перезапусками как код)
    String error = "";
    String errorDetail = "";

    // Состояние прямо сейчас, в JSON не попадает
    boolean streaming;
    long startedAt;
    boolean reasoningOpen;

    Message(String role, String text) {
        this.role = role;
        this.text = text;
        this.time = System.currentTimeMillis();
    }

    boolean hasError() {
        return error != null && !error.isEmpty();
    }

    JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("role", role);
            object.put("text", text == null ? "" : text);
            object.put("time", time);
            if (reasoning != null && !reasoning.isEmpty()) object.put("reasoning", reasoning);
            if (tokens > 0) object.put("tokens", tokens);
            if (elapsedMs > 0) object.put("elapsed", elapsedMs);
        } catch (Exception ignored) {
        }
        return object;
    }

    static Message fromJson(JSONObject object) {
        Message message = new Message(object.optString("role", ASSISTANT),
                object.optString("text", ""));
        message.reasoning = object.optString("reasoning", "");
        message.time = object.optLong("time", System.currentTimeMillis());
        message.tokens = object.optInt("tokens", 0);
        message.elapsedMs = object.optLong("elapsed", 0);
        return message;
    }
}
