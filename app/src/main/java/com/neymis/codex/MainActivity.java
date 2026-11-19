package com.neymis.codex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "openrouter/auto";
    private static final int REQ_CHATS = 101;

    private static final String OLD_DEFAULT_SYSTEM =
            "Ты Ава — помощник приложения Neymis Codex. Отвечай по-русски, если пользователь пишет по-русски. "
                    + "Будь дружелюбным, но по делу: без лишних вступлений. Код оформляй в блоках ```.";

    private static final String OLD_PAIMON_SYSTEM =
            "Ты - нейросеть Паймон от компании Neymis. Отвечай сухо, коротко и по делу без лишней воды.";

    private SharedPreferences prefs;
    private ChatStore store;
    private Chat chat;
    private ChatAdapter adapter;

    private LinearLayout rootView;
    private TextView headerTitle;
    private TextView headerModel;
    private TextView newButton;
    private TextView chatsButton;
    private TextView settingsButton;
    private RecyclerView recycler;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyHint;
    private EditText input;
    private TextView sendButton;

    private EditText settingsKey;
    private EditText settingsModel;
    private EditText settingsBase;
    private EditText settingsSystem;
    private SeekBar settingsTemp;
    private TextView settingsTempLabel;
    private CheckBox settingsTheme;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Api.Request request;
    private Message pendingMessage;
    private StringBuilder streamBuffer = new StringBuilder();
    private StringBuilder reasonBuffer = new StringBuilder();
    private boolean streaming;
    private int generation;
    private long lastNotify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            init();
        } catch (Throwable error) {
            showFatal(error);
        }
    }

    private void init() {
        prefs = getSharedPreferences("codex", MODE_PRIVATE);
        Palette.apply(prefs.getBoolean("dark", true));
        setTheme(Palette.dark ? R.style.AppTheme : R.style.AppTheme_Light);
        setContentView(R.layout.activity_main);

        rootView = findViewById(R.id.rootView);
        headerTitle = findViewById(R.id.headerTitle);
        headerModel = findViewById(R.id.headerModel);
        newButton = findViewById(R.id.newButton);
        chatsButton = findViewById(R.id.chatsButton);
        settingsButton = findViewById(R.id.settingsButton);
        recycler = findViewById(R.id.messages);
        emptyView = findViewById(R.id.emptyView);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptyHint = findViewById(R.id.emptyHint);
        input = findViewById(R.id.input);
        sendButton = findViewById(R.id.sendButton);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);

        migratePrompt();

        store = new ChatStore(this);
        chat = store.current();
        boolean repaired = false;
        for (Message message : chat.messages) {
            if (Message.ASSISTANT.equals(message.role)
                    && (message.text == null || message.text.isEmpty())) {
                message.text = "⚠ Ответ прерван";
                repaired = true;
            }
        }
        if (repaired) store.save();

        adapter = new ChatAdapter(chat.messages, new ChatAdapter.Listener() {
            @Override
            public void onMessageLongPress(int messageIndex) {
                showMessageActions(messageIndex);
            }

            @Override
            public void onRetry(int messageIndex) {
                regenerate(messageIndex);
            }
        });
        recycler.setAdapter(adapter);

        newButton.setOnClickListener(v -> newChat());
        chatsButton.setOnClickListener(v -> showChats());
        settingsButton.setOnClickListener(v -> showSettings());
        sendButton.setOnClickListener(v -> send());

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                saveDraft();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });

        applyPalette();
        updateHeader();
        updateEmpty();
        updateSendButton();
        restoreDraft();
        showLastCrash();
    }

    private void migratePrompt() {
        String stored = prefs.getString("system", null);
        if (stored == null) return;
        String trimmed = stored.trim();
        if (trimmed.equals(OLD_DEFAULT_SYSTEM) || trimmed.equals(OLD_PAIMON_SYSTEM)) {
            prefs.edit().remove("system").apply();
        }
    }

    @Override
    protected void onPause() {
        saveDraft();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        generation++;
        if (request != null) request.cancel();
        super.onDestroy();
    }

    private void applyPalette() {
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Palette.bgStart, Palette.bgEnd});
        rootView.setBackground(background);
        headerTitle.setTextColor(Palette.text);
        headerModel.setTextColor(Palette.textDim);
        newButton.setTextColor(Palette.accent);
        chatsButton.setTextColor(Palette.accent);
        settingsButton.setTextColor(Palette.accent);
        emptyTitle.setTextColor(Palette.text);
        emptyHint.setTextColor(Palette.textDim);
        input.setTextColor(Palette.text);
        input.setHintTextColor(Palette.textDim);
        GradientDrawable inputShape = new GradientDrawable();
        inputShape.setCornerRadius(dp(22));
        inputShape.setColor(Palette.inputBg);
        inputShape.setStroke(dp(1), Palette.stroke);
        input.setBackground(inputShape);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        sendButton.setTextColor(Palette.accent);
    }

    private void updateHeader() {
        headerModel.setText(prefs.getString("model", DEFAULT_MODEL));
    }

    private void updateSendButton() {
        sendButton.setText(streaming ? "■" : "➤");
        sendButton.setTextColor(streaming ? 0xFFFF8A80 : Palette.accent);
    }

    private void updateEmpty() {
        emptyView.setVisibility(chat.messages.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshAll() {
        if (adapter == null || recycler == null) return;
        if (recycler.isComputingLayout()) {
            recycler.post(this::refreshAll);
            return;
        }
        adapter.refresh();
    }

    private void refreshLast() {
        if (adapter == null || recycler == null) return;
        if (recycler.isComputingLayout()) {
            recycler.post(this::refreshLast);
            return;
        }
        adapter.notifyLastMessage();
    }

    private String draftKey() {
        return "draft_" + (chat == null ? "none" : chat.id);
    }

    private void restoreDraft() {
        if (chat == null) return;
        String draft = prefs.getString(draftKey(), "");
        input.setText(draft);
        if (draft != null && !draft.isEmpty()) {
            input.setSelection(draft.length());
        }
    }

    private void saveDraft() {
        if (prefs == null || chat == null || input == null) return;
        prefs.edit().putString(draftKey(), input.getText().toString()).apply();
    }

    private void send() {
        if (streaming) {
            stopStreaming();
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;

        String key = prefs.getString("key", "").trim();
        if (key.isEmpty()) {
            toast("Вставь ключ провайдера в настройках ⚙");
            showSettings();
            return;
        }

        if (chat.messages.isEmpty()) {
            chat.title = text.length() > 48 ? text.substring(0, 48) + "…" : text;
        }
        Message user = new Message(Message.USER, text);
        chat.messages.add(user);
        chat.updated = System.currentTimeMillis();

        Message placeholder = new Message(Message.ASSISTANT, "");
        chat.messages.add(placeholder);

        input.setText("");
        prefs.edit().remove(draftKey()).apply();
        refreshAll();
        updateEmpty();
        scrollToEnd();
        store.save();

        startRequest(placeholder);
    }

    private void startRequest(Message target) {
        String base = prefs.getString("base", DEFAULT_BASE);
        String key = prefs.getString("key", "").trim();
        String model = prefs.getString("model", DEFAULT_MODEL).trim();
        if (model.isEmpty()) model = DEFAULT_MODEL;
        String system = Api.systemPrompt(prefs.getString("system", ""));
        double temperature = prefs.getInt("temp", 7) / 10.0;

        JSONArray payload = new JSONArray();
        for (Message message : chat.messages) {
            if (message == target) continue;
            if (message.text == null || message.text.isEmpty()) continue;
            try {
                JSONObject item = new JSONObject();
                item.put("role", Message.USER.equals(message.role) ? "user" : "assistant");
                item.put("content", message.text);
                payload.put(item);
            } catch (Exception ignored) {
            }
        }

        streamBuffer = new StringBuilder();
        reasonBuffer = new StringBuilder();
        streaming = true;
        pendingMessage = target;
        target.streaming = true;
        target.startedAt = System.currentTimeMillis();
        target.error = "";
        target.errorDetail = "";
        target.tokens = 0;
        target.elapsedMs = 0;

        final Api.Request current = new Api.Request();
        request = current;
        updateSendButton();

        final int token = ++generation;
        Api.chat(base, key, model, system, temperature, payload, current,
                new Api.Listener() {
                    @Override
                    public void onDelta(String text) {
                        if (token != generation) return;
                        streamBuffer.append(text);
                        ui.post(() -> {
                            if (token != generation) return;
                            target.text = streamBuffer.toString();
                            notifyDelta();
                        });
                    }

                    @Override
                    public void onReasoning(String text) {
                        if (token != generation) return;
                        reasonBuffer.append(text);
                        ui.post(() -> {
                            if (token != generation) return;
                            target.reasoning = reasonBuffer.toString();
                            notifyDelta();
                        });
                    }

                    @Override
                    public void onDone() {
                        if (token != generation) return;
                        ui.post(() -> {
                            if (token == generation) finishStreaming(target, null, "", current);
                        });
                    }

                    @Override
                    public void onError(int code, String friendly, String detail) {
                        if (token != generation) return;
                        ui.post(() -> {
                            if (token == generation) finishStreaming(target, friendly, detail, current);
                        });
                    }
                });
    }

    private void notifyDelta() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 70) return;
        lastNotify = now;
        if (chat.messages.isEmpty()) return;
        refreshLast();
        autoScroll();
    }

    private void finishStreaming(Message target, String error, String detail, Api.Request used) {
        streaming = false;
        pendingMessage = null;
        request = null;
        target.streaming = false;
        updateSendButton();

        if (target.startedAt > 0) {
            target.elapsedMs = Math.max(1, System.currentTimeMillis() - target.startedAt);
        }
        if (used != null) {
            target.tokens = used.promptTokens + used.completionTokens;
        }

        String text = streamBuffer == null ? "" : streamBuffer.toString();
        target.text = text;
        target.reasoning = reasonBuffer == null ? "" : reasonBuffer.toString();
        target.reasoningOpen = false;
        target.error = error == null ? "" : error;
        target.errorDetail = detail == null ? "" : detail;

        chat.updated = System.currentTimeMillis();
        store.save();
        refreshAll();
        updateEmpty();
        scrollToEnd();
    }

    private void stopStreaming() {
        generation++;
        if (request != null) {
            request.cancel();
            request = null;
        }
        streaming = false;
        if (pendingMessage != null) {
            String text = streamBuffer == null ? "" : streamBuffer.toString();
            pendingMessage.streaming = false;
            if (pendingMessage.startedAt > 0) {
                pendingMessage.elapsedMs =
                        Math.max(1, System.currentTimeMillis() - pendingMessage.startedAt);
            }
            pendingMessage.text = text.isEmpty() ? "⏹ Остановлено" : text + "\n\n⏹ Остановлено";
            pendingMessage.reasoning = reasonBuffer == null ? "" : reasonBuffer.toString();
            pendingMessage = null;
        }
        store.save();
        refreshAll();
        updateSendButton();
    }

    private void scrollToEnd() {
        recycler.post(() -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) recycler.scrollToPosition(last);
        });
    }

    private void autoScroll() {
        LinearLayoutManager manager = (LinearLayoutManager) recycler.getLayoutManager();
        if (manager == null) return;
        int last = manager.findLastVisibleItemPosition();
        if (last >= adapter.getItemCount() - 3) {
            recycler.scrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void newChat() {
        if (streaming) stopStreaming();
        saveDraft();
        Chat fresh = new Chat(String.valueOf(System.currentTimeMillis()));
        store.chats.add(0, fresh);
        store.currentId = fresh.id;
        store.save();
        chat = fresh;
        adapter.setMessages(chat.messages);
        updateEmpty();
        restoreDraft();
        input.requestFocus();
    }

    private void showChats() {
        if (streaming) stopStreaming();
        saveDraft();
        store.save();
        Intent intent = new Intent(this, ChatListActivity.class);
        startActivityForResult(intent, REQ_CHATS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CHATS) return;
        if (resultCode != RESULT_OK) return;

        String id = data == null ? null : data.getStringExtra(ChatListActivity.EXTRA_CHAT);
        store.reload();

        if (ChatListActivity.NEW_CHAT.equals(id)) {
            newChat();
            return;
        }

        Chat target = null;
        if (id != null) {
            for (Chat item : store.chats) {
                if (item.id.equals(id)) {
                    target = item;
                    break;
                }
            }
        }
        if (target == null) target = store.current();
        openChat(target);
    }

    private void openChat(Chat target) {
        if (streaming) stopStreaming();
        saveDraft();
        chat = target;
        store.select(target);
        adapter.setMessages(chat.messages);
        updateEmpty();
        restoreDraft();
        scrollToEnd();
    }

    private void showMessageActions(final int index) {
        if (index < 0 || index >= chat.messages.size()) return;
        final Message message = chat.messages.get(index);
        final boolean user = Message.USER.equals(message.role);

        final List<String> actions = new ArrayList<>();
        actions.add("Копировать");
        if (message.hasError()) actions.add("Текст ошибки");
        if (user) {
            actions.add("Изменить и отправить");
        } else {
            actions.add("Повторить ответ");
        }
        actions.add("Удалить");

        new AlertDialog.Builder(this)
                .setTitle(user ? "Твоё сообщение" : "Ответ Паймон")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    String action = actions.get(which);
                    if (action.equals("Копировать")) {
                        copyToClipboard(message.text == null ? "" : message.text);
                    } else if (action.equals("Текст ошибки")) {
                        showErrorDetail(message);
                    } else if (action.equals("Изменить и отправить")) {
                        editMessage(index);
                    } else if (action.equals("Повторить ответ")) {
                        regenerate(index);
                    } else if (action.equals("Удалить")) {
                        deleteMessage(index);
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("Neymis Codex", text));
                toast("Скопировано");
            }
        } catch (Throwable ignored) {
        }
    }

    private void showErrorDetail(Message message) {
        String detail = message.errorDetail == null || message.errorDetail.isEmpty()
                ? "Подробностей нет."
                : message.errorDetail;
        new AlertDialog.Builder(this)
                .setTitle("Что ответил сервер")
                .setMessage(detail)
                .setPositiveButton("Копировать", (dialog, which) -> copyToClipboard(detail))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void editMessage(final int index) {
        if (index < 0 || index >= chat.messages.size()) return;
        final Message message = chat.messages.get(index);
        if (!Message.USER.equals(message.role)) {
            regenerate(index);
            return;
        }

        final EditText field = new EditText(this);
        field.setText(message.text);
        field.setSelection(field.getText().length());
        field.setTextColor(Palette.text);
        field.setHintTextColor(Palette.textDim);

        new AlertDialog.Builder(this)
                .setTitle("Изменить сообщение")
                .setView(field)
                .setPositiveButton("Отправить", (dialog, which) -> {
                    String text = field.getText().toString().trim();
                    if (text.isEmpty()) return;
                    if (streaming) stopStreaming();
                    message.text = text;
                    message.time = System.currentTimeMillis();
                    while (chat.messages.size() > index + 1) {
                        chat.messages.remove(chat.messages.size() - 1);
                    }
                    Message placeholder = new Message(Message.ASSISTANT, "");
                    chat.messages.add(placeholder);
                    chat.updated = System.currentTimeMillis();
                    store.save();
                    refreshAll();
                    updateEmpty();
                    scrollToEnd();
                    startRequest(placeholder);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void regenerate(int index) {
        if (index < 0 || index >= chat.messages.size()) return;
        Message message = chat.messages.get(index);
        int start = Message.USER.equals(message.role) ? index + 1 : index;
        if (start <= 0) {
            toast("Нечего повторять");
            return;
        }
        if (streaming) stopStreaming();
        while (chat.messages.size() > start) {
            chat.messages.remove(chat.messages.size() - 1);
        }
        Message placeholder = new Message(Message.ASSISTANT, "");
        chat.messages.add(placeholder);
        chat.updated = System.currentTimeMillis();
        store.save();
        refreshAll();
        updateEmpty();
        scrollToEnd();
        startRequest(placeholder);
    }

    private void deleteMessage(int index) {
        if (index < 0 || index >= chat.messages.size()) return;
        if (streaming) stopStreaming();
        chat.messages.remove(index);
        chat.updated = System.currentTimeMillis();
        store.save();
        refreshAll();
        updateEmpty();
    }

    private void showSettings() {
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null);
        settingsKey = view.findViewById(R.id.keyField);
        settingsModel = view.findViewById(R.id.modelField);
        settingsBase = view.findViewById(R.id.baseField);
        settingsSystem = view.findViewById(R.id.systemField);
        settingsTemp = view.findViewById(R.id.tempSeek);
        settingsTempLabel = view.findViewById(R.id.tempLabel);
        settingsTheme = view.findViewById(R.id.themeCheck);

        settingsKey.setText(prefs.getString("key", ""));
        settingsModel.setText(prefs.getString("model", DEFAULT_MODEL));
        settingsBase.setText(prefs.getString("base", DEFAULT_BASE));
        settingsSystem.setText(prefs.getString("system", ""));
        settingsTemp.setProgress(prefs.getInt("temp", 7));
        settingsTempLabel.setText(tempText(settingsTemp.getProgress()));
        settingsTheme.setChecked(!prefs.getBoolean("dark", true));

        settingsTemp.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                settingsTempLabel.setText(tempText(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        view.findViewById(R.id.presetOpenRouter).setOnClickListener(v -> {
            settingsBase.setText("https://openrouter.ai/api/v1");
            if (settingsModel.getText().toString().trim().isEmpty()) {
                settingsModel.setText("openrouter/auto");
            }
        });
        view.findViewById(R.id.presetDeepSeek).setOnClickListener(v -> {
            settingsBase.setText("https://api.deepseek.com");
            String current = settingsModel.getText().toString().trim();
            if (current.isEmpty() || current.contains("openrouter")) {
                settingsModel.setText("deepseek-chat");
            }
        });
        view.findViewById(R.id.presetZai).setOnClickListener(v -> {
            settingsBase.setText("https://api.z.ai/api/paas/v4");
            String current = settingsModel.getText().toString().trim();
            if (current.isEmpty() || current.contains("openrouter") || current.contains("deepseek")) {
                settingsModel.setText("glm-4.5-flash");
            }
        });
        view.findViewById(R.id.presetCustom).setOnClickListener(v -> {
            settingsBase.requestFocus();
            settingsBase.setSelection(settingsBase.getText().length());
        });
        view.findViewById(R.id.modelsButton).setOnClickListener(v -> showModels());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(view)
                .setPositiveButton("Сохранить", null)
                .setNeutralButton("Экспорт", null)
                .setNegativeButton("Закрыть", null)
                .create();
        dialog.show();

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean changed = saveSettings();
            dialog.dismiss();
            if (changed) recreate();
        });
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean changed = saveSettings();
            dialog.dismiss();
            exportChat();
            if (changed) recycler.postDelayed(this::recreate, 600);
        });
    }

    private String tempText(int progress) {
        return "Температура: " + String.format(Locale.US, "%.1f", progress / 10.0);
    }

    private boolean saveSettings() {
        boolean oldDark = prefs.getBoolean("dark", true);
        boolean newDark = !settingsTheme.isChecked();
        prefs.edit()
                .putString("key", settingsKey.getText().toString().trim())
                .putString("model", settingsModel.getText().toString().trim())
                .putString("base", settingsBase.getText().toString().trim())
                .putString("system", settingsSystem.getText().toString().trim())
                .putInt("temp", settingsTemp.getProgress())
                .putBoolean("dark", newDark)
                .apply();
        updateHeader();
        return oldDark != newDark;
    }

    private void showModels() {
        final String base = settingsBase.getText().toString().trim();
        final String key = settingsKey.getText().toString().trim();

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        final EditText search = new EditText(this);
        search.setHint("Поиск модели");
        search.setTextSize(14);

        final ListView list = new ListView(this);
        container.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        final ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(listAdapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Модели")
                .setView(container)
                .setNegativeButton("Закрыть", null)
                .create();
        dialog.show();

        final List<Api.Model> loaded = new ArrayList<>();
        new Thread(() -> {
            try {
                List<Api.Model> models = Api.models(base, key);
                loaded.addAll(models);
                ui.post(() -> {
                    listAdapter.clear();
                    for (Api.Model model : loaded) listAdapter.add(model.id);
                });
            } catch (Exception e) {
                ui.post(() -> toast("Не удалось загрузить: " + e.getMessage()));
            }
        }, "neymis-models").start();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase();
                listAdapter.clear();
                for (Api.Model model : loaded) {
                    if (model.id.toLowerCase().contains(query)) listAdapter.add(model.id);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            settingsModel.setText(String.valueOf(parent.getItemAtPosition(position)));
            dialog.dismiss();
        });
    }

    private void exportChat() {
        if (chat.messages.isEmpty()) {
            toast("Чат пуст");
            return;
        }
        try {
            File baseDir = getExternalFilesDir(null);
            if (baseDir == null) baseDir = getFilesDir();
            File dir = new File(baseDir, "exports");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("не удалось создать папку");
            }
            File file = new File(dir, "neymis-codex-" + System.currentTimeMillis() + ".md");
            StringBuilder text = new StringBuilder();
            text.append("# ").append(chat.title).append("\n\n");
            for (Message message : chat.messages) {
                text.append(Message.USER.equals(message.role) ? "**Ты:**" : "**Паймон:**")
                        .append("\n\n")
                        .append(message.text)
                        .append("\n\n---\n\n");
            }
            FileOutputStream out = new FileOutputStream(file);
            out.write(text.toString().getBytes("UTF-8"));
            out.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/markdown");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Экспорт чата"));
        } catch (Exception e) {
            toast("Ошибка экспорта: " + e.getMessage());
        }
    }

    private void showLastCrash() {
        final String trace = CrashLog.read(this);
        if (trace.isEmpty()) return;
        CrashLog.clear(this);
        final String shown = trace.length() > 3200 ? trace.substring(0, 3200) + "\n…" : trace;
        new AlertDialog.Builder(this)
                .setTitle("Прошлый запуск завершился с ошибкой")
                .setMessage(shown)
                .setPositiveButton("Копировать", (dialog, which) -> copyToClipboard(shown))
                .setNeutralButton("Сбросить чаты", (dialog, which) -> {
                    new File(getFilesDir(), "chats.json").delete();
                    recreate();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showFatal(Throwable error) {
        try {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));

            ScrollView scroll = new ScrollView(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(16), dp(24), dp(16), dp(16));

            TextView title = new TextView(this);
            title.setText("Neymis Codex не смог запуститься");
            title.setTextSize(18);
            title.setTextColor(Palette.text);
            box.addView(title);

            TextView trace = new TextView(this);
            trace.setText(writer.toString());
            trace.setTextSize(11);
            trace.setTextColor(Palette.textDim);
            trace.setTextIsSelectable(true);
            box.addView(trace);

            Button reset = new Button(this);
            reset.setText("Сбросить чаты и открыть заново");
            reset.setOnClickListener(v -> {
                new File(getFilesDir(), "chats.json").delete();
                recreate();
            });
            box.addView(reset);

            scroll.addView(box);
            setContentView(scroll);
            rootView = box;
        } catch (Throwable ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
