package com.youran.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 皮肤/外观设置：两块 UI 主色(顶栏/抽屉头)与主内容区背景图，全部持久化于 SharedPreferences + internal 文件。
 * 纯平台 API(零 AndroidX)：背景位图用 bounds 采样 + quality 压缩，防大图 OOM。
 * 由 MainActivity 调用；设置面板即时生效即重刷视图。
 */
final class Skin {
    private static final String PREFS = "skin";
    private static final String KEY_TOPBAR = "topbar_color";
    private static final String KEY_DRAWERHEAD = "drawerhead_color";
    private static final String BG_FILE = "bg.jpg";
    private static final String KEY_DARK = "dark_theme";   // UI 美化第三轮：深/浅色主题(默认浅色)
    private static final String KEY_BLUR = "bg_blur";      // UI 美化第三轮：背景模糊强度 0..100(默认0=无)

    static final int DEF_TOPBAR = 0xFF2C7BB6;      // 默认顶栏蓝
    static final int DEF_DRAWERHEAD = 0xFF1E5D8F;  // 默认抽屉头深蓝

    /** 本机屏幕最大边对应的后台解码目标边长(采样上限，超出则按比例降采样)。 */
    private static final int MAX_SIDE = 2048;
    /** 背景压缩质量(JPEG 0-100)。 */
    private static final int JPEG_Q = 88;

    private Skin() { }

    // ---- 主色持久化 ----

