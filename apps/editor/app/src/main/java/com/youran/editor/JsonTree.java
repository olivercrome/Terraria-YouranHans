package com.youran.editor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 内存 JSON 对象树封装：负责对一个已打开的 .json 文件做"当前容器层"的
 * 浏览 + 编辑(改 key / 删 key / 新建 key / 改叶值) + 脏标记 + 导出文本。
 *
 * 设计要点：
 *  - 画面每次只展示一个"当前容器"(JSONObject)，即 segPath 指向的那层；
 *    本类多数 API 以 containerPath(List&lt;String&gt;) 定位容器。
 *  - 顶层容器 = root 本身，containerPath 为空列表。
 *  - 数组按叶子显示(汉化内容无数组编辑需求)，如需展开可后续扩展。
 *  - 本项目零 AndroidX、纯 Java，故本类只依赖 org.json（安卓内置/开源同一版），
 *    便于脱离 Android 用 JVM 做真实单测。
 */
final class JsonTree {
    private final JSONObject root;   // 整份文件的根对象
    private boolean dirty = false;   // 本次会话内是否有未保存改动

    private JsonTree(JSONObject root) {
        this.root = root;
        if (!(root instanceof JSONObject)) {
            throw new IllegalArgumentException("根必须是 JSON 对象");
        }
    }

    // ---- 解析 ------------------------------------------------------------

    /** 从文件文本解析，返回树；文本非法时返回 null(错误信息放 errOut)。 */
    static JsonTree fromText(String text, StringBuilder errOut) {
        if (text == null || text.trim().isEmpty()) {
            errOut.append("文件为空");
            return null;
        }
        try {
            Object parsed = new JSONTokener(text).nextValue();
            if (!(parsed instanceof JSONObject)) {
                errOut.append("仅支持顶层为 JSON 对象的文件");
                return null;
            }
            try {
                return new JsonTree((JSONObject) parsed);
            } catch (IllegalArgumentException e) {
                errOut.append(e.getMessage());
                return null;
            }
        } catch (JSONException e) {
            errOut.append("JSON 解析失败: ").append(e.getMessage());
            return null;
        }
    }

    // ---- 访问 ------------------------------------------------------------

    boolean isDirty() { return dirty; }

    /** 保存成功后调用：清除本次会话的脏标记(右上角保存按钮随之置灰)。 */
    void clearDirty() { dirty = false; }

    JSONObject rootObject() { return root; }

    /** 沿 containerPath 得到当前容器对象；路径为空返回 root。找不到返回 null。 */
    JSONObject containerAt(List<String> containerPath) {
        JSONObject cur = root;
        if (containerPath == null) return cur;
        for (String k : containerPath) {
            Object o = cur.opt(k);
            if (o instanceof JSONObject) cur = (JSONObject) o;
            else return null;
        }
        return cur;
    }

