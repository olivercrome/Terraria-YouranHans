# YouranEditor（apps/editor）

面向「泰拉瑞亚/悠然汉化」的**便携式安卓 JSON 汉化编辑器**（主单机工具）。

一手在手机上把 `Localization/*.json` 当"工作台"来维护：以**分支 + json 页 + 收藏定位**组织，
支持原位编辑 key、高亮大/对象逐层下钻、批量入库、保存快照历史。

---

## 功能一览（当前版本 v2.2）
- **分支 / 文件页**：本地草稿分支 + SAF 目录绑定（不强制），进入就列出其下 `.json`。
- **新建空分支 / 从原版模板展开**：可从内置 `original-zh-Hans.zip` 一键铺出模板 json。
- **原生 JSON 树编辑器**：
  - 对象/数组逐层下钻渲染，key/value 原位编辑；
  - 「新建 key」两种模式：**直接给值** / **对象壳（自动下钻续填）**；
  - 取色帮助、`[i:itemID]` 配对、行内 `/` 换行等小工具。
- **⭐ 收藏定位**：整文件 / 具体 key 都可收藏，按分支→文件→key 自动归类；删除分支会自动清空其孤儿收藏。
- **🔍 搜索下钻**：字符串命中直达该行（ListView.setSelection），不翻屏。
- **🕘 保存历史**：同 key 的差异（旧→新）自动留痕，红划线旧 / 绿新，按文件分组查看。
- **批量入库**：空分支一键「导入文件夹」（拷入目录下所有 json）或「自选 JSON」(多选) 批量拷入。
- **外观**：深/浅主题即时切换、可选莫奈/随机配色、历史内页卡片化等。
- **纯本地**：数据以本地文件为主；不依赖账号/网络（模板只在首次选择时读取 assets）。

---

## 目录结构
```
apps/editor/
├─ app/
│  ├─ src/main/
│  │  ├─ java/com/youran/editor/
│  │  │  ├─ MainActivity.java  主界面
│  │  │  ├─ Branch / RepoRegistry    分支模型与本地扫描
│  │  │  ├─ JsonTree / JsonModel / SafDir  树句柄与 SAF/草稿存取
│  │  │  ├─ Skin / UiKit             语义色 & 纯 View 制造器
│  │  │  ├─ HistoryLog / ItemIdDb / ScrollThumb
│  │  │  └─ ...（其余工具类）
│  │  └─ res/  布局/资源（含 assets/original-zh-Hans.zip 模板包）
│  ├─ build.gradle
├─ build.gradle / settings.gradle / gradlew(.bat)  Gradle 8.7 · AGP 8.x
└─ gradle.properties
```

## 技术要点
- **纯 Java，Android 原生 + 少量 androidx**（appcompat/material 可选，去掉即可回纯 framework 单 dex 版）。
- minSdk 26 / target 34；编译 JDK 17。
- 主界面一个大 `MainActivity` + 一批小 helper 类（便于真机免联网单机）。

---

## 构建要求
- JDK 17（Termux：`pkg install openjdk-17`，`export JAVA_HOME=$PREFIX/usr/lib/jvm/java-17-openjdk`）
- Android SDK 34；构建时若需 aapt2 override：
  ```bash
  echo "android.aapt2FromMavenOverride=/usr/bin/aapt2" >> gradle.properties  # Termux 需要时
  ```
- 打包产物：
  ```bash
  cd apps/editor
  ./gradlew assembleDebug
  ls app/build/outputs/apk/debug/app-debug.apk
  ```

## 备注 / 关联
- 汉化文本本体与该包分开存放在**仓库根 `Localization/`**；编辑器可把分支绑到本地真机上的该目录（或先用草稿/批量导入体验）。
- 字体打包 / 二进制解析等 Windows 链路见独立的 `TerrariaSinicization` 仓库。

## License / 贡献
编辑器功能欢迎提 Issue / 改进。汉化内容行为遵循仓库根 README 贡献说明。
