# 物料配方计算器 · 安卓 App

把 `物料配方计算器-手机版.html` 包成真正的手机 App：一个 WebView 壳，页面、图标、返回键、拍照、相册都接好了。

**说明**：这个工程是完整源码，但 APK 需要在有 JDK 17 + Android SDK 的机器上编译。下面第一条路（GitHub）不需要你本地装任何开发工具。

---

## 路线 A：GitHub 云端编译（不装任何东西，10 分钟出 APK）

1. 打开 https://github.com 注册/登录，右上角 **+ → New repository**，名字随便（例如 `formula-calc`），选 **Private** 也行，点 **Create repository**。
2. 在新建好的仓库页点 **uploading an existing file**。
3. 把 **本文件夹里的全部内容**（`app/`、`gradle/`、`.github/`、`build.gradle`、`settings.gradle`、`gradle.properties`、`.gitignore`）一起拖进网页 → **Commit changes**。
   - 关键：`.github/workflows/build-apk.yml` 必须一起上传，它就是"自动编译"的脚本。Windows 资源管理器里以点开头的文件夹不隐藏，直接拖即可。
4. 打开仓库顶部的 **Actions** 标签 → 会看到一个叫 **Build APK** 的任务在跑（约 3–5 分钟）→ 变成绿色 ✅。
5. 点进这次运行 → 页面底部 **Artifacts** → 下载 **formula-calc-apk.zip** → 解压得到 **app-debug.apk**。
6. 把这个 APK 发到手机安装。手机若提示"禁止安装未知来源应用"，在弹窗里选择允许（设置 → 应用 → 特殊权限 → 安装未知应用）。

没看到 Actions 在跑？点 **Actions → Build APK → Run workflow** 手动触发一次。

---

## 路线 B：Android Studio（本机有电脑时）

1. 装 [Android Studio](https://developer.android.com/studio)（自带 JDK 17 和 SDK）。
2. **Open** → 选择本文件夹 `formula-calc-android`（不是里面的 app 目录）→ 等 Gradle Sync 完成。
3. 菜单 **Build → Build Bundle(s)/APK(s) → Build APK(s)**。
4. 完成后点弹窗里的 **locate**，APK 在 `app/build/outputs/apk/debug/app-debug.apk`。
5. 手机开 USB 调试后可点 ▶ 直接运行；或把 APK 拷到手机安装。

> 如果 Sync 提示 Gradle wrapper 缺失：**File → Sync Project with Gradle Files** 会自动补齐；仍不行就到 **Settings → Build, Execution, Deployment → Gradle**，把 Gradle 版本指定为 **8.7**。

---

## 路线 C：不装 App，先当网页用

直接把这个文件发到手机浏览器打开就能算（计算完全离线，只是没有桌面图标）：

`物料配方计算器-手机版.html`

Safari「分享 → 添加到主屏幕」、Chrome「⋮ → 添加到主屏幕」，效果和 App 很接近。

---

## 工程结构

```
formula-calc-android/
├── app/
│   ├── build.gradle                       # 包名 com.dsh.formula，minSdk 24 / targetSdk 34
│   └── src/main/
│       ├── AndroidManifest.xml            # INTERNET、FileProvider、横竖屏不重建
│       ├── assets/index.html              # ← 就是那个手机版页面，改功能改这里
│       ├── java/com/dsh/formula/MainActivity.java
│       └── res/                           # 图标、主题、颜色、file_paths
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle / settings.gradle / gradle.properties
└── .github/workflows/build-apk.yml        # GitHub 自动编译
```

## 壳做了哪些事

- 用 `WebViewAssetLoader` 从 `https://appassets.androidplatform.net/assets/` 加载页面 → 页面处于**安全上下文**，`navigator.clipboard` 可用，跨域请求 CDN 不被拦（直接 `file:///android_asset` 会踩这两个坑）。
- 页面里 `<input type="file" capture="environment">` 被接管 → 直接调**系统相机**；「相册」按钮走 `ACTION_GET_CONTENT`。
- 返回键先退回网页历史，没有历史才退出 App。
- 横竖屏切换不重建 Activity，表格数据不丢。
- 草稿存在 `localStorage`（已开 DOM Storage），杀掉 App 再进来数据还在。

## 已知限制

- **拍照识别要联网**：识别引擎（Tesseract）和中文模型是从 CDN 下载的，首次约 15–20MB，之后有缓存。**计算、改数、看结果全部离线**；纯离线时可用"手打/粘贴 + 重新计算"。
- 网页版 OCR 精度不如原生 PaddleOCR（尤其手写和带表格线的照片）。所以识别原文一定先落在文本框里，改完点「重新计算」。
- `release` 包用的是 debug 签名，自己装着用没问题，**不要拿去上架应用商店**（需要自建 keystore 并改 `app/build.gradle` 里的 `signingConfig`）。
- 图标是占位矢量图，换成自己的 logo 只需改 `app/src/main/res/drawable/ic_launcher.xml`。
- 部分国产 ROM 首次拍照可能被系统权限拦下；如果确实调不起相机，在 `AndroidManifest.xml` 里加 `<uses-permission android:name="android.permission.CAMERA"/>` 并在 `MainActivity` 启动前动态申请。

## 改东西

| 想改什么 | 改哪里 |
|---|---|
| App 名称 | `res/values/strings.xml` 的 `app_name` |
| 包名 | `app/build.gradle` 的 `applicationId` + `namespace`，以及 Java 包路径 |
| 功能/界面/解析规则 | `app/src/main/assets/index.html` |
| 主题色 | `res/values/colors.xml` |
| 版本号 | `app/build.gradle` 的 `versionCode` / `versionName` |

## 如果编译报错

这套配置按 **AGP 8.5.2 + Gradle 8.7 + JDK 17 + compileSdk 34** 写的。Sync/编译失败时把 `Build` 窗口的红色报错贴出来，可以直接定位（最常见的两种：JDK 不是 17、Android SDK 34 没装）。