    static int topBarColor(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_TOPBAR, DEF_TOPBAR);
    }

    static int drawerHeadColor(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DRAWERHEAD, DEF_DRAWERHEAD);
    }

    static void saveTopBarColor(Context c, int color) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_TOPBAR, color).apply();
    }

    static void saveDrawerHeadColor(Context c, int color) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DRAWERHEAD, color).apply();
    }

    // ---- 预设色板(供设置界面快速选；含默认与若干协调色) ----

    static final class Chip { final String label; final int color; Chip(String l, int c){ label = l; color = c; } }

    /** 顶栏可选色(亮组)。 */
    static Chip[] topBarPalette() {
        return new Chip[]{
            new Chip("默认蓝", 0xFF2C7BB6),
            new Chip("深藏青", 0xFF1E5D8F),
            new Chip("青绿",   0xFF2A9D8F),
            new Chip("橙",    0xFFE76F51),
            new Chip("靛紫",   0xFF6A5FA8),
            new Chip("墨灰",   0xFF55656E)
        };
    }

    /** 抽屉头可选色(深组，常比顶栏更深以分层)。 */
    static Chip[] drawerHexPalette() {
        return new Chip[]{
            new Chip("默认深蓝", 0xFF1E5D8F),
            new Chip("深藏青",   0xFF153E5C),
            new Chip("深青绿",   0xFF1F7066),
            new Chip("深橙",    0xFFB74F3A),
            new Chip("深靛",    0xFF4A4382),
            new Chip("近黑",    0xFF343A40)
        };
    }

    // ---- UI 美化第三轮 · 深浅色主题(深/浅切换，持久化；集中定义语义色供 MainActivity 复用) ----

    /** 当前是否深色主题(默认浅色)。 */
    static boolean isDark(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false);
    }

    static void saveDark(Context c, boolean dark) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DARK, dark).apply();
    }

    /** 背景模糊强度 0..100(0=不模糊)。 */
    static int blur(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BLUR, 0);
    }

    static void saveBlur(Context c, int v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_BLUR, Math.max(0, Math.min(100, v))).apply();
    }

    // 语义配色(明/暗两套)，尽量集中：改这里即全局换肤,减少散落的硬编码。
    // 浅色(默认)以 MVP/第二轮审美为准；深色取一套低反光、可读性好的深底浅字。

    /** 列表/面板主体文字(key、文件名、分支标题)。 */
    static int text(Context c) { return isDark(c) ? 0xFFE2E4E8 : 0xFF212121; }
    /** 次级文字(分支小字、value 预览、空态说明)。 */
    static int subText(Context c) { return isDark(c) ? 0xFF8E939B : 0xFF777777; }
    /** 更淡的辅助文字(路径、文件行图标点缀)。 */
    static int mutedText(Context c) { return isDark(c) ? 0xFF6B7178 : 0xFF8A92A3; }
    /** 分隔线/浅层描边。 */
    static int divider(Context c) { return isDark(c) ? 0xFF2A2E35 : 0xFFEFEFEF; }
    /** 行分隔(略深一档，令行边界更清晰)。 */
    static int rowDivider(Context c) { return isDark(c) ? 0xFF2A2E35 : 0xFFF0F0F0; }
    /** 主表面(抽屉/右滑面板底、空态卡片底)。 */
    static int surface(Context c) { return isDark(c) ? 0xFF1E2126 : 0xFFFFFFFF; }
    /** 面板头部/分隔浅底(设置面板头)。 */
    static int surfaceAlt(Context c) { return isDark(c) ? 0xFF17191D : 0xFFF2F4F7; }
    /** 圆角卡片内浅填色(设置里的条目行、「新建 key」按钮、空态卡内描述)。 */
    static int cardFill(Context c) { return isDark(c) ? 0xFF292E35 : 0xFFF7F8FA; }
    /** 输入框底(edit 圆角盒)。 */
    static int inputBg(Context c) { return isDark(c) ? 0xFF17191D : 0xFFF3F4F6; }
    /** 输入框底描边。 */
    static int inputStroke(Context c) { return isDark(c) ? 0xFF3A3F47 : 0xFFD3D7DE; }
    /** 对象(含子项)行的 key 文字/前景引导蓝。 */
    static int accentObj(Context c) { return isDark(c) ? 0xFF79A6FF : 0xFF2F6BFF; }
    /** 对象行下方“子项数”小字的偏蓝色。 */
    static int accentObjDim(Context c) { return isDark(c) ? 0xFF6F9BE8 : 0xFF5A8FD6; }
    /** 内容纯色底(无背景图时主区底色；dark 用之)。 */
    static int contentBg(Context c) { return isDark(c) ? 0xFF121317 : 0xFFFFFFFF; }
    /** 主区域当前路径小条(可点感)。 */
    static int crumbBg(Context c) { return isDark(c) ? 0xFF23262C : 0xFFEFF3F7; }
    /** 新建 key 占位按钮底(亮)。 */
    static int addBtnBg(Context c) { return isDark(c) ? 0xFF343A43 : 0xFFE4E4E4; }
    /** 状态栏/顶栏文字在明暗下的通用自适应色(顶栏文字固定白即可)。 */

    // ---- 背景图：导入(压缩存 internal) / 有无判断 / 按屏尺寸采样解码 ----

    /** 有无已导入背景。 */
    static boolean hasBg(Context c) {
        return bgFile(c).exists();
    }

    static File bgFile(Context c) {
        return new File(c.getFilesDir(), BG_FILE);
    }

    /**
     * 设置里点选一张图(uri)：先读 bounds 推算 inSampleSize，再采样解码,
     * 压缩成 JPEG 写到 internal；失败返回 null(原因放 err)。
     */
    static Boolean saveBgFromUri(Context c, Uri uri, StringBuilder err) {
        try {
            // 第一步：读宽高(不整图解码)以决定采样
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream is = c.getContentResolver().openInputStream(uri);
            if (is == null) { err.append("打不开所选文件"); return null; }
            BitmapFactory.decodeStream(is, null, o);
            is.close();
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) { err.append("不是可解码的图片"); return null; }

            // 采样到不超过上限的边长
            o.inJustDecodeBounds = false;
            int sample = 1;
            int maxSide = Math.max(w, h);
            while (maxSide / sample > MAX_SIDE) sample *= 2;
            o.inSampleSize = sample;

            is = c.getContentResolver().openInputStream(uri);
            if (is == null) { err.append("读取失败"); return null; }
            Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
            is.close();
            if (bmp == null) { err.append("解码失败"); return null; }

            // 压缩写盘(覆盖旧背景)
            File f = bgFile(c);
            if (f.exists() && !f.delete()) { /* 忽略旧档未删 */ }
            FileOutputStream os = new FileOutputStream(f);
            try { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, os); os.flush(); }
            finally { os.close(); bmp.recycle(); }
            return Boolean.TRUE;
        } catch (Exception e) {
            err.append("导入失败: ").append(e.getMessage() == null ? e.toString() : e.getMessage());
            return null;
        }
    }

    /**
     * 应用背景：把 internal bg 解码为一块不超过屏边的采样 Bitmap(内存可控)。
     * 没有背景返回 null(调用方垫纯色即可，null 表示无图)。
     */
    static Bitmap loadBgBitmap(Context c) {
        File f = bgFile(c);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            int sample = 1, maxSide = Math.max(w, h);
            while (maxSide / sample > MAX_SIDE) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 生成“给用户看”的背景：src 为已解码原图，按当前模糊强度做轻量磨砂(小图放大)，并按主题
     * 自动叠一层压暗(深色主题让浅图让字)；浅色主题不加罩以免整体太暗。返回可用新图；不用 recycle 原图。
     */
    static Bitmap processForDisplay(Context c, Bitmap src) {
        if (src == null || src.isRecycled()) return src;
        Bitmap out = src;
        int blur = blur(c);
        if (blur > 0) out = softBlur(src, blur);
        return tintByTheme(c, out, blur);
    }

    /** 真雾模糊：逐级对半双线性缩档叠加(≈高斯)，最后放大回原尺寸。拉满像隔雾、留淡形。 */
    private static Bitmap softBlur(Bitmap src, int blur) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 1 || h <= 1 || blur <= 0) return src;
        float k = blur / 100f;
        // 越小越糊：拉满约缩到宽 ~1/5(层级内的细节充分并入，像雾淡影)，微保留形状
        int tW = Math.max(3, (int) (w * (0.62f - 0.42f * k)));
        int curW = w; Bitmap cur = src;
        int guard = 0;
        while (curW > tW && guard < 42) {
            guard++;
            int nw = Math.max(Math.max(1, tW), curW / 2);
            int nh = Math.max(1, (int) ((float) nw * h / w));
            if (nh < 1) nh = 1;
            Bitmap nxt = Bitmap.createScaledBitmap(cur, Math.max(1, nw),
                    Math.max(1, nh), true);
            if (nxt != cur) cur = nxt;
            curW = nw;
            if (curW <= tW + 5) curW = tW;
        }
        if (cur == src) return src;
        Bitmap big = Bitmap.createScaledBitmap(cur, w, h, true); // 双线性放大：雾顺感
        if (big != cur) return big;
        return cur;
    }


    /** 按主题压暗/提亮罩：深色压暗保浅字可读；顺带在“强雾化”时叠一层淡奶色让字与背景更好分、更护眼。 */
    private static Bitmap tintByTheme(Context c, Bitmap in, int blur) {
        if (in == null || in.isRecycled()) return in;
        boolean dark = isDark(c);
        Bitmap out = in.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null) return in;
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint();
        if (dark) {
            p.setColor(0x52000000);        // 深色：自然压暗(略提一点确保浅字稳)
            cv.drawRect(0, 0, out.getWidth(), out.getHeight(), p);
        }
        // 雾化较浓时，无论明暗都再罩一层很淡的奶灰，让文字衬得开(视觉“轻雾纸感”)
        if (blur >= 30) {
            p.setColor(dark ? 0x1AFFFFFF : 0x14FFFFFF);
            cv.drawRect(0, 0, out.getWidth(), out.getHeight(), p);
        }
        return out;
    }
}
