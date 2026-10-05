# p2pRemote Android 客户端

[English](README.md) | 简体中文

p2pRemote Android 客户端基于 wgvpn 架构，通过 Android `VpnService` 创建虚拟网卡。客户端运行用户态 WireGuard，通过 gonc 加密 UDP 数据面传输数据，建立与桌面端一致的三层 VPN 隧道。

Android 客户端仅能发起连接：可以连接桌面设备，不能接收其他设备发起的连接。

## 官方链接

- 官网：<https://www.p2premote.top>
- 下载页：<https://www.p2premote.top/#download>
- GitHub 组织：<https://github.com/p2premote>
  - 桌面客户端：[p2premote-desktop-client](https://github.com/p2premote/p2premote-desktop-client)

## 架构

### 打洞层

`punch-native/` 提供 Rust JNI 库 `libp2premote_punch_jni.so`，封装 Rust 版 gonc。源码依赖 [p2premote-punch-rs](https://github.com/p2premote/p2premote-punch-rs)，需将该仓库检出到与本仓库同级的目录。Gradle 的 `preBuild` 任务会自动构建 JNI 库。

`PunchNative` 提供 Exchange、StartUdpTunnel、StopUdpTunnel 和 protect 回调。

### WireGuard 数据面

`libwgmobile.aar` 通过 gomobile 绑定 wireguard-go 用户态实现。AAR 不存入仓库，需要自行构建。

`libwgmobile` 提供 WgStart、WgAddPeer、WgRemovePeer 和 WgStop。源码位于 `p2premote-wg-ffi/mobile/libwgmobile`。

### 应用层

- `WgvpnService`：结合 VpnService 和 ForegroundService，执行主动连接的 9 步流程，负责断开连接时的清理、protect 回调和状态广播。
- `MainActivity`：处理 VPN 授权、显示虚拟 IP，并管理隧道状态机。

## 系统要求

- 最低版本：minSdk 29（Android 10）。系统原生支持 TLS 1.3，无需 Conscrypt 兼容层。
- 支持的 ABI：arm64-v8a 和 x86_64。

## 构建

### 1. 构建 libwgmobile AAR

首次构建 APK 前，必须先生成 `libwgmobile.aar`。该文件体积较大，且需随 wireguard-go 的变更重新生成，因此不存入仓库。

将源码仓库 [p2premote-wg-ffi](https://github.com/p2premote/p2premote-wg-ffi) 检出到与本仓库同级的目录。构建需要 Go 1.25+、gomobile 和 Android NDK，并需配置 `ANDROID_HOME`。具体要求见构建脚本头部说明。

从本仓库根目录执行以下命令，用 gomobile 构建 AAR，并复制到应用依赖目录：

```bash
cd ../p2premote-wg-ffi
./build-android-aar.sh
# 产物：p2premote-wg-ffi/libwgmobile.aar
cp libwgmobile.aar ../p2premote-android-client/app/libs/libwgmobile.aar
```

### 2. 构建 APK

确认 AAR 已按上一步放到应用依赖目录。打洞层 Rust JNI 库由 `preBuild` 任务自动交叉编译，脚本为 `scripts/build-punch-native.ps1`，需要 Rust 和 Android NDK。

从这些仓库的父目录执行：

```bash
cd p2premote-android-client
./gradlew assembleDebug
```

## 运行

从本仓库根目录安装并启动应用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.p2premote.android/.MainActivity
```

## CI 与发布

推送到 main 分支会触发 GitHub Actions 构建 APK。版本号由 `gradle.properties` 中的 `p2premoteBaseVersion` 和短提交号组成。

推送 `v*` 标签（例如 `v1.0.1`）时，会按标签版本构建并自动发布 Release。APK 可在 [Releases 页面](https://github.com/p2premote/p2premote-android-client/releases)下载。
