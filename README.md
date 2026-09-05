# Terraria-YouranHans

泰拉瑞亚（Terraria）× 悠然汉化 一体化仓库

本仓库同时维护两大部分：

- **`Localization/`** —— 汉化文本（物品 / NPC / 界面等 JSON 翻译），供替换进游戏资源包或配合构建工具使用。
- **`apps/editor/`** —— **YouranEditor**：为这份本地化而生的便携式安卓 JSON 汉化编辑器（App 本体源码 + 打包脚本），可在手机上直接编辑这些翻译文件。

---

## 版本信息
- **本地化当前版本**：1.4.5.8.5（+ 官汉）　· 表示目前自制汉化进度；日期：2026-09-05
- 详见各区 README。

## 目录结构
```
Terraria-YouranHans/
├─ Localization/       汉化文本(物品/NPC/界面)
├─ apps/
│  └─ editor/          YouranEditor 安卓汉化编辑器源码
└─ README.md
```

## 分区说明
- `Localization/` – 所有语言 JSON 文件。
  - 使用方式见根 [使用方式](#使用方式)。
- `apps/editor/` – 你可以在 PC/Android 上构建的编辑器 App（纯 framework + 无需外部后端）。
  - 构建 / 用法请看 `apps/editor/README.md`。

---

## 使用方式（汉化包）
把 `Localization` 目录下对应的 JSON 替换进游戏资源，或使用构建工具（字体打包链在
`TerrariaSinicization` 仓库）自动合并后再替换。

## 使用方式（Editor，可选）
更推荐用 `apps/editor` 的安卓编辑器源码直接展开/校对汉化：
```
# 可复用编辑器 App 在本机被当作“分支 + json 页 + 收藏定位”工作台
# 详见 apps/editor/README.md
```

---

## 构建 YouranEditor（快速）
该编辑器是纯 Android 工程，需要 JDK 17 与 Android SDK 34：
```
cd apps/editor
export JAVA_HOME=/path/to/openjdk-17
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/app-debug.apk
```
> 目标是 No-AndroidX/No-Dex 之外的小体积单机工具；当前默认保留 androidx 依赖方便主题，
> 若只需要 dex 单文件可在 release 开 minify 或换纯 framework 版（见 apps/editor/README.md）。

---

## 贡献
欢迎提交翻译修正或补充、或改进编辑器。如有问题请开 Issue。
