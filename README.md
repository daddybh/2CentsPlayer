# 2Cents Player

一个简洁优雅的音乐播放器应用，支持多平台。

## 平台支持

- ✅ Android (Kotlin + Jetpack Compose)
- 🔜 iOS (Coming soon)

## 项目结构

```
2CentsPlayer/
├── android/          # Android 应用
│   ├── app/          # 应用模块
│   │   └── src/main/java/com/twocents/player/
│   │       ├── MainActivity.kt          # 入口 Activity
│   │       ├── data/Models.kt           # 数据模型
│   │       └── ui/
│   │           ├── PlayerScreen.kt      # 播放器页面 UI
│   │           ├── PlayerViewModel.kt   # 播放状态管理
│   │           └── theme/               # Material3 主题
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── README.md
```

## 功能

- 🎵 播放/暂停控制
- ⏮ 上一曲 / ⏭ 下一曲
- ❤️ 红心收藏
- 🔍 网易云网页搜索
- 🎨 深色模式 + 渐变动效

## 开发环境

- Android Studio Ladybug 或更新版本
- Kotlin 2.1+
- Jetpack Compose (BOM 2024.12)
- MinSDK 26 / TargetSDK 35

## 构建运行

```bash
cd android
./gradlew assembleDebug
```
