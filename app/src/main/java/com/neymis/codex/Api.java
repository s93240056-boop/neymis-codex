package com.neymis.codex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

final class Api {

    private static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";

    interface Listener {
        void onDelta(String text);

        void onDone();

        void onError(String message);
    }

    static final class Request {
        volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) {
                try {
                    current.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    static final class Model {
        final String id;
        final String name;

        Model(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private Api() {
    }

    private static String root(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.isEmpty()) base = DEFAULT_BASE;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        return base;
    }

    static Thread chat(final String baseUrl, final String key, final String model,
                       final String system, final double temperature,
                       final JSONArray messages, final Request request,
                       final Listener listener) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(root(baseUrl) + "/chat/completions").openConnection();
                    request.connection = connection;
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(120000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("Accept", "text/event-stream");
                    connection.setRequestProperty("Authorization", "Bearer " + key);
                    connection.setRequestProperty("HTTP-Referer", "https://github.com/s93240056-boop/neymis-codex");
                    connection.setRequestProperty("X-Title", "Neymis Codex");

                    JSONObject body = new JSONObject();
                    body.put("model", model);
                    body.put("stream", true);
                    body.put("temperature", temperature);

                    JSONArray outgoing = new JSONArray();
                    if (system != null && !system.trim().isEmpty()) {
                        JSONObject sys = new JSONObject();
                        sys.put("role", "system");
                        sys.put("content", system);
                        outgoing.put(sys);
                    }
                    for (int i = 0; i < messages.length(); i++) outgoing.put(messages.get(i));
                    body.put("messages", outgoing);

                    OutputStream out = connection.getOutputStream();
                    out.write(body.toString().getBytes("UTF-8"));
                    out.flush();
                    out.close();

                    int code = connection.getResponseCode();
                    if (code >= 400) {
                        listener.onError(describeError(code, readAll(connection.getErrorStream())));
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (request.cancelled) break;
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;
                        if (data.equals("[DONE]")) break;
                        try {
                            JSONObject chunk = new JSONObject(data);
                            JSONArray choices = chunk.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            JSONObject choice = choices.optJSONObject(0);
                            if (choice == null) continue;
                            JSONObject delta = choice.optJSONObject("delta");
                            if (delta == null) continue;
                            String content = delta.optString("content", "");
                            if (!content.isEmpty()) listener.onDelta(content);
                        } catch (Exception ignored) {
                        }
                    }
                    reader.close();
                    if (!request.cancelled) listener.onDone();
                } catch (Exception e) {
                    if (!request.cancelled) listener.onError(friendly(e));
                } finally {
                    if (connection != null) {
                        try {
                            connection.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "neymis-codex-net");
        thread.start();
        return thread;
    }

    static List<Model> models(String baseUrl, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(root(baseUrl) + "/models").openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            if (key != null && !key.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + key.trim());
            }
            int code = connection.getResponseCode();
            String raw = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 400) throw new Exception(describeError(code, raw));
            JSONObject root = new JSONObject(raw);
            JSONArray data = root.optJSONArray("data");
            List<Model> result = new ArrayList<>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String id = item.optString("id", "");
                    if (id.isEmpty()) continue;
                    result.add(new Model(id, item.optString("name", id)));
                }
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) > 0) out.write(buffer, 0, count);
            stream.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String describeError(int code, String raw) {
        String message = "";
        try {
            JSONObject object = new JSONObject(raw);
            JSONObject error = object.optJSONObject("error");
            if (error != null) message = error.optString("message", "");
            if (message.isEmpty()) message = object.optString("message", "");
        } catch (Exception ignored) {
        }
        if (message.isEmpty() && raw != null) {
            message = raw.length() > 300 ? raw.substring(0, 300) : raw;
        }
        return "HTTP " + code + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String friendly(Throwable error) {
        if (error instanceof java.net.UnknownHostException) {
            return "Нет связи с сервером. Проверь интернет.";
        }
        if (error instanceof java.net.SocketTimeoutException) {
            return "Сервер не ответил вовремя. Попробуй ещё раз.";
        }
        String text = error.getMessage();
        if (text == null) text = error.getClass().getSimpleName();
        return text;
    }
}
