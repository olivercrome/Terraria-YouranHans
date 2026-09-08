package com.youran.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 便携汉化 JSON 编辑 App 主界面(MVP)：
 * 顶栏(汉堡☰ + 标题 + 右上角保存)、手写左侧抽屉(双分支)、
 * 分层 JSON 浏览/编辑(对象下钻、叶子弹框、新建/重命名/删除 key)。
 *
 * 分支 SAF 授权由 RepoRegistry 按分支持久化；未授权时走 getExternalFilesDir 草稿库。
 * 本文件功能较多，为便于阅读按区块排列。
 */
public class MainActivity extends Activity {
    // ---- 视图 ----------------
    private FrameLayout content;
    private View scrim;
    private LinearLayout drawer;
    private ImageView btnSave;
    private ImageView btnSettings;
    private ImageView btnSearch;
    private ImageView btnSaveHistory;
    private TextView title;
    // 保存后/重建列表仍停在同一深度的大致位置(避免“更新 key 后列表顶回”)
    private ListView liveListLv;
    private ListView curLv;        // 当前容器列表(供顶/底“快速滑到”)引用
    private final Handler wsBufHandler = new Handler(Looper.getMainLooper()); // 防崩溃草稿写盘用
    private boolean wsBufScheduled = false;
    private String liveSig = "";
    private int liveAnchor;
    private ListView drawerList;
    private View topBar;   // 顶栏(可从设置改主色)；布局里是 LinearLayout，用 View 引用免类型崩
    private LinearLayout settingPanel;   // 右侧滑出设置面板(懒创建)
    private View settingScrim;           // 右侧面板自己的遮罩

    // ---- 数据 ----------------
    private Bitmap cachedBg;         // “展示用”背景图缓存(已按 模糊/明暗 处理过)；null=无背景
    private Bitmap bgBase;           // 背景原图(屏幕尺寸解码的母版，供每次“调模糊/换主题”时重算)

    // ---- 搜索临时态：下一次 renderContainer 要标记并滚到的“该层命中 key”
    private List<String> hlSegs;     // 命中所在容器路径(待 renderContainer 消费)
    private String hlFile;           // 命中所在文件
    private String hlKey;            // 命中 key(若当前在命中那层则高亮)
    private final RepoRegistry repos = new RepoRegistry();
    private Branch currentBranch;          // 当前选中的分支；null=未选
    private JsonTree tree;                 // 当前打开的 json 树
    private String openName;               // 打开的 json 文件名
    private final List<String> path = new ArrayList<>(); // 容器路径(segPath)
    private List<String> currentFileNames = new ArrayList<>(); // 当前分支下列出的 json

    // ---- 收藏(快速定位到某 key)。每项:[父路径()分割|key|打开文件]
    // 父路径空=根层容器；“顶层段:父段:key|文件名”排序展示最直观 → 用字符串段列表记。
    private final List<Fav> favs = new ArrayList<>();
    // json 文件“自身收藏”档(branchId|file) —— 仅用于列表该 json 行右上角展示“是否已收藏”，不作跳转目标
    final java.util.HashSet<String> favFiles = new java.util.HashSet<>();

    boolean atWelcomePage = true;   // 当前是否处于“未选仓库”的引导首页(用于 Back 回这里)

    // 抽屉分支 置顶集合(按插入序)。重入不清?仅存会话,后续可落盘。
    final java.util.LinkedHashSet<String> pinnedBranchIds = new java.util.LinkedHashSet<>();
    private static final String PREFS_PIN = "youran_prefs";
    private static final String KEY_PINNED = "pinned_branch_ids";
    private boolean pinnedRestored = false;

    /** 启动首次重建抽屉前，把上次记住的顶置分支 id 恢复进 pinnedBranchIds(仅一次)。 */
    private void restorePinnedIfNeeded() {
        if (pinnedRestored) return;
        pinnedRestored = true;
        try {
            String v = getSharedPreferences(PREFS_PIN, MODE_PRIVATE).getString(KEY_PINNED, "[]");
            org.json.JSONArray arr = new org.json.JSONArray(v);
            for (int i = 0; i < arr.length(); i++) pinnedBranchIds.add(arr.getString(i));
        } catch (Exception ignored) { }
    }

