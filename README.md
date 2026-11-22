# SimplePS Android

> SimplePS 的 Android 版：**图片去背景 / 抠图 / 字体模仿 / 图片编辑**，纯 Kotlin 实现，离线可用。v2.0 全新深色编辑器界面。

桌面版（Windows EXE）见：<https://github.com/haoliying/SimplePS>

---

## 功能

- **自动去背景**：统计画面边缘颜色，从四边生长背景区域，保留同色主体。
- **颜色去背景**：自动取四角与四边中点作为背景采样点，按颜色距离去背。
- **矩形 / 笔刷抠图**：框选主体或绿/红笔刷标记后精细分割。
- **智能字体模仿**：框选图中一行文字并输入其内容 → 自动识别**字体 / 字号 / 倾斜角 / 颜色** → 输入新文字，点击图片任意位置合成。
- **图片变换**：缩放、任意角度旋转、90° 步进、水平/垂直翻转、矩形裁剪。
- **画布交互**：双指缩放 / 拖动平移，棋盘格透明背景显示。
- 结果保留透明通道，直接保存到相册 `Pictures/SimplePS`。

### 字体模仿怎么用

1. 底部切到「字体模仿」，点 **框选参考文字**，拖出图中那一行文字。
2. 输入图片上的文字内容，点 **分析样式**。
3. 系统识别字体/字号/倾斜/颜色（可手动微调）。
4. 输入新文字，点 **点击选位置** 在图上选点，再点 **合成文字**。
5. 保存到相册即可。

---

## 下载 APK

- Release：<https://github.com/haoliying/SimplePS-Android/releases>
- 或进入 **Actions → Build Android APK → Artifacts** 下载 `SimplePS-APK`。

安装：下载 `SimplePS.apk` 到手机，允许安装未知来源应用后点击安装。要求 Android 8.0（API 26）及以上。

---

## 本地构建

需要 JDK 17 + Android SDK（Platform 35、Build Tools 35.0.0）：

```bash
./gradlew assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

Android Studio 直接打开工程也可运行。

---

## 项目结构

```
app/src/main/java/com/simpleps/android/
├── MainActivity.kt              # Material 风格界面与交互
├── image/ImageProcessor.kt      # 去背景 / 抠图算法
├── image/FontRenderer.kt        # 字体渲染、字形检测、手写模仿
├── image/TextStyleAnalyzer.kt   # 从图片识别字体/字号/倾斜/颜色
├── image/Transforms.kt          # 缩放/旋转/裁剪/翻转/合成
└── ui/OverlayView.kt            # 画布 + 框选/笔刷/缩放平移/放置点
```

技术栈：Kotlin + Android View + Material Components，无 OpenCV、无网络模型依赖。

## License

MIT
