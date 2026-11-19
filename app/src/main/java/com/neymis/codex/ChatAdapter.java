package com.neymis.codex;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

final class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.Holder> {

    private static final int MAX_RENDER = 24000;
    private static final int MAX_REASONING = 1200;

    private List<Message> messages;

    ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    void setMessages(List<Message> messages) {
        this.messages = messages;
        notifyDataSetChanged();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final View bubble;
        final TextView role;
        final TextView reasoning;
        final TextView text;

        Holder(View view) {
            super(view);
            root = view.findViewById(R.id.itemRoot);
            bubble = view.findViewById(R.id.itemBubble);
            role = view.findViewById(R.id.itemRole);
            reasoning = view.findViewById(R.id.itemReasoning);
            text = view.findViewById(R.id.itemText);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Message message = messages.get(position);
        boolean user = Message.USER.equals(message.role);

        int pad = dp(holder.root, 12);
        holder.root.setPadding(pad, dp(holder.root, 4), pad, dp(holder.root, 4));

        ViewGroup.LayoutParams raw = holder.bubble.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            params.gravity = user ? Gravity.END : Gravity.START;
            params.leftMargin = user ? dp(holder.root, 44) : 0;
            params.rightMargin = user ? 0 : dp(holder.root, 44);
            holder.bubble.setLayoutParams(params);
        }

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(holder.root, 18));
        shape.setColor(user ? Palette.userBubble : Palette.aiBubble);
        holder.bubble.setBackground(shape);

        holder.role.setText(user ? "Ты" : "Паймон");
        holder.role.setTextColor(user ? 0xCCFFFFFF : Palette.accent);
        holder.text.setTextColor(user ? Palette.userText : Palette.aiText);

        String reasoning = message.reasoning == null ? "" : message.reasoning;
        if (reasoning.isEmpty()) {
            holder.reasoning.setVisibility(View.GONE);
        } else {
            holder.reasoning.setVisibility(View.VISIBLE);
            holder.reasoning.setTextColor(Palette.textDim);
            holder.reasoning.setText(reasoning.length() > MAX_REASONING
                    ? reasoning.substring(0, MAX_REASONING) + "…"
                    : reasoning);
        }

        try {
            if (message.text == null || message.text.isEmpty()) {
                holder.text.setText("…");
                holder.text.setTextColor(Palette.textDim);
            } else {
                String source = message.text.length() > MAX_RENDER
                        ? message.text.substring(0, MAX_RENDER) + "\n\n… (обрезано)"
                        : message.text;
                holder.text.setText(Markdown.render(source, Palette.codeBg));
            }
        } catch (Throwable error) {
            holder.text.setText(message.text == null ? "" : message.text);
        }
    }

    @Override
    public int getItemCount() {
        return messages == null ? 0 : messages.size();
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