    /** 顶置 id 集合有变化即落盘(重进保持)。 */
    private void persistPinned() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String id : pinnedBranchIds) arr.put(id);
            getSharedPreferences(PREFS_PIN, MODE_PRIVATE).edit().putString(KEY_PINNED, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    // 当前 json 内正处于的那一层容器“父链”(渲染该层列表前由进入处登记)，供内部行“是否已收藏”判断用
    List<String> curSelfSegs = new ArrayList<>();
    // 最近一次渲染的容器 ListView：toggle 收藏后即时重绘当前页(免得翻页才出星)
    ListView curContainerListView;

    /** 收藏条目(支持持久化→跨页面直达)。存 分支id + json 名 + 绝对父链 + key。 */
    static class Fav {
        String branchId;      // 仓库持久化键(如 youran/hai)——用它在收藏表里还原 Branch
        String file;          // 所属 json 文件名
        List<String> parent;  // 目标 key 所在“父容器”的绝对段链
        String key;
        Fav(String bid, String f, List<String> p, String k) {
            branchId = bid; file = f; parent = new ArrayList<>(p); key = k;
        }
    }


    private File rootDir;                  // 本地草稿库根(getExternalFilesDir) — 用于无 SAF 时跑通流程

    // 分支“导出”回调期对象：在 SAF 确认导出位置后才真正收集该分支 json
    private Branch branchForExport;
    // 编辑保存保位：点下“要去编辑的叶子/新建”那一行的瞬间记住此刻首可见行，
    // 保存后重建不要依赖“保存回调时实时列表”(那时可能已被重排回顶)，用这一份定位。
    private int saveKeepRow = -1;
    private String saveKeepKey = null;   // 保位首选：被编辑的那条 key(可改名则退(回退)到行号)

    // 记录页·删除型本地记录(仅当次会话;进程结束自动清，不必额外“退出清空”)
    private final java.util.List<DelRec> dels = new java.util.ArrayList<>();
    private static final class DelRec {   // 一条删除型记录
        final String file; final java.util.List<String> seg; final String key;
        final String snap;   // 待删当时内容快照(供回收站关&同会话直接恢复)
        boolean saved;       // false=仍是“待删(还能跳过去看)”, true=已总保存真删
        DelRec(String file, java.util.List<String> seg, String key, String snap, boolean saved) {
            this.file=file; this.seg=seg; this.key=key; this.snap=snap; this.saved=saved;
        }
    }
    /** 由“点删除(进待删)”在登记后调用：记成一条状态 PENDING 的删除型记录。 */
    private void logDel(java.util.List<String> seg, String key, JSONObject container) {
        if (tree == null || openName == null) return;
        String snap = "";
        try {
            Object vo = container != null ? container.opt(key) : null;
            if (vo != null) snap = vo.toString();
        } catch (Exception ignored) {}
        java.util.List<String> copySeg = new java.util.ArrayList<>(seg);
        for (DelRec d : dels)  // 已在“同一未完成待删”里：仅更新快照不重复
            if (d.file.equals(openName) && !d.saved && sameSeg(d.seg, copySeg) && d.key.equals(key)) return;
        dels.add(new DelRec(openName, copySeg, key, snap, false));   // 否则记新一条(即使是重删同名)
    }
    // ================== 回收站·存储层(本地四夹 files) ==================
    private static final String[] RC_KINDS = {"branches","json","block","key"};
    private File rcFolder(String kind) { return new File(recycleRoot(), kind); }
    private File recycleRoot() { return new File(rootDir, "recycle"); }
    private void ensureRecycleDirs() {
        try { recycleRoot().mkdirs(); for (String k : RC_KINDS) rcFolder(k).mkdirs(); } catch (Exception ignored) {}
    }
    private int recycleCount() {
        ensureRecycleDirs(); int n = 0;
        for (String k : RC_KINDS) { String[] c = rcFolder(k).list(); if (c != null) n += c.length; }
        return n;
    }
    // ============== 回收站全局映射清单 recycle/.index.json ==============
    // 每个真实收进(key/块/json/分支)都会在此登记一条：“同一来源(srcPath 前缀) 的多份备份可一并管理”。
    private File idxFile() { return new File(recycleRoot(), ".index.json"); }
    /** 读取清单并自愈：凡指向的文件/目录已不存在(被恢复/被彻底删)的行即时剔除并回写。 */
    private JSONArray idxLoad() {
        java.io.File f = idxFile(); JSONArray arr = new JSONArray();
        try { if (f.exists()) arr = new JSONArray(readAll(f)); } catch (Exception ignored) { }
        try {
            boolean dirty = false; JSONArray alive = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i); if (o == null) continue;
                String kind = o.optString("kind"), file = o.optString("file");
                java.io.File fc = new java.io.File(rcFolder(kind), file);
                if (fc.exists()) { alive.put(o); } else dirty = true;
            }
            if (dirty) idxSave(alive);
            return alive;
        } catch (Exception e) { return arr; }
    }
    private void idxSave(JSONArray arr) {
        try { ensureRecycleDirs(); writeJsonAstex(idxFile(), arr.toString(2)); } catch (Exception ignored) { }
    }
    /** 登记/合并一条 index；同 kind+同 hash(即同一次删除事件)幂等。 */
    private void idx_push(String kind, String file, String srcPath, String branch, String realName) {
        try {
            JSONArray a = idxLoad();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && kind.equals(o.optString("kind")) && file.equals(o.optString("file"))) return; // 已有
            }
            JSONObject o = new JSONObject();
            o.put("kind", kind); o.put("file", file); o.put("srcPath", srcPath == null ? "" : srcPath);
            o.put("branch", branch == null ? "" : branch); o.put("realName", realName == null ? "" : realName);
            o.put("at", System.currentTimeMillis());
            a.put(o); idxSave(a);
        } catch (Exception ignored) { }
    }
    /** 删某夹内单个文件时同步摘除其 index 行；file 可传文件名或目录名。 */

    /** 面板标题辅助：该 kind 下不同“源分支/文件”种数(取自 .index.json)，同来源聚合一目了然。 */
    private int idxSrcCount(String kind) {
        java.util.Set<String> s = new java.util.HashSet<>();
        try {
            JSONArray a = idxLoad();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                if (!kind.equals(o.optString("kind"))) continue;
                String sp = o.optString("srcPath", "");
                if (sp.trim().length() == 0) continue;
                s.add(sp.trim());   // 同源即同一 srcPath 串；跨类型聚合可再按 srcPath 全等/前缀归并
            }
        } catch (Exception ignored) { }
        return s.size();
    }

    private String rcName(String kind, String thumb) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            md.update((kind + "|" + thumb + "|" + System.currentTimeMillis()).getBytes("UTF-8"));
            byte[] d = md.digest(); StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString() + ".json";
        } catch (Exception e) { return System.currentTimeMillis() + ".json"; }
    }
    private boolean recyclePut(String kind, String thumb, JSONObject data) {
        if (!recycleEnabled()) return false;
        final int lim = RC_LIMITS[upperIndexNow()];
        if (lim > 0 && kindCount(kind) >= lim) return false;     // 超上限熔断: 该夹已达上限则不再写入
        try {
            ensureRecycleDirs();
            JSONObject wrap = new JSONObject();
            wrap.put("kind", kind); wrap.put("orig", thumb);
            wrap.put("put_at", System.currentTimeMillis()); wrap.put("data", data);
            java.io.File f = new java.io.File(rcFolder(kind), rcName(kind, thumb));
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(wrap.toString(2).getBytes("UTF-8")); o.close();
            // 分支从 srcPath(thumb) 派生出：srcPath=完整显示路径；branch=第一段目录可视名由调用方回填于 wrap.orig
            String srcP = thumb != null ? thumb : "";
            String br = (wrap.optString("branch", "").length() == 0)
                    ? (srcP.indexOf('/') > 0 ? srcP.substring(0, srcP.indexOf('/')) : "") : wrap.optString("branch", "");
            idx_push(kind, f.getName(), srcP, br, null);
            return true;
        } catch (Exception e) { return false; }
    }
    /** 叶子→key、对象块→block, 在真删仍“存在于树”的瞬间调用。 */
    private void recycleFromPendingRemove(java.util.List<String> seg, String key) {
        if (!recycleEnabled() || tree == null) return;
        try {
            JSONObject c = tree.containerAt(new java.util.ArrayList<String>(seg));
            if (c == null) return;
            Object v = c.opt(key);
            if (v == null) return;
            // “内容态剥离”：若当前 json 根只包了唯一一个对象壳(外层诸如此类“/顶层”外壳)，
            // 记录 seg 时把该外壳键从最前面剥掉 → key/块/恢复/兜底统一以“内容开头”，不会再带回外壳层
            java.util.List<String> segVis = new java.util.ArrayList<>(seg);
            try {
                JSONObject root0 = (tree != null) ? tree.rootObject() : null;
                if (root0 != null && root0.length() == 1) {
                    // 文件被单对象壳包着(如外层 /顶层)：seg 未必带它名 → 不做键名比对；
                    // 一旦确定“单壳”，若 seg 第一段就是该壳名则剥掉；即使不在首段，
                    // 兜底写回时以内容为根，不再把壳带进新文件，这由恢复侧统一实现。
                    java.util.Iterator<String> it = root0.keys();
                    String shellKey = it.hasNext() ? it.next() : null;
                    if (shellKey != null && !segVis.isEmpty() && shellKey.equals(segVis.get(0))) {
                        segVis.remove(0);
                    }
                }
            } catch (Exception ignore) { }
            String brTtl = currentBranch != null && currentBranch.title != null ? currentBranch.title : "?";
            JSONObject p = new JSONObject();
            p.put(key, v);
            String thumbK = brTtl + "/" + (openName != null ? openName : "file") + "::" + joinPath(segVis) + "." + key;
            recyclePut(v instanceof JSONObject ? "block" : "key", thumbK, p);
        } catch (Exception ignored) {}
    }
    /** 整个分支工作夹收进回收站 branches/<hash>/（回收站开才拷贝）。 */
    private void recycleBranchFs(java.io.File srcDir, String titleHint) {
        if (!recycleEnabled() || srcDir == null || !srcDir.isDirectory()) return;
        try {
            ensureRecycleDirs();
            String name = rcName("branches", (titleHint == null ? "" : titleHint));
            java.io.File dst = new java.io.File(rcFolder("branches"), name);
            dst.mkdirs();
            java.io.File[] kids = srcDir.listFiles();
            if (kids != null) for (java.io.File k : kids) copyRec(k, new java.io.File(dst, k.getName()));
            try { writeLocal(new java.io.File(dst, "_srcid"), srcDir.getName()); } catch (Exception ignore) {}   // 记录原分支id(folder名)供恢复时 rekey 暂存
            String th = (titleHint == null || titleHint.trim().isEmpty()) ? dst.getName() : titleHint;
            idx_push("branches", dst.getName(), th, th, null);
        } catch (Exception ignored) {}
    }
    /** 田字板四个夹的数字(各自 listFiles 长度;每夹 entry 数)。 */
    private int kindCount(String kind) {
        ensureRecycleDirs();
        String[] c = rcFolder(kind).list();
        return c == null ? 0 : c.length;
    }
    /** 回收站“设置·管理”页四个数字的 live view（于 openRecycleBoard 时填充）；任何改动经 afterRecycleChange() 即时更新，杜绝“要退出重进才变”。 */
    private TextView[] rcTileLive = new TextView[4];
    private TextView rcSettingVal;   // 设置页“回收站”行的右侧数字(供 live 刷新)
    private void afterRecycleChange() {
        refreshRcSettingVal();
        for (int k = 0; k < rcTileLive.length; k++) {
            if (rcTileLive[k] != null) {} }
        final String[] kinds = {"branches","json","block","key"};
        for (int k = 0; k < rcTileLive.length; k++) {
            if (rcTileLive[k] == null) continue;
            try { rcTileLive[k].setText(rcLabelOf(kinds[k]) + "\n" + kindCount(kinds[k]) + " 项"); } catch (Exception ignored) { }
        }
        if (rcHead != null && rcSubDlg != null && rcSubDlg.isShowing()) {} // rcSubDlg 由 refreshKeyList 自行重建
    }
    private void refreshRcSettingVal() {
        if (rcSettingVal == null) return;
        try { rcSettingVal.setText("已存 " + recycleCount()); } catch (Exception ignored) { }
    }
    private String rcLabelOf(String k) {
        return "branches".equals(k) ? "分支" : ("json".equals(k) ? "json" : ("block".equals(k) ? "块" : "子项"));
    }
    private int upperIndexNow() {
        // 0,50,100,200(序号存 pref rc_lim_idx,默认50)
        return Math.min(3, Math.max(0, loadPrefInt("recycle_lim_idx", 1)));
    }
    private static final int[] RC_LIMITS = {0, 50, 100, 200};
    private void saveUpperIndex(int index) { savePrefInt("recycle_lim_idx", index); }

    /** 设置-回收站弹窗：左上标题/右上可用态单选刷文字 / 田字四钮(淡底) / 左下上限数字(滚轮0/50/100/200)。 */
    private void openRecycleBoard() {
        final String[] kinds  = {"branches","json","block","key"};
        final String[] titles = {"分支","json","块","子项"};
        int[] bg = {0xE0A0A0A0, 0xE07F9AD8, 0xE06ABf82, 0xE0C97878};

        // ===== 整个可 view：单一圆角白底(外观为大圆角卡片) =====
        LinearLayout whole = new LinearLayout(this);
        whole.setOrientation(LinearLayout.VERTICAL);
        whole.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable shellbg = new android.graphics.drawable.GradientDrawable();
        shellbg.setColor(0xFFFFFFFF); shellbg.setCornerRadius(dp(26));
        whole.setBackground(shellbg);

        // 顶栏：加大标题 + 是否(长按350)
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL);
        TextView ttl = new TextView(this); ttl.setText("回收站");
        ttl.setTextSize(23f); ttl.setTypeface(Typeface.DEFAULT_BOLD); ttl.setTextColor(0xFF25232E);
        top.addView(ttl, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView ena = new TextView(this);
        ena.setText(recycleEnabled() ? "◉ 已启用" : "○ 未启用");
        ena.setTextSize(14f); ena.setTextColor(0xFF2F86FF); ena.setPadding(dp(8), dp(3), dp(2), dp(3));
        final boolean[] arm = {false};
        final android.os.Handler hk = new android.os.Handler(this.getMainLooper());
        ena.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                arm[0] = true;
                hk.postDelayed(() -> { if (arm[0]) { boolean n = !recycleEnabled(); setRecycleEnabled(n); ena.setText(n ? "◉ 已启用" : "○ 未启用"); } }, 350);
                return true;
            } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) { arm[0] = false; return true; }
            return true;
        });
        top.addView(ena, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        whole.addView(top);

        // 田字四钮
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < 2; j++) {
                int i = r * 2 + j;
                TextView c = new TextView(this);
                rcTileLive[i] = c;                       // 接入 live：任何回收动作后计数即时更新
                c.setText(titles[i] + "\n" + kindCount(kinds[i]) + " 项");
                c.setTextSize(16f); c.setTextColor(0xFF111317); c.setGravity(Gravity.CENTER);
                c.setPadding(dp(2), dp(22), dp(2), dp(22));
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(bg[i]); g.setCornerRadius(dp(16));
                c.setBackground(g);
                final int fidx = i;                                                   // 防闭包循环变量
                c.setOnClickListener(v2 -> openRecycleList(kinds[fidx], titles[fidx]));
                c.setClickable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(4), dp(3), dp(4), dp(3));
                row.addView(c, lp);
            }
            whole.addView(row);
        }

        // 左下：存储上限(小钮，与完成大体同行即可)
        LinearLayout lowrow = new LinearLayout(this); lowrow.setOrientation(LinearLayout.HORIZONTAL);
        TextView lim = new TextView(this); lim.setText("存储上限 " + RC_LIMITS[upperIndexNow()]);
        lim.setTextSize(13f); lim.setTextColor(0xFF2F86FF); lim.setGravity(Gravity.CENTER);
        lim.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable gl = new android.graphics.drawable.GradientDrawable();
        gl.setColor(0xFFEAF1FF); gl.setCornerRadius(dp(9)); gl.setStroke(dp(1), 0x33507BFF);
        lim.setBackground(gl);
        lim.setOnClickListener(v -> {
            final android.widget.NumberPicker np = new android.widget.NumberPicker(this);
            np.setMinValue(0); np.setMaxValue(RC_LIMITS.length - 1);
            np.setDisplayedValues(new String[]{"0", "50", "100", "200"});
            np.setWrapSelectorWheel(false); np.setValue(upperIndexNow());
            new android.app.AlertDialog.Builder(this).setTitle("存储上限")
                .setView(np)
                .setPositiveButton("确认", (dg, w) -> { int idx = np.getValue(); saveUpperIndex(idx); lim.setText("存储上限 " + RC_LIMITS[idx]); })
                .setNegativeButton("取消", null)
                .create().show();
        });
        LinearLayout.LayoutParams lpLow = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        lpLow.topMargin = dp(6);
        lowrow.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        lowrow.addView(lim, lpLow);
        whole.addView(lowrow);

        // 完成 / 清空回收站：画进圆角壳内(系统风文字钮)，壳底留“下方余量(≈0.7cm)”让两钮不贴死外沿
        LinearLayout act = new LinearLayout(this);
        act.setOrientation(LinearLayout.HORIZONTAL);
        act.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView okB = new TextView(this); okB.setText("完成");
        okB.setTextSize(16f); okB.setTextColor(0xFF2F86FF); okB.setPadding(dp(10), dp(4), dp(6), dp(4));
        TextView clB2 = new TextView(this); clB2.setText("清空回收站");
        clB2.setTextSize(16f); clB2.setTextColor(0xFFE74C3C); clB2.setPadding(dp(6), dp(4), dp(2), dp(4));
        act.addView(okB);
        act.addView(clB2);
        whole.addView(act, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 按钮就放圆角壳内的最末行，不给壳再塞任何底部空白

        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle(null)
                .setView(whole)
                .setCancelable(true)
                .create();
        okB.setOnClickListener(v -> d.dismiss());
        clB2.setOnClickListener(v -> {
            for (String k : RC_KINDS) { java.io.File kf = rcFolder(k); if (kf.exists()) deleteDirRec(kf); kf.mkdirs(); }
            toast("回收站已清空");
            d.dismiss();
        });
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        d.show();
    }
    /** 打开某类回收清单: 每份一行(文件名+体积/时间)可删除, 底部[清空此类]。恢复在下一步单独做。 */
    /** 整份 json 回收：每份=原 json 整档。记录里有 realName(原真名)，恢复按 realName 落回原分支；hash 只管夹名不作恢复来源。 */
    /** json 全档回收页：单击卡片进“单份”弹窗；删除/恢复后走 rcJsonRefresh(原地重建) 保持即时同步。 */
    private void openRecycleJson(String title) {
        rcJsonTitle = title;
        rcJsonDlg = new android.app.AlertDialog.Builder(this).create();
        rcBodyJson = buildJsonList(title);
        if (rcBodyJson == null) { rcJsonDlg = null; showEmptyKinPanel(title, "json 回收夹"); return; }  // 空态
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(rcBodyJson, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(8), dp(6), dp(8), dp(2));
        TextView ht = new TextView(this);
        int n0 = (rcFolder("json").listFiles() == null ? 0 : rcFolder("json").listFiles().length);
        ht.setText(title + " · " + n0 + " 份 · 源" + idxSrcCount("json"));
        ht.setTextColor(0xFF252833); ht.setTextSize(16f); ht.setTypeface(Typeface.DEFAULT_BOLD); outer.addView(ht);
        outer.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rcJsonDlg.setView(outer);
        rcJsonDlg.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "返回",
                (d, w) -> { rcJsonDlg = null; rcBodyJson = null; });
        rcJsonDlg.show();
    }
    /** json 列表内容主体（不含滚动与标题）：空则 null(走空态)；仅供本页重绑定复用。 */
    private LinearLayout buildJsonList(String tt) {
        java.io.File dir = rcFolder("json"); java.io.File[] items = dir.listFiles();
        if (items == null || items.length == 0) return null;
        LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
        for (java.io.File f : items) {
            final java.io.File ff = f;
            String realName = recFieldOf(ff, "realName", ff.getName());
            String brTtl = recFieldOf(ff, "branchTitle", "?");
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(7), dp(3), dp(7), dp(3));
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(0xFFF4F6FC); gd.setCornerRadius(dp(9)); gd.setStroke(dp(1), 0x2F000000);
            card.setBackground(gd);
            addJsonL(card, "分支", brTtl);
            addJsonL(card, "json", realName);
            card.setOnClickListener(v -> askJsonEntry(ff));
            inner.addView(card);
            inner.addView(UiKit.spacer(MainActivity.this, 2));
        }
        return inner;
    }
    /** json 动作后刷新：若 json 回收页仍在显示则先关旧再开新版（避免叠层“看着不更新”）。 */
    private void rcJsonRefresh() {
        final String tt = (rcJsonTitle == null || rcJsonTitle.length() == 0) ? "json" : rcJsonTitle;
        if (rcJsonDlg != null) { try { rcJsonDlg.dismiss(); } catch (Exception ignored) { } rcJsonDlg = null; rcBodyJson = null; rcJsonTitle = null; }
        openRecycleJson(tt);
    }
    /** 空回收夹也正常打开弹窗，显示“（已空）”占位 —— 与子项/块一致，不再仅 toast */
    private void showEmptyKinPanel(String title, String noun) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView h0 = new TextView(this); h0.setText(title); h0.setTextSize(16f);
        h0.setTypeface(Typeface.DEFAULT_BOLD); h0.setTextColor(0xFF252833); box.addView(h0);
        TextView empty = new TextView(this); empty.setText("（" + noun + "已空）"); empty.setTextSize(14f);
        empty.setTextColor(0xFF9AA1AC); empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(dp(0), dp(26), dp(0), dp(26)); box.addView(empty);
        new android.app.AlertDialog.Builder(this).setTitle(null).setView(box)
            .setNegativeButton("返回", null).show();
    }
    private void addJsonL(LinearLayout c, String tag, String v) {
        TextView t = new TextView(this);
        t.setText((tag + "：" + (v == null ? "—" : v)));
        t.setTextSize(13f); t.setTextColor(0xFF33363E); t.setPadding(dp(2), dp(1), dp(2), dp(1));
        c.addView(t);
    }
    private String recFieldOf(java.io.File f, String key, String dft) {
        try { JSONObject j = new JSONObject(readAll(f)); String v = j.optString(key, dft); return v; }
        catch (Exception e) { return dft; }
    }
    private void askJsonEntry(final java.io.File ff) {
        LinearLayout pv = new LinearLayout(this); pv.setOrientation(LinearLayout.VERTICAL);
        pv.setPadding(dp(12), dp(4), dp(12), dp(4));
        TextView msg = new TextView(this);
        msg.setText("将整份 json (“" + recFieldOf(ff, "realName", ff.getName()) + "”)按原名恢复还是彻底删除？");
        msg.setTextSize(13f); msg.setTextColor(0xFF333); pv.addView(msg);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
            .setTitle("恢复 json 还是删除？").setView(pv).setCancelable(true).create();
        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView bR = keyBtn("恢复", 0xFF2E9E5B); TextView bD = keyBtn("删除", 0xFFE05B4C);
        bR.setOnClickListener(x -> { d.dismiss(); restoreJsonEntry(ff); rcJsonRefresh(); });
        bD.setOnClickListener(x -> { d.dismiss();
            new android.app.AlertDialog.Builder(this)
                .setTitle("彻底删除该份 json？").setMessage("会把它从回收站永久移除")
                .setPositiveButton("删除", (y, z) -> { deleteDirRec(ff); toast("已删除"); rcJsonRefresh(); })
                .setNegativeButton("取消", null).show();
        });
        btns.addView(bR, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(10), 1); btns.addView(new View(this), gap);
        btns.addView(bD, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pv.addView(btns);
        d.show();
    }
    private void restoreJsonEntry(java.io.File ff) {
        try {
            JSONObject j = new JSONObject(readAll(ff));
            String realName = j.optString("realName", "restored.json");
            String bid = j.optString("branch", "");
            JSONObject data = j.optJSONObject("data");
            if (data == null) { toast("该份 json 无内容"); return; }
            java.io.File src = new java.io.File(rootDir, bid);
            Branch dest = null;
            for (Branch b : repos.all()) if (b.id.equals(bid)) { dest = b; break; }
            boolean missing = dest == null || !src.isDirectory();
            if (missing) { recreateBranchedForJson(j.optString("branchTitle", "restored"), realName, data); deleteDirRec(ff); return; }
            java.io.File tgt = new java.io.File(new java.io.File(rootDir, dest.id), realName.endsWith(".json") ? realName : realName + ".json");
            writeJsonAstex(tgt, data.toString(2));
            deleteDirRec(ff);
            repos.initializeFromLocal(rootDir); refreshDrawerList();
            toast("已把 " + realName + " 恢复回 " + dest.title);
        } catch (Exception e) { toast("恢复失败：" + (e.getMessage() == null ? e.toString() : e.getMessage())); }
    }
    private void recreateBranchedForJson(String brTitle, String realName, JSONObject data) {
        try {
            String safe = brTitle == null || brTitle.trim().isEmpty() ? "restored" : brTitle.replaceAll("[\\\\/:*?\"<>|]+", "_");
            // 与 key/块 一致：同主合并 —— 已有 re-<title> 目录则复用，不额外 _1;文件仍按原文件名放(同档覆盖，不同名共存)
            java.io.File dirB = new java.io.File(rootDir, "re-" + safe);
            if (!dirB.exists()) dirB.mkdirs();
            java.io.File tgt = new java.io.File(dirB, realName.endsWith(".json") ? realName : realName + ".json");
            writeJsonAstex(tgt, data.toString(2));
            repos.initializeFromLocal(rootDir); refreshDrawerList();
            toast("已并入 re-" + safe + "，放回 " + realName);
        } catch (Exception e) { toast("兜底失败"); }
    }
    /** 分支面板：与 json/子项一致 圆角卡片，单击卡片 → 在“恢复/删除”小窗里选；不再走老平铺+行内钮 */
    /** 分支原显示名：从 .index.json 里按夹名该行登记的 branch/srcPath 取“删前名称”；旧数据无 则退回夹内哈希名。 */
    private String branchDisplayName(java.io.File f) {
        String name = f.getName();
        try {
            JSONArray a = idxLoad();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                if ("branches".equals(o.optString("kind")) && name.equals(o.optString("file"))) {
                    String s = o.optString("srcPath", "");
                    if (s.trim().length() > 0) return s.trim();
                }
            }
        } catch (Exception ignored) { }
        return name;
    }
    private void openRecycleBranches(final String title) {
        rcBraDlg = new android.app.AlertDialog.Builder(this).setTitle(null).create();
        LinearLayout body = buildBranchList(title);
        if (body == null) { try { rcBraDlg.dismiss(); } catch (Exception ignored) { } rcBraDlg = null; showEmptyKinPanel(title, "分支回收夹"); return; }
        android.widget.ScrollView sv2 = new android.widget.ScrollView(this);
        sv2.addView(body, new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(6),dp(6),dp(6),dp(2));
        int bb = (rcFolder("branches").listFiles()==null?0:rcFolder("branches").listFiles().length);
        TextView head = new TextView(this); head.setText(title + " · " + bb + " 份 · 源" + idxSrcCount("branches"));
        head.setTextColor(0xFF252833); head.setTextSize(16f); head.setTypeface(Typeface.DEFAULT_BOLD); box.addView(head);
        box.addView(sv2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));
        rcBraDlg.setView(box);
        rcBraDlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, "清空此类", (d,w)->{
            try { for (java.io.File g : rcFolder("branches").listFiles()) if (g != null && g.exists()) deleteDirRec(g); } catch (Exception ignored) { }
            toast(title + " 回收夹已清空"); rcBraRefresh();
        });
        rcBraDlg.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "返回", (d,w)->{ try { rcBraDlg.dismiss(); } catch (Exception ignored) { } rcBraDlg=null; });
        rcBraDlg.show();
    }
    /** 分支列表主体；空返回 null（走空态页）。列表项显示“删前名称”。 */
    private LinearLayout buildBranchList(final String title) {
        java.io.File dir = rcFolder("branches");
        java.io.File[] items = dir.listFiles();
        if (items == null || items.length == 0) return null;
        LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
        for (java.io.File f : items) {
            final java.io.File ff = f;
            final String dsp = branchDisplayName(f);   // 删前名称
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(7),dp(4),dp(7),dp(4));
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(0xFFF4F6FC); gd.setCornerRadius(dp(9)); gd.setStroke(dp(1),0x2F000000); card.setBackground(gd);
            TextView a0 = new TextView(this); a0.setText("分支：" + dsp);
            a0.setTextSize(13f); a0.setTextColor(0xFF33363E); a0.setPadding(dp(2),dp(1),dp(2),dp(1)); card.addView(a0);
            TextView a1 = new TextView(this); a1.setText(dirLen(ff) == 0 ? "空分支" : ("含 " + fSized(ff)));
            a1.setTextSize(11f); a1.setTextColor(0xFF8A919C); a1.setPadding(dp(2),dp(1),dp(2),dp(1)); card.addView(a1);
            card.setOnClickListener(v -> askBranchEntry(ff, dsp, title));
            inner.addView(card); inner.addView(UiKit.spacer(MainActivity.this,2));
        }
        return inner;
    }
    /** 分支动作后刷新：若分支页在显示则先关旧、再开新版。 */
    private void rcBraRefresh() {
        if (rcBraDlg != null) { try { rcBraDlg.dismiss(); } catch (Exception ignored) { } rcBraDlg = null; }
        openRecycleBranches("分支");
    }
    private void askBranchEntry(final java.io.File ff, final String dsp, final String title) {
        LinearLayout pv = new LinearLayout(this); pv.setOrientation(LinearLayout.VERTICAL); pv.setPadding(dp(12),dp(4),dp(12),dp(4));
        TextView msg = new TextView(this); msg.setText("分支 “" + dsp + "” 整夹恢复，还是彻底删除？");
        msg.setTextSize(13f); msg.setTextColor(0xFF333); pv.addView(msg);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).setTitle("恢复还是删除？").setView(pv).setCancelable(true).create();
        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView bR = keyBtn("恢复",0xFF2E9E5B); TextView bD = keyBtn("删除",0xFFE05B4C);
        bR.setOnClickListener(x -> { d.dismiss(); restoreBranchArc(ff);
            rcBraRefresh();
        });
        bD.setOnClickListener(x -> { d.dismiss();
            new android.app.AlertDialog.Builder(this).setTitle("彻底删除该分支？")
                .setMessage("会把它从回收站永久移除，无法再恢复。")
                .setPositiveButton("删除",(y,z)->{ if (ff.exists()) deleteDirRec(ff); toast("已删除"); rcBraRefresh(); })  // 删除后立刻刷新分支页
                .setNegativeButton("取消",null).show();
        });
        btns.addView(bR,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        android.view.View sp = new android.view.View(this); sp.setLayoutParams(new LinearLayout.LayoutParams(dp(10),1));
        btns.addView(sp);
        btns.addView(bD,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        pv.addView(btns); d.show();
    }
    private void openRecycleList(String kind, String title) {
        // 四个 kind 全部走同一套 key/块已验证的持久面板(rcSubDlg/refreshKeyList)：子项/块照旧，json/分支按各自记录渲染并复用同一弹窗逻辑
        rcKind = kind; openKeyPanel(title); return;
    }
    private String fSized(java.io.File f) {
        long s = 0;
        s = f.isDirectory() ? dirLen(f) : f.length();
        if (s < 1024) return s + " B";
        if (s < 1048576) return (s / 1024) + " KB";
        return (s / 1048576) + " MB";
    }
    private long dirLen(java.io.File f) {
        long s = 0;
        java.io.File[] c = f.listFiles();
        if (c != null) for (java.io.File x : c) s += x.isDirectory() ? dirLen(x) : x.length();
        return s;
    }
    // =============== “子项”(key) 回收清单：右上多选勾选框(外观) + 单击弹 恢复/删除 ===============
    // ---- “子项”清单：用一个成员对话框；列表可原地即时刷新，不再重弹新窗 ----
    private android.app.AlertDialog rcSubDlg;
    private LinearLayout rcList;
    // —— json 全档 / 分支整夹 的持久页(与 key/块共用 rcSubDlg 的“原地重绑”思路) ——
    private android.app.AlertDialog rcJsonDlg; private LinearLayout rcBodyJson; private String rcJsonTitle;
    private android.app.AlertDialog rcBraDlg; private LinearLayout rcBodyBra;

    private TextView rcHead;
    private android.widget.CheckBox rcMulti;
    private String rcKind = "key";   // “子项”通用面板当前处理的 kind:key/block
    private String rcTitle = "子项";

    private void openKeyPanel(String title) {
        rcTitle = title;
        if (rcSubDlg == null) {
            LinearLayout whole = new LinearLayout(this); whole.setOrientation(LinearLayout.VERTICAL);
            whole.setPadding(dp(6), dp(6), dp(6), dp(2));
            LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.HORIZONTAL);
            rcHead = new TextView(this); rcHead.setTextColor(0xFF252833);
            rcHead.setTextSize(16f); rcHead.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(rcHead, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rcMulti = new android.widget.CheckBox(this);
            rcMulti.setText("多选模式"); rcMulti.setTextSize(13f);
            head.addView(rcMulti);
            whole.addView(head);
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            rcList = new LinearLayout(this); rcList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(rcList, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            whole.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            rcSubDlg = new android.app.AlertDialog.Builder(this)
                .setTitle(null).setView(whole).setNegativeButton("返回", null).create();
            rcSubDlg.setOnShowListener(v -> rcHead.post(this::refreshKeyList));
        }
        rcHead.setText(rcTitle + " · " + (rcFolder(rcKind).listFiles() == null ? 0 : rcFolder(rcKind).listFiles().length) + " 份 · 源" + idxSrcCount(rcKind));
        refreshKeyList();
        if (!rcSubDlg.isShowing()) rcSubDlg.show();
    }

    private void refreshKeyList() {
        if (rcList == null) return;
        afterRecycleChange();                                       // 增删造成的重构建 → 立即同步 Board/设置等数字(见 #3)
        java.io.File[] items = rcFolder(rcKind).listFiles();
        rcList.removeAllViews();
        if (items == null || items.length == 0) {
            // 与 json/分支一致：占位灰字垂直居中、上下留大呼吸空间，视觉置于面板主体的中部
            TextView empty = new TextView(this);
            String noun = "block".equals(rcKind) ? "块" : ("branches".equals(rcKind) ? "分支" : ("json".equals(rcKind) ? "json" : "子项"));
            empty.setText("（" + noun + "回收夹已空）");
            empty.setTextColor(0xFF9AA0A6);
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(14f);
            empty.setPadding(dp(0), dp(26), dp(0), dp(26));
            rcList.addView(empty);
            if (rcHead != null) rcHead.setText(rcTitle + " · 0 份 · 源" + idxSrcCount(rcKind));
            return;
        }
        if (rcHead != null) rcHead.setText(rcTitle + " · " + items.length + " 份 · 源" + idxSrcCount(rcKind));
        for (java.io.File f : items) {
            final java.io.File ff = f;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(7), dp(3), dp(7), dp(3));
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(0xFFF4F6FC); gd.setCornerRadius(dp(9)); gd.setStroke(dp(1), 0x2F000000);
            card.setBackground(gd);
            if ("branches".equals(rcKind)) {
                // 分支整夹条目：显示删前名
                addL(card, "分支", branchDisplayName(ff));
                boolean has = (ff.listFiles() != null && ff.listFiles().length > 0);
                addL(card, "内容", has ? (ff.listFiles().length + " 项") : "空分支");
            } else if ("json".equals(rcKind)) {
                addL(card, "分支", recFieldOf(ff, "branchTitle", "?"));
                addL(card, "json", recFieldOf(ff, "realName", ff.getName()));
            } else if ("block".equals(rcKind)) {
                String[] ib = parseKeyLayout(readOrigOf(ff));
                addL(card, "分支", ib[0]); addL(card, "json", ib[1]); addL(card, "块", ib[3]);
            } else {
                String[] ik = parseKeyLayout(readOrigOf(ff));
                addL(card, "分支", ik[0]); addL(card, "json", ik[1]); addL(card, "块", ik[2]); addL(card, "key", ik[3]);
            }
            card.setOnClickListener(v -> {
                if (rcMulti != null && rcMulti.isChecked()) { toast("多选模式已勾选，多选删除待接入"); return; }
                askEntryKind(rcKind, ff);
            });
            rcList.addView(card);
            rcList.addView(UiKit.spacer(MainActivity.this, 2));
        }
    }
    /** 统一入口：key/块沿用原 askKeyEntry；json/分支在此以同款“恢复/删除”处理并回顾面刷新。 */
    private void askEntryKind(String kind, final java.io.File ff) {
        if ("key".equals(kind) || "block".equals(kind)) { askKeyEntry(ff); return; }
        if ("json".equals(kind)) { askJsonWhole(ff); return; }
        if ("branches".equals(kind)) { askBranchWhole(ff); }
    }
    /** json 整份条目操作(在该持久页内)：恢复 / 删除二次确认后都 refreshKeyList。 */
    private void askJsonWhole(final java.io.File ff) {
        final String nm = recFieldOf(ff, "realName", ff.getName());
            // 恢复/删除 左右互换 + 后续删除二次确认
            android.app.AlertDialog _a = new android.app.AlertDialog.Builder(this)
            .setTitle("该 json 整份：" + nm)
            .setMessage("恢复(回原分支/原文件名)还是彻底删除？").create();
            _a.setButton(android.content.DialogInterface.BUTTON_POSITIVE, "删除", (d, w) -> {
                d.dismiss();
                new android.app.AlertDialog.Builder(this).setTitle("彻底删除该份 json？")
                    .setMessage("会把它从回收站永久移除。")
                    .setPositiveButton("删除", (y, z) -> { if (ff.exists()) deleteDirRec(ff); toast("已删除"); refreshKeyList(); })
                    .setNegativeButton("恢复", (y, z) -> { /* 免误点逃生 */ })
                    .setNeutralButton("取消", null).show();
            });
            _a.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "恢复", (d, w) -> { d.dismiss(); restoreJsonEntry(ff); refreshKeyList(); });
            _a.show();
            return;
    }
    /** 分支整夹条目操作：同上，恢复以原可读名回 re- 分支。 */
    private void askBranchWhole(final java.io.File ff) {
        final String nm = branchDisplayName(ff);
    // 恢复/删除 左右互换：删除在右为主钮(仍二次确认)，恢复在左为次钮
    final String bnm = nm;
    android.app.AlertDialog _b = new android.app.AlertDialog.Builder(this)
        .setTitle("分支：" + bnm)
        .setMessage("恢复(回 re- 分支)还是彻底删除？").create();
    _b.setButton(android.content.DialogInterface.BUTTON_POSITIVE, "删除", (d, w) -> {
        d.dismiss();
        new android.app.AlertDialog.Builder(this).setTitle("彻底删除该分支？")
            .setMessage("会把它从回收站永久移除。")
            .setPositiveButton("删除", (y, z) -> { if (ff.exists()) deleteDirRec(ff); toast("已删除"); refreshKeyList(); })
            .setNegativeButton("恢复", (y, z) -> { })   // 逃生：回到上一级
            .setNeutralButton("取消", null).show();
    });
    _b.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "恢复", (d, w) -> { d.dismiss(); restoreBranchArc(ff); refreshKeyList(); });
    _b.show();
    }
    private void addL(LinearLayout c, String tag, String v0) {
        String v = v0 == null ? "—" : v0;
        TextView t = new TextView(this);
        if (tag.equals("块") || tag.equals("key")) t.setText(v); else t.setText(tag + "：" + v);
        t.setTextSize(13f); t.setTextColor(tag.equals("key") ? 0xFF1E66C2 : 0xFF33363E);
        t.setPadding(dp(2), dp(1), dp(2), dp(1)); c.addView(t);
    }
    private String[] parseKeyLayout(String orig) {
        String[] out = {"", "", "", ""};
        if (orig == null) return out;
        String head = orig, tail = "";
        int dd = orig.indexOf("::");
        if (dd >= 0) { head = orig.substring(0, dd); tail = orig.substring(dd + 2); }
        String br = head, file = ""; int sl = head.indexOf('/');
        if (sl >= 0) { br = head.substring(0, sl); file = head.substring(sl + 1); }
        else { br = "?"; file = head; }
        String chain = tail, lastK = "";
        int dot = chain.lastIndexOf('.');
        String cpre = "";
        if (dot >= 0) { lastK = chain.substring(dot + 1); cpre = tail.substring(0, dot); }
        else lastK = chain;
        java.lang.StringBuilder blk = new java.lang.StringBuilder();
        if (!cpre.isEmpty()) { String[] cp = cpre.split("\\.");
            for (int i = 0; i < cp.length; i++) { if (i > 0) blk.append('.'); blk.append(cp[i]); } }
        out[0] = br; out[1] = file; out[2] = blk.toString(); out[3] = lastK;
        return out;
    }
    private void askKeyEntry(final java.io.File ff) {
        String orig = readOrigOf(ff);
        LinearLayout pv = new LinearLayout(this); pv.setOrientation(LinearLayout.VERTICAL);
        pv.setPadding(dp(12), dp(4), dp(12), dp(4));
        TextView padTop = new TextView(this); padTop.setHeight(dp(6)); pv.addView(padTop, 0);
        TextView msg = new TextView(this); msg.setText("该子项：" + foldRecycleLabel(orig));
        msg.setTextSize(13f); msg.setTextColor(0xFF333); pv.addView(msg);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this).setTitle("恢复还是删除？")
            .setView(pv).setCancelable(true).create();
        LinearLayout btns = new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView bR = keyBtn("恢复", 0xFF2E9E5B); TextView bD = keyBtn("删除", 0xFFE05B4C);
        bR.setOnClickListener(x -> { d.dismiss(); restoreKeyEntry(ff); refreshKeyList(); });
        bD.setOnClickListener(x -> { d.dismiss(); removeRecycleFile(ff); refreshKeyList(); });   // 不再二次“彻底删除该子项？”确认
        btns.addView(bR, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(10), 1); btns.addView(new View(this), gap);
        btns.addView(bD, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pv.addView(btns);
        d.show();
    }
    private TextView keyBtn(String txt, int color) {
        TextView t = new TextView(this); t.setText(txt);
        t.setTextSize(16f); t.setTextColor(0xFFFFFFFF); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(10)); t.setBackground(g);
        return t;
    }
    private void removeRecycleFile(java.io.File f) { if (f.exists()) deleteDirRec(f); toast("已彻底删除该子项"); afterRecycleChange(); }

    /** 恢复单条子项：原位置精确放回；缺失则兜底重建同名链，值回到其 key */
    private void restoreKeyEntry(java.io.File ff) {
        try {
            String orig = readOrigOf(ff);
            JSONObject data = readDataOf(ff);
            if (data == null || orig == null) { toast("该份缺数据，无法恢复"); return; }
            String kv = data.keys().next(); Object val = data.opt(kv);
            // orig= 分支/文件::段1.段2.key
            String brT = orig.substring(0, orig.indexOf('/'));
            String rest = orig.substring(orig.indexOf('/') + 1);
            String fileN = rest.substring(0, rest.indexOf("::"));
            String chainK = rest.substring(rest.indexOf("::") + 2);
            java.util.List<String> segs = new java.util.ArrayList<String>();
            int dot = chainK.lastIndexOf('.');
            String lastK = dot < 0 ? chainK : chainK.substring(dot + 1);
            String chain = dot < 0 ? "" : chainK.substring(0, dot);
            if (!chain.isEmpty()) for (String s : chain.split("\\.")) segs.add(s);

            // 定位原分支
            Branch dest = null;
            for (Branch b : repos.all()) if ((b.title != null && b.title.equals(brT)) || b.id.equals(brT)) { dest = b; break; }
            java.io.File dirB = dest != null ? new java.io.File(rootDir, dest.id) : null;
            boolean missingBranch = dirB == null || !dirB.isDirectory();
            java.io.File jf = null;
            if (dirB != null) jf = new java.io.File(dirB, fileN.endsWith(".json") ? fileN : fileN + ".json");
            if (missingBranch || jf == null || !jf.exists()) {
                // 兜底：能否原位差一点→在新分支(或目录)补同名文件顶层链
                recreateBranched(orig, segs, lastK, val, ff);
                return;
            }
            // 文件存在：把 segs 链走到（缺则建对象壳），末尾放 lastK=val
            JSONObject root;
            try { root = new org.json.JSONObject(readAll(jf)); } catch (Exception e) { recreateBranched(orig, segs, lastK, val, ff); return; }
            JSONObject cur = root;
            boolean missing = false;
            for (String s : segs) {
                JSONObject nxt = cur.optJSONObject(s);
                if (nxt == null) { nxt = new JSONObject(); missing = true; try { cur.put(s, nxt); } catch (Exception e) { } }
                cur = nxt;
            }
            try { cur.put(lastK, val); } catch (Exception e) { }
            // archive 壳(仅当根为单键“顶层”白名单)在落盘前剥掉，恢复以内容为根
            JSONObject peeled = archivePeel(root);
            writeJsonAstex(jf, (peeled != null ? peeled : root).toString(2));
            removeRecycleFile(ff);
            repos.initializeFromLocal(rootDir); refreshDrawerList();
            refreshKeyList();
            toast((missing ? "（上层原本缺失，已把缺层补齐并原位放回）" : "已原位恢复该子项 ") + lastK);
        } catch (Exception e) { toast("恢复失败：" + (e.getMessage() == null ? e.toString() : e.getMessage())); }
    }
    private void recreateBranched(String orig, java.util.List<String> segs, String lastK, Object val, java.io.File ff) {
        try {
            String brT = orig.substring(0, orig.indexOf('/'));
            String rest = orig.substring(orig.indexOf('/') + 1);
            String fileN = rest.substring(0, rest.indexOf("::")) ;
            // 良好命名：来自原分支标题/原文件名，重名 re 目录_1/_2…；文件名保留原名
            String safe = brT == null || brT.trim().isEmpty() ? "restored"
                    : brT.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            java.io.File dirB = new java.io.File(rootDir, "re-" + safe);
            int _k = 0; while (dirB.exists() && !dirB.isDirectory()) dirB = new java.io.File(rootDir, "re-" + safe + "_" + (++_k));
            if (!dirB.exists()) dirB.mkdirs();
            String jname = fileN.endsWith(".json") ? fileN : fileN + ".json";
            java.io.File jf = new java.io.File(dirB, jname);

            // 同主合并：同一 re 分支里已有同名 json → 并入那棵树(同父同 key 后到覆盖先到)，不同 key/不同父自然共存；缺文件/缺分支才新建
            JSONObject root;
            if (jf.exists()) {
                try { root = new JSONObject(readAll(jf)); }
                catch (Exception e) { root = new JSONObject(); }
            } else root = new JSONObject();

            // 先解 archive 壳再并：同类文件(带“顶层”壳)不因合并多套一层
            JSONObject peeled = archivePeel(root);
            JSONObject headR = peeled != null ? peeled : root;

            JSONObject cur = headR;
            // segs 若是空就表示直接放根级
            if (segs != null) {
                for (String s : segs) {
                    JSONObject nxt = cur.optJSONObject(s);
                    if (nxt == null) { nxt = new JSONObject(); try { cur.put(s, nxt); } catch (Exception e) { } }
                    cur = nxt;
                }
            }
            try { cur.put(lastK, val == null ? "" : val); } catch (Exception e) { }

            // 合入的是已去壳内容：写回时不再带壳
            writeJsonAstex(jf, headR.toString(2));
            removeRecycleFile(ff);
            repos.initializeFromLocal(rootDir); refreshDrawerList();
            refreshKeyList();
            toast("已并入 re-" + safe + "/" + jname);
        } catch (Exception e) { toast("兜底失败"); }
    }
    private void writeJsonAstex(java.io.File f, String txt) {
        try { java.io.FileOutputStream o = new java.io.FileOutputStream(f); o.write(txt.getBytes("UTF-8")); o.close(); }
        catch (Exception e) { toast("写文件失败"); }
    }
    private String readOrigOf(java.io.File f) {
        try { if (f.isDirectory()) return f.getName();
            JSONObject j = new JSONObject(readAll(f)); return j.optString("orig", f.getName()); }
        catch (Exception e) { return f.getName(); }
    }
    private JSONObject readDataOf(java.io.File f) {
        try { JSONObject j = new JSONObject(readAll(f)); Object d = j.opt("data");
            return d instanceof JSONObject ? (JSONObject) d : null; } catch (Exception e) { return null; }
    }
    private String foldRecycleLabel(String orig) {
        if (orig == null) return "";
        String[] outer = orig.split("::", 2);
        String head = outer.length > 0 ? outer[0] : "";
        String tail = outer.length > 1 ? outer[1] : orig;
        java.util.List<String> pieces = new java.util.ArrayList<String>();
        for (String s : head.split("/")) if (!s.isEmpty()) pieces.add(s);
        String chain = tail; int dot = chain.lastIndexOf('.'); String key3;
        String cpre = dot < 0 ? "" : chain.substring(0, dot);
        key3 = dot < 0 ? chain : chain.substring(dot + 1);
        if (!cpre.isEmpty()) { for (String s : cpre.split("\\.")) if (!s.isEmpty()) pieces.add(s); }
        pieces.add(key3);
        String joined = "";
        for (String p : pieces) {
            String j = p;
            if (j.toLowerCase().endsWith(".json")) j = j.substring(0, j.length() - 5);
            joined += (joined.isEmpty() ? "" : "-") + clip3(j);
        }
        return joined;
    }
    private String clip3(String s) { if (s == null) return ""; StringBuilder b = new StringBuilder(); int c = 0;
        for (int i = 0; i < s.length() && c < 3; i++) { b.append(s.charAt(i)); c++; } return b.toString(); }
    /** 归一外壳名：去掉 JSON 转义的 \/ 及反斜杠、C 风格首尾空格后用它判断白名单 */
    private String normShellKey(String s) {
        if (s == null) return "";
        String out = s.replace("\\/", "").replace("\\", "").replace("/", "").trim();
        return out;
    }
    /** 是否标 archive 壳：根只单键、且键名归一 == “顶层”。若是返回其唯一内容对象，否则返回 null */
    private JSONObject archivePeel(JSONObject root) {
        if (root == null || root.length() != 1) return null;
        try {
            java.util.Iterator<String> it = root.keys();
            String k = it.hasNext() ? it.next() : null;
            if (normShellKey(k).equals("顶层")) {
                Object o = root.opt(k);
                if (o instanceof JSONObject) return (JSONObject) o;
            }
        } catch (Exception e) { }
        return null;
    }
    private void copyRec(java.io.File src, java.io.File dst) {
        try {
            if (src.isDirectory()) { dst.mkdirs(); java.io.File[] c = src.listFiles(); if (c != null) for (java.io.File x : c) copyRec(x, new java.io.File(dst, x.getName())); }
            else { java.io.FileInputStream i = new java.io.FileInputStream(src); java.io.FileOutputStream o = new java.io.FileOutputStream(dst); byte[] b = new byte[8192]; int n; while ((n = i.read(b)) > 0) o.write(b, 0, n); i.close(); o.close(); }
        } catch (Exception ignored) {}
    }
    /** 直接跳到某条删除记录所在层并高亮该 key(类似 jumpByHistory 但对 seg)。 */
    private void markDelSaved(java.util.List<String> seg, String key) {
        java.util.List<String> cs = new java.util.ArrayList<>(seg);
        for (DelRec d : dels) if (d.file.equals(openName) && !d.saved && sameSeg(d.seg, cs) && d.key.equals(key)) {
            d.saved = true; break;
        }
    }
    /** 打开”基于 file 名在 currentBranch 里切换“（供删除记录跳/恢复用）。返回 tree、null 表示失败。 */
    /** 现场恢复“回收站关闭&真删&同会话”删除记录：改在保留当前编辑缓存的中间态恢复(绝不重载磁盘丢未保存编辑)。 */
    private void recoverDel(DelRec d) {
        String snap = d.snap;
        if (tree == null) return;
        if (!openName.equals(d.file)) { toast("请先切到 "+d.file+" 所在文件再恢复(避免丢当前未保存编辑)"); return; }
        JSONObject cont = tree.containerAt(new java.util.ArrayList<>(d.seg));
        if (cont == null) { toast("该容器当前不存在(可能已整块被删)，无法现场恢复"); return; }
        Object v;
        try {
            if (snap.trim().startsWith("{") || snap.trim().startsWith("[") || snap.trim().equals("null") || snap.trim().equals("true") || snap.trim().equals("false") || snap.trim().matches("-?\\d+(\\.\\d+)?")) {
                v = new org.json.JSONTokener(snap).nextValue();
            } else v = snap;
        } catch (Exception e1) { v = snap; }
        try { cont.put(d.key, v); tree.forceDirty(); } catch (Exception e2) { toast("恢复失败：" + e2.getMessage()); return; }
        dels.remove(d);
        hlSegs = new java.util.ArrayList<>(d.seg); hlKey = d.key;
        path.clear(); path.addAll(d.seg); renderContainer();
        toast("已把 items: "+ d.key +" 现场恢复回原位(可继续编辑/保存)");
    }


    private static final int REQ_PICK_DIR = 1001;
    private static final int REQ_PICK_BG   = 1002;   // 设置里选背景图
    private static final int REQ_IMPORT_FOLDER = 1003;  // 导入文件夹(批量拷 json 入当前草稿分支)
    private static final int REQ_IMPORT_JSF  = 1004;   // 自选 json(可多选) 拷入当前草稿分支
    private static final int REQ_EXPORT_ZIP = 1005;   // 分支导出 zip：选保存位置

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 排查用：把未捕获崩溃写进外部可见 crash.txt(本机无可用 adb，供用户回传定位)
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(dir, "crash.txt"), true);
                    java.io.PrintStream ps = new java.io.PrintStream(fo);
                    ps.println("==== " + new java.util.Date() + " " + Thread.currentThread().getName() + " ====");
                    ex.printStackTrace(ps);
                    ps.flush();
                    ps.close();
                }
            } catch (Throwable ignored) { }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        setContentView(R.layout.activity_main);

        title = findViewById(R.id.title);
        content = findViewById(R.id.content);
        scrim = findViewById(R.id.scrim);
        drawer = findViewById(R.id.drawer);
        drawerList = findViewById(R.id.drawerList);
        btnSave = findViewById(R.id.btnSave);
        btnSettings = findViewById(R.id.btnSettings);
        btnSearch = findViewById(R.id.btnSearch);
        btnSaveHistory = findViewById(R.id.btnSaveHistory);
        topBar = findViewById(R.id.topBar);

        // 顶栏标题
        title.setText(getString(R.string.app_name));

        // 草稿库目录(开发/无授权时使用)；正式运行靠 SAF，这里仅作回退
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        rootDir = new File(base, "YouranEditor");
        rootDir.mkdirs();

        // 汉堡开关抽屉
        findViewById(R.id.btnMenu).setOnClickListener(v -> toggleDrawer());
        scrim.setOnClickListener(v -> closeDrawer());
        drawerList.setOnItemClickListener((p, v, pos, l) -> {
            closeDrawer();
            switchToBranchGuarded(currentBranchList.get(pos));
        });
        drawerList.setOnItemLongClickListener((p, v, pos, l) -> {
            if (pos < 0 || pos >= currentBranchList.size()) return true;
            Branch br = currentBranchList.get(pos);
            showBranchMenu(br);
            return true;
        });
        // 抽屉右上角 ＋：新建汉化分支
        findViewById(R.id.draftBtnPlus).setOnClickListener(v -> {
            showCreateBranchDialog();
        });

        // 保存按钮(默认灰，onClick 才真正写盘)
        btnSave.setOnClickListener(v -> doSave());

        // 设置按钮(最右⚙)：第二轮 UI 美化做右侧滑出设置面板，此处先落入口
        btnSettings.setOnClickListener(v -> toggleSettings());

        // 搜索按钮(放大镜)：独立弹出搜索
        btnSearch.setOnClickListener(v -> openSearchDialog());

        // 保存记录按钮(搜索左侧)：
        //   · 短按(及时松开) = 单击：立即进入“过往保存”查看；
        //   · 按住达到阈时长(holdMs,下方常量)即自动回跳到最近保存点，不依赖松开(长按执行跳转)，
        //     长按达成后那次触摸的抬起不会误开记录页；短按不足阈值则当单击用。
        attachHistoryBtn();

        try {
            applySkinColors();
            repos.initializeFromLocal(rootDir);      // 抽屉改为从私有目录扫(默认空)
            refreshDrawerList();
            refreshSaveButton();
            renderWelcome();
            wsBufHandler.postDelayed(() -> offerRecoveryIfAny(), 600);   // 上次未保存草稿找回提示
        } catch (Throwable e) {
            // 崩在启动路径：把信息写到顶栏 title 便于用户/日志一眼定位(此环境无 adb)。
            String m = String.valueOf(e);
            StackTraceElement[] st = e.getStackTrace();
            if (st != null && st.length > 0) m += "\n@ " + st[0].getClassName() + "." + st[0].getMethodName() + "():" + st[0].getLineNumber();
            title.setText("启动出错: " + m);
            Toast.makeText(this, "启动出错: " + m, Toast.LENGTH_LONG).show();
        }
    }

    /** 记录按钮手下计时逻辑：短按(及时松)= 单击开记录页；按住达到阈值立即自动回跳(不等松手)。 */
    private void attachHistoryBtn() {
        final long hold = 480;                       // “长按最短判定时长”，到点即自动跳(可调)
        final boolean[] fired = {false};
        final Handler hh = new Handler(Looper.getMainLooper());
        final Runnable jump = new Runnable() {
            @Override public void run() {
                if (fired[0]) return;                 // 已是长按跳了，忽略后续到点
                fired[0] = true;
                java.util.List<HistoryLog.Entry> all = HistoryLog.all();
                if (all == null || all.isEmpty()) { toast("还没有已保存的最新修改可回跳"); return; }
                HistoryLog.Entry lastEntry = all.get(all.size() - 1);
                toast("回跳最近保存点：" + lastEntry.file + " / " + lastEntry.key);
                try { jumpByHistory(lastEntry); } catch (Exception e) { toast("回跳失败：" + e.getMessage()); }
            }
        };
        btnSaveHistory.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    fired[0] = false;
                    hh.postDelayed(jump, hold);
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    hh.removeCallbacks(jump);
                    if (!fired[0]) openHistoryEntry();     // 短按(少于阈值)当单击 → 立即开记录页
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    hh.removeCallbacks(jump);
                    return true;
                default:
                    return true;
            }
        });
    }

    /** 长按垃圾桶“取消待删”后小幅重画当前列表(保持所处分支不会切走)。 */
    private void reRenderRowList() {
        if (tree == null) return;
        renderContainer();
    }

    /** 下拉刷新=只把当前这个 ListView 的数据重映射一份(不重建整页/不清标题、避免“闪”)。 */

    /** 重建抽屉数据并重画(新建分支后调用)。 */
    private void refreshDrawerList() {
        restorePinnedIfNeeded();               // 首次重建前把上次置顶集合读回来(持久)
        java.util.List<Branch> all = repos.all();
        java.util.List<Branch> top = new ArrayList<>();
        for (String id : pinnedBranchIds) { Branch b = repos.byId(id); if (b != null) top.add(b); }
        for (Branch b : all) if (!pinnedBranchIds.contains(b.id)) top.add(b);
        currentBranchList = top;
        if (drawerList != null) drawerList.setAdapter(new BranchAdapter(currentBranchList));
    }

    /** “创建新汉化分支”窗：标题固定、下方输入名称，确认后建私有工作目录。 */
    private void showCreateBranchDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(8), dp(18), dp(6));
        TextView cap = new TextView(this);
        cap.setText("创建新汉化分支");
        cap.setTextSize(15f); cap.setTextColor(Skin.text(this));
        col.addView(cap);
        EditText name = new EditText(this);
        name.setHint("输入分支名称…");
        name.setSingleLine(true);
        name.setTextColor(Skin.text(this));
        name.setHintTextColor(Skin.subText(this));
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("新建分支")
                .setView(col)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        styleDialogDark(dlg);
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) { toast("名称不能为空"); return; }
            repos.addBranch(nm, rootDir);      // 私有目录 <root>/<slug>
            refreshDrawerList();
            dlg.dismiss();
            toast("已新建分支：" + nm);
        }));
        dlg.show();
    }

    /** 点击主页大框：创建「泰拉瑞亚模板分支」——复用一个输入窗，落盘后把 assets 里的
     *  original-zh-Hans.zip 中的 json 平铺解进该分支文件夹，再刷新抽屉。 */
    private void showCreateTemplateBranchDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(8), dp(18), dp(6));
        TextView cap = new TextView(this);
        cap.setText("创建新汉化分支");
        cap.setTextSize(15f); cap.setTextColor(Skin.text(this));
        col.addView(cap);
        EditText name = new EditText(this);
        name.setHint("输入分支名称…（泰拉瑞亚模板）");
        name.setSingleLine(true);
        name.setTextColor(Skin.text(this));
        name.setHintTextColor(Skin.subText(this));
        col.addView(name, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("创建泰拉瑞亚模板分支")
                .setView(col)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        styleDialogDark(dlg);
        dlg.setOnShowListener(d2 -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String nm = name.getText().toString().trim();
            if (nm.isEmpty()) { toast("名称不能为空"); return; }
            Branch b = repos.addBranch(nm, rootDir);   // 建 <root>/<slug>
            int n = deployAssetsZipTo(b);              // 解 assets zip 到该分支文件夹
            refreshDrawerList();
            dlg.dismiss();
            toast(n > 0 ? ("已部署 " + n + " 个模板 json 到分支 " + nm) : "分支已建，但未找到模板 zip 内容");
        }));
        dlg.show();
    }

    /** 读 Assets 里的 original-zh-Hans.zip，把其中 .json 平铺写进 <branch> 所在文件夹。返回写入数。 */
    private int deployAssetsZipTo(Branch b) {
        int n = 0;
        try {
            java.io.InputStream in = getAssets().open("original-zh-Hans.zip");
            java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(in);
            java.util.zip.ZipEntry e;
            java.io.File dir = new java.io.File(rootDir, b.id);
            if (!dir.exists()) dir.mkdirs();
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String nm = e.getName();
                int sl = nm.lastIndexOf('/');
                String leaf = sl >= 0 ? nm.substring(sl + 1) : nm;   // 平铺：只取文件名
                if (!leaf.toLowerCase().endsWith(".json")) continue;
                java.io.File out = new java.io.File(dir, leaf);
                if (out.getName().contains("..")) continue;
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                byte[] buf = new byte[8192]; int r;
                while ((r = z.read(buf)) > 0) fo.write(buf, 0, r);
                fo.close(); n++;
            }
            z.close(); in.close();
        } catch (Exception ex) { toast("解压模板失败：" + ex.getMessage()); }
        return n;
    }

    /** 抽屉分支 长按菜单：置顶/取消置顶(右上角箭头风格) + 重命名 + 删除。 */
    private void showBranchMenu(final Branch br) {
        final boolean pinned = pinnedBranchIds.contains(br.id);
        final AlertDialog[] h = new AlertDialog[1];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(6), dp(14), dp(6));

        // 顶行：名称 + 右上角置顶图标(↑顶横线 ⇧置顶 / 向下 ⇩取消置顶)
        // 顶行:名称 + 右上角“回到顶部箭头”置顶图标;在标头最右贴边(CustomTitle)。
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        TextView nm2 = new TextView(this);
        nm2.setText(br.title);
        nm2.setTextSize(17f); nm2.setTypeface(null, Typeface.BOLD);
        nm2.setTextColor(Skin.text(this));
        hdr.addView(nm2, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView pinArrow = new ImageView(this);
        boolean isPinNow = pinnedBranchIds.contains(br.id);
        pinArrow.setImageResource(isPinNow ? R.drawable.ic_pin_filled : R.drawable.ic_pin_off);
        pinArrow.setColorFilter(isPinNow ? 0xFF79A6FF : 0xFF9AA3AF);
        pinArrow.setPadding(dp(4), dp(4), dp(4), dp(0));
        pinArrow.setOnClickListener(v -> { h[0].dismiss(); toggleBranchPin(br.id); }); // 右上角(标题行最右贴边)
        hdr.addView(pinArrow, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout bRow = new LinearLayout(this);
        bRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView bRen = UiKit.chipBtn(MainActivity.this,"重命名", 0xFF42618F);
        bRen.setOnClickListener(v -> { h[0].dismiss(); renameBranch(br); });
        TextView bDel = UiKit.chipBtn(MainActivity.this,"删除", 0xFFD64545);
        bDel.setOnClickListener(v -> { h[0].dismiss(); confirmDeleteBranch(br); });
        bRow.addView(bRen, new LinearLayout.LayoutParams(0, dp(42), 1f));
        bRow.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        bRow.addView(bDel, new LinearLayout.LayoutParams(0, dp(42), 1f));
        col.addView(bRow, new LinearLayout.LayoutParams(-1, dp(46)));
        // “导出”整行：把该分支的 json 压成 zip(&lt;分支名&gt;.zip)，取 SAF 保存位置
        LinearLayout.LayoutParams exlp = new LinearLayout.LayoutParams(-1, dp(44));
        exlp.topMargin = dp(8);
        TextView bExp = UiKit.chipBtn(MainActivity.this, "⬇ 导出分支(zip)", 0xFF557A46);
        bExp.setOnClickListener(v -> { h[0].dismiss(); startExportBranch(br); });
        col.addView(bExp, exlp);

        AlertDialog d = new AlertDialog.Builder(this)
                .setCustomTitle(hdr)              // 标题自定义行:左分支名 + 右贴边置顶箭头
                .setView(col)
                .setPositiveButton("完成", null)
                .create();
        styleDialogDark(d);
        h[0] = d;
        d.setCanceledOnTouchOutside(true);
        d.show();
    }

    private void toggleBranchPin(String id) {
        if (pinnedBranchIds.contains(id)) pinnedBranchIds.remove(id);
        else pinnedBranchIds.add(id);
        persistPinned();                 // 顶置变化即刻落盘：重进/刷新都保持置顶
        refreshDrawerList();
    }

    /** 重命名分支：改文件夹名并全量重建(私有目录扫描视角)。 */
    private void renameBranch(Branch br) {
        java.io.File oldF = new java.io.File(rootDir, br.id);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(8), dp(18), dp(6));
        final EditText ty = new EditText(this);
        ty.setText(br.title);
        ty.setSingleLine(true); ty.selectAll();
        ty.setTextColor(Skin.text(this));
        col.addView(ty);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("重命名分支")
                .setView(col)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss()).create();
        styleDialogDark(dlg);
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String nw = ty.getText().toString().trim();
            if (nw.isEmpty()) { toast("名称不能为空"); return; }
            String nl = brSlug(nw);
            java.io.File nf = new java.io.File(rootDir, nl);
            if (!oldF.equals(nf) && nf.exists()) { toast("已存在同名分支"); return; }
            if (oldF.exists()) oldF.renameTo(nf); else { nf.mkdirs(); }
            pinnedBranchIds.remove(br.id); if (pinnedBranchIds.contains(br.id)) pinnedBranchIds.remove(br.id);
            pinnedBranchIds.add(nl);
            repos.initializeFromLocal(rootDir);
            pendingBranchRekey(String.valueOf(br.id), nl);    // 阶段4:分支改名后把其未保存暂存改挂到新 id,不丢
            refreshDrawerList();
            dlg.dismiss(); toast("已重命名");
        }));
        dlg.show();
    }

    private void confirmDeleteBranch(Branch br) {
        new AlertDialog.Builder(this)
                .setTitle("删除分支")
                .setMessage("确定删除「" + br.title + "」？将移除其工作文件夹及里面所有 json。")
                .setPositiveButton("删除", (d, w) -> {
                    ensureFavsLoaded();              // 先把两套收藏读进内存，删除后才能安全 prune(避免空盘误写)
                    pinnedBranchIds.remove(br.id);
                    java.io.File fromB = new java.io.File(rootDir, br.id);
                    recycleBranchFs(fromB, br.title);   // 回收站开 = 先把整个分支夹副本收进 recycle/branches/
                    deleteDirRec(fromB);
                    repos.initializeFromLocal(rootDir);
                    pruneOrphans();                  // 现在 br 已不在存活分支里 → 它名下收藏(含历史残留)一并清掉并写回
                    refreshDrawerList();
                    refreshSaveButton();
                    // 若正停留在被删的这个分支里 → 清空会话并回到引导首页，避免停留/重进残留空壳
                    if (currentBranch != null && currentBranch.id != null && currentBranch.id.equals(br.id)) {
                        currentBranch = null; tree = null; openName = null;
                        try { path.clear(); } catch (Exception ignore) {}
                        clearWsBuf();                      // 该分支已入回收站: 清掉它那份 .buf,避免死分支占灯
                        renderWelcome();
                        refreshSaveButton();               // 死了的分支不再计灯(其待删暂存仍留在 pending 供恢复)
                    }
                    toast("已删除分支及其收藏");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteDirRec(java.io.File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) { java.io.File[] c = f.listFiles(); if (c != null) for (java.io.File x : c) deleteDirRec(x); }
        f.delete();
    }
    private void copyDirRec(java.io.File src, java.io.File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            java.io.File[] c = src.listFiles();
            if (c == null) return;
            for (java.io.File x : c) copyDirRec(x, new java.io.File(dst, x.getName()));
        } else {
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        }
    }
    /** 分支整夹恢复：以 re-<原文件夹名> 拷回 rootDir(不覆盖现存分支)，再统一重扫。 */
    private void restoreBranchArc(java.io.File ff) {
        try {
            // 分支恢复要用“原可读名”，不再用回收夹哈希名：据 name/file 查 .index.json 里该夹登记的 srcPath(原分支可读名)
            String name = ff.getName();
            String readable = null;
            try {
                JSONArray a = idxLoad();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i); if (o == null) continue;
                    if ("branches".equals(o.optString("kind")) && name.equals(o.optString("file"))) {
                        String sp = o.optString("srcPath", "");
                        if (sp.trim().length() > 0) readable = sp;
                        break;
                    }
                }
            } catch (Exception ignored) { }
            if (readable == null) readable = name;         // 旧数据没登记时退回夹内名
            String safe = brSlug(readable);
            java.io.File dirB = new java.io.File(rootDir, "re-" + safe);
            int k = 0; while (dirB.exists()) dirB = new java.io.File(rootDir, "re-" + safe + "_" + (++k));
            // 恢复时把原分支名下未保存暂存改挂到新建 re- 分支(原分支id以 recycle 里 _srcid 为准,缺失才回退文件名)
            String oldId = null;
            try { java.io.File sidf = new java.io.File(ff, "_srcid"); if (sidf.exists()) oldId = readAll(sidf).trim(); } catch (Exception ignore) { }
            if (oldId == null || oldId.isEmpty()) oldId = safe;
            if (!oldId.isEmpty()) pendingBranchRekey(oldId, dirB.getName());
            // 分支整夹本来就指向一个完整 json 集目录：把内容直接拷进 re 分支，无需再嵌套一格
            copyDirRec(ff, dirB);
            try { java.io.File mf = new java.io.File(dirB, "_srcid"); if (mf.exists()) mf.delete(); } catch (Exception ignore) {}
            deleteDirRec(ff);
            repos.initializeFromLocal(rootDir); refreshDrawerList();
            refreshDoneOrWelcomeIfNeeded();
            toast("已恢复分支为 re-" + safe);
        } catch (Exception e) { toast("恢复分支失败"); }
    }
    private void refreshDoneOrWelcomeIfNeeded() { if (atWelcomePage) renderWelcome(); }
    private static String brSlug(String s) {
        String out = s.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return out.isEmpty() ? "branch" : out;
    }

    private void renderWelcome() {
        atWelcomePage = true;                 // 本页即“未选仓库”引导首页：返回到此为止
        title.setText(getString(R.string.app_name));   // 顶部标题回主页时刷回软件名(修复残留旧"分支/文件")
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER);
        wrap.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView emoji = new TextView(this);
        emoji.setText("🗂\uFE0F");
        emoji.setTextSize(40f);
        emoji.setGravity(Gravity.CENTER);
        wrap.addView(emoji, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText("点击创建你的泰拉瑞亚汉化！");     // 主框文案（模板分支）
        t.setTextSize(16f);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(Skin.text(this));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(12), dp(18), dp(2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.gravity = Gravity.CENTER;
        wrap.addView(t, tl);

        TextView hint = new TextView(this);
        hint.setText("点这里创建一个自带模板的分支，进去就能改 json（此按钮建的是泰拉瑞亚模板分支）");
        hint.setTextSize(12f);
        hint.setTextColor(Skin.mutedText(this));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrap.addView(hint, hl);
        wrap.setClickable(true);                                   // 主框整卡可点 → 创建泰拉瑞亚模板分支
        wrap.setOnClickListener(v -> showCreateTemplateBranchDialog());

        // 外层 shell 占满并居中的卡片留白
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER);
        shell.setPadding(dp(22), dp(0), dp(22), dp(0));
        shell.addView(asCard(wrap), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContent(shell);
    }

    /** 给子内容包一层圆角卡片(白/深灰表面 + 细描边)，供空态/引导复用。 */
    private FrameLayout asCard(View inner) {
        FrameLayout card = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Skin.surface(this));
        gd.setCornerRadius(dp(18));
        gd.setStroke(dp(1), Skin.divider(this));
        card.setBackground(gd);
        card.setPadding(dp(4), dp(10), dp(4), dp(10));
        card.addView(inner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // 卡片需由 FrameLayout(content) 用居中 LayoutParams 挂载；setContent 会重挂，故此处自足即可
        return card;
    }

    private void setContent(View v) {
        content.removeAllViews();
        content.addView(v, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        applyBgLayer();
    }

    /** 内容区垫背景图层：放到最底(index 0)；无背景时移除旧层。 */
    private void applyBgLayer() {
        // 移除可能残留的旧背景层
        for (int i = 0; i < content.getChildCount(); i++) {
            View ch = content.getChildAt(i);
            if (ch instanceof ImageView && "bg".equals(ch.getTag())) { content.removeViewAt(i); i--; }
        }
        ensureBgBase();
        if (bgBase == null) return;   // 无背景
        // 确保展示位图为“母版已处理”版本(模糊/自动明暗)。无背景则 bgBase 为空。
        Bitmap show = Skin.processForDisplay(this, bgBase);
        if (show == null) return;
        ImageView bg = new ImageView(this);
        bg.setTag("bg");
        bg.setImageBitmap(show);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        content.addView(bg, 0, lp);
        // 释放上一版“展示位图”(仅回收我们自建副本，保留母版 bgBase)
        if (cachedBg != null && cachedBg != show && !cachedBg.isRecycled()) cachedBg.recycle();
        cachedBg = show;
    }

    /** 惰性解码母版原图(屏幕尺寸)。导入新背景/移除时也由对应流程把它置空重建。 */
    private void ensureBgBase() {
        if (bgBase != null && !bgBase.isRecycled()) return;
        bgBase = Skin.loadBgBitmap(this);
    }

    /** 重新按当前(模糊强度+主题)生成展示图并垫出。调模糊滑杆 / 切主题后即时调用。 */
    private void reprocessBackground() {
        ensureBgBase();
        if (bgBase == null) return;
        // 先把旧展示图作废并垫底，让“即改即看”正确
        applyBgLayer();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 输入框美化：浅灰圆角矩形底 + 内侧细描边，制造轻微“内凹盒”观感。 */
    private void styleEditBox(EditText et) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Skin.inputBg(this));               // 输入底(明暗跟随主题)
        gd.setCornerRadius(dp(10));                    // 圆角
        gd.setStroke(dp(1), Skin.inputStroke(this));   // 内侧细描边
        et.setBackground(gd);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setTextColor(Skin.text(this));
    }

    /**
     * 内容弹窗统一外观：整体套一个「大圆角底色」。浅色白底、深色深灰底(避免死黑)，
     * 深色下标题/消息/按钮文字翻浅色，保证清楚可读。
     */
    private void styleDialogDark(android.app.AlertDialog dlg) {
        if (dlg == null) return;
        boolean dark = Skin.isDark(this);
        // 圆角底色壳：深灰(深色)、白(浅色)，让“灰色弹窗”边缘层次清楚、不再是纯黑死板或直角。
        android.graphics.drawable.GradientDrawable shell =
                new android.graphics.drawable.GradientDrawable();
        shell.setColor(dark ? 0xFF3A3E45 : 0xFFFFFFFF);   // 深色灰 / 浅色白
        shell.setCornerRadius(dp(20));                     // 大圆角
        try {
            dlg.getWindow().setBackgroundDrawable(shell);
        } catch (Exception ignored) { }

        // A方案(稳优先)：编辑类弹窗不再做任何窗口动画——瞬间出现，杜绝上下位移/闪现。
        // （后续若想统一加精致的淡入，再替换为该开关的整体实现，不在这类入口逐个放。）
        try {
            if (dlg.getWindow() != null)
                dlg.getWindow().setWindowAnimations(0);
        } catch (Exception ignored) { }

        if (!dark) return;                                 // 浅色已是深字白底，不需再动文字
        // 标题与消息(平台内部 TextView)翻转浅色
        try {
            if (dlg.findViewById(android.R.id.title) != null)
                ((TextView) dlg.findViewById(android.R.id.title)).setTextColor(0xFFF2F4F8);
        } catch (Exception ignored) { }
        try {
            if (dlg.findViewById(android.R.id.message) != null)
                ((TextView) dlg.findViewById(android.R.id.message)).setTextColor(0xFFC9CDD4);
        } catch (Exception ignored) { }
        // 正/负按钮文字浅亮(灰底上用浅蓝更醒目)
        try {
            java.util.List<Integer> btnIds = new ArrayList<>();
            btnIds.add(android.app.AlertDialog.BUTTON_POSITIVE);
            btnIds.add(android.app.AlertDialog.BUTTON_NEGATIVE);
            btnIds.add(android.app.AlertDialog.BUTTON_NEUTRAL);
            for (Integer bid : btnIds)
                if (dlg.getButton(bid) != null) dlg.getButton(bid).setTextColor(0xFF8DB0FF);
        } catch (Exception ignored) { }
    }

    // ==================== 抽屉(侧栏) ====================

    private List<Branch> currentBranchList = new ArrayList<>();

    private void toggleDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        else openDrawer();
    }

    /** 当前 B 设计每行自己管理开合；此处为空实现仅供长按统一收回占位。 */
    // ==================== ★ 新收藏 / 动作窗 ====================

    /** key 长按动作窗：⭐收藏/取消收藏 + 🗑删除，占一行各半宽；右下角 取消。 */
    private void showActionPanel(List<String> parentSegs, JSONObject container, String key, boolean isObj) {
        ensureFavsLoaded();   // 进动作窗前先读落盘，重进后能正确显示“取消收藏”而非误当没收藏
        boolean already = isFaved(parentSegs, key);          // 是否已在收藏(含当前文件)…重进后也能正确识别
        String sub = isObj ? "子项组" : "标量值";

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), dp(6));

        TextView cap = new TextView(this);
        cap.setText("已选中： " + key + "  （" + sub + "）");
        cap.setTextSize(13f); cap.setTextColor(Skin.text(this));
        col.addView(cap);

        // ⭐ 收藏 / 删除 —— 一行、各占一半
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams oLW = new LinearLayout.LayoutParams(0, dp(44), 1f);
        int gap = dp(6);
        TextView bFav = UiKit.chipBtn(MainActivity.this,already ? "取消收藏" : "⭐ 收藏", already ? 0xFF665A2F : 0xFF8A63D2);
        bFav.setOnClickListener(v -> toggleFavorite(parentSegs, key, currentBranch));
        ops.addView(bFav, oLW);
        (oLW).rightMargin = gap;
        TextView bDel = UiKit.chipBtn(MainActivity.this,"删除", 0xFFD64545);
        bDel.setOnClickListener(v -> { /*下方法阻塞关窗统一处理*/ });
        LinearLayout.LayoutParams oLW2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        oLW2.leftMargin = gap;
        ops.addView(bDel, oLW2);
        col.addView(ops, new LinearLayout.LayoutParams(-1, dp(48)));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("对这个 key 做点什么？")
                .setView(col)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        styleDialogDark(dlg);
        // ⭐ 收藏/取消收藏
        bFav.setOnClickListener(v -> { toggleFavorite(parentSegs, key, currentBranch); dlg.dismiss(); });
        // 🗑 删除 → 再弹一个“确定删除”确认
        bDel.setOnClickListener(v -> { dlg.dismiss(); confirmUnifiedDelete(container, key, isObj); });
        dlg.show();
    }

    private boolean isFaved(List<String> parent, String key) {
        // 要与收藏/取消逻辑完全一致：属当前正在编辑的这个 json(openName)、同一父链、同 key —— 重进后也成立
        for (Fav f : favs)
            if (f.key.equals(key) && f.file.equals(openName) && sameSeg(f.parent, parent)) return true;
        return false;
    }
    /** 行内判断“是否收藏”加“以现存为准”：父容器此刻没有该 key(已被真删/移走)，或正待删，都视为未收藏。 */
    private boolean isFavedLive(List<String> parent, String key) {
        if (tree == null) return isFaved(parent, key);
        JSONObject cont = tree.containerAt(parent);
        if (cont == null || !cont.has(key)) return false;      // 已经不存在的目标 → 不显示星/不作为收藏判定
        if (tree.isPendingDel(parent, key)) return false;      // 待删也不当作“仍收藏”
        return isFaved(parent, key);
    }

    /** 定位到 container 对应的 JSONObject；当前给出的即当前容器(不做重解析,保留引用)。 */
    /** 如本 key 已在收藏则移除(取消收藏)，否则加入。 */
    private void toggleFavorite(List<String> parentSegs, String key, Branch br) {
        boolean rm = false;
        java.util.Iterator<Fav> it = favs.iterator();
        while (it.hasNext()) { Fav f = it.next();
            if (f.key.equals(key) && sameSeg(f.parent, parentSegs) && f.file.equals(openName)
                    && f.branchId.equals(br != null ? br.id : "")) { it.remove(); rm = true; } }
        if (rm) toast("已取消收藏： " + key);
        else { favs.add(new Fav(br.id, openName, parentSegs, key)); toast("已收藏： " + key); }
        saveFavorites();
        if (curContainerListView != null) curContainerListView.invalidateViews();  // 立刻出星/隐,免翻页
    }

    // ================== ★ 收藏持久化 (退出不清空；存包目录 files/favorites.json) ==================
    private java.io.File favoritesFile() {
        // 用 App 内私有目录(重进必可信)：记在一个易看的 favorites.json。
        return new java.io.File(getFilesDir(), "favorites.json");
    }

    /** 整表落盘——多行/父链用数组。启动读取、增删后各写一次。 */
    private synchronized void saveFavorites() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (Fav f : favs) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("b", f.branchId);
                o.put("file", f.file);
                o.put("k", f.key);
                org.json.JSONArray p = new org.json.JSONArray();
                for (String s : f.parent) p.put(s);
                o.put("p", p);
                arr.put(o);
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(favoritesFile());
            fo.write(arr.toString().getBytes("UTF-8"));
            fo.close();
        } catch (Exception ignored) {}
    }

    /** 启动时读表。只读不回写；真正用不到的(键已删/仓库消失/文件不在)在跳转用不到时顺手清。 */
    synchronized void loadFavorites() {
        favs.clear();
        try {
            java.io.File fl = favoritesFile();
            if (!fl.exists()) return;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = new java.io.FileInputStream(fl);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String txt = new String(bos.toByteArray(), "UTF-8");
            org.json.JSONArray arr = new org.json.JSONArray(txt);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                org.json.JSONArray pa = o.optJSONArray("p");
                List<String> pl = new ArrayList<>();
                if (pa != null) for (int j = 0; j < pa.length(); j++) pl.add(pa.getString(j));
                favs.add(new Fav(o.optString("b", ""), o.optString("file", ""), pl, o.optString("k", "")));
            }
        } catch (Exception ignored) {}
    }
    private static boolean sameSeg(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }
    /** 删除前统一弹“确定？”(简单、一视同仁，不因叶子就直删)。删后同步清收藏。 */
    /** 单击“删除”即进入“待删除”，不再二次确认。只记录不真删,名称后紧贴垃圾桶(=可长按反悔);点总保存才真删。 */
    private void confirmUnifiedDelete(JSONObject container, String key, boolean isObj) {
        final List<String> cur = new ArrayList<>(path);
        tree.addPendingDel(cur, key);          // 软删：登记+置脏
        logDel(cur, key, container);           // 记录页出现一条“删除型”(状态待删)
        refreshSaveButton();
        renderContainer();                      // 名称后垃圾桶出现即视觉反馈,不再额外 toast
        // —— trace(只读) ——
        toast("TRACE软删 seg=[" + java.util.Arrays.toString(cur.toArray()) + "] key=" + key);
    }
    private void removeFavs(List<String> parent, String key) {
        boolean touched = false;
        java.util.Iterator<Fav> it = favs.iterator();
        while (it.hasNext()) { Fav f = it.next();
            if (f.key.equals(key) && sameSeg(f.parent, parent)) { it.remove(); touched = true; } }
        if (touched) saveFavorites();
    }

    private boolean favsBooted = false;

    /** 进入前只加载一次落盘收藏(防覆盖本会话新收藏)。 */
    private void ensureFavsLoaded() {
        if (favsBooted) return;
        favsBooted = true;
        if (favoritesFile().exists()) loadFavorites();
        loadFavFiles();          // json 自身收藏请持久list(重启还在)
    }

    /** 设置·收藏表：列出(本文件内)已收藏 key，点任一 → 跳到其所处容器并高亮该行。 */
    /** ⭐收藏表——按分支分层的“横向制表下钻”：分支固定圆角卡→点入显出该分支被收藏的 json；
     *  json 卡内再以制表缩进列出其内部收藏(key/上层点)。点里层= 直达该收藏(打开+高亮)。 */
    private void openFavoritesDialog() {
        ensureFavsLoaded();
        pruneOrphans();            // 打开前自愈一次(孤儿收藏不再显示/计数)
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(4), dp(10), dp(4));
        // 副标题固定说明
        TextView note = new TextView(this);
        note.setText("按分支分行▸单击 “✚ 收起/展开”，点最内一条即直达。");
        note.setTextSize(11f); note.setTextColor(Skin.mutedText(this));
        root.addView(note);
        renderChain(root);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("⭐ 收藏表")
                .setView(root)
                .setPositiveButton("关闭", null)
                .create();
        styleDialogDark(dlg);
        dlg.show();
    }

    /** 分支固定列出(空支占一圆角可点)。点分支/ json 时本视图逐层替换 body 再 continue. */
    private void renderChain(final LinearLayout host) {
        host.removeAllViews();
        java.util.List<Branch> bts = repos.all();
        for (final Branch br : bts) {                       // step0: 每个分支(固定,含无收藏)
            StringBuilder sub = new StringBuilder();
            boolean hasAny = false;
            java.util.Set<String> filesRec = new java.util.HashSet<>();
            for (Fav f : favs) if (f.branchId.equals(br.id)) { filesRec.add(f.file); hasAny = true; }
            for (String k : favFiles) if (k.startsWith(br.id + "|")) { filesRec.add(k.substring(br.id.length() + 1)); hasAny = true; }
            if (hasAny && sub.length() > 4) sub.append(" · ");
            addFavCard(host, "\t\t👝 " + br.title + (hasAny ? "" : "   (空)"), 0xFF8A63D2,
                    v -> showBranchFileLayer(host, br, filesRec));
        }
    }

    /** step1: 某分支下被收藏的 json列表——每行单击＝进入“查看其内部收藏点”;若该 json 自身也被收藏,
     *  其行卡再附加一颗可点的“▸打开整json”小钮(避免整档直达与下钻抢占同一单击)。 */
    private void showBranchFileLayer(final LinearLayout host, final Branch br, final java.util.Set<String> files) {
        host.removeAllViews();
        addFavCard(host, "↩ 分支 " + br.title, 0x44FFFFFF, v -> renderChain(host));
        if (files.isEmpty()) {
            TextView em = new TextView(this); em.setText("这个分支还没有被收藏的 json。");
            em.setTextColor(Skin.subText(this)); em.setPadding(dp(10), dp(12), 0, dp(12)); host.addView(em);
            return;
        }
        java.util.List<String> listSorted = new java.util.ArrayList<>(files);
        java.util.Collections.sort(listSorted, String.CASE_INSENSITIVE_ORDER);
        for (final String f : listSorted) addFileRow(host, br, f);
    }

    /** 单 json 行：本体=进入内层；若整档也收藏→右端独立小钮=整档即开(不抢单击)。 */
    private void addFileRow(final LinearLayout host, final Branch br, final String file) {
        final boolean whole = favFiles.contains(br.id + "|" + file);
        LinearLayout rl = new LinearLayout(this);
        rl.setOrientation(LinearLayout.HORIZONTAL);
        rl.setGravity(Gravity.CENTER_VERTICAL);
        int base = dp(12); rl.setPadding(base, dp(7), base, dp(7));
        // 左主文本——单击下钻其内部收藏
        TextView t = new TextView(this);
        t.setText("\t\uD83D\uDC4D " + file);
        t.setTextColor(0xFF3489E0);
        rl.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (whole) {
            TextView go = new TextView(this);
            go.setText("▸整 json");
            go.setTextSize(12f); go.setTextColor(0xFFFFD76A);
            go.setPadding(dp(8), dp(4), dp(8), dp(4));
            go.setOnClickListener(v -> jumpWholeFile(br, file));
            rl.addView(go, new LinearLayout.LayoutParams(-2, -2));
            t.setText(t.getText() + "  ★");           // 亮 = 整档也被收藏
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x11000000); g.setStroke(dp(1), 0xFF2E5B9E); g.setCornerRadius(dp(10));
        rl.setBackground(g);
        rl.setOnClickListener(v -> showJsonInnerLayer(host, br, file));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(6);
        host.addView(rl, lp);
    }

    /** step2: 该 json 内部被收藏的 key/上层——逐条=单击直达(跳并高亮)。顶部给“整 json”钮。 */
    private void showJsonInnerLayer(final LinearLayout host, final Branch br, final String file) {
        host.removeAllViews();
        // 唯一返回：回到“该分支全部被收藏的 json 文件列表”(重建、不丢别的 json)
        addFavCard(host, "↩ " + br.title + " · 全部 json", 0x44C7D2FF,
                v -> showBranchFileLayer(host, br, branchFavFiles(br)));

        boolean anyLine = false;
        for (final Fav f : favs) {
            if (!f.branchId.equals(br.id) || !f.file.equals(file)) continue;
            anyLine = true;
            String ctx = "";
            for (String s : f.parent) ctx += "\t";
            addFavCard(host, "\t" + ctx + "★ " + readable(f), 0xFFFFD76A, v -> jumpFav(f));
        }
        if (!anyLine) {
            TextView t = new TextView(this);
            t.setText(favFiles.contains(br.id + "|" + file) ? "仅整档收藏，用列表行‘▸整 json’打开"
                    : "内部暂无可直达点");
            t.setTextColor(Skin.subText(this)); host.addView(t);
        }
    }
    /** 该分支内所有曾出现被收藏的 json 文件名(键收藏 + 整档收藏合并)，用于返回列表时全量重建。 */
    private java.util.Set<String> branchFavFiles(Branch br) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Fav f : favs) if (br.id.equals(f.branchId)) out.add(f.file);
        String pre = br.id + "|";
        for (String k : favFiles) if (k.startsWith(pre)) out.add(k.substring(pre.length()));
        return out;
    }
    private String readable(Fav f) {
        StringBuilder sb = new StringBuilder();
        for (String s : f.parent) { if (sb.length() > 0) sb.append(" › "); sb.append(s); }
        sb.append(" 〉").append(f.key);
        return sb.toString();
    }
    /** 整 json 跳：打开该 json 且展开其顶层对象(纯开出根即可)。 */
    private void jumpWholeFile(Branch br, String file) {
        try { openFile(br, file); toast("打开：" + file); } catch (Exception e) { toast("打开失败"); }
    }

    private void addFavCard(LinearLayout host, String text, int tint, android.view.View.OnClickListener on) {
        TextView card = new TextView(this);
        card.setText(text);
        card.setTextSize(14f);
        int pad = dp(6);
        card.setPadding(dp(12), pad, dp(12), pad);
        card.setTextColor(Skin.text(this));   // 白天黑/夜间白自动跟皮肤
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x22FFFFFF); g.setStroke(dp(1), tint); g.setCornerRadius(dp(10));
        card.setBackground(g);
        if (on != null) card.setOnClickListener(on);
        card.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(5);
        host.addView(card, lp);
    }

    /** 跳转到某收藏：同文件直接切 path；跨文件先切(当前仅提示)。跳后高亮该行。 */
    /** 收藏表点项：无论当前停在哪个页面(含未选仓库主屏)——自动切到收藏所属仓库并打开其 json，
     *  再落到父层并高亮该 key；若指到的 key 已不存在(用户删过/目录失效)则先从表里移除并提示。 */
    private void jumpFav(Fav f) {
        Branch br = repos.byId(f.branchId);
        if (br == null) { toast("找不到这条收藏所属的仓库，已在表移除。"); dropFav(f); return; }
        try {
            openFile(br, f.file);                     // 强制打开该 json(顶部渲染)
        } catch (Exception e) {
            dropFav(f); toast("收藏所属文件打不开了(可能已删)。已从收藏移除。"); return;
        }
        // 目标容器此刻未必展开，用父链渲染到该父层
        path.clear(); path.addAll(f.parent);
        hlFile = openName;
        hlSegs = new ArrayList<>(f.parent);
        hlKey = f.key;
        renderContainer();                            // path=父层→容器含该 key 行
        // 目标此刻必须仍在：不在了(此前真删/路径失效)→ 当场移除这条收藏，不给“点了跳走却没有”
        JSONObject cc = (tree == null) ? null : tree.containerAt(new ArrayList<>(f.parent));
        if (cc == null || !cc.has(f.key)) { dropFav(f); toast("该收藏目标已不存在，已从收藏移除。"); return; }
        toast("已直达收藏： " + f.key);
    }

    private void dropFav(Fav f) {
        favs.remove(f);
        saveFavorites();
    }

    /**
     * 清理“指向已不存在分支”的孤儿收藏(key收藏 favs + 整json收藏 favFiles)。
     * 用于：删分支后、以及打开收藏面/设置前自愈历史残留(过去删分支没同步清)。
     * 幂等：只动孤儿，不影响现存分支的收藏。
     * ⚠ 必须先 ensureFavsLoaded()(把两套落盘读进内存)再调，否则内存为空会被当成“无孤儿”而把整盘清没。
     */
    private void pruneOrphans() {
        java.util.Set<String> alive = new java.util.HashSet<>();
        for (Branch b : repos.all()) alive.add(b.id);
        boolean touchedFav = false, touchedFile = false;
        java.util.Iterator<Fav> it = favs.iterator();
        while (it.hasNext()) { Fav f = it.next();
            if (!alive.contains(f.branchId)) { it.remove(); touchedFav = true; } }
        java.util.Iterator<String> it2 = favFiles.iterator();
        while (it2.hasNext()) {
            String k = it2.next();
            int p = k.indexOf('|');
            String bid = p >= 0 ? k.substring(0, p) : k;
            if (!alive.contains(bid)) { it2.remove(); touchedFile = true; }
        }
        if (touchedFav) saveFavorites();
        if (touchedFile) saveFavFiles();
    }

    private void openDrawer() {
        // 面板宽度约主屏 80%
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
        ViewGroup.LayoutParams lp = drawer.getLayoutParams();
        lp.width = w;
        drawer.setLayoutParams(lp);

        drawer.setTranslationX(-w);          // 从左侧让出一段展开起点
        drawer.setAlpha(0f);
        drawer.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        refreshBranchList();

        // 快出→末端停缓(单调不反弹) 的“开”
        drawer.animate().translationX(0f).alpha(1f).setDuration(340)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.45f)).start();
        scrim.animate().alpha(0.6f).setDuration(280)
                .setInterpolator(new android.view.animation.PathInterpolator(0.22f, 0.9f, 0.34f, 1f)).start();
    }

    private void closeDrawer() {
        int w = drawer.getWidth();
        // 平滑收回(加速略出)的“关”
        drawer.animate().translationX(-(w > 0 ? w : (int) (getResources().getDisplayMetrics().widthPixels * 0.8f)))
                .alpha(0f).setDuration(240)
                .setInterpolator(new android.view.animation.PathInterpolator(0.36f, 0f, 0.66f, 0f))
                .withEndAction(() -> { drawer.setVisibility(View.GONE); drawer.setAlpha(1f); })
                .start();
        scrim.animate().alpha(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    // ==================== 设置(右侧滑出) ====================

    private boolean settingOpen = false;

    /** ⚙ 点击入口：右侧滑出设置面板。 */
    private void toggleSettings() {
        if (settingOpen) closeSettingsPanel();
        else openSettingsPanel();
    }

    /** 顶栏 / 抽屉头两块主色即时应用(取持久化的值)；同时把全局深浅肤色(窗口底/抽屉底)同步。 */
    private void applySkinColors() {
        int tb = Skin.topBarColor(this);
        int dh = Skin.drawerHeadColor(this);
        if (topBar != null) topBar.setBackgroundColor(tb);
        View hdr = findViewById(R.id.drawerHeader);
        if (hdr != null) hdr.setBackgroundColor(dh);
        // status bar 与顶栏同色更协调
        try { getWindow().setStatusBarColor(tb); } catch (Exception ignored) { }
        applyThemeGlobal();
    }

    /** 深浅肤色的全局底色：窗口(内容区背后)与抽屉面板表面。无背景图时即主区底色。 */
    /** 深浅肤色的全局底色：窗口(内容区背后)与抽屉面板表面。无背景图时即主区底色。 */
    private void applyThemeGlobal() {
        try {
            android.graphics.drawable.ColorDrawable cbg =
                    new android.graphics.drawable.ColorDrawable(Skin.contentBg(this));
            getWindow().setBackgroundDrawable(cbg);
        } catch (Exception ignored) { }
        if (drawer != null) drawer.setBackgroundColor(Skin.surface(this));
        View root = findViewById(R.id.root);
        if (root != null) root.setBackgroundColor(Skin.contentBg(this));
    }

    /** 切换深浅主题后：重绘当前界面的语义配色(content 内列表/卡片/空态会按新色重建)。 */
    private void refreshForTheme() {
        applyThemeGlobal();
        if (tree != null) renderContainer();                       // 在某个打开的 json 内(含分层)
        else if (currentBranch != null) showBranchHint(currentBranch); // 停在文件列表页
        else renderWelcome();                                      // 首屏(空态卡)
    }

    private void openSettingsPanel() {
        // 每次打开都完全重建面板：让“已导入背景 / 已选中色块”的最新状态即时反映。
        ViewGroup root = (ViewGroup) findViewById(R.id.root);
        if (root == null) root = (ViewGroup) content.getParent();
        // 先安全摘除旧 View(可能已挂在 root 下)再重建，避免重复 addView 抛“already has a parent”。
        if (settingScrim != null && settingScrim.getParent() != null)
            ((ViewGroup) settingScrim.getParent()).removeView(settingScrim);
        if (settingPanel != null && settingPanel.getParent() != null)
            ((ViewGroup) settingPanel.getParent()).removeView(settingPanel);
        buildSettingsUi();
        root.addView(settingScrim);
        root.addView(settingPanel);
        settingOpen = true;
        // 右侧滑入
        int w = settingPanel.getWidth() > 0 ? settingPanel.getWidth() : (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        settingPanel.setTranslationX(w);
        settingScrim.setVisibility(View.VISIBLE);
        settingScrim.setAlpha(0f);
        settingPanel.setVisibility(View.VISIBLE);
        // 右侧快入→末端停缓(单调不反弹)的“开”
        settingPanel.animate().translationX(0f).setDuration(360)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.45f)).start();
        settingScrim.animate().alpha(0.55f).setDuration(300)
                .setInterpolator(new android.view.animation.PathInterpolator(0.24f, 0.9f, 0.33f, 1f)).start();
    }

    private void closeSettingsPanel() {
        if (!settingOpen) {
            if (settingScrim != null) settingScrim.setVisibility(View.GONE);
            if (settingPanel != null) settingPanel.setVisibility(View.GONE);
            return;
        }
        settingOpen = false;
        int w = settingPanel.getWidth() > 0 ? settingPanel.getWidth() : 0;
        // 平滑滑回右侧的“关”
        settingPanel.animate().translationX(w).setDuration(260)
            .setInterpolator(new android.view.animation.PathInterpolator(0.36f, 0f, 0.68f, 0f))
            .withEndAction(() -> settingPanel.setVisibility(View.GONE)).start();
        settingScrim.animate().alpha(0f).setDuration(240)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .withEndAction(() -> settingScrim.setVisibility(View.GONE)).start();
    }

    private void buildSettingsUi() {
        ensureFavsLoaded();   // 先读一次落盘收藏，使“⭐ 收藏表 N 条”计数不再显示空
        pruneOrphans();       // 自愈孤儿(历史删分支遗留)，计数即时回正

        // 遮罩(右面板自身)
        settingScrim = new View(this);
        settingScrim.setBackgroundColor(0x88000000);
        settingScrim.setVisibility(View.GONE);
        settingScrim.setOnClickListener(v -> closeSettingsPanel());
        FrameLayout.LayoutParams lpM = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        settingScrim.setLayoutParams(lpM);

        // 面板本体：右缘圆角卡片容器(浅色白/深色深灰表面跟随主题)
        settingPanel = new LinearLayout(this);
        settingPanel.setOrientation(LinearLayout.VERTICAL);
        settingPanel.setBackgroundColor(Skin.surface(this));
        settingPanel.setVisibility(View.GONE);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        FrameLayout.LayoutParams lpP = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT);
        lpP.gravity = android.view.Gravity.END;
        settingPanel.setLayoutParams(lpP);

        // 面板头部
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(14), dp(8), dp(14));
        head.setBackgroundColor(Skin.surfaceAlt(this));
        TextView ht = new TextView(this);
        ht.setText("设置");
        ht.setTextSize(17f);
        ht.setOnLongClickListener(v -> { showRawEditor(); return true; });   // 长按顶部标题 → 打开原文·记事本
        ht.setTextColor(Skin.text(this));
        ht.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(ht, hlp);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(20f);
        close.setTextColor(Skin.subText(this));
        close.setPadding(dp(12), dp(4), dp(16), dp(4));
        close.setOnClickListener(v -> closeSettingsPanel());
        head.addView(close);
        settingPanel.addView(head);

        // 内容区(可滚动)
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(18));

        // 背景图入口
        body.addView(UiKit.sectionLabel(MainActivity.this,"背景图"));
        body.addView(UiKit.settingRow(MainActivity.this,"更换图片", Skin.hasBg(this) ? "已导入" : "未设置", v -> pickBackground()));
        if (Skin.hasBg(this)) {
            body.addView(addSeekbar(body, "模糊强度", Skin.blur(this),
                    p -> {              // 拖动过程即持久化并实时刷新(不收起面板)
                        Skin.saveBlur(this, p);
                        reprocessBackground();   // 模糊已改，母版重出即时可见
                    }));
            body.addView(UiKit.addInfo(MainActivity.this,body, "说明：深色主题会自动给图压一层暗，浅色不动，让字不被图吃。"));
            body.addView(UiKit.settingRow(MainActivity.this,"移除背景", "恢复纯色", v -> removeBackground()));
        }

        // 深浅色主题(第三轮 D)
        body.addView(UiKit.sectionLabel(MainActivity.this,"主题"));
        body.addView(UiKit.settingRow(MainActivity.this,"深色模式", Skin.isDark(this) ? "开" : "关", v -> {
            Skin.saveDark(this, !Skin.isDark(this));
            boolean nowDark = Skin.isDark(this);
            // 深浅变也会影响“自动压暗”那层罩，需重新生成展示背景
            reprocessBackground();
            if (drawerList != null) drawerList.setAdapter(new BranchAdapter(currentBranchList)); // 可留空
            toast(nowDark ? "已切换到深色主题" : "已切换到浅色主题");
            closeSettingsPanel();
            refreshForTheme();
        }));

        // 颜色：直接进“随机 / 莫奈 / 自定义”三选界面；不再先经过默认色板那层
        body.addView(UiKit.sectionLabel(MainActivity.this,"UI 主色"));
        body.addView(UiKit.settingRow(MainActivity.this,"颜色管理", "随机 / 莫奈 / 自定义", v -> openColorManager()));
        // ★ 收藏：长按某 key → ⭐收藏，这里点后可快速回到那行
        body.addView(UiKit.settingRow(MainActivity.this,"⭐ 收藏表", favs.isEmpty() ? "空" : favs.size() + " 条", v -> openFavoritesDialog()));

        // ✅ 回收站现在单独起一段(UiKit.sectionLabel 独立标题)，不再跟在“UI 主色”标题的组里
        body.addView(UiKit.sectionLabel(MainActivity.this, "回收站"));
        // ★ 回收站：浅卡无强调色、右端留 ›；数字随 afterRecycleChange live
        {
            LinearLayout rc = new LinearLayout(this);
            rc.setOrientation(LinearLayout.HORIZONTAL); rc.setGravity(Gravity.CENTER_VERTICAL);
            TextView la = new TextView(this); la.setText("回收站");
            la.setTextSize(15f);
            rc.addView(la, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            rcSettingVal = new TextView(this); rcSettingVal.setText("已存 " + recycleCount());
            rcSettingVal.setTextSize(13f);
            rc.addView(rcSettingVal);
            TextView ra = new TextView(this); ra.setText("  \u203A");
            ra.setTextSize(16f);
            rc.addView(ra);
            rc.setOnClickListener(v -> openRecycleBoard());
            rc.setClickable(true);
            rc.setPadding(dp(8), dp(12), dp(8), dp(12));
            android.graphics.drawable.GradientDrawable gg2 = new android.graphics.drawable.GradientDrawable();
            gg2.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gg2.setColor(Skin.cardFill(this));                    // 随主题(浅/深)自适应
            gg2.setCornerRadius(dp(10));
            rc.setBackground(gg2);
            la.setTextColor(Skin.text(this));
            rcSettingVal.setTextColor(Skin.subText(this));
            ra.setTextColor(Skin.mutedText(this));
            body.addView(rc);
        }


        // 底部软件名-日期(两行居中)，用小 spacer 撑到底
        View spacer = new View(this);
        body.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        TextView foot1 = new TextView(this);
        foot1.setText("YouranEditor");
        foot1.setGravity(Gravity.CENTER);
        foot1.setTextColor(Skin.mutedText(this));
        foot1.setTextSize(13f);
        body.addView(foot1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView foot2 = new TextView(this);
        foot2.setText("汉化 JSON 便携编辑 · 2026");
        foot2.setGravity(Gravity.CENTER);
        foot2.setTextColor(Skin.mutedText(this));
        foot2.setTextSize(12f);
        body.addView(foot2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        sv.addView(body);

        // 内容区(可滚动)右缘也配一根可拖细条：复用 ScrollThumb(哑控件，ScrollView 用像素跳)
        android.graphics.drawable.GradientDrawable svBg = new android.graphics.drawable.GradientDrawable();
        svBg.setColor(0x00FFFFFF);       // 透明底，由面板表面决定
        final ScrollThumb stb = new ScrollThumb(this);
        stb.setScrub(f -> {
            View child = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
            if (child == null) return;
            int max = Math.max(0, child.getHeight() - sv.getHeight());
            if (max > 0) sv.scrollTo(0, (int) (f * max));
        });
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            sv.setOnScrollChangeListener((vv, sx, sy, ox, oy) -> {
                View child = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
                int max = child == null ? 0 : Math.max(0, child.getHeight() - sv.getHeight());
                int cur = Math.max(0, Math.min(sy, max));
                stb.setProgress(max > 0 ? (float) cur / max : 0f);
                stb.setVisibleRatio(max > 0 ? (float) Math.max(1, sv.getHeight())
                        / (child.getHeight()) : 1f);
            });
        }

        FrameLayout svStage = new FrameLayout(this);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        svStage.addView(sv, svLp);
        FrameLayout.LayoutParams stbLp = new FrameLayout.LayoutParams(dp(24),
                FrameLayout.LayoutParams.MATCH_PARENT);
        stbLp.gravity = android.view.Gravity.END;
        svStage.addView(stb, stbLp);
        settingPanel.addView(svStage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** 带一条进度滑杆的圆角条目(滑杆无级，上、下限由 SeekBar 0..100 天然限定)。拖动实时回调 onProgress。 */
    private View addSeekbar(LinearLayout ignoreBody, String label, int progress,
                            java.util.function.IntConsumer onProgress) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(12), dp(8), dp(12));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13f);
        l.setTextColor(Skin.text(this));
        box.addView(l, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(100);
        sb.setProgress(progress);
        sb.setPadding(0, dp(4), 0, 0);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seek, int p, boolean user) {
                if (user && onProgress != null) onProgress.accept(p);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Skin.cardFill(this));
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        return box;
    }

    /** 颜色条目行(左文案，右色块预览)。palette 决定点开可选的色板。 */

    private void pickBackground() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK_BG);
    }

    private void removeBackground() {
        File f = Skin.bgFile(this);
        if (f.exists()) f.delete();
        if (cachedBg != null) { cachedBg.recycle(); cachedBg = null; }
        if (bgBase != null && !bgBase.isRecycled()) bgBase.recycle();
        bgBase = null;
        toast("已移除背景");
        ensureBgBase();      // 文件已删 → loadBgBitmap 返回 null → bgBase 定格 null
        applyBgLayer();
        closeSettingsPanel();
    }

    private void refreshBranchList() {
        // 用与置顶一致的排序重建（顶置分支排最前）；绝不能用 repos.all() 把“顶置”顺位挤掉
        refreshDrawerList();
    }

    /** 抽屉里点某分支：若未绑定 SAF 先绑定，否则列其 json 文件。 */
    /** 抽屉点某分支的可选守卫：有未保存改动就先弹警示，确认=自动保存后再切换；
     *  返回=留在原处，绝不丢改动。 */
    private void switchToBranchGuarded(final Branch br) {
        if (tree == null || openName == null || !tree.isDirty()) {
            onBranchSelected(br);
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("尚未保存")
                .setMessage("当前修改还没保存。前往分支前会先自动保存；\n“返回”则留在原处不影响。")
                .setNegativeButton("返回", null)
                .setPositiveButton("保存并前往", (d, w) -> {
                    doSave();
                    if (tree != null && tree.isDirty()) {
                        toast("保存未完成，已留在当前分支");
                        return;
                    }
                    onBranchSelected(br);
                })
                .show();
    }

    private void onBranchSelected(Branch br) {
        currentBranch = br;
        atWelcomePage = false;   // 已离开引导首页(即便此刻才停在“选择 json”列表)：返回都应回 renderWelcome 首页
        closeFile();
        if (br.hasSaf()) {
            // 已有授权：读取文件列表
            showBranchHint(br);
        } else {
            // 本地草稿/纯空白分支:直接进 json 列表(为空也有空态,不再要求选目录)
            showBranchHint(br);
        }
    }

    private void showBranchHint(Branch br) {
        title.setText(br.title + "  ·  " + br.subtitle);
        List<String> names = listJsonFor(br);
        rebindFileRow(br, names);
    }

    /** 列某分支可直接访问的 .json(SAF 或草稿库)。 */
    private List<String> listJsonFor(Branch br) {
        if (br.hasSaf()) {
            try {
                return SafDir.listJson(this, br.safUri);
            } catch (Exception e) {
                // 授权可能已失效：清除后回退草稿库
                repos.saveBranchSaf(this, br, null);
                toast("SAF 目录失效: " + e.getMessage());
            }
        }
        // 草稿库模式：rootDir 下每个分支一个子目录
        File dir = new File(rootDir, br.getJsonDirName());
        if (!dir.exists()) dir.mkdirs();
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        List<String> names = new ArrayList<>();
        if (fs != null) for (File f : fs) names.add(f.getName());
        Collections.sort(names);
        return names;
    }

    private void rebindFileRow(Branch br, List<String> names) {
        currentFileNames = names;
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.branch_no_file, Toast.LENGTH_SHORT).show();
            // 仍显示一个可引导的空态(卡片感)
            TextView tv = new TextView(this);
            tv.setText(R.string.open_branch_hint);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(15f);
            tv.setPadding(dp(20), dp(20), dp(20), dp(20));
            tv.setTextColor(Skin.subText(this));
            android.graphics.drawable.GradientDrawable eg = new android.graphics.drawable.GradientDrawable();
            eg.setColor(Skin.surface(this));
            eg.setCornerRadius(dp(16));
            eg.setStroke(dp(1), Skin.divider(this));
            tv.setBackground(eg);
            LinearLayout shell2 = new LinearLayout(this);
            shell2.setOrientation(LinearLayout.VERTICAL);
            shell2.setGravity(Gravity.CENTER);
            shell2.setPadding(dp(20), 0, dp(20), 0);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            shell2.addView(tv, clp);
            // 真正的“导入”动作栏(仅对无 SAF 的草稿/空分支提供,因为只有它的 json 存在本地夹里可平铺)
            if (!br.hasSaf()) {
                LinearLayout actRow = new LinearLayout(this);
                actRow.setOrientation(LinearLayout.HORIZONTAL);
                actRow.setWeightSum(2f);
                TextView bFolder = new TextView(this);
                bFolder.setText("▁ 导入文件夹");
                bFolder.setTextSize(14f); bFolder.setTypeface(null, Typeface.BOLD);
                bFolder.setTextColor(0xFFFFFFFF);
                bFolder.setGravity(Gravity.CENTER);
                bFolder.setPadding(dp(4), dp(9), dp(4), dp(9));
                android.graphics.drawable.GradientDrawable g1 = new android.graphics.drawable.GradientDrawable();
                g1.setColor(0xFF4C6EF5); g1.setCornerRadius(dp(8)); bFolder.setBackground(g1);
                bFolder.setOnClickListener(v -> startImportFolder());
                TextView bJson = new TextView(this);
                bJson.setText("＋ 自选 JSON");
                bJson.setTextSize(14f); bJson.setTypeface(null, Typeface.BOLD);
                bJson.setTextColor(0xFF21364D);
                bJson.setGravity(Gravity.CENTER);
                bJson.setPadding(dp(4), dp(9), dp(4), dp(9));
                android.graphics.drawable.GradientDrawable g2 = new android.graphics.drawable.GradientDrawable();
                g2.setColor(0xFFB7D7C5); g2.setCornerRadius(dp(8)); bJson.setBackground(g2);
                bJson.setOnClickListener(v -> startImportPickJson());
                actRow.addView(bFolder, new LinearLayout.LayoutParams(0, -2, 1f));
                actRow.addView(new View(this), new LinearLayout.LayoutParams(dp(8), 1));
                actRow.addView(bJson, new LinearLayout.LayoutParams(0, -2, 1f));
                LinearLayout.LayoutParams arl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                arl.topMargin = dp(14);
                shell2.addView(actRow, arl);
            }
            // “＋ 新建 json” 页头部在空态也要保持可见，直接与空提示叠在同一个纵轴上
            LinearLayout pageEmpty = filesPageTop(newBt());
            pageEmpty.addView(shell2, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            setContent(pageEmpty);
            return;
        }
        ListView lv = new ListView(this);
        lv.setAdapter(new FileListAdapter(names));
        lv.setOnItemClickListener((p, v, pos, id) -> openFile(br, names.get(pos)));
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            showFileActionDialog(br, names.get(pos));
            return true;
        });
        // 右缘可拖细条(与 json 分层主列表一致，复用已验证组件 ScrollThumb)
        final int fcount = names.size();
        final ScrollThumb ftb = new ScrollThumb(this);
        ftb.setScrub(f -> {
            if (fcount <= 0) return;
            int row = (int) Math.round(f * (fcount - 1));
            row = Math.max(0, Math.min(fcount - 1, row));
            lv.setSelection(row);
        });
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScroll(android.widget.AbsListView v,
                    int firstVisible, int visibleCount, int totalCount) {
                ftb.setProgress(totalCount > 1 ? (float) Math.min(firstVisible, totalCount - 1)
                        / (totalCount - 1) : 0f);
                ftb.setVisibleRatio(totalCount > 0 ? (float) visibleCount / totalCount : 1f);
            }
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {
                int fv = v.getFirstVisiblePosition(), tc = v.getCount();
                ftb.setProgress(tc > 1 ? (float) Math.min(fv, tc - 1) / (tc - 1) : 0f);
                ftb.setVisibleRatio(v.getChildCount() > 0 && tc > 0
                        ? (float) v.getChildCount() / tc : 1f);
            }
        });
        ftb.setProgress(0f);
        FrameLayout fstage = new FrameLayout(this);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        fstage.addView(lv, flp);
        FrameLayout.LayoutParams ftp = new FrameLayout.LayoutParams(dp(24),
                FrameLayout.LayoutParams.MATCH_PARENT);
        ftp.gravity = android.view.Gravity.END;
        fstage.addView(ftb, ftp);
        LinearLayout pgList = filesPageTop(newBt());
        LinearLayout.LayoutParams inner = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        pgList.addView(fstage, inner);
        setContent(pgList);
    }

    /** “＋ 新建 json”入口(各 json 页共用)：返回整行按钮。 */
    private TextView newBt() {
        TextView bt = new TextView(this);
        bt.setText("＋ 新建 json");
        bt.setTextSize(14f);
        bt.setTypeface(null, Typeface.BOLD);
        bt.setTextColor(0xFF5B8DEF);
        bt.setPadding(dp(14), dp(11), dp(14), dp(11));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(10)); g.setStroke(dp(1), 0xFF5B8DEF); g.setColor(0x0AFFFFFF);
        bt.setBackground(g);
        bt.setOnClickListener(v -> showNewJsonDialog(currentBranch));
        return bt;
    }

    /** 包一行头部按钮与内容(body)的纵轴页面。 */
    private LinearLayout filesPageTop(View button) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), dp(4), dp(10), dp(6));
        col.addView(button);
        return col;
    }

    /** 在当前本地分支(无 SAF 草稿/纯空)新建一个空对象 json。 */
    private void showNewJsonDialog(final Branch br) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(6), dp(16), dp(4));
        TextView cap = new TextView(this);
        cap.setText("新建 json"); cap.setTextColor(Skin.text(this));
        col.addView(cap);
        EditText nm = new EditText(this);
        nm.setHint("输入 json 名称…（可省 .json）");
        nm.setSingleLine(true); nm.setTextColor(Skin.text(this));
        nm.setHintTextColor(Skin.subText(this));
        col.addView(nm);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("新建 json")
                .setView(col)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", (q, w) -> q.dismiss()).create();
        styleDialogDark(d);
        d.setOnShowListener(q2 -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            String raw = nm.getText().toString().trim();
            if (raw.isEmpty()) { toast("名称不能为空"); return; }
            String file = raw.endsWith(".json") ? raw : raw + ".json";
            if (file.contains("/") || file.contains("\\")) { toast("名称不能含斜杠"); return; }
            String bid = br != null && !br.hasSaf() ? br.id : (br != null ? br.id : "draft");
            java.io.File dir = new java.io.File(rootDir, bid);
            if (!dir.exists()) dir.mkdirs();
            java.io.File out = new java.io.File(dir, file);
            if (out.exists()) { toast("该 json 已存在"); return; }
            try {
                java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                os.write("{}".getBytes("UTF-8")); os.close();
                toast("已新建 " + file);
                currentBranch = br;
                showBranchHint(br);           // 刷出含新档的文件列表
            } catch (Exception e) { toast("创建失败 " + e.getMessage()); }
            d.dismiss();
        }));
        d.show();
    }

    /** 空态“导入文件夹”：选一个目录，把它顶层全部 json 拷进当前(无SAF)草稿分支本地夹。 */
    private void startImportFolder() {
        if (currentBranch == null || currentBranch.hasSaf()) { toast("仅草稿空分支可这样批量导入"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_IMPORT_FOLDER);
        } catch (Exception e) { toast("无法打开目录选择器: " + e.getMessage()); }
    }

    /** 空态“自选 JSON”：一次可选多个 json，拷进当前(无SAF)草稿分支本地夹。 */
    private void startImportPickJson() {
        if (currentBranch == null || currentBranch.hasSaf()) { toast("仅草稿空分支可这样批量导入"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_IMPORT_JSF);
        } catch (Exception e) { toast("无法打开文件选择: " + e.getMessage()); }
    }

    /** 当前(无SAF)分支本地草稿 json 目录：rootDir/<getJsonDirName()>/ 。自动建。 */
    private File localBranchJsonDir() {
        File d = new File(rootDir, currentBranch.getJsonDirName());
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 名字已存在则跳过，否则将 text 以 name 写入本地夹；返回 true=写入。 */
    private boolean writeIntoLocalBranches(String name, String text, File dir) {
        File out = new File(dir, name);
        if (out.exists()) return false;
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.flush(); fos.close();
            return true;
        } catch (Exception e) {
            toast("写入失败 " + name + ": " + e.getMessage());
            return false;
        }
    }

    /** 分支长按菜单“导出”：ACTION_CREATE_DOCUMENT 让用户给 zip 取名+选保存位置。 */
    private void startExportBranch(final Branch br) {
        branchForExport = br;
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, exportZipBaseName(br) + ".zip");
            startActivityForResult(i, REQ_EXPORT_ZIP);
        } catch (Exception e) {
            toast("导出: " + e.getMessage());
        }
    }

    /** zip 默认文件名 = 分支名（去除路径类不合法字符）。 */
    private String exportZipBaseName(Branch br) {
        String n = (br.title == null || br.title.trim().isEmpty()) ? br.getJsonDirName() : br.title.trim();
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 读取草稿库本地某 json（文件不存在/读失败返回 null）。 */
    private String readLocalJson(File root, String dir, String name) {
        try {
            File f = new File(new File(root, dir), name);
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) >= 0) off += r;
            in.close();
            return new String(b, 0, off, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把一个分支的全部 .json(草稿/SAF) 压成 zip 内存字节；无 json 返回 null。 */
    private byte[] branchZipBytes(Branch br) throws Exception {
        boolean isSaf = br != null && br.hasSaf();
        java.util.List<String> names = new java.util.ArrayList<>();
        if (isSaf) {
            java.util.List<String> hs = SafDir.listJson(this, br.safUri);
            if (hs != null) names.addAll(hs);
        } else {
            File d = new File(rootDir, br.getJsonDirName());
            if (d.exists()) {
                File[] all = d.listFiles();
                if (all != null) {
                    for (File f : all) {
                        if (f.isFile() && f.getName().toLowerCase(java.util.Locale.US).endsWith(".json"))
                            names.add(f.getName());
                    }
                }
            }
        }
        java.util.Collections.sort(names);
        if (names.isEmpty()) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(bos);
        for (String name : names) {
            String text = isSaf ? SafDir.readFile(this, br.safUri, name) : readLocalJson(rootDir, br.getJsonDirName(), name);
            if (text == null) continue;
            zout.putNextEntry(new java.util.zip.ZipEntry(name));
            zout.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zout.closeEntry();
        }
        zout.finish();
        zout.close();
        return bos.toByteArray();
    }


    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_DIR && res == RESULT_OK && data != null && data.getData() != null) {
            if (currentBranch == null) { toast("请先选择分支"); return; }
            Uri treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                repos.saveBranchSaf(this, currentBranch, treeUri);
                toast("已绑定: " + SafDir.treeDisplayName(this, treeUri));
            } catch (Exception e) {
                repos.saveBranchSaf(this, currentBranch, null);
                toast("授权失败: " + e.getMessage());
            }
            showBranchHint(currentBranch);
        } else if (req == REQ_PICK_BG && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            StringBuilder err = new StringBuilder();
            Boolean ok = Skin.saveBgFromUri(this, uri, err);
            if (ok == null || !ok) {
                toast(err.length() == 0 ? "导入失败" : err.toString());
                return;
            }
            // 使旧的缓存位图失效：换新文件后强制母版重解码
            if (cachedBg != null) { cachedBg.recycle(); cachedBg = null; }
            if (bgBase != null && !bgBase.isRecycled()) bgBase.recycle();
            bgBase = null;
            toast("背景已导入");
            applyBgLayer();
            closeSettingsPanel();
        } else if (req == REQ_IMPORT_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            // 导入文件夹：把所选目录顶层 json 拷进当前草稿分支(重复跳过)
            Uri tree = data.getData();
            int okN = 0, sk = 0;
            try {
                for (String name : SafDir.listJson(this, tree)) {
                    try {
                        String text = SafDir.readFile(this, tree, name);
                        if (writeIntoLocalBranches(name, text, localBranchJsonDir())) okN++; else sk++;
                    } catch (Exception e) { sk++; }
                }
            } catch (Exception e) { toast("读目录失败: " + e.getMessage()); }
            toast(okN == 0 ? (sk > 0 ? "目录里没有新的 json" : "没有可导入的 json") : ("导入 " + okN + " 个 json" + (sk > 0 ? ("，跳过 " + sk) : "")));
            onBranchSelected(currentBranch);
        } else if (req == REQ_IMPORT_JSF && res == RESULT_OK && data != null) {
            // 自选 json(可多选)：逐个拷进当前草稿分支(重复跳过)
            File dir = localBranchJsonDir();
            int okN = 0, sk = 0;
            java.util.List<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int j = 0; j < data.getClipData().getItemCount(); j++) {
                    Uri u = data.getClipData().getItemAt(j).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) uris.add(data.getData());
            for (Uri u : uris) {
                if (u == null) { sk++; continue; }
                String name = null, text = null;
                try { name = queryOpenableName(u); text = readOpenableText(u); } catch (Exception ignore) {}
                if (name == null || text == null) { sk++; continue; }
                if (!name.toLowerCase(java.util.Locale.US).endsWith(".json") || !writeIntoLocalBranches(name, text, dir)) sk++;
                else okN++;
            }
            toast(!uris.isEmpty()
                    ? ("导入 " + okN + " 个 json" + (sk > 0 ? ("，跳过 " + sk) : ""))
                    : "没有选择任何文件");
            onBranchSelected(currentBranch);
        } else if (req == REQ_EXPORT_ZIP && res == RESULT_OK && data != null && data.getData() != null) {
            // 分支导出：在拿到目标位置后按“分支名.zip”把 json 打包写入
            Uri out = data.getData();
            Branch eb = branchForExport;
            branchForExport = null;
            if (eb == null) return;
            try {
                byte[] bytes = branchZipBytes(eb);
                if (bytes == null) { toast("该分支还没有可导出的 json"); return; }
                java.io.OutputStream os = getContentResolver().openOutputStream(out);
                if (os == null) throw new java.io.IOException("无法写目标文件");
                os.write(bytes);
                os.flush();
                os.close();
                toast("导出完成 ✔");
            } catch (Exception e) {
                toast("导出失败: " + e.getMessage());
            }
        }
    }

    /** 查系统文件 Uri 的显示名(尽力而为，失败返回 null)。 */
    private String queryOpenableName(Uri u) {
        try (android.database.Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception e) { /* fallthrough */ }
        String s = u.getLastPathSegment();
        if (s != null) {
            int q = s.indexOf(':');
            if (q >= 0) s = s.substring(q + 1);
        }
        return s;
    }

    /** Read a content Uri fully as UTF-8 string. */
    private String readOpenableText(Uri u) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new java.io.IOException("打不开: " + u);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int r;
        while ((r = in.read(buf)) >= 0) bos.write(buf, 0, r);
        in.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ==================== 打开 / 分层渲染 ====================

    private void closeFile() {
        tree = null;
        openName = null;
        path.clear();
        refreshSaveButton();
    }

    private void openFile(Branch br, String name) {
        atWelcomePage = false;            // 已进入某 json 工作区：系统返回应回到 renderWelcome 首页
        checkpointSwitchIF(br, name);        // 从当前旧文件(不同名)切过来：先把那份脏树先落草稿，防整体丢失
        if (autoReAdoptWs(br, name)) return; // 若这次是要回到“同分支同文件且刚才有未保存草稿”→ 自动接回，不再读磁盘
        if (adoptPendingSlot(br, name)) return;   // 否则看该分支同文件是否留有 .ws/pending 快照(含软删标记)也接回
        String text;
        if (br.hasSaf()) {
            try { text = SafDir.readFile(this, br.safUri, name); }
            catch (Exception e) { toast("读取失败: " + e.getMessage()); return; }
        } else {
            File f = new File(new File(rootDir, br.getJsonDirName()), name);
            if (!f.exists()) { toast("文件不存在: " + name); return; }
            try { text = readAll(f); }
            catch (Exception e) { toast("读取失败: " + e.getMessage()); return; }
        }
        StringBuilder err = new StringBuilder();
        JsonTree t = JsonTree.fromText(text, err);
        if (t == null) { toast(err.toString()); return; }
        tree = t;
        openName = name;
        HistoryLog.noteOpen(openName, tree.rootObject());   // 记录基线=打开时的值
        path.clear();
        title.setText((br.title.equals("悠然汉化") ? "悠然" : br.title) + " / " + name);
        renderContainer();
    }

    /** 渲染当前容器(顶层或 path 所指层)：key 列表 + 每层末尾「＋ 新建」。 */
    private void renderContainer() {
        if (tree == null) return;
        if (tree.isDirty()) scheduleWsFlush();   // 未保存编辑 → 延迟写草稿防崩溃丢改动(仅在改脏后)

        JSONObject picked = tree.containerAt(path);
        if (picked == null) { toast("路径异常"); path.clear(); picked = tree.rootObject(); }
        final JSONObject container = picked;
        refreshSaveButton();

        // 记住“本次要回到”的分层：同深度重建(如编辑保存/加 key)照旧停在那里，避免列表顶回
        String sigNow = joinPath(path);
        boolean sameGen = !liveSig.isEmpty() && liveSig.equals(sigNow);
        final int keepRow;
        if (sameGen && saveKeepRow >= 0) {
            keepRow = saveKeepRow;                         // 用户刚点“去编辑那行”→ 保存后回到约那行
            saveKeepRow = -1;                              // 一次性消费，防没有编辑的普通刷新使用旧值
        } else {
            keepRow = (sameGen && liveListLv != null) ? Math.max(0, liveListLv.getFirstVisiblePosition()) : 0;
        }
        liveSig = sigNow;

        List<Object> items = new ArrayList<>(); // String key 或 特殊类型
        items.addAll(tree.sortedKeys(container));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        ListView lv = new ListView(this);
        String wantHl = (openName != null && hlFile != null && openName.equals(hlFile)
                && containerPathEqual(path, hlSegs)) ? hlKey : null;
        curSelfSegs = new ArrayList<>(path);   // 记录本层父链=path;顶层=空(收藏判断在同一 json)
        ContainerAdapter adapter = new ContainerAdapter(tree, container, wantHl);
        curContainerListView = lv;             // toggle 收藏后即时 invalidateViews(不用翻页)
        lv.setAdapter(adapter);
        // 命中行：把“那一条命中”带到可见区最上方，确保用户立刻看到跳到的子项
        if (wantHl != null) {
            final int rr = adapter.keys.indexOf(wantHl);
            if (rr >= 0) {
                // 布局就绪后：先置顶对齐一次，再隔半拍兜底(避免首帧测量前就绪不了导致“只回上层顶部”)
                lv.post(() -> lv.setSelectionFromTop(rr, 0));
                lv.postDelayed(() -> lv.setSelectionFromTop(rr, 0), 90);
            }
        } else if (sameGen) {
            // 同深度的小规模重建(如：编辑 value 后保存/加子 key)。
            // 要求：关弹窗后列表“纹丝不动”——保持你原本视口的那一行作新列表顶，
            // 不做居中、不加偏移、不额外滚动(仅当它确实在你原位置的上一屏时兜一帧)。
            int n = adapter.keys.size();
            if (n > 0) {
                int target = -1;
                if (saveKeepKey != null) target = adapter.keys.indexOf(saveKeepKey); // 改名会 -1 → 退回行号
                if (target < 0) target = Math.min(keepRow, n - 1);
                int from = Math.max(0, Math.min(target, n - 1));   // 保持原样：首可见=目标行，不居中偏置
                lv.setSelectionFromTop(from, 0);
            }
        }
        // 每次重建完成后记录“当下列表”，供下次同深度重建时参照
        liveListLv = lv;
        curLv = lv;                                    // 顶层/底“快滑”引用的当前容器
        // 用完后回收临时态(避免用户后续在不同层操作残留蓝色)
        hlFile = null; hlSegs = null; hlKey = null;
        lv.setOnItemClickListener((p, v, pos, id) -> {
            String key = items.get(pos).toString();
            if (tree.isObjectValue(container, key)) {
                saveKeepRow = -1;                 // 换层：交给子层自己的首刷，不沿用叶子的保位
                saveKeepKey = null;
                path.add(key);
                renderContainer();
            } else {
                saveKeepRow = Math.max(0, lv.getFirstVisiblePosition());
                saveKeepKey = key;                // 锚点用“被编辑的这行”
                promptEditLeaf(container, key);
            }
        });
        // 长按目标 key → 动作窗(⭐收藏 / 🗑删除(确认) / 右下角取消)。不再直接删、不再出“重命名”。
        lv.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) {
                String k = items.get(pos).toString();
                boolean isObj = tree.isObjectValue(container, k);
                // 需要该 key 所在父容器与“返回路径”：当前层即 path 直接作为父
                showActionPanel(new ArrayList<>(path), container, k, isObj);
            }
            return true;
        });
        // ===== 底部固定两操作行(不盖条目：它们排在 ListView 区之下，不重叠任何行) =====
        // 「📍 当前位置」还返回底部常驻(tap 滚到列表最底那些“＋newkey/末行”快速跳到)
        TextView crumb = new TextView(this);
        crumb.setTextSize(13f);
        crumb.setTextColor(Skin.mutedText(this));
        crumb.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.graphics.drawable.GradientDrawable cg = new android.graphics.drawable.GradientDrawable();
        cg.setColor(Skin.crumbBg(this));
        cg.setCornerRadius(dp(10));
        crumb.setBackground(cg);
        crumb.setText("📍 当前位置：" + joinPath(path));
        crumb.setOnClickListener(v -> {
            ListView ll = curLv != null ? curLv : liveListLv;
            if (ll != null && ll.getAdapter() != null) fastSeekRow(ll, ll.getCount() - 1);
        });
        // 「＋ 新建 key」= 固定底部钮(位置 crumb 上方)；点它新建当前容器的 key，不必滑到列表底
        TextView addKeyBtn = new TextView(this);
        addKeyBtn.setTextSize(14f);
        addKeyBtn.setTextColor(0xFF4CAF50);
        addKeyBtn.setText("＋ 新建 key");
        addKeyBtn.setGravity(Gravity.CENTER);
        addKeyBtn.setPadding(dp(12), dp(10), dp(12), dp(6));
        android.graphics.drawable.GradientDrawable addBg = new android.graphics.drawable.GradientDrawable();
        addBg.setColor(0x1130A84C);
        addBg.setCornerRadius(dp(10));
        addKeyBtn.setBackground(addBg);
        addKeyBtn.setOnClickListener(v -> {
            if (tree == null) return;
            JSONObject cont = tree.containerAt(path);
            if (cont == null) { toast("当前位置不可新建(请到对象/数组块内)"); return; }
            promptAddKey(cont);
        });
        // ===== 顶部“下拉=刷新”把手(自绘,去掉库自带 indicator 以防其溢出被列表盖住) =====
        final TextView guide = new TextView(this);
        guide.setTextSize(11f);
        guide.setGravity(Gravity.CENTER);
        guide.setPadding(0, dp(3), 0, dp(3));
        guide.setTextColor(0xFF7FA6D9);
        guide.setText("下拉刷新");
        android.graphics.drawable.GradientDrawable barBg = new android.graphics.drawable.GradientDrawable();
        barBg.setColor(Skin.crumbBg(this));
        barBg.setCornerRadius(dp(12));
        guide.setBackground(barBg);
        guide.setOnTouchListener(new android.view.View.OnTouchListener() {
            final int TRIG = (int)(dp(64));
            int base = Integer.MIN_VALUE;
            @Override public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN: base = (int)e.getRawY(); return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (base != Integer.MIN_VALUE && e.getRawY() - base > TRIG) {
                            base = Integer.MIN_VALUE;
                            guide.setText("正在刷新…");
                            guide.postDelayed(() -> {  // 延迟半拍让“正在刷新”文本先上屏
                                saveKeepRow = Math.max(0, lv.getFirstVisiblePosition());
                                renderContainer();
                            }, 80);
                            return false;
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: base = Integer.MIN_VALUE; return true;
                }
                return true;
            }
        });
        guide.setOnClickListener(v -> {
            saveKeepRow = Math.max(0, lv.getFirstVisiblePosition());
            renderContainer();
        });

        // ===== “包住列表”的 SwipeRefresh：圆画在 (它包裹的) 列表层之上，不会再跑到条目下面 =====
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout srlWrap = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        srlWrap.setColorSchemeColors(0xFF448AFF, 0xFF26A69A);
        srlWrap.setProgressBackgroundColorSchemeColor(0xFF11131E);
        srlWrap.addView(lv, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        srlWrap.setOnRefreshListener(() -> {
            saveKeepRow = Math.max(0, lv.getFirstVisiblePosition());
            renderContainer();
            srlWrap.setRefreshing(false);
        });

        // wrap 纵向：顶部下拉把手(自绘行) / 包住列表的 srlWrap(占剩余) / 底部 ＋新建 / 📍位置
        wrap.addView(guide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(30)));
        wrap.addView(srlWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        wrap.addView(addKeyBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        wrap.addView(crumb, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 点顶部“当前页名称”→ 用“快速滑动”滚到最顶
        title.setOnClickListener(v -> { ListView ll = curLv; if (ll != null) fastSeekRow(ll, 0); });
        // 右缘可拖细滚动条(第三轮 B)：包一层舞台，滑块叠在列表右侧极少宽度。
        final ScrollThumb tbar = new ScrollThumb(this);
        final int listCount = items.size() + 1;      // 与 ContainerAdapter.count 一致(含单“新建”行)
        tbar.setScrub(f -> {                         // 用户拖动细条→跳到对应比例行首
            int totalRows = listCount;
            if (totalRows <= 0) return;
            int row = (int) Math.round(f * (totalRows - 1));
            row = Math.max(0, Math.min(totalRows - 1, row));
            lv.setSelection(row);
        });
        lv.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScroll(android.widget.AbsListView v,
                    int firstVisible, int visibleCount, int totalCount) {
                tbar.setVisibleRatio(totalCount > 0 ? (float) visibleCount / totalCount : 1f);
                tbar.setProgress(totalCount > 1 ? (float) firstVisible / (totalCount - 1) : 0f);
            }
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) {
                if (s == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    int fv = v.getFirstVisiblePosition(); int tc = v.getCount();
                    tbar.setProgress(tc > 1 ? (float) Math.min(fv, tc - 1) / (tc - 1) : 0f);
                }
            }
        });
        // 让进度在系统决定前先算一次(列表还没滚过时 onScroll 未必立刻触发)
        tbar.setVisibleRatio(1f);
        tbar.setProgress(0f);
        FrameLayout stage = new FrameLayout(this);
        FrameLayout.LayoutParams wrapLP = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        stage.addView(wrap, wrapLP);
        FrameLayout.LayoutParams barLP = new FrameLayout.LayoutParams(dp(24), FrameLayout.LayoutParams.MATCH_PARENT);
        barLP.gravity = android.view.Gravity.END;
        stage.addView(tbar, barLP);
        setContent(stage);
    }

    private String joinPath(List<String> p) {
        StringBuilder sb = new StringBuilder();
        for (String s : p) { if (sb.length() > 0) sb.append(" → "); sb.append(s); }
        return sb.length() == 0 ? "/ 顶层" : sb.toString();
    }

    // 后退处理：容器栈向上
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (settingOpen) { closeSettingsPanel(); return true; }
            if (drawer.getVisibility() == View.VISIBLE) { closeDrawer(); return true; }
            if (tree != null && !path.isEmpty()) { path.remove(path.size() - 1); renderContainer(); return true; }
            if (tree != null) { closeFile(); showRootForCurrent(); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showRootForCurrent() {
        if (currentBranch == null) renderWelcome();
        else showBranchHint(currentBranch);
    }

    // ==================== 编辑交互(叶子/新建/改删 key) ====================

    /** 点叶子：弹出编辑 value 对话框(也允许改 key 名)。 */
    private void promptEditLeaf(JSONObject container, String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        // key 输入(可改)：圆角矩形包裹
        EditText keyInput = new EditText(this);
        keyInput.setHint("key");
        keyInput.setText(key);
        styleEditBox(keyInput);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.bottomMargin = dp(10);
        box.addView(keyInput, klp);

        // value 输入：圆角矩形包裹
        Object val = container.opt(key);
        EditText valInput = new EditText(this);
        valInput.setHint("value");
        valInput.setMinLines(3);
        valInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        styleEditBox(valInput);
        // 值为 JSON null 时：不把“null”当文本塞进框(会像真内容)——
        // 置空 + 灰色占位 (null)，让用户看懂这是空值；写入任意内容即把它替换。
        boolean originNull = (val instanceof JSONObject) ? false
                : (val instanceof JSONArray) ? false
                : container.isNull(key);
        if (originNull) {
            valInput.setText("");
            valInput.setHint("(null)   —  当前是空值；写内容即替换，想保持 null 请清空");
        } else if (val != null && !(val instanceof JSONObject) && !(val instanceof JSONArray)) {
            valInput.setText(String.valueOf(val));
        }
        box.addView(valInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addEditToolStrip(box, valInput);   // ＋工具：插 颜色码/ [i:iD] 等，插当前光标
        final AlertDialog dlg = new AlertDialog.Builder(this)
                // （A去标题：不再单独显示“编辑：key名称”这一层，保留路径/改key/文本/+工具/按钮）
                .setMessage("路径: " + joinPath(path))
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        // 关键：深色处理/无窗口动画须在 show() 前就生效，否则错过时机、仍会跑默认入场而“上移”。
        styleDialogDark(dlg);
        dlg.setOnShowListener(d ->
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String nk = keyInput.getText().toString().trim();
                    if (nk.isEmpty()) { toast("key 不能为空"); return; }
                    if (!nk.equals(key) && container.has(nk)) {
                        toast("同层已有该 key: " + nk); return;
                    }
                    String raw = valInput.getText().toString();
                    // null 值特殊语义：没写新内容=保持 null；写入了才替换(见预填框的灰字提示)
                    if (originNull) {
                        if (raw.trim().isEmpty()) {
                            // 用户取消都没变化 → 原样保持 null，直接收键盘/关
                            dlg.dismiss(); refreshSaveButton(); renderContainer(); return;
                        }
                        toast("此值原本是 (null) 空值：现用你写的内容替换(若是误写想保留 null，请再清空保存)");
                    }
                    String perr = JsonModel.validateValue(raw);
                    if (perr != null) { toast("占位符检查: " + perr); return; }
                    // —— (A/B) 无实质改动就点保存 → 仅关弹窗(总保存不应因此点亮) ——
                    String bDisp = (val == null || val instanceof JSONObject || val instanceof JSONArray)
                            ? null : String.valueOf(val);
                    boolean realChange = !nk.equals(key)
                            || (originNull
                                ? !(raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim()))
                                : !raw.equals(bDisp));
                    if (!realChange) { dlg.dismiss(); refreshSaveButton(); renderContainer(); return; }
                    // 先改值，再改名(若变了)
                    String err = tree.setLeaf(container, key, raw);
                    if (err != null) { toast(err); return; }
                    if (!nk.equals(key)) {
                        String rerr = tree.renameKey(container, key, nk);
                        if (rerr != null) { toast(rerr); return; }
                    }
                    checkpointIfEditing();   // 实质编辑点保存 → 立即把这整棵写进 .ws 草稿(不依赖离开再落)
                    dlg.dismiss();
                    refreshSaveButton();
                    renderContainer();
                }));
        dlg.show();
    }

    /** 每层底部「＋ 新建」：输入 key(必须) 和 value(可空)。 */
    /** 模式标识：false＝①直接给值(原样，可空叶子/可粘 JSON 文本)；true＝②对象壳(建空对象可随后逐层下钻)。 */
    private static final int MODE_FILL = 0;    // ① 直接给值
    private static final int MODE_SHELL = 1;   // ② 对象壳

    /**
     * 纯数字序号容器的下一个 key：当 container 的直接子 key 全部都是非负整数(或为空)时，
     * 返回 目前最大值+1 的字符串(空容器从 1 开始)；混合/非数字则返回 null(不预填,走原逻辑)。
     * 用于 LoadingTips_Default 这类 1..N 序号词典：点添加自动给下一个号。
     */
    private String nextSeqKey(JSONObject container) {
        if (container == null) return null;
        java.util.Set<Long> used = new HashSet<>();
        int numeric = 0, total = 0;
        java.util.Iterator<String> it = container.keys();
        long hi = 0;
        while (it.hasNext()) {
            String k = it.next();
            total++;
            long v;
            boolean isInt = false;
            try { v = Long.parseLong(k.trim()); isInt = (v >= 0); } catch (Exception e) { v = -1; isInt = false; }
            if (isInt) { numeric++; used.add(v); if (v > hi) hi = v; }
        }
        if (total > 0 && numeric != total) return null;   // 非纯数字容器，不乱预填
        // “最小空当”优先：从 1 开始找第一个还没被占用的正整数；没有空当再接着 max+1
        long next = 1;
        while (used.contains(next)) next++;                // 空容器 used 空 → next=1；连号到 hi 则扫到 hi+1
        return String.valueOf(next);
    }

    /** 快速滑到 targetRow：不用瞬移 setSelection，也避免 ListView 平滑滑过远列表时“等半天”。
     *  分约 12 帧(setSelectionFromTop)约 190ms 结束，视觉上是快滑而非瞬间剪贴。 */
    private void fastSeekRow(final ListView lv, final int targetRow) {
        if (lv == null || lv.getAdapter() == null) return;
        int count = lv.getAdapter().getCount();
        if (count <= 0) return;
        int target = Math.max(0, Math.min(count - 1, targetRow));
        final int from = Math.max(0, lv.getFirstVisiblePosition());
        if (from == target) return;
        final Handler h = new Handler(Looper.getMainLooper());
        final int steps = 12;
        final long delay = 16;                    // 16ms×12 ≈ 192ms，够快也不显传送
        final int[] frame = {0};
        h.post(new Runnable() {
            @Override public void run() {
                frame[0]++;
                int pos = from + (int) Math.round((target - from) * ((double) frame[0] / steps));
                lv.setSelectionFromTop(pos, 0);
                if (frame[0] >= steps) {
                    lv.setSelectionFromTop(target, 0);
                } else {
                    h.postDelayed(this, delay);
                }
            }
        });
    }

    private void promptAddKey(JSONObject container) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        // 模式小开关行：两种新建方式一眼可见、可来回切换
        final int[] mode = {MODE_FILL};

        // ⚠ 值输入框必须先建好：下文 paint(匿名 Runnable)要换它的 hint。布局 addView 顺序在下方另行摆放。
        EditText valInput = new EditText(this);
        styleEditBox(valInput);
        valInput.setMinLines(2);

        TextView noteMode = new TextView(this);
        noteMode.setText("将挂到: " + joinPath(path));
        noteMode.setTextSize(11f);
        noteMode.setTextColor(Skin.mutedText(this));
        noteMode.setPadding(0, 0, 0, dp(6));
        box.addView(noteMode, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setWeightSum(2f);
        modeRow.setPadding(0, dp(2), 0, dp(10));
        // 两钮动态创建，点选即互斥着色
        final TextView bFill = UiKit.chipBtnC(MainActivity.this,"① 直接给值");
        final TextView bShell = UiKit.chipBtnC(MainActivity.this,"② 对象壳");
        java.lang.Runnable paint = new java.lang.Runnable() {
            @Override public void run() {
                boolean fill = mode[0] == MODE_FILL;
                bFill.setBackground(bgM(fill ? 0xFF4C6EF5 : 0x22000000, fill ? -1 : 0xFFCCCCCC));
                bShell.setBackground(bgM(!fill ? 0xFF7C4DFF : 0x22000000, !fill ? -1 : 0xFFCCCCCC));
                bFill.setTextColor(fill ? 0xFFFFFFFF : Skin.text(MainActivity.this));
                bShell.setTextColor(!fill ? 0xFFFFFFFF : Skin.text(MainActivity.this));
                valInput.setHint(fill ? "值(可选，留空仅建空 key；可粘 {…}/[…]/文本快速写入)"
                        : "对象壳：留空＝建空对象并自动钻入下层继续填；有内容＝解析成对象后停在当前层");
            }
        };
        bFill.setOnClickListener(v -> { mode[0] = MODE_FILL; paint.run(); });
        bShell.setOnClickListener(v -> { mode[0] = MODE_SHELL; paint.run(); });
        LinearLayout.LayoutParams mlp1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        mlp1.rightMargin = dp(8);
        bFill.setLayoutParams(mlp1);
        bShell.setLayoutParams(mlp2);
        bFill.setGravity(android.view.Gravity.CENTER);
        bShell.setGravity(android.view.Gravity.CENTER);
        modeRow.addView(bFill);
        modeRow.addView(bShell);
        box.addView(modeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText keyInput = new EditText(this);
        keyInput.setHint("key 名称");
        styleEditBox(keyInput);
        // 纯数字序号容器(如 LoadingTips_Default 1..141)：点“新建 key”自动预填下一个号(现141→142)
        String seqKey = nextSeqKey(container);
        if (seqKey != null) {
            keyInput.setText(seqKey);
            keyInput.setSelection(0, seqKey.length());
        }
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.bottomMargin = dp(10);
        box.addView(keyInput, klp);

        box.addView(valInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addEditToolStrip(box, valInput);   // ＋工具：插颜色码 / [i:iD]，插当前光标
        paint.run();                        // 应用初次配色/提示语

        final java.lang.Boolean[] running = {java.lang.Boolean.FALSE};  // 防双击
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("新建 key")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        styleDialogDark(dlg);                            // 提前到 show 前生效(别错过无窗口动画时机)
        dlg.setOnShowListener(d ->
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (running[0].booleanValue()) return;   // 防重入(下钻渲染可能较慢)
                    String nk = keyInput.getText().toString().trim();
                    if (nk.isEmpty()) { toast("key 不能为空"); return; }
                    String nv = valInput.getText().toString();
                    running[0] = java.lang.Boolean.TRUE;
                    if (mode[0] == MODE_SHELL) {
                        // ② 对象壳：无值→建空壳并【自动下钻】到新层继续填；有值→解析为对象后停在当前层
                        if (nv.trim().isEmpty()) {
                            String err = tree.addObjectShell(container, nk);
                            if (err != null) { toast(err); running[0] = java.lang.Boolean.FALSE; return; }
                            wsMarkPending();                 // 阶段1：真实“新建/添加 key/壳”即登记总暂存(不依赖离开)
                            dlg.dismiss();
                            refreshSaveButton();
                            path.add(nk);                    // 直接落到刚建的空壳层
                            renderContainer();               // 顶部显示新层，「➕ 新建 key」仍可接着加
                            return;
                        }
                        // 用户给了一段文本 → 交给 addKey 自动做类型推断(可能变对象/数组/文本)；不自动下钻
                        String err2 = tree.addKey(container, nk, nv);
                        if (err2 != null) { toast(err2); running[0] = java.lang.Boolean.FALSE; return; }
                        wsMarkPending();                  // 阶段1：真实新增即登记
                        dlg.dismiss();
                        refreshSaveButton();
                        renderContainer();
                        return;
                    }
                    // ① 直接给值(原行为)
                    String err3 = tree.addKey(container, nk, nv);
                    if (err3 != null) { toast(err3); running[0] = java.lang.Boolean.FALSE; return; }
                    wsMarkPending();                  // 阶段1：真实新增即登记
                    dlg.dismiss();
                    refreshSaveButton();
                    renderContainer();
                }));
        dlg.show();
    }
    /** 模式小按钮填充用圆角色底/描边。 */
    private android.graphics.drawable.Drawable bgM(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(10));
        if (stroke >= 0) g.setStroke(dp(1), stroke);
        return g;
    }

    // ==================== 文件级操作(长按 json 列表行) ====================

    private void showFileActionDialog(Branch br, String name) {
        final String fk = br.id + "|" + name;
        String[] opts;
        if (favFiles.contains(fk)) opts = new String[]{ "取消收藏", "打开", "重命名", "删除" };
        else opts = new String[]{ "⭐ 收藏该 json", "打开", "重命名", "删除" };
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(opts, (d, w) -> {
                    if (w == 0) {
                        if (favFiles.contains(fk)) favFiles.remove(fk); else favFiles.add(fk);
                        saveFavFiles();
                        toast(favFiles.contains(fk) ? "已收藏 json" : "已取消收藏 json");
                        showBranchHint(br);              // 重拷 json 列表,让星即时更新
                    } else if (w == 1) openFile(br, name);
                    else if (w == 2) renameFileDialog(br, name);
                    else deleteFileAction(br, name);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** C-2：整档收藏行在 json 改名后一并改名(避免孤儿「分支id|旧名」)。仅在确实收藏旧名时才改变集合并落盘。 */
    private void favFileRename(String brId, String oldName, String newName) {
        if (brId == null || oldName == null || newName == null) return;
        if (oldName.equals(newName)) return;
        String a = brId + "|" + oldName;
        String b = brId + "|" + newName;
        if (!favFiles.remove(a)) return;          // 本来就没收藏旧名 → 不用动
        if (!favFiles.contains(b)) favFiles.add(b);
        saveFavFiles();
    }

    private void saveFavFiles() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String k : favFiles) sb.append(k).append('\n');
            java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "favfiles.txt"));
            o.write(sb.toString().getBytes("UTF-8")); o.close();
        } catch (Exception ignored) {}
    }
    private void loadFavFiles() {
        try {
            favFiles.clear();
            java.io.File fl = new java.io.File(getFilesDir(), "favfiles.txt");
            if (!fl.exists()) return;
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            java.io.InputStream in = new java.io.FileInputStream(fl);
            byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            String s = new String(b.toByteArray(), "UTF-8");
            for (String line : s.split("\n")) { line = line.trim(); if (!line.isEmpty()) favFiles.add(line); }
        } catch (Exception ignored) {}
    }

    private void renameFileDialog(Branch br, String oldName) {
        final EditText input = new EditText(this);
        input.setText(oldName);
        input.setHint("新文件名(含 .json)");
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("重命名文件")
                .setView(input)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        dlg.setOnShowListener(d -> {
            styleDialogDark(dlg);                        // 深色主题下整窗翻深底浅字
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String nn = input.getText().toString().trim();
                    if (!nn.endsWith(".json")) nn = nn + ".json";
                    try {
                        if (br.hasSaf()) {
                            SafDir.renameFile(this, br.safUri, oldName, nn);
                        } else {
                            File dir = new File(rootDir, br.getJsonDirName());
                            File src = new File(dir, oldName), dst = new File(dir, nn);
                            if (!src.renameTo(dst)) throw new Exception("重命名失败");
                        }
                    } catch (Exception e) { toast("重命名失败: " + e.getMessage()); return; }
                    String brid = br.id == null ? "" : String.valueOf(br.id);
                    pendingFileRekey(brid, oldName, nn);        // 阶段4:json改名后重挂未保存暂存
                    favFileRename(brid, oldName, nn);           // C-2:整档收藏(favfiles)也随改名迁移,避免孤儿星表
                    dlg.dismiss();
                    toast("已重命名");
                    showBranchHint(br);
                });
        });
        dlg.show();
    }

    private void deleteFileAction(Branch br, String name) {
        final boolean isSaf = br != null && br.hasSaf();
        final String extra = isSaf ? "\n（此分支绑定的是外部 SAF 目录，删除是直接被外部移除，不进入回收站、不可恢复。）"
                                   : "\n（回收站开启时，本地 json 删除会先放入回收站，可去回收站恢复。）";
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("确定删除 " + name + " ？" + extra)
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        if (br.hasSaf()) {
                            SafDir.deleteFile(this, br.safUri, name);
                        } else {
                            File f = new File(new File(rootDir, br.getJsonDirName()), name);
                            // 回收开且是本地 json → 整份软删入 recycle/json，hash 只管夹名、原名放 realName
                            if (recycleEnabled() && f.exists()) {
                                JSONObject j = new JSONObject(readAll(f));
                                JSONObject rec = new JSONObject();
                                rec.put("kind", "json");
                                rec.put("realName", name);
                                rec.put("branch", br.id);
                                rec.put("branchTitle", br.title);
                                rec.put("put_at", System.currentTimeMillis());
                                rec.put("data", j);
                                String hid = "json" + (System.currentTimeMillis() % 2147483647L);
                                java.io.File ex = new java.io.File(rcFolder("json"), hid + ".json");
                                int k = 0; while (ex.exists()) ex = new java.io.File(rcFolder("json"), (hid + "_" + (++k)) + ".json");
                                writeJsonAstex(ex, rec.toString(2));
                                idx_push("json", ex.getName(), (br.title == null ? "" : br.title) + "/" + name, br.title, name);
                            }
                            if (!f.exists() || !f.delete()) throw new Exception("删除失败");
                        }
                    } catch (Exception e) { toast("删除失败: " + e.getMessage()); return; }
                    toast("已删除 " + name);
                    showBranchHint(br);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 保存链 ====================

    /** 有未保存改动时高亮右上角保存按钮(蓝底白字)；无改动置灰禁用。 */
    private void refreshSaveButton() {
        // 阶段1：总保存亮起 = “当前这份有未保存” 或 “跨任一分支/主页里尚有未落盘暂存(pending)” —— 二者都视为要落盘的内容
        boolean has = (tree != null && tree.isDirty()) || wsLivePendingCount() > 0 || wsLiveBufExists();
        btnSave.setEnabled(has);
        btnSave.setClickable(has);
        // 图标：无改动时半透明置灰，有改动时纯白“点亮”
        btnSave.setAlpha(has ? 1f : 0.35f);
        btnSave.setImageTintList(ColorStateList.valueOf(has ? 0xFFFFFFFF : 0xAAFFFFFF));
    }

    /** 保存按钮点击：真正的写盘入口。 */
    private void doSave() {
        // 阶段2：总保存应在“没开任何分支/文件(主页面)”也能把 .ws/pending 里所有未落盘份整体写盘
        if (tree == null || openName == null) {
            int n = flushPendingToDisk();
            try { File cf = wsBufFile(); if (cf.exists()) cf.delete(); } catch (Exception ignore) { }
            refreshSaveButton();
            if (n == 0) toast(getString(R.string.nothing_to_save));
            else toast("已把 " + n + " 份未保存内容写入磁盘");
            return;
        }
        if (!tree.isDirty()) { toast(getString(R.string.nothing_to_save)); return; }
        // 软删：真删前快照待删，落盘后再把对应 key 的收藏清掉(收藏夹立即反映删除)
        java.util.List<Object[]> delSnap = tree.pendingSnap();
        int removedNow = 0;
        if (!delSnap.isEmpty()) {   // 先回收(此刻仍在树上)再真删
            for (Object[] o : delSnap) {
                @SuppressWarnings("unchecked")
                java.util.List<String> rseg = (java.util.List<String>) o[0];
                recycleFromPendingRemove(rseg, (String) o[1]);
            }
            removedNow = tree.applyPendingDel();
        }
        if (removedNow > 0) {
            for (Object[] o : delSnap) {
                @SuppressWarnings("unchecked")
                java.util.List<String> seg = (java.util.List<String>) o[0];
                removeFavs(seg, (String) o[1]);             // 命中即从收藏表移除并持久化(removeFavs 内部 saveFavorites)
                markDelSaved(seg, (String) o[1]);           // 记录页删除型转为“已删”
            }
        }
        try { writeTreeToDisk(); }
        catch (Exception e) { toast("保存失败: " + e.getMessage()); return; }
        HistoryLog.commitSave(openName, tree.rootObject());   // 记录：本次相对打开/上次，变了哪些 key(旧→新)
        tree.clearDirty();
        clearWsBuf();                        // 已正式落盘，清掉那份防崩溃草稿
        if (currentBranch != null && openName != null) wsClearPendingOf(currentBranch.id == null ? "" : currentBranch.id, openName);
        refreshSaveButton();
        toast(getString(R.string.save_done));
        // （保存就正常写回当前文件，不再自动同步“核心汉化”目录；需要另行手动导入。）
    }

    /** 把当前 json 写回本分支文件：SAF 覆盖自己，或写本地草稿库同名文件。 */
    private void writeTreeToDisk() throws Exception {
        String text = tree.dump();
        if (currentBranch != null && currentBranch.hasSaf()) {
            SafDir.writeFile(this, currentBranch.safUri, openName, text);
        } else {
            File d = new File(rootDir, currentBranch.getJsonDirName());
            if (!d.exists()) d.mkdirs();
            writeLocal(new File(d, openName), text);
        }
    }

    /** 阶段2：把“.ws/pending 里所有未落盘份(含其它分支/文件)”按各自分支与文件名真正写盘。
     *  某分支已被删除/不在存活库则不动它(保留暂存不丢,返回需跳过);返回写盘个数。 */
    /** 把快照文本里的软删清单真正应用到落盘内容(总保存=真删)。 */
    private String applyDeletesTo(String text, org.json.JSONArray del) {
        if (text == null || text.isEmpty() || del == null || del.length() == 0) return text;
        try {
            StringBuilder err = new StringBuilder();
            JsonTree t = JsonTree.fromText(text, err);
            if (t == null) return text;
            t.delImport(del);
            t.applyPendingDel();
            return t.dump();
        } catch (Exception ignore) { return text; }
    }

    private int flushPendingToDisk() {
        File dir = wsPendingDir();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null || fs.length == 0) return 0;
        int wrote = 0; int skipped = 0;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".buf")) continue;
            try {
                JSONObject meta = new JSONObject(readAll(f));
                String brId = meta.optString("branchId", "");
                String name = meta.optString("openName", "");
                String text = meta.optString("text", "");
                if (brId.isEmpty() || name.isEmpty() || text.isEmpty()) continue;
                Branch b = repos.byId(brId);
                if (b == null) { skipped++; continue; }          // 分支已删 → 暂存保留到最后由恢复/删除逻辑接管
                String finalText = applyDeletesTo(text, meta.optJSONArray("del"));  // 总保存=把软删项真删后落盘
                writeTextIntoBranch(b, name, finalText);
                if (!f.delete()) f.deleteOnExit();
                wrote++;
            } catch (Exception ignore) { skipped++; }
        }
        return wrote;
    }
    /** 把 (branch,file,text) 以该分支方式实际落盘：SAF 或本地草稿目录下同名文件。 */
    private void writeTextIntoBranch(Branch b, String file, String text) throws Exception {
        if (b.hasSaf()) { SafDir.writeFile(this, b.safUri, file, text); }
        else { File d = new File(rootDir, b.getJsonDirName()); if (!d.exists()) d.mkdirs(); writeLocal(new File(d, file), text); }
    }

    /** 写本地文本(UTF-8)。 */
    private static void writeLocal(File f, String text) throws Exception {
        java.io.FileOutputStream os = new java.io.FileOutputStream(f);
        try { os.write(text.getBytes(StandardCharsets.UTF_8)); os.flush(); }
        finally { os.close(); }
    }

    /** 读取本地 utf-8 文本文件全部内容(供草稿库打开文件)。 */
    private static String readAll(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) >= 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } finally { in.close(); }
    }

    // ==================== 内嵌适配器 ====================

    /** 侧栏分支列表：标题 + 仓库小字。 */
    private class BranchAdapter extends BaseAdapter {
        private final List<Branch> items;
        BranchAdapter(List<Branch> list) { items = list; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = cv == null ? new LinearLayout(MainActivity.this) : (LinearLayout) cv;
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.removeAllViews();
            Branch b = items.get(pos);
            row.setBackgroundColor(pinnedBranchIds.contains(b.id)
                    ? (Skin.isDark(MainActivity.this) ? 0xFF2A3444 : 0xFFE3E9F4)  // 置顶:整行略深
                    : 0x00000000);
            TextView t1 = new TextView(MainActivity.this);
            t1.setText(b.title);
            t1.setTextSize(17f);
            t1.setTypeface(null, Typeface.BOLD);
            t1.setTextColor(Skin.text(MainActivity.this));
            row.addView(t1);
            if (b.subtitle != null) {
                TextView t2 = new TextView(MainActivity.this);
                t2.setText(b.subtitle);
                t2.setTextSize(12f);
                t2.setTextColor(Skin.mutedText(MainActivity.this));
                row.addView(t2);
            }
            return row;
        }
    }

    /** 分支下 .json 文件名列表。 */
    private class FileListAdapter extends BaseAdapter {
        private final List<String> items;
        FileListAdapter(List<String> list) { items = list; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            final String file = items.get(pos);
            final String bid = (MainActivity.this.currentBranch != null) ? MainActivity.this.currentBranch.id : "";
            final String key = bid + "|" + file;
            LinearLayout row = cv == null ? new LinearLayout(MainActivity.this) : (LinearLayout) cv;
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(6), dp(16), dp(6));
            if (row.getChildCount() == 0) {}
            row.removeAllViews();

            TextView t = new TextView(MainActivity.this);
            t.setText("📄 " + file);
            t.setTextSize(15f);
            t.setTextColor(Skin.text(MainActivity.this));
            t.setHintTextColor(Skin.mutedText(MainActivity.this));
            t.setPadding(dp(0), dp(8), dp(8), dp(8));
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

            // json 自身收藏标识：右侧星(仅展示；交互在长按菜单)
            ImageView star = new ImageView(MainActivity.this);
            star.setImageResource(R.drawable.ic_fav);
            boolean favf = favFiles.contains(key);
            star.setColorFilter(favf ? 0xFFE09B3D : 0);
            star.setVisibility(favf ? View.VISIBLE : View.GONE);   // 未收藏→不显示任何星(无论任何条)
            row.addView(star, new LinearLayout.LayoutParams(dp(24), dp(24)));
            return row;
        }
    }

    /** 分层浏览容器：sortedKeys + 层末「＋ 新建 key」占位行。 */
    private class ContainerAdapter extends BaseAdapter {
        private final JsonTree t;
        private final JSONObject container;
        private final List<String> keys;
        private final List<String> parentSegs;   // 本层行所属容器在树里的祖先-seg(=path)，判断“待删”用
        private final int highlightPos;   // 该层要标亮的行号；-1=无(搜索跳转)
        ContainerAdapter(JsonTree tr, JSONObject c) { this(tr, c, null); }
        ContainerAdapter(JsonTree tr, JSONObject c, String hlKey) {
            t = tr; container = c; keys = tr.sortedKeys(c);
            parentSegs = new ArrayList<>(path);   // 构造该层列表那一刻的父链
            highlightPos = (hlKey == null ? -1 : keys.indexOf(hlKey));
        }
        @Override public int getCount() { return keys.size(); }
        @Override public Object getItem(int i) { return i < keys.size() ? keys.get(i) : null; }
        @Override public long getItemId(int i) { return i; }
        // 末行「＋ 新建」也可点(isEnabled 恒 true)，否则 ListView 禁用行不触发点击导致没反应。
        @Override public boolean isEnabled(int pos) { return true; }
        @Override public View getView(int pos, View cv, ViewGroup parent) {
            if (pos >= keys.size()) {
                // 新建占位行：居中灰底小按钮
                TextView add = new TextView(MainActivity.this);
                add.setText("➕ 新建 key");
                add.setGravity(Gravity.CENTER);
                add.setTextSize(15f);
                add.setTypeface(null, Typeface.BOLD);
                add.setPadding(dp(12), dp(10), dp(12), dp(10));
                add.setTextColor(Skin.text(MainActivity.this));
                // 圆角小按钮
                android.graphics.drawable.GradientDrawable ag = new android.graphics.drawable.GradientDrawable();
                ag.setColor(Skin.addBtnBg(MainActivity.this));
                ag.setCornerRadius(dp(10));
                add.setBackground(ag);
                android.view.ViewGroup.LayoutParams alp = parent == null
                        ? new android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new ListView.LayoutParams(
                            ListView.LayoutParams.MATCH_PARENT,
                            ListView.LayoutParams.WRAP_CONTENT);
                add.setLayoutParams(alp);
                return add;
            }
            return buildRow(pos, cv, parent);
        }

        /** 单个条目(对象或叶子)两行排布：上行 key 标题，下行预览单行省略。 */
        private View buildRow(int pos, View cv, ViewGroup parent) {
            String k = keys.get(pos);
            String v = t.displayOf(container, k);
            boolean obj = t.isObjectValue(container, k);
            String valueShown = obj
                    ? "{…} " + childCount(container, k) + " 子项 →"
                    : v;

            // 关键：该列表同时有 TextView(新建占位行) 与 LinearLayout(普通行) 两种 convertView，
            // ListView 滚动时会跨类型复用 convertView，直接强转会把 TextView 当 LinearLayout 而崩。
            // 仅当 convertView 确为 LinearLayout 才复用，否则新建。
            LinearLayout box = (cv instanceof LinearLayout)
                    ? (LinearLayout) cv
                    : new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.removeAllViews();
            box.setPadding(dp(16), dp(10), dp(16), dp(10));
            // 行透明，避免遮挡(将来可设的)content 背景图
            box.setBackgroundColor(0x00000000);

            // 上行：key 标题(该确已收藏的条目标题右侧附收藏星；未收藏不加任何)
            TextView kt = new TextView(MainActivity.this);
            kt.setText(obj ? "▸ " + k : k);
            kt.setTextSize(15f);
            kt.setTextColor(obj ? Skin.accentObj(MainActivity.this) : Skin.text(MainActivity.this));
            kt.setTypeface(null, Typeface.BOLD);
            kt.setSingleLine(true);
            kt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            boolean favRow = isFavedLive(curSelfSegs, k);  // 只对该行本身判定(逐粒,目标必须在且未待删才亮星)
            boolean pendRow = t.isPendingDel(parentSegs, k);   // 是否已“标为待删”→ 行尾显示垃圾桶
            final List<String> thisSeg = parentSegs;
            if (favRow || pendRow) {
                // 待删/收藏的“尾巴”统一钉在行的最右一条竖轨上(收藏星+垃圾桶同列相邻)，
                // 视觉整齐；垃圾桶只在收藏星右侧、同列高宽一致，不四处飘位。
                LinearLayout ktWrap = new LinearLayout(MainActivity.this);
                ktWrap.setOrientation(LinearLayout.HORIZONTAL);
                ktWrap.setGravity(Gravity.CENTER_VERTICAL);
                ktWrap.addView(kt, new LinearLayout.LayoutParams(0, -2, 1f));
                LinearLayout rail = new LinearLayout(MainActivity.this);
                rail.setOrientation(LinearLayout.HORIZONTAL);
                rail.setGravity(Gravity.CENTER_VERTICAL);
                if (favRow) {
                    ImageView favStar = new ImageView(MainActivity.this);
                    favStar.setImageResource(R.drawable.ic_fav);
                    favStar.setColorFilter(0xFFE09B3D);
                    favStar.setPadding(dp(2), dp(1), dp(0), dp(1));
                    rail.addView(favStar, new LinearLayout.LayoutParams(dp(18), dp(18)));
                }
                if (pendRow) {
                    ImageView pendTrash = new ImageView(MainActivity.this);
                    pendTrash.setImageResource(R.drawable.ic_baseline_auto_delete);
                    pendTrash.setColorFilter(0xFFD64545);               // 红：待删除
                    pendTrash.setPadding(dp(0), dp(1), dp(1), dp(1));
                    rail.addView(pendTrash, new LinearLayout.LayoutParams(dp(18), dp(18)));
                    pendTrash.setOnLongClickListener(p -> {
                        t.undoPendingDel(thisSeg, k);                   // 长按垃圾桶=取消待删(无确认)
                        wsBufHandler.post(() -> { if (tree != null && liveListLv != null) reRenderRowList(); });
                        return true;
                    });
                }
                ktWrap.addView(rail, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                box.addView(ktWrap);
            } else {
                box.addView(kt);
            }

            // 下行：value 预览(对象则为子项数)；单行省略
            TextView vt = new TextView(MainActivity.this);
            vt.setText(valueShown);
            vt.setTextSize(13f);
            vt.setTextColor(obj ? Skin.accentObjDim(MainActivity.this) : Skin.subText(MainActivity.this));
            vt.setSingleLine(true);
            vt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            vt.setTypeface(null, Typeface.NORMAL);
            box.addView(vt, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // （去横线：条目不画与下一行的分隔线，视觉更清爽。行距/内边距已由上方 padding 保证。）

            // 搜索跳转高亮：给“命中那一行”一个浅蓝半透明底，一眼锁定(不影响文字色)
            if (pos == highlightPos) {
                android.graphics.drawable.GradientDrawable hg = new android.graphics.drawable.GradientDrawable();
                hg.setColor(Skin.isDark(MainActivity.this)
                        ? 0x662F86FF : 0x33386DFF);
                hg.setCornerRadius(dp(8));
                box.setBackground(hg);
            }
            // 大优化起步：普通行直接返回其内容(不再加“BB”左滑包装——那版已回退，
            // 右缘删除钮/滑不动均源自它)。左滑手势后续随 RecyclerView 结构一并重做。
            return box;
        }
        private int childCount(JSONObject c, String k) {
            Object o = c.opt(k);
            return o instanceof JSONObject ? ((JSONObject) o).length() : 0;
        }
    }

    /* ==================== 搜索：放大镜 弹窗(仓库内全局 + 层内递归) ==================== */

    /** 一条命中：落在哪个文件、所在“上层容器路径”、key 名、是否对象、命中文本片段。 */
    private static class Hit {
        String file;                 // 文件名，如 Localization.json
        List<String> upper;          // 命中 key 所在层(不含该层)…仅存“容器路径=含命中那层的祖先容器链”
        String key;                  // 命中的那个 key
        List<String> containerSegs; // 从根到“包含命中 key 的容器”的段(用于跳转定位到该容器层)
        String fileOf;               // 标注用：文件·最上层条目简述经 join
        String rightTag;             // items 特殊搜索：该 key 的“物品名称(中文)”→显示在 key 行右侧；空则不显示该补标
        boolean tipSync;             // 特殊搜索自动补出的“对侧(ItemTooltip)”整行标记，仍可照常跳转
    }

/** 判断一条命中是否恰好处在某个单层块里(直接是该块对象的直属 key)。 */
private static boolean topIsBlock(Hit h, String block) {
    return h != null && h.upper != null && h.upper.size() == 1 && block.equals(h.upper.get(0));
}

/**
 * items 特殊搜索增强：仅当该份 json 顶层同时有 ItemName 与 ItemTooltip 两块才生效。<br>
 * 命中某块里的物品 key(名字/介绍内容命中同类 key)时, 对侧块只要有同 key → 补出那一侧一个 tipSync 行(可跳转);
 * 并能取到 ItemName 中文名 → 记入 rightTag 供 key 行右侧显示实际内容。取不到(无介绍/打错搜不到)则不补不显示。
 */
private static void enhanceItemHits(JSONObject root, String fn, List<Hit> hits) {
    JSONObject names = root == null ? null : root.optJSONObject("ItemName");
    JSONObject tips  = root == null ? null : root.optJSONObject("ItemTooltip");
    if (names == null || tips == null) return;                 // 非 items 结构 → 普通搜索，什么都不加

    // (file|块|key) 集合: 结果里已有这些行, 补对侧时跳过以防重复
    java.util.Set<String> present = new java.util.HashSet<>();
    for (Hit h : hits)
        if (h != null && h.file != null && h.upper != null)
            present.add(h.file + '\u0001' + (h.upper.size() == 1 ? h.upper.get(0) : "") + '\u0001' + h.key);

    List<Hit> add = new ArrayList<>();
    java.util.Set<String> done = new java.util.HashSet<>();
    for (Hit h : hits) {
        if (h == null || h.file == null || !h.file.equals(fn)) continue;
        String k = h.key;
        if (k == null) continue;
        // 只要 key 也是某物品名 → 把中文名放右侧补览(key/文字命中都给)
        if (names.has(k) && (h.rightTag == null || h.rightTag.isEmpty()))
            h.rightTag = names.optString(k, "");
        if (topIsBlock(h, "ItemName"))
            syncSibling(h, "ItemTooltip", names, tips, present, done, add);
        else if (topIsBlock(h, "ItemTooltip"))
            syncSibling(h, "ItemName", names, tips, present, done, add);
    }
    if (!add.isEmpty()) hits.addAll(add);
}

/** 命中块 h + 对侧同名 key 存在 → 补一行可跳转的对侧行。 */
private static void syncSibling(Hit h, String sideBlock, JSONObject names, JSONObject tips,
                                Set<String> present, Set<String> done, List<Hit> add) {
    JSONObject side = "ItemName".equals(sideBlock) ? names : tips;
    if (side == null || h.key == null || !side.has(h.key)) return;            // 对侧没有同名 → 不补
    String tag = h.file + '\u0001' + sideBlock + '\u0001' + h.key;
    if (present.contains(tag) || done.contains(tag)) return;
    Hit n = new Hit();
    n.file = h.file;
    n.key = h.key;
    n.upper = new ArrayList<>(java.util.Collections.<String>singletonList(sideBlock));
    n.containerSegs = new ArrayList<>(java.util.Collections.<String>singletonList(sideBlock));
    n.tipSync = true;
    if (names != null && names.has(h.key)) n.rightTag = names.optString(h.key, "");
    add.add(n);
    done.add(tag);
}

    private void collectHits(JSONObject node, List<String> containerSegs, String fileName,
                             String needle, boolean ci, List<Hit> out) {
        if (node == null) return;
        java.util.Iterator<String> it = node.keys();
        List<String> ks = new ArrayList<>();
        while (it.hasNext()) ks.add(it.next());
        for (String k : ks) {
            Object v = node.opt(k);
            boolean isObj = v instanceof JSONObject;
            String kTxt = k;
            String vTxt = v == null ? "" : JsonModel.preview(v); // 面向叶子
            boolean hitKey = contains(ci, kTxt, needle);
            boolean hitVal = !isObj && !vTxt.isEmpty() && contains(ci, vTxt, needle);
            if (hitKey || hitVal) {
                Hit h = new Hit();
                h.file = fileName;
                h.key = k;
                h.containerSegs = new ArrayList<>(containerSegs); // 当前容器路径=段(含根它所在)
                h.upper = new ArrayList<>(containerSegs);
                out.add(h);
            }
            if (isObj) { // 进入下一层
                List<String> seg = new ArrayList<>(containerSegs);
                seg.add(k);
                collectHits((JSONObject) v, seg, fileName, needle, ci, out);
            }
        }
    }

    private static boolean containerPathEqual(List<String> a, List<String> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    private static boolean contains(boolean ci, String hay, String needle) {
        if (needle == null || needle.isEmpty()) return false;
        return ci ? hay.toLowerCase().contains(needle.toLowerCase())
                  : hay.contains(needle);
    }

    /** 读任意分支文件的文本(不切换顶层状态)，返回解析出错为 null。 */
    private String readFileTextQuiet(Branch br, String name) {
        try {
            if (br.hasSaf()) return SafDir.readFile(this, br.safUri, name);
            File f = new File(new File(rootDir, br.getJsonDirName()), name);
            if (!f.exists()) return null;
            return readAll(f);
        } catch (Exception e) { return null; }
    }

    // ================= 防崩溃草稿(未点总保存的编辑/软删，本地 .ws 目录) =================
    // 草稿仍放 rootDir/.ws；但会让仓库扫描跳过所有“.”开头目录(见 RepoRegistry)，
    // 因此不会把它当分支列进分支/收藏页，也保持私有持久落盘区。
    private File wsDir() { return new File(rootDir, ".ws"); }
    private File wsBufFile() { return new File(wsDir(), "current.buf"); }
    /** 存在未落盘显示的依据还包括：离开时的崩溃草稿文件仍在(它代表“有过未保存编辑且还没总保存”)。 */
    private boolean wsBufExists() { try { return wsBufFile().exists(); } catch (Exception e) { return false; } }

    // ------------ 阶段4·改名/删除下让暂存“rekey 不丢” ------------
    /** json 被改名成 newName 时，把同分支下旧名那份 pending 槽重挂到新名(并同步 .buf)，不让改动错位。 */
    private void pendingFileRekey(String brId, String oldName, String newName) {
        try {
            if (oldName == null || oldName.equals(newName) || oldName.isEmpty() || newName == null || newName.isEmpty()) return;
            File dir = wsPendingDir(); File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".buf")) continue;
                try {
                    JSONObject meta = new JSONObject(readAll(f));
                    if (!brId.equals(meta.optString("branchId", ""))) continue;
                    if (!oldName.equals(meta.optString("openName", ""))) continue;
                    String text = meta.optString("text", "");
                    JSONObject nm = new JSONObject();
                    nm.put("branchId", meta.optString("branchId", ""));
                    nm.put("openName", newName);
                    nm.put("text", text);
                    writeLocal(wsPendingFile(brId, newName), nm.toString(2));
                    if (!f.delete()) f.deleteOnExit();
                    return;
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
    }
    /** 分支重命名(id 变 newBrId)时，把旧 branchId 的 pending/.buf 全部改挂新 id。 */
    private void pendingBranchRekey(String oldBrId, String newBrId) {
        if (oldBrId == null || newBrId == null || oldBrId.equals(newBrId)) return;
        try {
            File dir = wsPendingDir(); File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) return;
            for (File f : fs) {
                if (!f.isFile() || !f.getName().endsWith(".buf")) continue;
                try {
                    JSONObject meta = new JSONObject(readAll(f));
                    if (!oldBrId.equals(meta.optString("branchId", ""))) continue;
                    writeLocal(wsPendingFile(newBrId, meta.optString("openName", "")), meta.toString(2));
                    if (!f.delete()) f.deleteOnExit();
                } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { }
    }

    // ------------- 阶段1·“分支多份暂存槽” -------------
    // 与崩溃备份同目录(.ws)，但按 “分支+json 名” 一份一文件保存，供 总保存/退出确认 盘点“尚未落盘”的改动；
    // 真正把这些份批量写盘属于第2阶段，现只负责把【真实“编辑保存”】随时登记 + 点亮总保存(任意分支/主页)。
    private File wsPendingDir() { File d = new File(wsDir(), "pending"); if (!d.exists()) { try { d.mkdirs(); } catch (Exception ignore) { } } return d; }
    private File wsPendingFile(String brId, String name) {
        String br = (brId == null || brId.isEmpty()) ? "_" : brId;
        String nm = (name == null || name.isEmpty()) ? "_" : name.replace('/', '_');
        return new File(wsPendingDir(), br + "__" + nm + ".buf");
    }
    private int wsPendingCount() {
        File[] fs = wsPendingDir().listFiles();
        int n = 0; if (fs != null) { for (File f : fs) { if (f.isFile() && f.getName().endsWith(".buf")) n++; } } return n;
    }
    /** 只在“尚存活分支名下”的未落盘份(即它当前真正可以落盘、总保存“有东西可写”)才算亮灯依据。
     *  被删/在回收站的暂存仍保留不删,但不计入灯;恢复 rekey 后会重新由 alive 可见而恢复点亮。 */
    private int wsLivePendingCount() {
        File[] fs = wsPendingDir().listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (!f.isFile() || !f.getName().endsWith(".buf")) continue;
            try {
                JSONObject m = new JSONObject(readAll(f));
                if (repos.byId(m.optString("branchId", "")) != null) n++;
            } catch (Exception ignore) { }
        }
        return n;
    }
    private boolean wsLiveBufExists() {
        if (!wsBufExists()) return false;
        try { JSONObject m = new JSONObject(readAll(wsBufFile())); return repos.byId(m.optString("branchId", "")) != null; } catch (Exception e) { return wsBufExists(); }
    }
    private void wsMarkPending() {
        if (tree == null || currentBranch == null || openName == null) return;
        try {
            JSONObject meta = new JSONObject();
            meta.put("branchId", currentBranch.id == null ? "" : currentBranch.id);
            meta.put("openName", openName);
            meta.put("text", tree.dump());
            meta.put("del", tree.delExport());
            writeLocal(wsPendingFile(currentBranch.id == null ? "" : currentBranch.id, openName), meta.toString(2));
        } catch (Exception ignore) { }
    }
    private void wsClearPendingOf(String brId, String name) {
        try { File f = wsPendingFile(brId, name); if (f.exists()) f.delete(); } catch (Exception ignore) { }
    }

    /** 有未保存改动就(合并延迟)写一次草稿，防止进程被杀丢劳动；写盘只在需要时,体量小不卡。 */
    private void scheduleWsFlush() {
        if (wsBufScheduled) return;
        if (tree == null || tree.rootObject() == null) return;          // 空/未打开不用保护
        wsBufScheduled = true;
        wsBufHandler.removeCallbacksAndMessages(null);
        wsBufHandler.postDelayed(() -> { wsBufScheduled = false; flushWsBufNow(); }, 800);
    }

    /** 立即把打开的这份 json “最新缓存文本”写成草稿(含定位信息)。不落主文件。 */
    private synchronized void flushWsBufNow() {
        wsBufScheduled = false;
        if (tree == null || tree.rootObject() == null) return;
        try {
            File d = wsDir(); if (!d.exists()) { if (!d.mkdirs()) return; }
            JSONObject meta = new JSONObject();
            if (currentBranch != null) {
                meta.put("branchId", currentBranch.id == null ? "" : currentBranch.id);
                meta.put("branchTitle", currentBranch.title == null ? "" : currentBranch.title);
            }
            meta.put("openName", openName == null ? "" : openName);
            meta.put("text", tree.dump());
            meta.put("del", tree.delExport());                 // 软删清单一并存档: 删分支/回主页后仍保持“待删”态
            writeLocal(wsBufFile(), meta.toString(2));
        } catch (Exception ignore) { }
    }

    /** 离开前若还有未保存编辑，立即同步写一份 .ws 草稿(不用等 800ms 延时)——这是“临时保存”的权威单一落点。 */
    private void checkpointIfEditing() {
        if (tree == null || openName == null) return;
        if (currentBranch == null || !tree.isDirty()) return;
        wsMarkPending();                      // 阶段1：把这份“未落盘”改动登记为分支级总暂存(供跨分支/主页常亮总保存)
        try { flushWsBufNow(); } catch (Exception ignore) { }   // 崩溃应急快照照旧(同目录同源,不重复)
    }

    /** 同分支内从当前编辑中的 A 切到另一份 B：先把 A 那份落草稿(.ws 当前只存一份,拿切换点当权威 checkpoint)。 */
    private void checkpointSwitchIF(final Branch br, final String newer) {
        if (currentBranch == br && currentBranch != null && openName != null
                && !openName.equals(newer) && tree != null && tree.isDirty()) {
            try { flushWsBufNow(); } catch (Exception ignore) { }
        }
    }

    /** 同会话又在同分支把同一文件打开回来时自动接回其中未保存草稿(不打扰提示)。匹配到即 adopt 并返回 true。 */
    private boolean autoReAdoptWs(final Branch br, final String name) {
        final File f = wsBufFile();
        if (f == null || !f.exists() || br == null) return false;
        try {
            final JSONObject meta = new JSONObject(readAll(f));
            if (!name.equals(meta.optString("openName"))) return false;
            if (!bString(br).equals(meta.optString("branchId"))) return false;
            final String txt = meta.optString("text");
            if (txt == null || txt.isEmpty()) return false;
            final org.json.JSONArray delx = meta.optJSONArray("del");
            if (!f.delete()) f.deleteOnExit();
            adoptOpenText(br, name, txt);
            if (tree != null) tree.delImport(delx);   // 继续保持“待删/软删”状态(而非丢标记)
            refreshSaveButton();
            try { java.util.List<Object[]> tp = tree == null ? null : tree.pendingSnap();
                toast("TRACE buf-adopt 树待删=" + (tp == null ? 0 : tp.size()) + " 钮=" + btnSave.isEnabled());
            } catch (Exception ignore) { }
            return true;
        } catch (Exception ignore) { return false; }
    }
    private String bString(Branch b) { return b == null ? "" : String.valueOf(b.id); }

    /** 同分支・同文件若在 .ws/pending 留有未落盘快照，就把它整份连同一同 rest软删标记 adopt 回(w/o disk). */
    private boolean adoptPendingSlot(final Branch br, final String name) {
        final File f = wsPendingFile(br == null ? "" : br.id == null ? "" : String.valueOf(br.id), name);
        if (f == null || !f.exists()) return false;
        try {
            final JSONObject meta = new JSONObject(readAll(f));
            final org.json.JSONArray delx = meta.optJSONArray("del");
            adoptOpenText(br, name, meta.optString("text"));
            if (tree != null) tree.delImport(delx);       // 把待删/划线状态还回来
            if (!f.delete()) f.deleteOnExit();
            refreshSaveButton();                          // 该 json 内有两个待删态条目(未落盘) → 总保存应点亮
            // —— trace(只读)：定位“待删/划线未恢复”在删・恢复・打开的哪一环断 ——
            try {
                java.util.List<Object[]> tp = tree == null ? null : tree.pendingSnap();
                int cc = tp == null ? 0 : tp.size();
                String fst = "";
                if (tp != null && !tp.isEmpty()) {
                    @SuppressWarnings("unchecked") java.util.List<String> s00 = (java.util.List<String>) tp.get(0)[0];
                    fst = "首径[" + java.util.Arrays.toString(s00.toArray()) + "]·" + tp.get(0)[1];
                }
                toast("TRACE adopt回挂: del条=" + (delx == null ? 0 : delx.length())
                        + " 树待删=" + cc + " | " + fst + " | 保存钮=" + btnSave.isEnabled());
            } catch (Exception ignore) { }
            return true;
        } catch (Exception ignore) { return false; }
    }

    /** 保存成功或用户放弃草稿后再清掉草稿。 */
    private void clearWsBuf() {
        try { File f = wsBufFile(); if (f.exists()) f.delete(); } catch (Exception ignore) { }
    }

    /** 启动时若有草稿，弹恢复询问(恢复=把草稿文本接进树并标脏；放弃=删除草稿)。 */
    private void offerRecoveryIfAny() {
        final File f = wsBufFile();
        if (f == null || !f.exists()) return;
        try {
            String raw = readAll(f);
            if (raw == null || raw.trim().isEmpty()) { f.delete(); return; }
            final JSONObject meta = new JSONObject(raw);
            final String brId = meta.optString("branchId");
            final String openFileName = meta.optString("openName");
            final String docText = meta.optString("text");
            if (docText.isEmpty()) { f.delete(); return; }
            final Branch br = repos.byId(brId);
            if (br == null) {
                toast("检测到未保存草稿，但原分支已不存在，暂保留在回收暂存，未自动恢复。");
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("找回未保存的草稿")
                .setMessage("上次在「" + openFileName + "」里有未点“保存”的改动。\n恢复回来继续编辑吗？(恢复后需再点右上保存才会写盘)")
                .setPositiveButton("恢复", (d, w) -> {
                    f.delete();
                    adoptOpenText(br, openFileName, docText);
                })
                .setNegativeButton("放弃", (d, w) -> f.delete())
                .show();
        } catch (Exception ignore) { }
    }

    /** 以给定内容接管打开(用于崩溃恢复)，保持与正常打开一致的渲染，并标“有待保存”。 */
    private void adoptOpenText(Branch br, String name, String text) {
        try {
            StringBuilder err = new StringBuilder();
            JsonTree nt = JsonTree.fromText(text, err);
            if (nt == null) { toast("草稿似乎损坏无法解析：" + err); return; }
            currentBranch = br;
            atWelcomePage = false;                          // 已进入某 json 工作区
            tree = nt;
            openName = name;
            tree.forceDirty();                              // 未点总保存，恢复后保持“待保存”态
            HistoryLog.noteOpen(openName, tree.rootObject());
            path.clear();
            title.setText((br.title.equals("悠然汉化") ? "悠然" : br.title) + " / " + name);
            renderContainer();
        } catch (Exception e) {
            toast("草稿恢复失败：" + e.getMessage());
        }
    }

    /** 打开原文·记事本(极简 EditText 全屏对话框),编辑整份 json 原文,保存即时回填当前工作区。 */
    private void showRawEditor() {
        if (tree == null || currentBranch == null || openName == null) { toast("请先打开一个 json 再查看原文"); return; }
        final String start = tree.dump();
        android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.setCancelable(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        final android.widget.EditText ed = new android.widget.EditText(this);
        ed.setGravity(Gravity.TOP | Gravity.START);
        ed.setTextSize(13f);
        ed.setTypeface(Typeface.MONOSPACE);
        ed.setText(start);
        ed.setSelection(start.length());
        ed.setBackgroundColor(0xFF1A1C24);
        ed.setTextColor(0xFFE8E8EA);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText("原文 · 记事本  (" + openName + ")");
        t.setTextSize(16f);
        t.setTextColor(Skin.text(this));
        t.setTypeface(null, Typeface.BOLD);
        bar.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView save = new TextView(this);
        save.setText("保存");
        save.setTextSize(16f);
        save.setTextColor(0xFF4CAF50);
        save.setPadding(dp(12), dp(6), dp(12), dp(6));
        save.setOnClickListener(v -> {
            String txt = ed.getText().toString();
            dlg.dismiss();  // 先关，避免解析长互卡时多层叠
            adoptOpenText(currentBranch, openName, txt);
        });
        bar.addView(save);
        TextView cancel = new TextView(this);
        cancel.setText("放弃");
        cancel.setTextSize(16f);
        cancel.setTextColor(Skin.subText(this));
        cancel.setPadding(dp(4), dp(6), dp(12), dp(6));
        cancel.setOnClickListener(v -> dlg.dismiss());
        bar.addView(cancel);
        root.addView(bar);
        root.addView(ed, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        dlg.setContentView(root);

        android.view.Window w = dlg.getWindow();
        if (w != null) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.96f);
            lp.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.95f);
            w.setAttributes(lp);
        }
        dlg.show();
    }

    /** 打开搜索弹窗：scope 为“当前打开文件”或“全局/整个分支仓库”。 */
    // ==================== 保存记录(过往保存历史) ====================

    private static final String KEY_HIST_NO_MORE = "hist_notice_suppressed";

    /** 点“保存记录”按钮入口：首次包告知(可选不再弹)，之后进记录页。 */
    private void openHistoryEntry() {
        if (HistoryLog.all().isEmpty() && dels.isEmpty()) {
            toast("还没有保存记录——修改内容并点右上“保存”、或删除条目后，这里才会记录。");
        }
        boolean suppressed = loadPrefBool(KEY_HIST_NO_MORE, false);
        if (!suppressed) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(6), dp(2), dp(6), dp(2));
            TextView msg = new TextView(this);
            msg.setText("当你修改内容保存后这里会记录你过往的保存。");
            msg.setTextSize(14f);
            msg.setTextColor(Skin.text(this));
            body.addView(msg);
            final CheckBox noMore = new CheckBox(this);
            noMore.setText("以后不再弹出此提示");
            noMore.setTextColor(Skin.text(this));
            body.addView(noMore);
            android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                    .setTitle("保存记录")
                    .setView(body)
                    .setPositiveButton("知道了", (dl, w) -> {
                        if (noMore.isChecked()) savePrefBool(KEY_HIST_NO_MORE, true);
                        openHistoryView();
                    })
                    .setNegativeButton("先不看", null)
                    .create();
            d.setOnShowListener(ds -> styleDialogDark(d));
            d.show();
            styleDialogDark(d);
            return;
        }
        openHistoryView();
    }

    /** 展示过往保存：外层=文件大圆角卡；其内按上层分组卡；最内=每个改动的 key(旧划线/新)。 */
    private void openHistoryView() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(2), dp(2), dp(10), dp(10));   // 左侧留一点像素、不贴边

        // 一级：按文件聚合
        java.util.Map<String, List<HistoryLog.Entry>> byFile = new java.util.TreeMap<>();
        for (HistoryLog.Entry e : HistoryLog.all()) {
            List<HistoryLog.Entry> g = byFile.get(e.file);
            if (g == null) { g = new ArrayList<>(); byFile.put(e.file, g); }
            g.add(e);
        }
        if (byFile.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("（还没有任何保存记录）");
            none.setTextColor(Skin.mutedText(this));
            outer.addView(none);
        }
        // 历史对话框引用(供长按条目弹去旧后关掉本窗)
        final android.app.AlertDialog[] histDlg = {null};
        // 二级文件名
        for (java.util.Map.Entry<String, List<HistoryLog.Entry>> f : byFile.entrySet()) {
            TextView fileHead = new TextView(this);
            fileHead.setText(f.getKey());
            fileHead.setTextSize(16f);
            fileHead.setTypeface(Typeface.DEFAULT_BOLD);
            fileHead.setTextColor(0xFF2F86FF);
            outer.addView(fileHead);
            fileHead.setPadding(dp(0), dp(6), dp(0), dp(2));

            // 二级：文件内按上层(cont)聚合
            java.util.LinkedHashMap<String, List<HistoryLog.Entry>> byUpper =
                    new java.util.LinkedHashMap<>();
            for (HistoryLog.Entry e : f.getValue()) {
                String u = e.upper == null || e.upper.isEmpty() ? "（顶层）" : e.upper;
                List<HistoryLog.Entry> g = byUpper.get(u);
                if (g == null) { g = new ArrayList<>(); byUpper.put(u, g); }
                g.add(e);
            }
            for (java.util.Map.Entry<String, List<HistoryLog.Entry>> up : byUpper.entrySet()) {
                // 上层=小圆角卡
                LinearLayout innerUpper = UiKit.roundedList(MainActivity.this,0xFFEFEFEF, up.getKey());
                for (HistoryLog.Entry e : up.getValue()) {
                    final HistoryLog.Entry ent = e;
                    LinearLayout keyCard = UiKit.roundedList(MainActivity.this,0xFFE6EFFF, "");
                    // 每 key：两段(旧红划线 / 新绿)
                    keyCard.addView(UiKit.labelLine(MainActivity.this,"key", e.key, 0xFF222222, false));
                    keyCard.addView(UiKit.labelLine(MainActivity.this,"旧", e.old_, 0xFFC0392B, true));
                    keyCard.addView(UiKit.labelLine(MainActivity.this,"新", e.now, 0xFF1E8449, false));
                    final LinearLayout cardRow = keyCard;
                    // 长按该条记录→ 直接带你去当前 json 里那条 key(优先现在的名字，找不到则去根)
                    cardRow.setOnLongClickListener(v -> {
                        if (histDlg[0] != null) { try { histDlg[0].dismiss(); } catch (Exception ignored) {} }
                        jumpByHistory(ent);
                        return true;
                    });
                    innerUpper.addView(keyCard);
                }
                outer.addView(UiKit.outerAdded(MainActivity.this,innerUpper));
            }
            outer.addView(UiKit.spacer(MainActivity.this,6));
        }

        // ===== 🗑 删除型记录(不只来自“保存修改”,还来自软删/真删 的 key) =====
        if (!dels.isEmpty()) {
            if (byFile.isEmpty()) {
                TextView headerD = new TextView(this);
                headerD.setText("👇 以下为删除操作留痕(修改上的普通记录仍在第一组)");
                headerD.setTextSize(11f); headerD.setTextColor(Skin.mutedText(this));
                outer.addView(headerD);
            }
            TextView headDel = new TextView(this);
            headDel.setText("🗑 删除型(" + dels.size() + ")");
            headDel.setTextSize(15f); headDel.setTypeface(Typeface.DEFAULT_BOLD);
            headDel.setTextColor(0xFFE74C3C);
            outer.addView(headDel);
            outer.addView(UiKit.spacer(MainActivity.this,4));

            for (DelRec d : dels) {
                final DelRec dd = d;
                LinearLayout card = UiKit.roundedList(MainActivity.this, 0xFFFFE5E5, dd.file);
                card.addView(UiKit.labelLine(MainActivity.this, "key", dd.key, 0xFF222222, dd.saved));
                TextView status = new TextView(this);
                if (!dd.saved) { status.setText("状态：待删 — 长按去现场看/反悔"); }
                else if (recycleEnabled()) { status.setText("状态：已删 · 回收站开 — 长按将提示去回收站");
                    card.setBackgroundColor(0xFFFFF3CD); }
                else { status.setText("状态：已删 · 回收站关 — 长按现场恢复(同会话)"); }
                status.setTextSize(11f);
                status.setTextColor(Skin.subText(this));
                card.addView(status);
                final java.util.List<String> segF = dd.seg;
                card.setOnLongClickListener(v -> {
                    if (!dd.saved) {                 // ① 还没真删：可跳去看反悔(保留当前内存编辑缓存,不走磁盘重载)
                        if (tree != null && openName != null && openName.equals(dd.file)) {
                            hlSegs = new ArrayList<>(segF); hlKey = dd.key; path.clear(); path.addAll(segF);
                            if (histDlg[0] != null) histDlg[0].dismiss();   // 先关记录窗，避免“看着没跳”
                            renderContainer();
                        } else toast("请先切到 " + dd.file + " 所在文件再看(避免丢当前未保存编辑)");
                    } else if (recycleEnabled()) {   // ② 已真删 & 回收站开 → 去回收站
                        toast("该条已删除；开启回收站时请到【回收站】板块恢复");
                    } else {                         // ③ 已真删 & 回收站关 → 同会话现场恢复
                        recoverDel(dd);
                    }
                    return true;
                });
                outer.addView(card);
                outer.addView(UiKit.spacer(MainActivity.this,4));
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(outer);
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("过往保存")
                .setView(sv)
                .setPositiveButton("完成", (dl, w) -> dl.dismiss())
                .create();
        histDlg[0] = d;   // 让长按“带到现场”的监听能先把本窗关掉
        d.setOnShowListener(ds -> styleDialogDark(d));
        d.show();
        styleDialogDark(d);
    }

    private boolean loadPrefBool(String k, boolean def) {
        return getSharedPreferences("youran_ui", MODE_PRIVATE).getBoolean(k, def);
    }

    private void savePrefBool(String k, boolean v) {
        getSharedPreferences("youran_ui", MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }
    private int loadPrefInt(String k, int def) {
        return getSharedPreferences("youran_ui", MODE_PRIVATE).getInt(k, def);
    }
    private void savePrefInt(String k, int v) {
        getSharedPreferences("youran_ui", MODE_PRIVATE).edit().putInt(k, v).apply();
    }

    /** 回收站开关(临时：只读此位，真实回收站功能后续块3实现；默认关=不启用)。 */
    private boolean recycleEnabled() { return loadPrefBool("recycle_enabled", false); }
    private void setRecycleEnabled(boolean v) { savePrefBool("recycle_enabled", v); }

    /** 历史条目“长按直达”：尽力静默保存当前未存改动(失败则停并提示)，
     *  然后按记录的文件打开并跳到该 key 现在的容器层高亮。优先用当前 key 名重新定位，
     *  key 同名已不存在时退回文件根。 */
    private void jumpByHistory(HistoryLog.Entry ent) {
        if (ent == null) return;
        if (tree != null && openName != null && tree.isDirty()) {
            doSave();
            if (tree != null && tree.isDirty()) { toast("有条目保存失败"); return; }
        }
        if (currentBranch == null) { toast("请先选一个分支"); return; }
        List<String> avail;
        try { avail = listJsonFor(currentBranch); } catch (Exception e) { toast("找不到文件：" + e.getMessage()); return; }
        String exact = null;
        for (String n : avail) {
            if (n.equals(ent.file) || n.equals(ent.file + ".json")) { exact = n; break; }
        }
        if (exact == null) { toast("该 json 不在当前侧边分支，请先切过去再长按"); return; }
        openFile(currentBranch, exact);          // 打开后 tree 就是目标 json
        if (tree == null) return;
        List<String> segs = locateKeySegs(tree.rootObject(), ent.key, new ArrayList<String>());
        path.clear();
        if (segs != null) path.addAll(segs);
        hlFile = exact;
        hlSegs = segs != null ? new ArrayList<String>(segs) : new ArrayList<String>();
        hlKey = ent.key;
        renderContainer();
        if (segs != null && segs.isEmpty()) toast("已到该条(文件根就含此 key)");
        else if (segs != null) toast("已到 \"" + ent.key + "\" 所在条目");
        else toast("该 key 在当前版本下找不到了，已到文件根");
    }

    /** 深度遍历 obj，定位“能把 key 作为直接子键”的那个容器，换算成容器路径 segs。
     *  找不到(null 传回)、根就含 key(空列表→成立)。 */
    private List<String> locateKeySegs(JSONObject obj, String key, List<String> here) {
        if (obj == null) return null;
        if (obj.has(key)) return new ArrayList<String>(here);
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String childName = it.next();
            try {
                JSONObject c = obj.optJSONObject(childName);
                if (c == null) continue;
                List<String> sub = new ArrayList<String>(here);
                sub.add(childName);
                List<String> r = locateKeySegs(c, key, sub);
                if (r != null) return r;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** 仅收起软键盘(不关页面)。 */
    private void hideIme() {
        android.view.View f = getCurrentFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        else imm.hideSoftInputFromInputMethod(null, 0);
    }

    private void openSearchDialog() {
        final Branch br = currentBranch;
        if (br == null) { toast("请先在左上菜单选一个分支再搜索"); return; }
        // 可用搜索对象集
        List<String> allJson = new ArrayList<>();
        boolean hasOpenFile = tree != null && openName != null;
        if (hasOpenFile) allJson.add(openName);
        if (currentBranch != null) {
            List<String> others = listJsonFor(br);
            for (String n : others) if (!allJson.contains(n)) allJson.add(n);
        }
        boolean onlyOpen = allJson.size() == 1 && hasOpenFile && allJson.get(0).equals(openName);

        // 装配弹窗 UI
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .create();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));

        TextView tip = new TextView(this);
        final boolean gob = allJson.size() > (hasOpenFile ? 1 : 0);
        tip.setText(hasOpenFile
                ? (gob ? "已含当前文件，可切换全局范围搜整个分支"
                       : "正在“当前 .json”全部层级内递归搜索")
                : "当前未打开 json；搜索范围为整个分支仓库");
        tip.setTextSize(12f);
        tip.setTextColor(Skin.mutedText(this));
        box.addView(tip);

        // 范围切换(两档)
        // [全局/仓库] 始终可用；[当前文件层递归] 仅当前有打开文件时可用并默认选中
        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox rbFile = new CheckBox(this);
        rbFile.setEnabled(hasOpenFile);
        rbFile.setChecked(true);
        rbFile.setText("当前文件内（递归子层）");
        rbFile.setTextColor(Skin.text(this));
        final CheckBox rbGlobal = new CheckBox(this);
        rbGlobal.setEnabled(!onlyOpen);
        rbGlobal.setChecked(!hasOpenFile);
        rbGlobal.setText("全局（整个分支仓库）");
        rbGlobal.setTextColor(Skin.text(this));
        // 单选联动
        rbFile.setOnCheckedChangeListener((b, c) -> { if (c) rbGlobal.setChecked(false); });
        rbGlobal.setOnCheckedChangeListener((b, c) -> { if (c) rbFile.setChecked(false); });
        scopeRow.addView(rbFile, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        scopeRow.addView(rbGlobal, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(scopeRow);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.search_hint);
        styleEditBox(input);
        box.addView(input);

        final TextView status = new TextView(this);
        status.setTextColor(Skin.subText(this));
        status.setTextSize(13f);
        box.addView(status);

        final ListView results = new ListView(this);
        results.setVisibility(View.GONE);
        box.addView(results, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.42f)));

        dlg.setTitle("搜索");
        dlg.setView(box);
        styleDialogDark(dlg);          // 深浅统一
        // 无“确认”按钮：回车=开搜；结果 单击=选中、双击=跳转并关页+收键盘(返回键也可关)

        // 执行搜索(点回车/输入实时可，这里回车触发)
        final java.util.function.Consumer<Void> run = (x) -> {
            final String raw = input.getText().toString().trim();
            // 搜索增强：支持 “父-子” 两段。
            //   含 “-”：最后一个 “-” 之前 = “父层约束”；之后 = 要搜的子项(key/值片段)。
            //   只有一个连字符长单词仍然照旧整体弱搜，不被误拆。
            int dash = raw.lastIndexOf('-');
            final boolean dsplit = dash > 0 && dash < raw.length() - 1;
            final String par = dsplit ? raw.substring(0, dash).trim() : "";
            final String term = dsplit ? raw.substring(dash + 1).trim() : raw;
            if (term.isEmpty() || (dsplit && par.isEmpty())) {
                status.setText(dsplit ? "连字符前后都得有内容: 例 “上层-子项”"
                                       : "输入要搜的 key 或文本");
                return;
            }
            final String needle = term;   // 匿名 adapter 需要 effectively final；term 已不再重赋值
            final List<Hit> hits = new ArrayList<>();
            final boolean ci = true;
            // 生成 scope 目标文件名集合：明确>模糊
            //  1) 勾了“当前文件内”(限定有打开文件才可选) → 只搜当前打开文件(递归全层)
            //  2) 否则一律“全局/整个分支仓库”(递归去读分支目录下所有 .json，未经判断它可点即不去挡)
            List<String> targets = new ArrayList<>();
            boolean searchCurrentFile = hasOpenFile && rbFile.isChecked();
            if (searchCurrentFile) {
                targets.add(openName);
            } else {
                for (String n : allJson) if (!targets.contains(n)) targets.add(n);
            }
            if (targets.isEmpty()) {
                status.setText("当前分支没有可搜的 .json");
                return;
            }
            for (String fn : targets) {
                try {
                    JSONObject root;
                    if (hasOpenFile && fn.equals(openName)) root = tree.rootObject();
                    else {
                        String txt = readFileTextQuiet(br, fn);
                        if (txt == null) continue;
                        root = new JSONObject(txt);
                    }
                    collectHits(root, new ArrayList<String>(), fn, needle, ci, hits);
                    enhanceItemHits(root, fn, hits);
                } catch (Exception ignore) { }
            }
            // 搜索增强·父段过滤：仅保留“该命中有一个祖先对象名 / 其文件名包含父段”的那些行。
            if (!par.isEmpty()) {
                java.util.List<Hit> kept = new java.util.ArrayList<>();
                for (Hit h : hits) {
                    boolean ok = false;
                    if (h.file != null && contains(ci, h.file, par)) ok = true;
                    if (!ok && h.upper != null) {
                        for (String s : h.upper) {
                            if (contains(ci, s, par)) { ok = true; break; }
                        }
                    }
                    if (ok) kept.add(h);
                }
                hits.clear();
                hits.addAll(kept);
            }
            if (hits.isEmpty()) {
                status.setText(par.isEmpty()
                        ? "没有找到包含 “" + needle + "” 的条目"
                        : "没有找到含父段 “" + par + "”、子项 “" + needle + "” 的条目");
                results.setAdapter(null);
                results.setVisibility(View.GONE);
                return;
            }
            status.setText("共 " + hits.size() + " 条 · 单击选中 / 双击跳转并退出");
            // 每一行：key行高亮匹配段 + 小字 “文件 / 上层”
            results.setAdapter(new android.widget.BaseAdapter() {
                @Override public int getCount() { return hits.size(); }
                @Override public Object getItem(int p) { return hits.get(p); }
                @Override public long getItemId(int p) { return p; }
                @Override public View getView(int p, View c, ViewGroup parent) {
                    Hit h = hits.get(p);
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.VERTICAL);
                    // 第一行：key（可能带右侧“物品中文名”补览）、同排可再点跳
                    LinearLayout keyRowH = new LinearLayout(MainActivity.this);
                    keyRowH.setOrientation(LinearLayout.HORIZONTAL);
                    keyRowH.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    TextView keyT = new TextView(MainActivity.this);
                    keyT.setTextSize(15f);
                    keyT.setText(hi(ci, h.key, needle));
                    keyT.setTextColor(Skin.text(MainActivity.this));
                    keyRowH.addView(keyT, new LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                    // items 特殊搜索：key 行右侧给物品名实际内容(如 name=铁剑)。取不到(空)则不显示。
                    if (h.rightTag != null && !h.rightTag.isEmpty()) {
                        View spacerN = new View(MainActivity.this);
                        keyRowH.addView(spacerN, new LinearLayout.LayoutParams(0, 0, 1f));
                        TextView tagN = new TextView(MainActivity.this);
                        tagN.setText(h.rightTag.length() > 18 ? h.rightTag.substring(0, 18) + "…" : h.rightTag);
                        tagN.setTextSize(12f);
                        tagN.setTextColor(Skin.accentObj(MainActivity.this));
                        keyRowH.addView(tagN, new LinearLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                    }
                    row.addView(keyRowH);
                    // 上层/文件小字
                    String upper = h.upper == null || h.upper.isEmpty()
                            ? "(根层)" : String.join(" › ", h.upper);
                    TextView loc = new TextView(MainActivity.this);
                    loc.setText(h.file + "  上层: " + upper);
                    loc.setTextSize(11f);
                    loc.setTextColor(Skin.mutedText(MainActivity.this));
                    row.addView(loc);
                    row.setPadding(dp(6), dp(6), dp(6), dp(6));
                    // 圆角小卡
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(Skin.cardFill(MainActivity.this));
                    gd.setCornerRadius(dp(8));
                    row.setBackground(gd);
                    row.setPadding(dp(8), dp(6), dp(8), dp(6));
                    return row;
                }
            });
            // 像街机存“键值”：吞掉超快的机械重复(间隔<几十毫秒视同一次)，
            // “同一行再拍一次(与上一次有足够间隔)”就跳转——快/慢双击都稳。
            final long[] lastTime = {0L};
            final int[] lastPos = {-2};
            final int MIN_DUP = 90;     // 内部重复的最小间隔(毫秒)
            results.setOnItemClickListener((p, v, pos, id) -> {
                Hit h = hits.get(pos);
                long t = android.os.SystemClock.uptimeMillis();
                long dt = t - lastTime[0];
                boolean sameRow = (pos == lastPos[0]);
                // 更新“上一拍”
                lastPos[0] = pos;
                // 超快的机械重复(极短间隔，往往是同一真实手指多报了一次)——只看成一次：仍算“选中+给提示”，不误跳
                if (sameRow && dt < MIN_DUP) { lastTime[0] = t; return; }
                // 这里是否“已选中”该行由外层 state 记(选中=上次真实点击就是它)
                boolean newlyJump = sameRow && dt < 1500L;  // “双击区段”(几十ms吞重复，上限放宽到1.5s保留慢双击)
                lastTime[0] = t;
                if (newlyJump) {
                    // 第二拍(第一拍已把该行标记为“已选中”)跳转 + 关页收键盘
                    try { jumpTo(h.containerSegs, h.key, br, h.file); } catch (Exception ignored) {}
                    hideIme();
                    dlg.dismiss();
                } else {
                    // 选中该行：给明确反馈(顶部状态)，不关页
                    status.setText("已选中：" + h.key + "　再点一次同一项＝跳转退出");
                }
            });
            results.setVisibility(View.VISIBLE);
        };
        input.setOnEditorActionListener((tv, act, ev) -> { run.accept(null); return true; });
        dlg.setOnShowListener(d -> styleDialogDark(dlg));
        dlg.show();
        styleDialogDark(dlg);
        // 弹出后主动聚焦输入框+拉起键盘：直接打词后点右下角回车即可整备份/当前文件递归搜
        input.requestFocus();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, 0);
            } catch (Exception ignored) { }
        }, 260);
    }

    /** 搜索跳转护栏：跳到“同一个仓库的另一个 json”的 key 时不做确认，
     *  只是尽力静默保存当前修改(成功才跳；失败不跳并以 toast 明示)。仍同文件时直达不打扰。 */
    private void jumpTo(List<String> containerSegs, String key, Branch br, String file) {
        if (br == null) return;
        boolean switchedFile = (tree == null || openName == null || !openName.equals(file));
        if (!switchedFile || tree == null || !tree.isDirty()) {
            doJumpTo(containerSegs, key, br, file);
            return;
        }
        doSave();                                  // 静默尽力保存当前改动
        if (tree != null && tree.isDirty()) {      // 仍脏说明这次没保存成功
            toast("有条目保存失败");
            return;
        }
        doJumpTo(containerSegs, key, br, file);
    }

    /** 实际执行：切到命中 key 所在容器层，标亮并滚到那一行；若非顶层给约 2 秒“文件-条目”提示。 */
    private void doJumpTo(List<String> containerSegs, String key, Branch br, String file) {
        if (br == null) return;
        boolean switchedFile = (tree == null || openName == null || !openName.equals(file));
        if (switchedFile) openFile(br, file);   // 置 tree/openName(顶层)
        if (tree == null) return;
        List<String> target = new ArrayList<>();
        if (containerSegs != null) target.addAll(containerSegs);
        // 走到“包含该命中 key”的容器层
        path.clear();
        path.addAll(target);
        // 交临时态给下一次 renderContainer 高亮该 key
        hlFile = file; hlSegs = new ArrayList<>(target); hlKey = key;
        renderContainer();
        // 目标容器非“文件根”(有上层路径)：给 2s 提示当前位置“文件-条目”
        if (!target.isEmpty()) {
            toastMsg2s("当前在 " + file + "-" + target.get(target.size() - 1) + " 条目中");
        }
    }

    private void toastMsg2s(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); // 长约 2s
    }

    /** 高亮包含 needle 的部分：用下划线+加粗区分命中段。 */
    private CharSequence hi(boolean ci, String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return text;
        int st;
        if (ci) {
            String n = needle.toLowerCase(); st = text.toLowerCase().indexOf(n);
        } else st = text.indexOf(needle);
        if (st < 0) return text;
        android.text.SpannableString sp = new android.text.SpannableString(text);
        sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                st, st + needle.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF2F86FF),
                st, st + needle.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** 颜色管理主弹窗：先勾要改哪些(顶栏/抽屉头可多选)，来源三选(随机/莫奈/自定义)。 */
    private void openColorManager() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(6), dp(2), dp(6), dp(2));
        TextView t0 = new TextView(this); t0.setText("应用到:"); t0.setTextColor(Skin.mutedText(this));
        body.addView(t0);

        final boolean[] pick = {true, true};   // {顶栏, 抽屉头}
        LinearLayout targets = new LinearLayout(this);
        targets.setOrientation(LinearLayout.HORIZONTAL);
        final CheckBox ckTop = new CheckBox(this); ckTop.setChecked(true); ckTop.setText("顶栏");
        ckTop.setTextColor(Skin.text(this)); ckTop.setOnCheckedChangeListener((d,w)->pick[0]=w);
        final CheckBox ckHead = new CheckBox(this); ckHead.setChecked(true); ckHead.setText("抽屉头");
        ckHead.setTextColor(Skin.text(this)); ckHead.setOnCheckedChangeListener((d,w)->pick[1]=w);
        targets.addView(ckTop, new LinearLayout.LayoutParams(0, WRAPC, 1f));
        targets.addView(ckHead, new LinearLayout.LayoutParams(0, WRAPC, 1f));
        body.addView(targets);

        TextView t1 = new TextView(this); t1.setText("取色来源"); t1.setTextColor(Skin.mutedText(this));
        body.addView(t1);

        TextView rnd = UiKit.chipBtn(MainActivity.this,"随机", 0xFF3BA35A);
        rnd.setOnClickListener(v -> putColors(pick, 0xFF000000 | (int) (Math.random() * 0xFFFFFF)));
        body.addView(rnd);

        TextView mon = UiKit.chipBtn(MainActivity.this,"莫奈(壁纸主色)", 0xFF48586B);
        mon.setOnClickListener(v -> putColors(pick, wallpaperAccent()));
        body.addView(mon);

        TextView custom = UiKit.chipBtn(MainActivity.this,"自定义 ▾", 0xFFC59B3B);
        final LinearLayout hexBox = new LinearLayout(this);
        hexBox.setOrientation(LinearLayout.VERTICAL);
        hexBox.setVisibility(View.GONE);
        custom.setOnClickListener(v -> slideToggle(hexBox, hexBox.getVisibility() == View.GONE));
        body.addView(custom);
        final EditText hex = new EditText(this);
        hex.setHint("#RRGGBB"); hex.setSingleLine(true); styleEditBox(hex);
        TextView go = new TextView(this); go.setText("应用 # 输入"); go.setTextColor(0xFF2F86FF);
        go.setTextSize(14f); go.setPadding(dp(2),dp(6),dp(2),dp(6));
        go.setOnClickListener(v -> {
            String hx = hex.getText().toString().trim().replace("#", "");
            if (hx.length() != 6) { toast("请填 6 位十六进制，如 FF0000"); return; }
            try { putColors(pick, 0xFF000000 | (int) Long.parseLong(hx, 16)); }
            catch (Exception e) { toast("颜色代码不合法"); }
        });
        hexBox.addView(hex);
        hexBox.addView(go);
        body.addView(hexBox);

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("颜色 · 统一管理")
                .setView(body)
                .setPositiveButton("完成", (d, w) -> d.dismiss())
                .create();
        dlg.setOnShowListener(d -> styleDialogDark(dlg));
        dlg.show();
        styleDialogDark(dlg);
    }

    private static final int WRAPC = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;

    private void putColors(boolean[] pick, int color) {
        if (pick[0]) Skin.saveTopBarColor(this, color);
        if (pick[1]) Skin.saveDrawerHeadColor(this, color);
        applySkinColors();
        toast("颜色已应用到 " + ((pick[0] ? "顶栏" : "") + (pick[1] ? ((pick[0] ? "・" : "") + "抽屉头") : "")));
    }

    /** 下收小动画。 */
    private void slideToggle(View box, boolean show) {
        if (box == null) return;
        box.animate().cancel();
        if (show) { box.setVisibility(View.VISIBLE); box.setAlpha(0f);
                    box.animate().alpha(1f).setDuration(150).start(); }
        else box.animate().alpha(0f).setDuration(120)
                 .withEndAction(() -> box.setVisibility(View.GONE)).start();
    }

    /** 莫奈(壁纸主色)近似。API<27 或读不到回落深蓝。 */
    private int wallpaperAccent() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(this);
                android.app.WallpaperColors c = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
                if (c != null && c.getPrimaryColor() != null) return c.getPrimaryColor().toArgb();
            }
        } catch (Exception ignored) { }
        return 0xFF2E5F9E;
    }


    /** 把 s 插入目标 EditText 当前光标/选区处，光标落在刚插入末尾。 */
    private void insertAtCursor(EditText et, String s) {
        if (et == null || s == null) return;
        int st = et.getSelectionStart();
        int en = et.getSelectionEnd();
        if (st < 0) st = et.getText().length();
        if (en < st) en = st;
        et.getText().replace(st, en, s);
        et.setSelection(st + s.length());
    }

    /** 取最近文本里已用的 [c/XXXXXX] 主色(便于 HSV 起始)；没有给一个中性的蓝紫靠近现有顶栏绿意。 */
    private int inferTextColor(EditText tv) {
        try {
            String s = tv.getText() == null ? "" : tv.getText().toString();
            int p = s.lastIndexOf("[c/");
            if (p >= 0) {
                int q = s.indexOf(':', p);
                if (q - p == 7) {                      // "[c/hex:"＝p+3..q
                    String hx = s.substring(p + 3, q);
                    if (hx.length() == 6) return 0xFF000000 | (int) Long.parseLong(hx, 16);
                }
            }
        } catch (Exception ignored) { }
        return 0xFF8899FF;
    }

    /** 把取到的色包住“当前选中文字”：产出 [c/RRGGBB:字]；没选区则提示。 */
    private void wrapColorTag(EditText tv, int color) {
        if (tv == null) return;
        int st = tv.getSelectionStart(); int en = tv.getSelectionEnd();
        if (st < 0 || en < 0) { st = 0; en = 0; }
        if (en < st) { int k = st; st = en; en = k; }
        if (en - st <= 0) { toast("请先在框里选中要染色的文字再点取色"); return; }
        String sel = tv.getText().subSequence(st, en).toString();
        String hex = String.format("%06X", 0xFFFFFF & color);
        String tag = "[c/" + hex + ":" + sel + "]";
        tv.getText().replace(st, en, tag);
        tv.setSelection(st + tag.length());
    }

    /** ＋[i:iD]：商品/物品 id 联想拾取弹窗。目标 EditText=正在编辑的 value。 */
    private void openItemIdDialog(final EditText target) {
        ItemIdDb.ensureLoaded(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), dp(2), dp(4), dp(2));

        final EditText box = new EditText(this);
        box.setHint("输入 数字id / 中文名 / 英文名（回车=采用）");
        box.setSingleLine(true);
        styleEditBox(box);
        col.addView(box, new LinearLayout.LayoutParams(-1, WRAPC));

        TextView how = new TextView(this);
        how.setText("示例：输入 5 会在下方列出 5… 开头的 id，可一直往下翻；英文/中文名同样检索。");
        how.setTextSize(11f);
        how.setTextColor(Skin.mutedText(this));
        how.setPadding(dp(2), dp(4), dp(2), dp(4));
        col.addView(how);

        final ArrayList<ItemIdDb.Item> shown = new ArrayList<>();
        final int CAP = 300;
        ListView lv = new ListView(this);
        lv.setFadingEdgeLength(0);
        col.addView(lv, new LinearLayout.LayoutParams(-1, dp(320)));

        final android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int p) { return shown.get(p); }
            @Override public long getItemId(int p) { return shown.get(p).id; }
            @Override public View getView(int p, View cv, android.view.ViewGroup parent) {
                if (cv == null) {
                    cv = new LinearLayout(MainActivity.this);
                    ((LinearLayout) cv).setOrientation(LinearLayout.VERTICAL);
                    cv.setPadding(dp(8), dp(4), dp(8), dp(4));
                    cv.setBackground(UiKit.rounded(MainActivity.this,0x14FFFFFF));
                }
                ItemIdDb.Item it = shown.get(p);
                TextView t = new TextView(MainActivity.this);
                String head = String.valueOf(it.id) +
                        (it.en.length() > 0 ? "　" + it.en : "");
                t.setText(head);
                t.setTextColor(Skin.text(MainActivity.this));
                t.setTextSize(13f);
                TextView a = new TextView(MainActivity.this);
                a.setText(it.zh.length() > 0 ? it.zh : (it.en.length() > 0 ? it.en : ""));
                a.setTextSize(11f);
                a.setTextColor(Skin.mutedText(MainActivity.this));
                ((LinearLayout) cv).removeAllViews();
                ((LinearLayout) cv).addView(t);
                if (it.zh.length() > 0) ((LinearLayout) cv).addView(a);
                return cv;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener((p, v, pos, id) -> {
            ItemIdDb.Item it = shown.get(pos);
            box.setText("[i:" + it.id + "]");
            box.requestFocus();
            box.setSelection(box.length());
        });

        java.lang.Runnable refresh = () -> {
            List<ItemIdDb.Item> got = ItemIdDb.search(String.valueOf(box.getText()), CAP);
            shown.clear();
            shown.addAll(got);
            ad.notifyDataSetChanged();
        };
        box.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { refresh.run(); }
        });
        refresh.run();

        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("插入 [i:物品id]")
                .setView(col)
                .setPositiveButton("确定插入", (d, w) -> {
                    String t = box.getText().toString().trim();
                    // 取方括号或纯数字
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\\[i:(\\d+)\\]").matcher(t);
                    String num = m.find() ? m.group(1)
                            : (ItemIdDb.isNum(t) ? t.replaceAll("[^0-9]", "") : "");
                    if (num.isEmpty()) { toast("请先在上方输入或点选一个物品 id（数字）"); return; }
                    insertAtCursor(target, "[i:" + num + "]");
                    d.dismiss();
                })
                .setNegativeButton("取消", (d, w) -> d.dismiss())
                .create();
        dlg.setOnShowListener(d -> styleDialogDark(dlg));
        dlg.show();
        styleDialogDark(dlg);
    }

    /** 一行里紧跟可换的“取色”与“插 [i:iD]”，用右侧“＋”收纳展开的小短条。 */
    private void addEditToolStrip(LinearLayout host, EditText tv) {
        if (host == null || tv == null) return;
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dp(2), 0, dp(4));
        // “＋”开合开关
        final boolean[] open = {false};
        TextView plus = UiKit.chipBtn(MainActivity.this,"＋工具", 0xFF2F86FF);
        plus.setOnClickListener(v -> {
            open[0] = !open[0];
            strip.removeAllViews();
            if (!open[0]) { strip.addView(plus); return; }
            strip.addView(plus);
            // 取色 → 把“选中的文字”包进泰拉瑞亚 [c/RRGGBB:..] ；无选中则提示先选中
            TextView dye = UiKit.chipBtn(MainActivity.this,"⧉ 取色 · 包色", 0xFF2F86FF);
            dye.setOnClickListener(x -> openHsv("选择颜色", inferTextColor(tv),
                    picked -> wrapColorTag(tv, picked)));
            strip.addView(dye);
            // [i:id]：目前先插字面；等后续 id 对照表进来再升级为带可滚动的拾取小窗(暂占位)
            TextView idc = UiKit.chipBtn(MainActivity.this,"＋[i:iD]", 0xFF8A63D2);
            idc.setOnClickListener(x -> openItemIdDialog(tv));
            strip.addView(idc);
        });
        strip.addView(plus);
        host.addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 极简 HSV 取色：三根滑条(H/S/V) + 实时色块。pick 以 int(ARGB) 回传。 */
    /** B：系统返回 = 回到“未选仓库”引导首页(renderWelcome)；只有已在首页时才真正退出。 */
    @Override public void onBackPressed() {
        if (atWelcomePage) {
            // 阶段3：在主页点返回 → 若还有未保存条目先弹三键确认,而不是直接退出
            if (hasUnsavedAny()) { showUnsavedExitDialog(); return; }
            super.onBackPressed(); return;
        }
        checkpointIfEditing();      // 在 json 内:未总保存的编辑先落 .ws 草稿,不让“返回=丢失”
        atWelcomePage = true;
        renderWelcome();                                        // 从仓库/json 里按返回 → 回选仓库首页
        refreshSaveButton();                                    // 阶段1:回主页只要仍有未落盘暂存,总保存保持点亮
    }

    private boolean hasUnsavedAny() {
        if (wsPendingCount() > 0) return true;
        if (wsBufExists()) return true;
        return tree != null && tree.isDirty();
    }

    /** 阶段3：主页“有未保存的修改，是否保存？”三键。 */
    private void showUnsavedExitDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("未保存的修改")
            .setMessage("有未保存的修改，是否保存？")
            .setPositiveButton("保存并退出", (d, w) -> {
                d.dismiss();
                doSave();                                  // 在主页 → 会走“把 pending 整批写盘”的批量出口
                super.onBackPressed();                     // 真正退出
            })
            .setNeutralButton("返回", (d, w) -> {          // 不退出，跳到最后修改处继续改
                d.dismiss();
                goToLastPendingEdit();
            })
            .setNegativeButton("取消", (d, w) -> d.dismiss()) // 点外/取消=什么都不做,留在主页
            .setOnDismissListener(dg -> {})
            .show();
    }

    /** 跳到“最近一份未保存”所在 json(继续编辑)。若该分支已被删除则只提示。 */
    private void goToLastPendingEdit() {
        try {
            File dir = wsPendingDir(); File[] fs = dir == null ? null : dir.listFiles();
            java.io.File last = null; long t = -1;
            if (fs != null) for (File f : fs) if (f.isFile() && f.getName().endsWith(".buf") && f.lastModified() > t) { t = f.lastModified(); last = f; }
            if (last == null) { toast("当前没有待续改的未保存内容"); return; }
            JSONObject meta = new JSONObject(readAll(last));
            String brId = meta.optString("branchId", ""), name = meta.optString("openName", "");
            Branch b = repos.byId(brId);
            if (b == null) { toast("那份分支已不存在，无法跳回"); return; }
            // 让 openFile 自动接回同一 pending(它能 auto- adopt & 清那份槽),随后带用户进到该文件
            if (autoReAdoptWs(b, name)) { toast("已回到最近的修改处，可继续点『编辑保存/总保存』"); return; }
            openFile(b, name);
        } catch (Exception e) { toast("跳回失败：" + e.getMessage()); }
    }

    private void openHsv(String title, int initial, java.util.function.IntConsumer onPick) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(4), 0, dp(4), 0);

        int initH = 210, initS = 80, initV = 85;
        final int[] h = {initH}, s = {initS}, v = {initV};
        try {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(initial, hsv);
            h[0] = Math.round(hsv[0]); s[0] = Math.round(hsv[1] * 100f);
            v[0] = Math.round(hsv[2] * 100f);
        } catch (Exception ignored) { }

        body.addView(sliderRow("色相  H", 0, 359, h[0], p -> { h[0] = p; }));
        body.addView(sliderRow("饱和  S", 0, 100, s[0], p -> { s[0] = p; }));
        body.addView(sliderRow("明度  V", 0, 100, v[0], p -> { v[0] = p; }));
        final View swatch = new View(this);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        sp.topMargin = dp(10);
        swatch.setLayoutParams(sp);

        final java.lang.Runnable repaint = () -> {
            float[] f = {h[0], s[0] / 100f, v[0] / 100f};
            swatch.setBackgroundColor(android.graphics.Color.HSVToColor(f));
        };
        body.addView(swatch);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(title == null ? "颜色" : title)
                .setView(body)
                .setPositiveButton("用这个色", (d, w) -> {
                    int argb = 0xFF000000
                            | android.graphics.Color.HSVToColor(new float[]{h[0], s[0] / 100f, v[0] / 100f});
                    if (onPick != null) onPick.accept(argb);
                })
                .setNegativeButton("取消", null)
                .create();
        // 把三条 SeekBar 都存到 body 以便刷新取色回调后再重绘预览？openHsv 用 sliderRow 若无刷新会不实时，
        // 这里我们让每次滑动都调 repaint（见 sliderRow 内 progress 回调我们已绑定 repaint）。
        repaint.run();   // 初刷
        dlg.setOnShowListener(d -> { styleDialogDark(dlg); repaint.run(); });
        dlg.show();
        styleDialogDark(dlg);
        _hsvRepaint = repaint;
    }
    private java.lang.Runnable _hsvRepaint;   // openHsv 已把预览刷新交给 sliderRow 统一(见下)

    private View sliderRow(String label, int minR, int maxR, int init,
                           java.util.function.IntConsumer on) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = new TextView(this);
        lab.setText(label + "  ");
        lab.setTextSize(13f);
        lab.setTextColor(Skin.text(this));
        row.addView(lab, new LinearLayout.LayoutParams(WRAP_CT, WRAP_CT));
        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(maxR);
        sb.setProgress(init);
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                if (on != null) on.accept(p);
                if (_hsvRepaint != null) _hsvRepaint.run();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        row.addView(sb, new LinearLayout.LayoutParams(0, WRAP_CT, 1f));
        return row;
    }

    private static final int WRAP_CT = -2; // LinearLayout.LayoutParams.WRAP_CONTENT
    private static final int MATCH_P = -1; // 占位避免误写未引用词

    /** 现在“＋工具”开合瞬时有值，用 getSelectionStart 保证光标。
     *  注：若框从没焦点也可能 st=-1→已 fallback 到末尾。 */
    @Override
    protected void onDestroy() {
        if (cachedBg != null && !cachedBg.isRecycled()) cachedBg.recycle();
        cachedBg = null;
        if (bgBase != null && !bgBase.isRecycled()) bgBase.recycle();
        bgBase = null;
        super.onDestroy();
    }
}