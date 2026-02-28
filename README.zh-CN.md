# LiveAssassin

[English README](./README.md)

LiveAssassin 是一个 Android USB 采集卡视频/音频预览应用。

## 功能特性
- USB 采集卡视频预览（UVC）
- USB 采集卡音频回放
- 全屏沉浸模式
- 前置摄像头画中画（PIP）叠加，可调位置和大小
- 采集分辨率选择（默认最高可用分辨率）
- 按架构 CI 分包构建（`arm64-v8a`、`armeabi-v7a`）

## 环境要求
- Android Studio (Hedgehog 及以上推荐)
- JDK 11
- Android 手机支持 OTG / USB Host
- USB 视频采集卡（UVC）

## 快速开始
1. 使用 Android Studio 打开项目。
2. 首次运行会下载依赖（需要联网）。
3. 通过 OTG 连接手机与 USB 采集卡。
4. 启动应用并授权：
- 相机权限
- 麦克风权限
- USB 设备权限（系统弹窗）

## 本地构建
单架构 Debug APK：

```bash
./gradlew :app:assembleDebug -PtargetAbi=arm64-v8a
./gradlew :app:assembleDebug -PtargetAbi=armeabi-v7a
```

单架构 Release APK：

```bash
./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a
./gradlew :app:assembleRelease -PtargetAbi=armeabi-v7a
```

Release Bundle（AAB）：

```bash
./gradlew :app:bundleRelease
```

## 使用说明
1. 将采集卡通过 OTG 连接手机。
2. 点击 `开始采集`。
3. 需要全屏时点击 `旋转全屏`，点击预览区域可隐藏/显示悬浮控制。
4. 若需要前置摄像头叠加，打开 `前置 PIP`，并通过滑条调整位置/大小。

## 重要说明
- 当前 `targetSdk` 设置为 `33`，用于兼容依赖中 USB 广播注册行为。
- 音频回放优先尝试路由到蓝牙输出设备，其次有线耳机，最后手机扬声器。

## 架构与产物
- 支持架构：`arm64-v8a`、`armeabi-v7a`
- Debug 流水线产物：
- `app-debug-arm64-v8a.apk`
- `app-debug-armeabi-v7a.apk`
- Release 流水线产物：
- `app-release-arm64-v8a.apk`
- `app-release-armeabi-v7a.apk`
- `app-release.aab`

## 发布版本
创建并推送版本标签：

```bash
git tag v1.0.1
git push origin v1.0.1
```

推送标签后会触发 Release 流水线并上传 APK/AAB。

## 开源协议
本项目采用 [Apache License 2.0](./LICENSE)。
