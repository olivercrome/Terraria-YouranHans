package com.youran.editor;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线物品 id 对照：读 assets 里 itemid_zh.json(中文显示名→netId) 与 itemid_en.json(英文内部名→netId)。
 * 给 “＋[i:iD]” 提供 数字前缀 / 英文名(不区分大小写) / 中文名 联想检索。
 */
public class ItemIdDb {

    public static class Item {
        public final int id;
        public final String en;   // 英文内部名
        public final String zh;   // 中文显示名
        Item(int id, String en, String zh) { this.id = id; this.en = en; this.zh = zh; }
    }

    private static List<Item> all;                 // query 目标全量(懒加载一次)
    private static Map<Integer, String> idToEn;    // id -> 英名(供候选行展示)
    private static Map<Integer, String> idToZh;    // id -> 中名

    /** 加载 assets 两项。失败返回空且不抛。 */
    public static synchronized void ensureLoaded(Context ctx) {
        if (all != null) return;
        List<Item> list = new ArrayList<>();
        Map<Integer,String> toEn = new HashMap<>();
        Map<Integer,String> toZh = new HashMap<>();
        try {
            AssetManager am = ctx.getAssets();
            JSONObject en = read(am, "itemid_en.json");
            JSONObject zh = read(am, "itemid_zh.json");
            for (Iterator<String> it = en.keys(); it.hasNext();) {
                String k = it.next();
                if (en.isNull(k)) continue;
                int id = en.optInt(k);
                toEn.put(id, k);
            }
            for (Iterator<String> it = zh.keys(); it.hasNext();) {
                String k = it.next();
                if (zh.isNull(k)) continue;
                int id = zh.optInt(k);
                toZh.put(id, k);
            }
            Set<Integer> ids = new HashSet<>(toEn.keySet());
            ids.addAll(toZh.keySet());
            for (int id : ids) {
                String enN = toEn.get(id), zhN = toZh.get(id);
                list.add(new Item(id, enN == null ? "" : enN, zhN == null ? "" : zhN));
            }
        } catch (Exception e) {
            list = new ArrayList<>();
        }
        all = list;
        idToEn = toEn;
        idToZh = toZh;
    }

    /** 供候选行显示英文名(缺则空串)。 */
    public static String enName(int id) { return idToEn == null ? "" : String.valueOf(idToEn.get(id)); }

    /** 按检索词返回上限条：数字→id 前缀优先；文本→英名(忽略大小写)或中文前缀优先、其次包含。 */
    public static List<Item> search(String query, int cap) {
        List<Item> pref = new ArrayList<>(), cont = new ArrayList<>();
        List<Item> src = raw();
        if (query == null) query = "";
        String q = query.trim();
        if (!q.isEmpty()) {
            boolean numeric = isNum(q);
            String lower = numeric ? "" : q.toLowerCase();
            for (Item it : src) {
                boolean p = false, c = false;
                if (numeric) {
                    String s = Integer.toString(it.id);
                    p = s.startsWith(q);
                    c = !p && s.contains(q);
                } else {
                    boolean enP = it.en.length() > 0 && it.en.toLowerCase().startsWith(lower);
                    boolean zhP = it.zh.length() > 0 && it.zh.startsWith(q);
                    p = enP || zhP;
                    boolean enC = !p && it.en.length() > 0 && it.en.toLowerCase().contains(lower);
                    boolean zhC = !p && it.zh.length() > 0 && it.zh.contains(q);
                    c = enC || zhC;
                }
                if (p) pref.add(it);
                else if (c) cont.add(it);
            }
        } else {
            // 空输入：给开头一屏(按 id 升序)方便浏览
            pref.addAll(src);
        }
        // 排序：前缀组更靠前、各组内按 id 升序
        Comparator<Item> byId = new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return Integer.compare(a.id, b.id); }
        };
        java.util.Collections.sort(pref, byId);
        java.util.Collections.sort(cont, byId);
        List<Item> out = new ArrayList<>();
        out.addAll(pref);
        out.addAll(cont);
        if (out.size() > cap) out = out.subList(0, cap);
        return out;
    }

    public static boolean isNum(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static List<Item> raw() {
        return all == null ? new ArrayList<Item>() : all;
    }

    private static JSONObject read(AssetManager am, String f) throws Exception {
        InputStream in = am.open(f);
        byte[] b = new byte[in.available()];
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) break;
            off += r;
        }
        in.close();
        String s = new String(b, StandardCharsets.UTF_8);
        return new JSONObject(s);
    }
}
