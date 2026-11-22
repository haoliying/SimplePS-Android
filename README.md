# SimplePS Android

> SimplePS 的 Android 版：**图片去背景 / 抠图 / 字体模仿**，纯 Kotlin 实现，离线可用。

桌面版（Windows EXE）见：<https://github.com/haoliying/SimplePS>

---

## 功能

- **自动去背景**：统计画面边缘颜色，从四边生长背景区域，保留同色主体。
- **颜色去背景**：自动取四角与四边中点作为背景采样点，按颜色距离去背。
- **矩形抠图**：在图片上拖拽框住主体，按框内外颜色分布分类。
- **笔刷抠图**：绿色笔刷标记保留区域、红色笔刷标记擦除区域，精细修正。
- **字体模仿**：扫描系统字体，支持标准排版（描边 / 渐变 / 阴影）与手写模仿（逐字旋转抖动）。
- 结果保留透明通道，直接保存到相册 `Pictures/SimplePS`。

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
├── MainActivity.kt              # 界面与交互
├── image/ImageProcessor.kt      # 去背景 / 抠图算法
├── image/FontRenderer.kt        # 字体渲染与手写模仿
└── ui/OverlayView.kt            # 图片显示 + 框选/笔刷
```

技术栈：Kotlin + Android View + Material Components，无 OpenCV、无网络模型依赖。

## License

MIT
