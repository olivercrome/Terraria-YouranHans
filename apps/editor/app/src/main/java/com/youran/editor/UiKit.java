package com.youran.editor;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UiKit —— 纯 View 排版制造器(只依赖 Context + Skin 语义色)。
 * 从 MainActivity 抽出的一批「无 Activity 状态」的小工具，做到结构性减脂：
 * MainActivity 只保留带交互/命中状态的逻辑，本类专心画统一风格的小件。
 * java.util 常驻 use。均为静态，首参传 Context(通常 Activity 本身)。
 */
public final class UiKit {
    private UiKit() {}                       // 纯静态工具，禁止实例化

    /** dp —— 与 MainActivity.dp 一致的密度换算(在 UiKit 内部自足，一行不依赖外部)。 */
    static int dp(Context c, float dip) {
        return (int) (c.getResources().getDisplayMetrics().density * dip + 0.5f);
    }

    /** 灰灰小段标题(设置面板小节头)。 */
    public static TextView sectionLabel(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(13f);
        t.setTextColor(Skin.mutedText(ctx));
        t.setPadding(0, dp(ctx, 16), 0, dp(ctx, 6));
        return t;
    }

    /** 一句灰色小说明(不放卡片，简单文本底当前主题)。ignored 兼容旧调用被弃参(UI 布局位子句仍可替)。 */
    public static View addInfo(Context ctx, LinearLayout ignored, String text) {
        TextView t = new TextView(ctx);
        t.setText("· " + text);
        t.setTextSize(12f);
        t.setTextColor(Skin.mutedText(ctx));
        t.setPadding(dp(ctx, 8), 0, dp(ctx, 8), dp(ctx, 6));
        t.setTextIsSelectable(false);
        return t;
    }

    /** 一个可点击的条目行(左文案右箭头)。圆角卡片外观。 */
    public static LinearLayout settingRow(Context ctx, String label, String value, View.OnClickListener on) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextSize(15f);
        l.setTextColor(Skin.text(ctx));
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(ctx);
        val.setText(value == null ? "" : value);
        val.setTextSize(13f);
        val.setTextColor(Skin.subText(ctx));
        row.addView(val);
        TextView arrow = new TextView(ctx);
        arrow.setText("  ›");
        arrow.setTextSize(16f);
        arrow.setTextColor(Skin.mutedText(ctx));
        row.addView(arrow);
        row.setOnClickListener(on);
        row.setClickable(true);
        row.setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Skin.cardFill(ctx));
        gd.setCornerRadius(dp(ctx, 10));
        row.setBackground(gd);
        return row;
    }

    /** 圆角矩形背景(实心)。 */
    public static GradientDrawable rounded(Context ctx, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, 10));
        return g;
    }

    /** 顶端圆角卡(实心 + 可选小标题)。history/嵌套展示用。 */
    public static LinearLayout roundedList(Context ctx, int bgHex, String head) {
        LinearLayout ll = new LinearLayout(ctx);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 6), dp(ctx, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ll.setLayoutParams(lp);
        ll.setBackground(rounded(ctx, bgHex));
        if (head != null && !head.isEmpty()) {
            TextView h = new TextView(ctx);
            h.setText(head);
            h.setTextSize(12f);
            h.setTextColor(0xFF222222);
            ll.addView(h);
        }
        return ll;
    }

    /** key/旧/新 小段行(Strike+红=旧，绿=新，strike 由调用方给色决定)。 */
    public static TextView labelLine(Context ctx, String slash, String content, int color, boolean strike) {
        TextView t = new TextView(ctx);
        String tag = slash.isEmpty() ? "" : ("[" + slash + "] ");
        t.setText(tag + (content == null ? "（空）" : content));
        t.setTextSize(13f);
        t.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
        t.setTextColor(color);
        if (strike) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        return t;
    }

    /** 竖直小空隙(带纵向高度)。 */
    public static View spacer(Context ctx, int dip) {
        View v = new View(ctx);
        v.setLayoutParams(new ViewGroup.LayoutParams(1, dp(ctx, dip)));
        return v;
    }

    /** 包一层竖直用于上下留一点白的小容器。 */
    public static LinearLayout outerAdded(Context ctx, View inner) {
        LinearLayout out = new LinearLayout(ctx);
        out.setOrientation(LinearLayout.VERTICAL);
        out.setPadding(0, dp(ctx, 2), 0, dp(ctx, 2));
        out.addView(inner);
        return out;
    }

    /** 圆角小按钮(chip)。底色 tint、圆角14、自带右距8(供顺序 add 的横排)。 */
    public static TextView chipBtn(Context ctx, String label, int tint) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(13f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF222222);
        t.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        GradientDrawable g = new GradientDrawable();
        g.setColor(tint);
        g.setCornerRadius(dp(ctx, 14));
        t.setBackground(g);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(ctx, 8);
        t.setLayoutParams(lp);
        return t;
    }

    /** 模式小按钮(无内边距、圆形切莫。配色随后由调用方按选中态 setBackground/setTextColor)。 */
    public static TextView chipBtnC(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextSize(13f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        return t;
    }
}
