# p2pRemote Android

基于 wgvpn 架构的 Android 客户端：通过 Android `VpnService` 建立虚拟网卡，
运行 userspace WireGuard + gonc 加密 UDP 数据面，实现与桌面端一致的三层 VPN 隧道。

Android 是仅发起端：可以连接桌面设备，但不能接收其他设备发起的连接。

## 架构

- **打洞层**（`punch-native/`，Rust JNI 库 `libp2premote_punch_jni.so`）：
  - Rust 版 gonc（`p2premote-punch-rs-gonc`）的 JNI 绑定，由 Gradle `preBuild` 自动构建
  - `PunchNative`：Exchange / StartUdpTunnel / StopUdpTunnel / protect 回调
- **WG 数据面**（`libwgmobile.aar`，gomobile 绑定，不入库）：
  - `libwgmobile`：wireguard-go userspace 绑定（WgStart / WgAddPeer / WgRemovePeer / WgStop），
    源码位于 `p2premote-wg-ffi/mobile/libwgmobile`
- **应用层**：
  - `WgvpnService`：VpnService + ForegroundService，完整 9 步 active 建链 / 断链清理 / protect 回调 / 状态广播
  - `MainActivity`：VPN 授权流程 + 虚拟 IP 展示 + 隧道状态机
- **minSdk 29**（Android 10）：系统原生 TLS 1.3，无需 Conscrypt 兼容层
- **ABI**：arm64-v8a + x86_64

## 构建

### 1. 构建 libwgmobile AAR（首次必做）

AAR 不入库（体积大、随 wireguard-go 改动频繁重生成），需先用 gomobile 构建：

```bash
cd ../p2premote-wg-ffi
./build-android-aar.sh
# 产物：p2premote-wg-ffi/libwgmobile.aar
cp libwgmobile.aar ../p2premote-android-client/app/libs/libwgmobile.aar
```

前置依赖：Go 1.25+、gomobile、Android NDK、`ANDROID_HOME` 已配置。详见脚本头部说明。

### 2. 构建 APK

打洞层 Rust JNI 库由 `preBuild` 任务自动交叉编译（`scripts/build-punch-native.ps1`，
需要 Rust + Android NDK）；WG AAR 按上一步手动放置后：

```bash
cd p2premote-android-client
./gradlew assembleDebug
```

## 运行

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.p2premote.android/.MainActivity
```

## 已知限制

- **WG 私钥 / token 明文存储**于 SharedPreferences，与桌面端一致；加固见 `SessionStore` 注释。
- **TUN 未配置 DNS**（`addDnsServer`）：RDP/VNC 按虚拟 IP 直连不受影响，对端 LAN 内域名暂不可解析。
- **真机联网验证**：建链/打洞/握手的端到端验证需联网真机 + 对端桌面（见 `.codex-tasks/android-wgvpn-client/PROGRESS.md`）。
