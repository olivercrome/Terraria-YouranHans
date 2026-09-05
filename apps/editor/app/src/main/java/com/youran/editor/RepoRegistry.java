package com.youran.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 分支/汉化工作区注册表（通用模板编辑器用）。
 * 抽屉不再内置“悠然/害人”，而是启动时扫描 私有目录/<分支> 子文件夹得到分支(空则不显示)，
 * 可由用户新建分支(本地目录)增列。仍在保留 SAF Uri 持久化能力便于个别分支绑外部目录。
 */
final class RepoRegistry {
    private static final String PREFS = "repo_registry";
    private static final String PREFIX_URI = "saf_uri_";

    private final List<Branch> branches = new ArrayList<>();

    RepoRegistry() {
        // 抽屉默认空：分支靠 启动本地扫描 / 新建 而来
    }

    /** 从 App 私有工作区 <root>/<id>/ 重建分支(名字=目录名，稳定可重扫)。目录不存在则创建空。 */
    void initializeFromLocal(File root) {
        branches.clear();
        if (!root.exists()) root.mkdirs();
        File[] ds = root.listFiles(File::isDirectory);
        if (ds == null) return;
        List<File> list = new ArrayList<>();
        for (File d : ds) list.add(d);
        Collections.sort(list, Comparator.comparing(File::getName));
        for (File d : list) {
            String nm = d.getName();
            branches.add(new Branch(nm, nm, "本地分支", null));
        }
    }

    /** 新建一个本地分支：在 <root>/<slug> 建目录并登记。返回新增 Branch。 */
    Branch addBranch(String userTitle, File root) {
        String slug = sanitize(userTitle);
        File dir = new File(root, slug);
        if (!dir.exists()) dir.mkdirs();
        Branch b = new Branch(slug, userTitle, "本地分支", null);
        branches.add(b);
        return b;
    }

    private static String sanitize(String s) {
        String out = s.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return out.isEmpty() ? "branch" : out;
    }

    /** 载入持久化的 SAF Uri。必须在 UI 线程创建后调用一次(或惰性)。 */
    void load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Branch b : branches) {
            String s = sp.getString(PREFIX_URI + b.id, null);
            b.safUri = s == null ? null : Uri.parse(s);
        }
    }

    /** 保存某分支的 SAF Uri(为空则清除)。 */
    void saveBranchSaf(Context ctx, Branch b, Uri uri) {
        b.safUri = uri;
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String key = PREFIX_URI + b.id;
        if (uri == null) e.remove(key); else e.putString(key, uri.toString());
        e.apply();
    }

    /** 按 id 取分支；找不到返回 null。 */
    Branch byId(String id) {
        for (Branch b : branches) if (b.id.equals(id)) return b;
        return null;
    }

    /** 全部内置分支(不可修改的视图语义由调用方遵守)。 */
    List<Branch> all() {
        return branches;
    }
}
