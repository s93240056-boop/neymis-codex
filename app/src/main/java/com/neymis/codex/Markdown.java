package com.neymis.codex;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

final class Markdown {

    private Markdown() {
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
