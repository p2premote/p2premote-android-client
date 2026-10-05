# p2pRemote Android Client

English | [简体中文](README_zh.md)

The p2pRemote Android client uses the wgvpn architecture and creates a virtual network interface through Android's `VpnService`. It runs userspace WireGuard and carries traffic over the gonc encrypted UDP data plane, providing the same layer-3 VPN tunnel as the desktop client.

The Android client can only initiate connections. It can connect to desktop devices but cannot accept connections from other devices.

## Official Links

- Website: <https://www.p2premote.top>
- Downloads: <https://www.p2premote.top/#download>
- GitHub organization: <https://github.com/p2premote>
  - Desktop client: [p2premote-desktop-client](https://github.com/p2premote/p2premote-desktop-client)

## Architecture

### NAT Traversal Layer

`punch-native/` provides the Rust JNI library `libp2premote_punch_jni.so`, which wraps the Rust version of gonc. It depends on [p2premote-punch-rs](https://github.com/p2premote/p2premote-punch-rs). Check out that repository alongside this one. Gradle's `preBuild` task builds the JNI library automatically.

`PunchNative` provides Exchange, StartUdpTunnel, StopUdpTunnel, and the protect callback.

### WireGuard Data Plane

`libwgmobile.aar` uses gomobile bindings for the wireguard-go userspace implementation. The AAR is not checked into the repository and must be built locally.

`libwgmobile` provides WgStart, WgAddPeer, WgRemovePeer, and WgStop. Its source is in `p2premote-wg-ffi/mobile/libwgmobile`.

### Application Layer

- `WgvpnService`: combines VpnService and ForegroundService to run the 9-step outgoing connection flow. It also handles cleanup on disconnect, protect callbacks, and state broadcasts.
- `MainActivity`: handles VPN authorization, displays the virtual IP address, and manages the tunnel state machine.

## System Requirements

- Minimum version: minSdk 29 (Android 10). The system supports TLS 1.3 natively, so no Conscrypt compatibility layer is required.
- Supported ABIs: arm64-v8a and x86_64.

## Building

### 1. Build the libwgmobile AAR

Before building the APK for the first time, you must generate `libwgmobile.aar`. The file is large and needs to be regenerated when wireguard-go changes, so it is not checked into the repository.

Check out [p2premote-wg-ffi](https://github.com/p2premote/p2premote-wg-ffi) alongside this repository. Building requires Go 1.25+, gomobile, and the Android NDK, with `ANDROID_HOME` configured. See the build script's header comments for details.

From this repository's root directory, run these commands to build the AAR with gomobile and copy it into the application's dependency directory:

```bash
cd ../p2premote-wg-ffi
./build-android-aar.sh
# Output: p2premote-wg-ffi/libwgmobile.aar
cp libwgmobile.aar ../p2premote-android-client/app/libs/libwgmobile.aar
```

### 2. Build the APK

Make sure the AAR is in the application's dependency directory as described above. The `preBuild` task cross-compiles the NAT traversal JNI library automatically using `scripts/build-punch-native.ps1`. This requires Rust and the Android NDK.

From the parent directory containing these repositories, run:

```bash
cd p2premote-android-client
./gradlew assembleDebug
```

## Running

From this repository's root directory, install and launch the application:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n top.p2premote.android/.MainActivity
```

## CI and Releases

Pushing to the main branch triggers an APK build in GitHub Actions. The version combines `p2premoteBaseVersion` from `gradle.properties` with the short commit hash.

Pushing a `v*` tag, such as `v1.0.1`, builds that version and publishes a release automatically. Download the APK from the [Releases page](https://github.com/p2premote/p2premote-android-client/releases).
