package com.neymis.codex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class Chat {

    String id;
    String title = "Новый чат";
    long updated = System.currentTimeMillis();
    final List<Message> messages = new ArrayList<>();

    Chat(String id) {
        this.id = id;
    }

    JSONObject toJson() {
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        for (Message message : messages) array.put(message.toJson());
        try {
            object.put("id", id);
            object.put("title", title);
            object.put("updated", updated);
            object.put("messages", array);
        } catch (Exception ignored) {
        }
        return object;
    }

    static Chat fromJson(JSONObject object) {
        Chat chat = new Chat(object.optString("id", String.valueOf(System.currentTimeMillis())));
        chat.title = object.optString("title", "Новый чат");
        chat.updated = object.optLong("updated", System.currentTimeMillis());
        JSONArray array = object.optJSONArray("messages");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) chat.messages.add(Message.fromJson(item));
            }
        }
        return chat;
    }
}
