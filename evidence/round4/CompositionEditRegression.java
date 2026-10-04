package com.oplusime.panel;

/** 可直接用 JDK 运行的编辑模型回归，独立于设备验证。 */
public final class CompositionEditRegression {
    private static int checks;
    private static void check(String input, int caret, int key, String text, int position) {
        CompositionEdit.Result result = CompositionEdit.apply(input, caret, key);
        if (result == null || !result.text.equals(text) || result.caret != position)
            throw new AssertionError(input + " @ " + caret + " key=" + key);
        checks++;
    }
    public static void main(String[] args) {
        check("nihao", 2, 'x', "nixhao", 3);
        check("nixhao", 3, 0xff08, "nihao", 2);
        check("nihao", 2, 0xff08, "nhao", 1);
        check("nihao", 0, 0xff08, "nihao", 0);
        check("nihao", 0, 'x', "xnihao", 1);
        check("nihao", 5, 'x', "nihaox", 6);
        check("n", 1, 0xff08, "", 0);
        check("nihao", 2, '\'', "ni'hao", 3);
        check("nihao", -2, 'X', "Xnihao", 1);
        check("nihao", 99, 8, "niha", 4);
        if (CompositionEdit.apply("nihao", 2, 0xff0d) != null)
            throw new AssertionError("Return must remain on host path");
        checks++;
        for (String input : new String[] {"n", "nihao", "zhongguoren", "abcdefghijklmnopqrstuvwxyz"}) {
            for (int position = 0; position <= input.length(); position++) {
                CompositionEdit.Result inserted = CompositionEdit.apply(input, position, 'a');
                check(inserted.text, inserted.caret, 0xff08, input, position);
            }
        }
        System.out.println("PASS: " + checks + " raw composition edit checks");
    }
}
