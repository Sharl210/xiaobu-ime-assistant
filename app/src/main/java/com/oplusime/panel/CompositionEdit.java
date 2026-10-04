package com.oplusime.panel;

/** 原始拼音的纯编辑模型：不含 Android 或宿主标识。 */
final class CompositionEdit {
    static final class Result {
        final String text;
        final int caret;
        Result(String text, int caret) { this.text = text; this.caret = caret; }
    }
    static Result apply(String text, int position, int key) {
        int caret = Math.max(0, Math.min(position, text.length()));
        if (key == 0xff08 || key == 8) {
            if (caret == 0) return new Result(text, 0);
            int left = text.offsetByCodePoints(caret, -1);
            return new Result(text.substring(0, left) + text.substring(caret), left);
        }
        if (key >= 'a' && key <= 'z' || key >= 'A' && key <= 'Z' || key == '\'') {
            return new Result(text.substring(0, caret) + (char) key + text.substring(caret), caret + 1);
        }
        return null;
    }
}
