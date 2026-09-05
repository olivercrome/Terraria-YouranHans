package com.youran.editor;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SAF (Storage Access Framework) helper: remembers a user-chosen tree
 * (ACTION_OPEN_DOCUMENT_TREE) with persistable permission and exposes its
 * .json children for read/write via ContentResolver + DocumentsContract.
 */
final class SafDir {
    private static final String PREFS = "saf_prefs";
    private static final String KEY_TREE = "tree_uri";

    /** Restore the previously granted tree Uri, else null. */
    static Uri loadTree(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE, null);
        return s == null ? null : Uri.parse(s);
    }

    /** Remember a tree Uri after takePersistableUriPermission. */
    static void saveTree(Context ctx, Uri tree) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TREE, tree.toString()).apply();
    }

    /** Forget the saved tree (permission lost / user switched folders). */
    static void clearTree(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_TREE).apply();
    }

    // ---- listing ---------------------------------------------------------

    /** Names of *.json files directly under the granted tree, sorted. */
    static List<String> listJson(Context ctx, Uri tree) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        List<String> names = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null);
            if (c == null) throw new IOException("无法枚举目录：无返回");
            while (c.moveToNext()) {
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name != null && name.toLowerCase().endsWith(".json")
                        && (mime == null || mime.startsWith("application/json")
                            || mime.startsWith("text/")
                            || "application/octet-stream".equals(mime))) {
                    names.add(name);
                }
            }
        } finally {
            if (c != null) c.close();
        }
        Collections.sort(names);
        return names;
    }

    /** Resolve document id of a direct child by display name. */
    private static String childDocId(Context ctx, Uri tree, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        ContentResolver cr = ctx.getContentResolver();
        Cursor c = null;
        try {
            c = cr.query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c == null) throw new IOException("无法查询目录内容");
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return c.getString(0);
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ---- read / write ----------------------------------------------------

    /** Read a child json file's full UTF-8 text. */
    static String readFile(Context ctx, Uri tree, String name) throws Exception {
        String docId = childDocId(ctx, tree, name);
        if (docId == null) throw new IOException("目录下找不到文件: " + name);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (InputStream in = ctx.getContentResolver().openInputStream(doc)) {
            if (in == null) throw new IOException("无法打开: " + name);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int r;
            while ((r = in.read(tmp)) >= 0) buf.write(tmp, 0, r);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("读取失败: " + name + " - " + e.getMessage());
        }
    }

    /** Overwrite a child json file with the given UTF-8 text. */
    static void writeFile(Context ctx, Uri tree, String name, String text) throws Exception {
        String docId = childDocId(ctx, tree, name);
        if (docId == null) throw new IOException("目录下找不到文件: " + name);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (OutputStream out = ctx.getContentResolver().openOutputStream(doc, "wt")) {
            if (out == null) throw new IOException("无法写入: " + name);
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new IOException("保存失败: " + name + " - " + e.getMessage());
        }
    }

    // ---- rename / delete -------------------------------------------------

    /** Rename a direct child json file inside the granted tree. */
    static boolean renameFile(Context ctx, Uri tree, String oldName, String newName) throws Exception {
        String docId = childDocId(ctx, tree, oldName);
        if (docId == null) throw new IOException("目录下找不到文件: " + oldName);
        // 目标已存在则拒绝(避免覆盖)
        if (!oldName.equals(newName) && childDocId(ctx, tree, newName) != null) {
            throw new IOException("目标已存在: " + newName);
        }
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Uri renamed = DocumentsContract.renameDocument(ctx.getContentResolver(), doc, newName);
        return renamed != null;
    }

    /** Delete a direct child json file inside the granted tree. */
    static boolean deleteFile(Context ctx, Uri tree, String name) throws Exception {
        String docId = childDocId(ctx, tree, name);
        if (docId == null) return false;
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        return DocumentsContract.deleteDocument(ctx.getContentResolver(), doc);
    }

    // ---- display ---------------------------------------------------------

    /** Best-effort readable name for the granted tree (shown in title). */
    static String treeDisplayName(Context ctx, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Cursor c = ctx.getContentResolver().query(doc,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String n = c.getString(0);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) { }
        String p = tree.getLastPathSegment();
        return p != null ? p : "SAF 目录";
    }
}
