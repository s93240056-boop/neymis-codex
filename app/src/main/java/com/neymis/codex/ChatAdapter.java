package com.neymis.codex;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_MESSAGE = 0;
    private static final int TYPE_SEPARATOR = 1;

    private static final int MAX_RENDER = 24000;
    private static final int MAX_REASONING = 1200;

    interface Listener {
        void onMessageLongPress(int messageIndex);

        void onRetry(int messageIndex);
    }

    private static final class Row {
        final boolean separator;
        final String label;
        final int index;

        Row(String label) {
            this.separator = true;
            this.label = label;
            this.index = -1;
        }

        Row(int index) {
            this.separator = false;
            this.label = "";
            this.index = index;
        }
    }

    static final class MessageHolder extends RecyclerView.ViewHolder {
        final FrameLayout root;
        final ImageView avatar;
        final LinearLayout bubble;
        final TextView role;
        final TextView reasoningHeader;
        final TextView reasoning;
        final LinearLayout content;
        final TextView typing;
        final TextView meta;

        Runnable tick;
        TextView streamView;
        CharSequence cursorBase;
        boolean cursorOn;

        MessageHolder(View view) {
            super(view);
            root = view.findViewById(R.id.itemRoot);
            avatar = view.findViewById(R.id.itemAvatar);
            bubble = view.findViewById(R.id.itemBubble);
            role = view.findViewById(R.id.itemRole);
            reasoningHeader = view.findViewById(R.id.itemReasoningHeader);
            reasoning = view.findViewById(R.id.itemReasoning);
            content = view.findViewById(R.id.itemContent);
            typing = view.findViewById(R.id.itemTyping);
            meta = view.findViewById(R.id.itemMeta);
        }
    }

    static final class SeparatorHolder extends RecyclerView.ViewHolder {
        final TextView label;

        SeparatorHolder(View view) {
            super(view);
            label = view.findViewById(R.id.separatorLabel);
        }
    }

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private List<Message> messages;
    private List<Row> rows = new ArrayList<>();
    private final Listener listener;

    ChatAdapter(List<Message> messages, Listener listener) {
        this.messages = messages;
        this.listener = listener;
        rebuild();
    }

    void setMessages(List<Message> messages) {
        this.messages = messages;
        rebuild();
        notifyDataSetChanged();
    }

    private void rebuild() {
        rows = new ArrayList<>();
        if (messages == null) return;
        String lastLabel = null;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String label = dayLabel(message.time);
            if (!label.equals(lastLabel)) {
                rows.add(new Row(label));
                lastLabel = label;
            }
            rows.add(new Row(i));
        }
    }

    int messageCount() {
        return messages == null ? 0 : messages.size();
    }

    int messageAt(int position) {
        if (position < 0 || position >= rows.size()) return -1;
        return rows.get(position).index;
    }

    void notifyMessage(int messageIndex) {
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).separator && rows.get(i).index == messageIndex) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    void refresh() {
        rebuild();
        notifyDataSetChanged();
    }

    void notifyLastMessage() {
        if (messages == null || messages.isEmpty()) return;
        int lastIndex = messages.size() - 1;
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (rows.get(i).separator) continue;
            if (rows.get(i).index == lastIndex) {
                notifyItemChanged(i);
            } else {
                refresh();
            }
            return;
        }
        refresh();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).separator ? TYPE_SEPARATOR : TYPE_MESSAGE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SEPARATOR) {
            return new SeparatorHolder(inflater.inflate(R.layout.item_separator, parent, false));
        }
        return new MessageHolder(inflater.inflate(R.layout.item_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder rawHolder, int position) {
        Row row = rows.get(position);
        if (row.separator) {
            if (rawHolder instanceof SeparatorHolder) {
                SeparatorHolder holder = (SeparatorHolder) rawHolder;
                holder.label.setText(row.label);
                holder.label.setTextColor(Palette.meta);
            }
            return;
        }
        if (!(rawHolder instanceof MessageHolder)) return;
        if (messages == null || row.index < 0 || row.index >= messages.size()) return;
        bindMessage((MessageHolder) rawHolder, messages.get(row.index), row.index);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof MessageHolder) stopTicker((MessageHolder) holder);
        super.onViewRecycled(holder);
    }

    private void bindMessage(final MessageHolder holder, final Message message, final int index) {
        stopTicker(holder);
        final Context context = holder.root.getContext();
        final boolean user = Message.USER.equals(message.role);

        int pad = dp(context, 12);
        holder.root.setPadding(pad, dp(context, 4), pad, dp(context, 4));

        ViewGroup.LayoutParams raw = holder.bubble.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            params.gravity = (user ? Gravity.END : Gravity.START) | Gravity.TOP;
            params.leftMargin = dp(context, 44);
            params.rightMargin = user ? 0 : dp(context, 16);
            holder.bubble.setLayoutParams(params);
        }

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 18));
        shape.setColor(user ? Palette.userBubble : Palette.aiBubble);
        holder.bubble.setBackground(shape);

        holder.avatar.setVisibility(user ? View.GONE : View.VISIBLE);

        holder.role.setText(user ? "Ты" : "Паймон");
        holder.role.setTextColor(user ? 0xCCFFFFFF : Palette.accent);

        String reasoning = message.reasoning == null ? "" : message.reasoning;
        boolean hasReasoning = !reasoning.isEmpty();
        holder.reasoningHeader.setVisibility(hasReasoning ? View.VISIBLE : View.GONE);
        holder.reasoning.setVisibility(hasReasoning ? View.VISIBLE : View.GONE);
        if (hasReasoning) {
            holder.reasoningHeader.setText(message.streaming ? "Размышления…" : "Размышления");
            holder.reasoningHeader.setTextColor(user ? 0x99FFFFFF : Palette.meta);
            holder.reasoning.setTextColor(user ? 0xCCFFFFFF : Palette.textDim);
            holder.reasoning.setText(reasoning.length() > MAX_REASONING
                    ? reasoning.substring(0, MAX_REASONING) + "…"
                    : reasoning);
        }

        holder.content.removeAllViews();
        holder.streamView = null;
        holder.cursorBase = null;

        buildContent(holder, message, index, user, context);

        boolean typing = message.streaming;
        holder.typing.setVisibility(typing ? View.VISIBLE : View.GONE);
        if (typing) {
            holder.typing.setTextColor(Palette.textDim);
            boolean thinking = hasReasoning && (message.text == null || message.text.isEmpty());
            holder.typing.setText(thinking ? "Размышляет…" : "Печатает…");
            startTicker(holder, message);
        }

        StringBuilder meta = new StringBuilder();
        if (message.time > 0) meta.append(clock(message.time));
        if (message.tokens > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(message.tokens).append(" ток.");
        }
        if (message.elapsedMs > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(String.format(Locale.US, "%.1f с", message.elapsedMs / 1000.0));
        }
        holder.meta.setText(meta.toString());
        holder.meta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
        holder.meta.setTextColor(user ? 0x99FFFFFF : Palette.meta);

        holder.bubble.setOnLongClickListener(v -> {
            if (listener != null) listener.onMessageLongPress(index);
            return true;
        });
    }

    private void buildContent(final MessageHolder holder, final Message message, final int index,
                              boolean user, final Context context) {
        String text = message.text == null ? "" : message.text;
        boolean streaming = message.streaming;

        if (!text.isEmpty()) {
            if (streaming) {
                TextView view = makeText(context, user);
                CharSequence rendered;
                try {
                    rendered = Markdown.render(cap(text), Palette.codeBg);
                } catch (Throwable error) {
                    rendered = text;
                }
                SpannableStringBuilder base = new SpannableStringBuilder(rendered);
                holder.cursorBase = base;
                holder.streamView = view;
                view.setText(base);
                view.append("▍");
                addToContent(holder, view, context);
            } else {
                List<Markdown.Block> blocks;
                try {
                    blocks = Markdown.blocks(cap(text));
                } catch (Throwable error) {
                    blocks = new ArrayList<>();
                    blocks.add(new Markdown.Block(false, "", cap(text)));
                }
                for (Markdown.Block block : blocks) {
                    if (block.code) {
                        addToContent(holder, codeBlock(context, block.language, block.text), context);
                    } else {
                        TextView view = makeText(context, user);
                        try {
                            view.setText(Markdown.render(block.text, Palette.codeBg));
                        } catch (Throwable error) {
                            view.setText(block.text);
                        }
                        addToContent(holder, view, context);
                    }
                }
            }
        }

        if (message.hasError()) {
            TextView error = new TextView(context);
            error.setText("⚠ " + message.error);
            error.setTextSize(13);
            error.setTextColor(Palette.error);
            error.setPadding(0, dp(context, text.isEmpty() ? 0 : 8), 0, 0);
            addToContent(holder, error, context);

            TextView retry = new TextView(context);
            retry.setText("↻ Повторить");
            retry.setTextSize(13);
            retry.setTextColor(Palette.accent);
            retry.setPadding(0, dp(context, 8), dp(context, 8), dp(context, 2));
            retry.setOnClickListener(v -> {
                if (listener != null) listener.onRetry(index);
            });
            addToContent(holder, retry, context);
        }
    }

    private void addToContent(MessageHolder holder, View view, Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 6);
        holder.content.addView(view, params);
    }

    private TextView makeText(Context context, boolean user) {
        TextView view = new TextView(context);
        view.setTextSize(15);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setTextColor(user ? Palette.userText : Palette.aiText);
        view.setTextIsSelectable(false);
        return view;
    }

    private View codeBlock(final Context context, String language, final String code) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Palette.codeBg);
        shape.setCornerRadius(dp(context, 10));
        box.setBackground(shape);
        box.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 10));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView lang = new TextView(context);
        lang.setText(language == null || language.isEmpty() ? "код" : language);
        lang.setTextSize(11);
        lang.setTextColor(Palette.meta);
        lang.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(lang);

        TextView copy = new TextView(context);
        copy.setText("⧉ Копировать");
        copy.setTextSize(11);
        copy.setTextColor(Palette.accent);
        copy.setPadding(dp(context, 8), dp(context, 4), dp(context, 2), dp(context, 4));
        copy.setOnClickListener(v -> copyText(context, code, "Код скопирован"));
        header.addView(copy);

        box.addView(header);

        TextView body = new TextView(context);
        body.setText(code);
        body.setTextSize(13);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(Palette.aiText);
        body.setPadding(0, dp(context, 4), 0, 0);
        body.setTextIsSelectable(false);
        box.addView(body);

        return box;
    }

    private void copyText(Context context, String text, String toast) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("Neymis Codex", text));
                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private void startTicker(final MessageHolder holder, final Message message) {
        Runnable runnable = new Runnable() {
            int frame;

            @Override
            public void run() {
                if (!message.streaming) return;
                frame++;
                int phase = frame % 4;
                String dots = phase == 1 ? "." : (phase == 2 ? ".." : (phase == 3 ? "..." : ""));
                boolean thinking = message.reasoning != null && !message.reasoning.isEmpty()
                        && (message.text == null || message.text.isEmpty());
                if (thinking) {
                    long seconds = Math.max(0, (System.currentTimeMillis() - message.startedAt) / 1000);
                    holder.typing.setText(seconds > 0
                            ? "Размышляет… " + seconds + " с"
                            : "Размышляет" + dots);
                } else {
                    holder.typing.setText("Печатает" + dots);
                }
                if (holder.streamView != null && holder.cursorBase != null) {
                    holder.cursorOn = !holder.cursorOn;
                    holder.streamView.setText(holder.cursorBase);
                    if (holder.cursorOn) holder.streamView.append("▍");
                }
                ticker.postDelayed(this, 420);
            }
        };
        holder.tick = runnable;
        ticker.postDelayed(runnable, 420);
    }

    private void stopTicker(MessageHolder holder) {
        if (holder.tick != null) {
            ticker.removeCallbacks(holder.tick);
            holder.tick = null;
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private static String cap(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_RENDER) return text;
        return text.substring(0, MAX_RENDER) + "\n\n… (обрезано)";
    }

    private static String clock(long time) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(time));
    }

    private static String dayLabel(long time) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(time <= 0 ? System.currentTimeMillis() : time);
        if (sameDay(now, then)) return "Сегодня";
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(yesterday, then)) return "Вчера";
        return new SimpleDateFormat("d MMMM", new Locale("ru"))
                .format(new Date(then.getTimeInMillis()));
    }

    private static boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
