# Android 客户端编译说明

Android 客户端统一使用 `-v` 传入版本号，格式为三段式 SemVer，例如 `1.6.4`。脚本会追加当前 Git 短提交号，最终 APK 版本名类似 `1.6.4-abcdef`。

## 环境要求

- Windows PowerShell
- Android SDK（compileSdk 35）
- Android SDK Build Tools、Platform Tools 和 NDK
- Java/Gradle 环境；项目使用 Gradle Wrapper 8.5
- PowerShell 可执行项目中的 native 构建脚本

编译前必须显式设置 `ANDROID_HOME`，脚本不会猜测或回退到其他 SDK 路径：

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

## 编译 Debug APK

在 `p2premote-android-client` 项目根目录执行：

```powershell
.\build.ps1 -v 1.6.4
```

脚本会依次：

1. 构建测速使用的 riperf3 JNI native 库；
2. 执行 `gradlew.bat assembleDebug`；
3. 将 APK 重命名为带版本号的文件。

最终 APK 位于：

```text
app\build\outputs\apk\debug\p2pRemote-1.6.4-<git-sha>.apk
```

## ABI 支持

当前 Android APK 的 native ABI 为：

- `arm64-v8a`
- `x86_64`

ABI 由 `app/build.gradle` 的 `abiFilters` 控制。修改 ABI 后，需要同时确认 riperf3 native 构建脚本和依赖 AAR 包含相同 ABI。

## 直接使用 Gradle

不经过版本重命名时，可以直接执行：

```powershell
.\gradlew.bat assembleDebug --no-daemon `
  '-Pp2premoteClientVersion=1.6.4-local'
```

推荐使用 `build.ps1 -v <version>`，这样会自动构建 native 依赖并检查 APK 是否生成。