    /** 当前容器所有叶子 key 的字典序排列。 */
    List<String> sortedKeys(JSONObject container) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = container.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }

    boolean isObjectValue(JSONObject container, String key) {
        return container.opt(key) instanceof JSONObject;
    }

    boolean isArrayValue(JSONObject container, String key) {
        return container.opt(key) instanceof JSONArray;
    }

    /** 叶子值的展示文本(字符串原样/对象/数组/数字/bool/null)。 */
    String displayOf(JSONObject container, String key) {
        Object v = container.opt(key);
        if (v == null) return "null";
        return JsonModel.preview(v);
    }

    // ---- 编辑操作(全部标脏) ---------------------------------------------

    /** 改某 key(冒号左边)。新名不能为空、不能与同层其它 key 撞名。返回错误或 null。 */
    String renameKey(JSONObject container, String oldKey, String newKey) {
        if (newKey == null || newKey.trim().isEmpty()) return "key 不能为空";
        if (!container.has(oldKey)) return "原 key 不存在: " + oldKey;
        if (!oldKey.equals(newKey) && container.has(newKey)) {
            return "同层已存在 key: " + newKey;
        }
        // android.jar 内置 org.json：remove 不抛 JSONException(返回 Object)
        Object v = container.remove(oldKey);
        if (v == null) v = JSONObject.NULL;
        try {
            container.put(newKey, v);
        } catch (JSONException e) {
            try {
                container.put(oldKey, v); // 回滚
            } catch (JSONException e2) { /* 极端情况，忽略 */ }
            return e.getMessage();
        }
        dirty = true;
        return null;
    }

    /** 删除某 key。返回错误或 null。 */
    String deleteKey(JSONObject container, String key) {
        if (!container.has(key)) return "key 不存在: " + key;
        container.remove(key);
        dirty = true;
        return null;
    }

    /** 在当前容器末尾/字典序位新增一个 key。值由 valueText 自动推断类型。 */
    String addKey(JSONObject container, String key, String valueText) {
        if (key == null || key.trim().isEmpty()) return "key 不能为空";
        if (container.has(key)) return "同层已存在 key: " + key;
        Object lit;
        try {
            lit = parseValue(valueText);
        } catch (JSONException e) {
            return "值不是合法 JSON: " + e.getMessage();
        }
        try {
            container.put(key, lit);
        } catch (JSONException e) {
            return e.getMessage();
        }
        dirty = true;
        return null;
    }

    /** 在当前容器上新建一个「空对象壳」key(便于随后一层层下钻再填其内容)。返回错误或 null。 */
    String addObjectShell(JSONObject container, String key) {
        if (key == null || key.trim().isEmpty()) return "key 不能为空";
        if (container.has(key)) return "同层已存在 key: " + key;
        try {
            container.put(key, new JSONObject());
        } catch (JSONException e) {
            return e.getMessage();
        }
        dirty = true;
        return null;
    }

    /** 修改叶子值(冒号右边)。若原是对象则不允许由此改成文本(防误删子树)。 */
    String setLeaf(JSONObject container, String key, String valueText) {
        Object old = container.opt(key);
        if (old == null) return "key 不存在: " + key;
        if (old instanceof JSONObject || old instanceof JSONArray) {
            // 允许把对象整体替换成一个新 JSON 文本(用户可能有意换成对象/别的)，
            // 但为安全校验用户输入的必须是合法 JSON。
            Object lit;
            try {
                lit = parseValue(valueText);
            } catch (JSONException e) {
                return "值不是合法 JSON: " + e.getMessage();
            }
            try {
                container.put(key, lit);
            } catch (JSONException e) {
                return "值不是合法 JSON: " + e.getMessage();
            }
            dirty = true;
            return null;
        }
        // 普通叶子：译文串，若用户只填纯文本不自动转成对象
        Object lit;
        try {
            lit = parseValueQuiet(valueText);
        } catch (JSONException e) {
            return "值不是合法 JSON: " + e.getMessage();
        }
        try {
            container.put(key, lit);
        } catch (JSONException e) {
            return "值不是合法 JSON: " + e.getMessage();
        }
        dirty = true;
        return null;
    }

    // ---- 值文本 -> 类型化 JSON 值 -----------------------------------------

    /**
     * 把“叶子值输入框”的文本转为可放入 JSON 的标量值，规则只做标量：
     *   "true"/"false"/"null" → 布尔 / JSONObject.NULL
     *   纯数字 → number
     *   其它一律当普通字符串(保留原样，含 {$...}、[color=..]、换行…)。
     * 对象/数组不适合由一段文本“猜”出来，需要用「＋ 对象壳」/「＋数组壳」构造，
     * 以免把 {$LegacyMenu.58} 这类本地化原文误判成 JSON 对象卡住保存。
     */
    private static Object parseValueQuiet(String s) throws JSONException {
        if (s == null) return JSONObject.NULL;
        String t = s.trim();
        if (t.isEmpty()) return "";
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        if (t.equals("null")) return JSONObject.NULL;
        // 尝试数字
        try {
            if (t.matches("-?\\d+(\\.\\d+)?")) {
                if (t.indexOf('.') >= 0) return Double.valueOf(t);
                return Long.valueOf(t);
            }
        } catch (NumberFormatException ignored) { }
        return s; // 原样字符串(保留换行等)
    }

    private static Object parseValue(String s) throws JSONException {
        return parseValueQuiet(s);
    }

    // ---- 导出 ------------------------------------------------------------

    /** 导出 4 空格缩进 JSON 文本(与源汉化文件风格一致)，供保存写盘。 */
    String dump() {
        // android.jar 内置 org.json 的 toString(indent) 声明抛 JSONException
        try {
            return root.toString(4);
        } catch (JSONException e) {
            // 正常不应发生：树内对象结构合法时才序列化
            return "{}";
        }
    }
}
