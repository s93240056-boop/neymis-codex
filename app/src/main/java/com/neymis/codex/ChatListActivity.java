package com.neymis.codex;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ChatListActivity extends AppCompatActivity {

    static final String EXTRA_CHAT = "chatId";
    static final String NEW_CHAT = "__new__";

    private ChatStore store;
    private ChatListAdapter adapter;
    private EditText search;
    private TextView empty;
    private String selected;
    private boolean changed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("codex", MODE_PRIVATE);
        Palette.apply(prefs.getBoolean("dark", true));
        setTheme(Palette.dark ? R.style.AppTheme : R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_chat_list);
            setup();
        } catch (Throwable error) {
            Intent data = new Intent();
            setResult(RESULT_CANCELED, data);
            finish();
        }
    }

    private void setup() {
        LinearLayout root = findViewById(R.id.chatListRoot);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Palette.bgStart, Palette.bgEnd});
        root.setBackground(background);

        ((TextView) findViewById(R.id.backButton)).setTextColor(Palette.accent);
        ((TextView) findViewById(R.id.newChatButton)).setTextColor(Palette.accent);
        ((TextView) findViewById(R.id.listTitle)).setTextColor(Palette.text);

        search = findViewById(R.id.searchField);
        search.setTextColor(Palette.text);
        search.setHintTextColor(Palette.textDim);
        GradientDrawable inputShape = new GradientDrawable();
        inputShape.setCornerRadius(dp(20));
        inputShape.setColor(Palette.inputBg);
        inputShape.setStroke(dp(1), Palette.stroke);
        search.setBackground(inputShape);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));

        empty = findViewById(R.id.chatListEmpty);
        empty.setTextColor(Palette.textDim);

        store = new ChatStore(this);

        RecyclerView list = findViewById(R.id.chatList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);

        adapter = new ChatListAdapter(new ChatListAdapter.Listener() {
            @Override
            public void onChatClick(Chat chat) {
                selected = chat.id;
                finish();
            }

            @Override
            public void onChatLongPress(Chat chat) {
                showRename(chat);
            }
        });
        list.setAdapter(adapter);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                Chat chat = adapter.itemAt(position);
                if (chat == null) {
                    submit();
                    return;
                }
                confirmDelete(chat, position);
            }
        });
        helper.attachToRecyclerView(list);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.newChatButton).setOnClickListener(v -> {
            selected = NEW_CHAT;
            finish();
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                submit();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        submit();
    }

    private void submit() {
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.getDefault());
        List<Chat> filtered = new ArrayList<>();
        for (Chat chat : store.chats) {
            if (query.isEmpty() || matches(chat, query)) filtered.add(chat);
        }
        adapter.setItems(filtered, store.currentId);
        empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(query.isEmpty() ? "Пока нет чатов" : "Ничего не найдено");
    }

    private static boolean matches(Chat chat, String query) {
        if (chat.title != null && chat.title.toLowerCase(Locale.getDefault()).contains(query)) {
            return true;
        }
        for (Message message : chat.messages) {
            if (message.text != null
                    && message.text.toLowerCase(Locale.getDefault()).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private void confirmDelete(final Chat chat, final int position) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить чат?")
                .setMessage(chat.title)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    store.delete(chat);
                    changed = true;
                    submit();
                })
                .setNegativeButton("Отмена", (dialog, which) -> restore(position))
                .setOnCancelListener(dialog -> restore(position))
                .show();
    }

    private void restore(int position) {
        if (position >= 0 && position < adapter.getItemCount()) {
            adapter.notifyItemChanged(position);
        } else {
            submit();
        }
    }

    private void showRename(final Chat chat) {
        final EditText field = new EditText(this);
        field.setText(chat.title);
        field.setSelection(field.getText().length());
        field.setTextColor(Palette.text);
        field.setHintTextColor(Palette.textDim);
        field.setHint("Название чата");

        new AlertDialog.Builder(this)
                .setTitle("Переименовать чат")
                .setView(field)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String title = field.getText().toString().trim();
                    if (!title.isEmpty()) {
                        chat.title = title;
                        store.save();
                        changed = true;
                        submit();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public void finish() {
        Intent data = new Intent();
        if (selected != null) data.putExtra(EXTRA_CHAT, selected);
        setResult(changed || selected != null ? RESULT_OK : RESULT_CANCELED, data);
        super.finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
