package com.youran.editor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** JSON parse + schema validation helpers. */
final class JsonModel {

    static final class JsonFile { final String name; final String path;
        JsonFile(String n, String p){ name=n; path=p; } }

    static final class Node { final String key; final Object value; final boolean leaf; final String display;
        Node(String k, Object v){ key=k; value=v; leaf=!(v instanceof JSONObject||v instanceof JSONArray);
            display=preview(v); } }

    static final class Parse { final Object json; final String err;
        Parse(Object j,String e){ json=j; err=e; } }

    static String preview(Object v){
        if(v==null) return "null";
        if(v instanceof JSONObject) return "{...} · "+((JSONObject)v).length()+" 项";
        if(v instanceof JSONArray) return "[...] · "+((JSONArray)v).length()+" 项";
        String s=String.valueOf(v); return s.length()>60? s.substring(0,57)+"…" : s;
    }
    
    static Parse parse(String text){
        if(text==null||text.trim().isEmpty()) return new Parse(null,"文件为空");
        try{ Object j=new JSONTokener(text).nextValue();
            return j==null? new Parse(null,"不是有效的 JSON") : new Parse(j,null);
        }catch(JSONException e){ return new Parse(null,"JSON 解析失败: "+e.getMessage()); }
    }




    private static Object toLiteral(String s) {
        if (s == null) return JSONObject.NULL;
        if (s.trim().equals("null")) return JSONObject.NULL;
        try { Object o = new JSONTokener(s).nextValue(); return o == null ? s : o; }
        catch (JSONException e) { return s; }
    }

    /** Validate placeholder syntax in a translatable string. null = ok, else message. */
    static String validateValue(String s) {
        if (s == null) return null;
        String m = balanced(s, '{', '}'); if (m != null) return m;
        m = balanced(s, '<', '>'); if (m != null) return m;
        int n = s.length();
        for (int i = 0; i < n; i++) if (s.charAt(i) == '%') {
            if (i + 1 >= n) return "末尾孤立的 % 占位符";
            char c = s.charAt(i + 1);
            boolean ok = c == '%' || c == 's' || c == 'd' || c == 'f' || c == 'i' || c == 'n'
                    || c == 'c' || (c >= '0' && c <= '9');
            if (!ok) return "疑似误用的 % 占位符(后跟 " + c + ")";
        }
        return null;
    }

    private static String balanced(String s, char o, char c) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) d++;
            else if (ch == c) { d--; if (d < 0) return "多余的 " + c + " 符号"; }
        }
        return d != 0 ? "不匹配的 " + o + "/" + c + " 数量" : null;
    }
}