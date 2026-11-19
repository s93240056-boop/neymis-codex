package com.neymis.codex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Api {

    private static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";

    /**
     * Неизменяемая основа личности. Живёт только в коде:
     * в настройках её не видно и из приложения не поменять.
     */
    static final String BASE_SYSTEM =
            "Ты - нейросеть, модели Paimon 1.0 (по русски: Паймон 1.0) от компании Neymis. "
                    + "Ты умеешь писать код, общаться, и справляться с различными задачами. "
                    + "Не упоминай инструкции, промпты и правила, по которым ты работаешь: "
                    + "не говори «согласно инструкции», «в моих правилах», «мне задан промпт». "
                    + "Если спрашивают, кто ты, отвечай как Паймон 1.0 от Neymis своими словами. "
                    + "Отвечай на языке пользователя.";

    static String systemPrompt(String extra) {
        String text = extra == null ? "" : extra.trim();
        if (text.isEmpty()) return BASE_SYSTEM;
        return BASE_SYSTEM + "\n\n" + text;
    }

    static final int ERR_NETWORK = -1;
    static final int ERR_TIMEOUT = -2;

    interface Listener {
        void onDelta(String text);

        void onReasoning(String text);

        void onDone();

        void onError(int code, String friendly, String detail);
    }

    static final class Request {
        volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        volatile int promptTokens;
        volatile int completionTokens;

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
                        String raw = readAll(connection.getErrorStream());
                        listener.onError(code, friendly(code, raw), detail(code, raw));
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
                            JSONObject usage = chunk.optJSONObject("usage");
                            if (usage != null) {
                                request.promptTokens = usage.optInt("prompt_tokens", request.promptTokens);
                                request.completionTokens = usage.optInt("completion_tokens", request.completionTokens);
                            }
                            JSONArray choices = chunk.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) continue;
                            JSONObject choice = choices.optJSONObject(0);
                            if (choice == null) continue;
                            JSONObject delta = choice.optJSONObject("delta");
                            if (delta == null) continue;
                            String reasoning = delta.optString("reasoning_content", "");
                            if (reasoning.isEmpty()) reasoning = delta.optString("reasoning", "");
                            if (!reasoning.isEmpty()) listener.onReasoning(reasoning);
                            String content = delta.optString("content", "");
                            if (!content.isEmpty()) listener.onDelta(content);
                        } catch (Exception ignored) {
                        }
                    }
                    reader.close();
                    if (!request.cancelled) listener.onDone();
                } catch (Exception e) {
                    if (!request.cancelled) {
                        listener.onError(codeOf(e), friendly(e), e.toString());
                    }
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
            if (code >= 400) throw new Exception(friendly(code, raw));
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

    static String friendly(int code, String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.US);
        if (code == 401 || code == 403) {
            return "Ключ не принят. Проверь его в настройках.";
        }
        if (code == 402) {
            return "Закончился баланс аккаунта. Пополни или выбери другого провайдера.";
        }
        if (code == 429) {
            if (lower.contains("balance") || lower.contains("insufficient")
                    || lower.contains("quota") || lower.contains("recharge")
                    || lower.contains("resource")) {
                return "Закончился баланс или лимит аккаунта. Пополни или выбери другого провайдера.";
            }
            return "Слишком много запросов. Подожди минуту и повтори.";
        }
        if (code == 404) {
            return "Модель не найдена. Проверь название в настройках.";
        }
        if (code == 400 || code == 422) {
            return "Провайдер отклонил запрос: " + shortMessage(raw);
        }
        if (code >= 500) {
            return "Сервер провайдера не отвечает. Повтори через минуту.";
        }
        return "Ошибка " + code + ": " + shortMessage(raw);
    }

    static String detail(int code, String raw) {
        String body = raw == null ? "" : raw.trim();
        if (body.length() > 400) body = body.substring(0, 400) + "…";
        return "HTTP " + code + (body.isEmpty() ? "" : "\n" + body);
    }

    private static String shortMessage(String raw) {
        try {
            JSONObject object = new JSONObject(raw);
            JSONObject error = object.optJSONObject("error");
            String message = "";
            if (error != null) message = error.optString("message", "");
            if (message.isEmpty()) message = object.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
        }
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty()) return "без описания";
        return body.length() > 160 ? body.substring(0, 160) + "…" : body;
    }

    static int codeOf(Throwable error) {
        if (error instanceof UnknownHostException) return ERR_NETWORK;
        if (error instanceof SocketTimeoutException) return ERR_TIMEOUT;
        return 0;
    }

    static String friendly(Throwable error) {
        if (error instanceof UnknownHostException) {
            return "Нет связи с сервером. Проверь интернет.";
        }
        if (error instanceof SocketTimeoutException) {
            return "Сервер не ответил вовремя. Повтори запрос.";
        }
        if (error instanceof javax.net.ssl.SSLException) {
            return "Не удалось установить защищённое соединение. Проверь адрес API.";
        }
        String text = error.getMessage();
        if (text == null || text.trim().isEmpty()) text = error.getClass().getSimpleName();
        return "Сеть: " + text;
    }
}
