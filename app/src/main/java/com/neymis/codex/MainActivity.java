package com.neymis.codex;

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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_BASE = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "openrouter/auto";

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
    private boolean streaming;
    private int generation;
    private long lastNotify;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("codex", MODE_PRIVATE);
        Palette.apply(prefs.getBoolean("dark", true));
        setTheme(Palette.dark ? R.style.AppTheme : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
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
        adapter = new ChatAdapter(chat.messages);
        recycler.setAdapter(adapter);

        newButton.setOnClickListener(v -> newChat());
        chatsButton.setOnClickListener(v -> showChats());
        settingsButton.setOnClickListener(v -> showSettings());
        sendButton.setOnClickListener(v -> send());

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

    private void send() {
        if (streaming) {
            stopStreaming();
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;

        String key = prefs.getString("key", "").trim();
        if (key.isEmpty()) {
            toast("Вставь ключ OpenRouter в настройках ⚙");
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
        adapter.notifyDataSetChanged();
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
        String system = prefs.getString("system", getString(R.string.default_system));
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
        streaming = true;
        pendingMessage = target;
        request = new Api.Request();
        updateSendButton();

        final int token = ++generation;
        Api.chat(base, key, model, system, temperature, payload, request,
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
                    public void onDone() {
                        if (token != generation) return;
                        ui.post(() -> {
                            if (token == generation) finishStreaming(target, null);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        if (token != generation) return;
                        ui.post(() -> {
                            if (token == generation) finishStreaming(target, message);
                        });
                    }
                });
    }

    private void notifyDelta() {
        long now = System.currentTimeMillis();
        if (now - lastNotify < 70) return;
        lastNotify = now;
        if (chat.messages.isEmpty()) return;
        adapter.notifyItemChanged(chat.messages.size() - 1);
        autoScroll();
    }

    private void finishStreaming(Message target, String error) {
        streaming = false;
        pendingMessage = null;
        request = null;
        updateSendButton();

        String text = streamBuffer == null ? "" : streamBuffer.toString();
        if (error != null) {
            target.text = text.isEmpty() ? "⚠ " + error : text + "\n\n⚠ " + error;
        } else if (text.isEmpty()) {
            target.text = "⚠ Пустой ответ. Проверь модель и ключ.";
        } else {
            target.text = text;
        }

        chat.updated = System.currentTimeMillis();
        store.save();
        adapter.notifyDataSetChanged();
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
            pendingMessage.text = text.isEmpty() ? "⏹ Остановлено" : text + "\n\n⏹ Остановлено";
            pendingMessage = null;
        }
        store.save();
        adapter.notifyDataSetChanged();
        updateSendButton();
    }

    private void scrollToEnd() {
        recycler.post(() -> {
            if (!chat.messages.isEmpty()) {
                recycler.scrollToPosition(chat.messages.size() - 1);
            }
        });
    }

    private void autoScroll() {
        LinearLayoutManager manager = (LinearLayoutManager) recycler.getLayoutManager();
        if (manager == null) return;
        int last = manager.findLastVisibleItemPosition();
        if (last >= chat.messages.size() - 2) {
            recycler.scrollToPosition(chat.messages.size() - 1);
        }
    }

    private void newChat() {
        if (streaming) stopStreaming();
        Chat fresh = new Chat(String.valueOf(System.currentTimeMillis()));
        store.chats.add(0, fresh);
        store.currentId = fresh.id;
        store.save();
        chat = fresh;
        adapter.setMessages(chat.messages);
        updateEmpty();
        input.requestFocus();
    }

    private void showChats() {
        final List<Chat> items = new ArrayList<>(store.chats);
        if (items.isEmpty()) {
            newChat();
            return;
        }
        String[] titles = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Chat item = items.get(i);
            String mark = item.id.equals(store.currentId) ? "● " : "";
            String title = item.title.length() > 34 ? item.title.substring(0, 34) + "…" : item.title;
            titles[i] = mark + title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Чаты")
                .setItems(titles, (dialog, which) -> openChat(items.get(which)))
                .setNeutralButton("Удалить текущий", (dialog, which) -> confirmDelete())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void openChat(Chat target) {
        if (streaming) stopStreaming();
        chat = target;
        store.select(target);
        adapter.setMessages(chat.messages);
        updateEmpty();
        scrollToEnd();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Удалить чат?")
                .setMessage(chat.title)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    if (streaming) stopStreaming();
                    store.delete(chat);
                    chat = store.current();
                    adapter.setMessages(chat.messages);
                    updateEmpty();
                })
                .setNegativeButton("Отмена", null)
                .show();
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
        settingsSystem.setText(prefs.getString("system", getString(R.string.default_system)));
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
            saveSettings();
            dialog.dismiss();
        });
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            saveSettings();
            dialog.dismiss();
            exportChat();
        });
    }

    private String tempText(int progress) {
        return "Температура: " + String.format(Locale.US, "%.1f", progress / 10.0);
    }

    private void saveSettings() {
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
        if (oldDark != newDark) recreate();
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
                text.append(Message.USER.equals(message.role) ? "**Ты:**" : "**Ава:**")
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
