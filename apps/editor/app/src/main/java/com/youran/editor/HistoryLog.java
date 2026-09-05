package com.youran.editor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 保存历史(diff)记录：每次 [保存] 时把「这一次相对「打开该文件时」到底改了哪些 key 的 旧→新」
 * 记下来(粒度可只到发生变化的叶子 key)；一个 key 在保存前多次改只记最后一次差异(本来就只记最终一次提交差异)。
 * “上层不记录”：不额外把容器整体当一大段存起来，只拿 key 名当标题 + 旧/新两段做展示。
 * 数据在内存(session)；刷新即清空。供“🔍左侧的记录按钮”查看。
 */
public class HistoryLog {
    /** 一条记录：发生在 file 的某个上层(upper 拼接展示用)里的某 key，新旧内容。 */
    public static class Entry {
        public String file;
        public String upper;        // 展示用：该 key 所属上层的名字(可多层用 / 连接，""=顶层)
        public String key;
        public String old_;
        public String now;
        Entry(String f, String u, String k, String o, String n) { file = f; upper = u; key = k; old_ = o; now = n; }
    }

    /** 打开某文件时打入基线(等于「打开时」)；随后每次保存 diff 相对它。 */
    public static void noteOpen(String file, JSONObject root) {
        try { base.put(file, cloneObj(root)); }
        catch (Exception ignored) { }
    }

    /** 保存成功后被调用：收集从 base 到当前 root 有变化的叶子 key 并追加；随后刷新基线。 */
    public static void commitSave(String file, JSONObject root) {
        try {
            JSONObject b = base.get(file);
            List<Entry> got = new ArrayList<>();
            if (b != null) diffWalk(file, new ArrayList<String>(), b, root, got);
            log.addAll(got);          // 次序=便查看的排列(按文件再上层)
            // 刷新基线供下次保存继续比较
            base.put(file, cloneObj(root));
        } catch (Exception ignored) { }
    }

    public static List<Entry> all() { return log; }

    public static void clear() { log.clear(); base.clear(); }

    // ----------------------------------------------------------------- private

    private static final List<Entry> log = new ArrayList<>();
    private static final Map<String, JSONObject> base = new TreeMap<>();

    private static JSONObject cloneObj(JSONObject o) throws Exception {
        return new JSONObject(o.toString());
    }

    /** 深比两棵 JSON 结构；cont 是到当前节点的路径段。只对叶子(原始值)差异记录。 */
    private static void diffWalk(String file, List<String> cont,
                                 JSONObject bi, JSONObject cur, List<Entry> out) {
        Iterator<String> it = bi.keys();
        Set<String> all = new HashSet<>();
        while (it.hasNext()) all.add(it.next());
        it = cur.keys();
        while (it.hasNext()) all.add(it.next());
        for (String k : all) {
            Object b = opt(bi, k), c = opt(cur, k);
            boolean bObj = b instanceof JSONObject;
            boolean cObj = c instanceof JSONObject;
            boolean bArr = b instanceof JSONArray;
            boolean cArr = c instanceof JSONArray;
            if ((bObj && cObj) || (bArr && cArr)) {
                // 同位同型递归下去对叶子
                if (bObj) {
                    List<String> sub = new ArrayList<>(cont); sub.add(k);
                    diffWalk(file, sub, (JSONObject) b, (JSONObject) c, out);
                }
                continue;
            }
            // —— 记一条叶子差异(旧/新)，upper=该叶子所在的容器路径(即 cont 这段祖先) ——
            String ov = real(b);
            String nv = real(c);
            if (!ov.equals(nv)) {
                out.add(new Entry(file, join(cont), k, ov, nv));
            }
        }
    }

    /** 把祖先 path 渲染成展示标题(“a / b”)，空则为顶层。 */
    private static String join(List<String> cont) {
        if (cont == null || cont.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cont.size(); i++) { if (i > 0) sb.append(" / "); sb.append(cont.get(i)); }
        return sb.toString();
    }

    private static String real(Object o) {
        return o == null ? "（无）" : render(o);
    }

    private static Object opt(JSONObject o, String k) {
        try { return o.has(k) ? o.get(k) : null; } catch (Exception e) { return null; }
    }

    private static String render(Object o) {
        if (o == null) return "null";
        if (o instanceof JSONObject || o instanceof JSONArray) return o.toString();
        return String.valueOf(o);
    }

    // suppress-unused guard already satisfied by callers
}
