package com.youran.editor;

import android.net.Uri;

/**
 * 一个汉化分支(MVP 固定两个)：悠然汉化 / 害人汉化。
 * 记录显示名、仓库小字、可空 SAF Uri(绑定的 Localization 目录，持久化于 SharedPreferences)。
 * 当前为零 AndroidX 纯 Java 项目，Branch 仅作数据描述对象，不持有 Context。
 */
final class Branch {
    final String id;          // 持久化 key 用，如 "youran" / "hai"
    final String title;       // 侧栏标题：悠然汉化 / 害人汉化
    final String subtitle;    // 标题下小字：Terraria-YouranHans / TerrariaSinicization/害人汉化
    Uri safUri;               // 用户绑定到的 Localization 目录；未授权为 null

    Branch(String id, String title, String subtitle, Uri safUri) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.safUri = safUri;
    }

    /** 该分支在草稿库 / 落盘时的目录名(用于本地开发草稿库的定位)。 */
    String getJsonDirName() {
        // 文档：悠然=Localization 下的同目录名；害人=Localization/害人汉化。
        // 草稿库开发模式下以其 id 为子目录名即可，正式走 SAF 不依赖它。
        return id;
    }

    boolean hasSaf() {
        return safUri != null;
    }
}
