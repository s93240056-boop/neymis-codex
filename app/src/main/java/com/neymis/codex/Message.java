package com.neymis.codex;

import org.json.JSONObject;

final class Message {

    static final String USER = "user";
    static final String ASSISTANT = "assistant";

    String role;
    String text;
    long time;

    Message(String role, String text) {
        this.role = role;
        this.text = text;
        this.time = System.currentTimeMillis();
    }

    JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("role", role);
            object.put("text", text);
            object.put("time", time);
        } catch (Exception ignored) {
        }
        return object;
    }

    static Message fromJson(JSONObject object) {
        Message message = new Message(object.optString("role", ASSISTANT),
                object.optString("text", ""));
        message.time = object.optLong("time", System.currentTimeMillis());
        return message;
    }
}
