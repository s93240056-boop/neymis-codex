package com.neymis.codex;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ChatListAdapter extends RecyclerView.Adapter<ChatListAdapter.Holder> {

    interface Listener {
        void onChatClick(Chat chat);

        void onChatLongPress(Chat chat);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        Holder(View view) {
            super(view);
            title = view.findViewById(R.id.chatItemTitle);
            subtitle = view.findViewById(R.id.chatItemSubtitle);
        }
    }

    private final List<Chat> items = new ArrayList<>();
    private final Listener listener;
    private String currentId;

    ChatListAdapter(Listener listener) {
        this.listener = listener;
    }

    void setItems(List<Chat> chats, String currentId) {
        items.clear();
        if (chats != null) items.addAll(chats);
        this.currentId = currentId;
        notifyDataSetChanged();
    }

    Chat itemAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        return items.get(position);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Chat chat = items.get(position);
        boolean current = chat.id.equals(currentId);

        holder.title.setText(chat.title == null || chat.title.isEmpty() ? "Новый чат" : chat.title);
        holder.title.setTextColor(Palette.text);

        StringBuilder subtitle = new StringBuilder();
        if (current) subtitle.append("● сейчас · ");
        int count = chat.messages.size();
        subtitle.append(count).append(" ").append(plural(count));
        if (chat.updated > 0) {
            subtitle.append(" · ")
                    .append(new SimpleDateFormat("d MMM, HH:mm", new Locale("ru"))
                            .format(new Date(chat.updated)));
        }
        holder.subtitle.setText(subtitle.toString());
        holder.subtitle.setTextColor(current ? Palette.accent : Palette.meta);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChatClick(chat);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onChatLongPress(chat);
            return true;
        });
    }

    private static String plural(int count) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) return "сообщений";
        if (mod10 == 1) return "сообщение";
        if (mod10 >= 2 && mod10 <= 4) return "сообщения";
        return "сообщений";
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
