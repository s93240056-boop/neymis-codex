package com.neymis.codex;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

final class ChatStore {

    private final File file;
    final List<Chat> chats = new ArrayList<>();
    String currentId;

    ChatStore(Context context) {
        file = new File(context.getFilesDir(), "chats.json");
        load();
    }

    private void load() {
        try {
            if (!file.exists()) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            in.close();
            JSONObject root = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            currentId = root.optString("current", "");
            if (currentId.isEmpty()) currentId = null;
            JSONArray array = root.optJSONArray("chats");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) chats.add(Chat.fromJson(item));
                }
            }
        } catch (Exception ignored) {
        }
    }

    void save() {
        try {
            JSONArray array = new JSONArray();
            for (Chat chat : chats) array.put(chat.toJson());
            JSONObject root = new JSONObject();
            root.put("current", currentId == null ? "" : currentId);
            root.put("chats", array);
            FileOutputStream out = new FileOutputStream(file);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    Chat current() {
        if (currentId != null) {
            for (Chat chat : chats) {
                if (chat.id.equals(currentId)) return chat;
            }
        }
        Chat chat = new Chat(String.valueOf(System.currentTimeMillis()));
        chats.add(0, chat);
        currentId = chat.id;
        save();
        return chat;
    }

    void select(Chat chat) {
        currentId = chat.id;
        save();
    }

    void reload() {
        chats.clear();
        currentId = null;
        load();
    }

    void delete(Chat chat) {
        chats.remove(chat);
        if (chat.id.equals(currentId)) currentId = null;
        save();
    }
}
