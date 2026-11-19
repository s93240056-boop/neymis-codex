package com.neymis.codex;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.ArrayList;
import java.util.List;

final class Markdown {

    private Markdown() {
    }

    static final class Block {
        final boolean code;
        final String language;
        final String text;

        Block(boolean code, String language, String text) {
            this.code = code;
            this.language = language;
            this.text = text;
        }
    }

    /**
     * Режет ответ на куски: обычный текст и блоки кода.
     * Нужно, чтобы у каждого блока кода была своя кнопка «Копировать».
     */
    static List<Block> blocks(String source) {
        List<Block> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        String[] lines = source.split("\n", -1);
        boolean inCode = false;
        String language = "";
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    result.add(new Block(true, language, trimBlank(buffer.toString())));
                    buffer.setLength(0);
                    inCode = false;
                    language = "";
                } else {
                    String text = trimBlank(buffer.toString());
                    if (!text.isEmpty()) result.add(new Block(false, "", text));
                    buffer.setLength(0);
                    inCode = true;
                    language = trimmed.substring(3).trim();
                }
                continue;
            }
            if (buffer.length() > 0) buffer.append('\n');
            buffer.append(line);
        }
        if (buffer.length() > 0) {
            String text = trimBlank(buffer.toString());
            if (!text.isEmpty()) result.add(new Block(inCode, language, text));
        }
        return result;
    }

    private static String trimBlank(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    static final class Section {
        final int level;
        final String title;
        final String body;

        Section(int level, String title, String body) {
            this.level = level;
            this.title = title;
            this.body = body;
        }
    }

    /**
     * Делит ответ на разделы по заголовкам #, ##, ###.
     * Строки внутри блоков кода заголовками не считаются.
     */
    static List<Section> sections(String source) {
        List<Section> result = new ArrayList<>();
        if (source == null || source.isEmpty()) return result;
        String[] lines = source.split("\n", -1);
        boolean inCode = false;
        int level = 0;
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCode = !inCode;
            }
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') hashes++;
            boolean heading = !inCode && hashes >= 1 && hashes <= 3
                    && hashes < trimmed.length() && trimmed.charAt(hashes) == ' ';
            if (heading) {
                if (title != null || body.length() > 0) {
                    result.add(new Section(level, title, trimBlank(body.toString())));
                }
                body.setLength(0);
                level = hashes;
                title = trimBlank(trimmed.substring(hashes));
                continue;
            }
            if (body.length() > 0) body.append('\n');
            body.append(line);
        }
        if (title != null || body.length() > 0) {
            result.add(new Section(level, title, trimBlank(body.toString())));
        }
        return result;
    }

    static CharSequence render(String source, int codeBackground) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null || source.isEmpty()) return out;
        String[] lines = source.split("\n", -1);
        boolean code = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                code = !code;
                if (code) {
                    String language = trimmed.substring(3).trim();
                    if (!language.isEmpty()) {
                        int start = out.length();
                        out.append(language);
                        out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.setSpan(new RelativeSizeSpan(0.8f), start, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        out.append('\n');
                    }
                }
                continue;
            }
            if (code) {
                int start = out.length();
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBackground), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                renderLine(line, out, codeBackground);
            }
            if (index < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static void renderLine(String line, SpannableStringBuilder out, int codeBackground) {
        String trimmed = line.trim();
        if (trimmed.startsWith("#")) {
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
            String text = trimmed.substring(level).trim();
            int start = out.length();
            inline(text, out, codeBackground);
            out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            float scale = level <= 1 ? 1.22f : (level == 2 ? 1.12f : 1.05f);
            out.setSpan(new RelativeSizeSpan(scale), start, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            out.append("•  ");
            inline(trimmed.substring(2), out, codeBackground);
            return;
        }
        if (trimmed.startsWith("> ")) {
            out.append("│  ");
            inline(trimmed.substring(2), out, codeBackground);
            return;
        }
        inline(line, out, codeBackground);
    }

    private static void inline(String line, SpannableStringBuilder out, int codeBackground) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(line, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(codeBackground), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                int end = line.indexOf("**", i + 2);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(line, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '*') {
                int end = line.indexOf('*', i + 1);
                if (end > i + 1) {
                    int start = out.length();
                    out.append(line, i + 1, end);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
    }
}
