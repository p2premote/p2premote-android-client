# p2pRemote Android ProGuard/R8 规则
#
# gomobile 生成的 AAR 通过 JNI 反射调用，类名/方法名一旦混淆或裁剪，
# native 层（gonc / wireguard-go）将找不到对应符号直接崩溃。
# 以下 keep 规则保护所有 gomobile 绑定类与 go runtime。

# ============ gomobile 生成包 ============
# libwgmobile.* —— wireguard-go userspace 绑定（WgStart / WgAddPeer 等）
-keep class libwgmobile.** { *; }
# go runtime / go.Seq 序列化层
-keep class go.** { *; }

# ============ 回调接口 ============
# gomobile 导出的 Java 接口由 native 侧通过反射或注册表调用，不可被重命名。
-keepclassmembers class libwgmobile.** { *; }
# Rust JNI 使用静态符号 Java_top_p2premote_android_PunchNative_* 绑定，
# nativeSetProtectCallback 回调由 native 侧反射调用，类/方法名不可混淆。
-keep class top.p2premote.android.PunchNative { *; }
-keep class top.p2premote.android.PunchNative$* { *; }

# ============ 应用自身 ============
# VpnService 子类、Broadcast、Activity 等被系统/反射引用，保留类名。
# 注：四大组件由 manifest 声明，AGP 会自动 keep，此处为显式保险。
-keep class top.p2premote.android.WgvpnService { *; }
-keep class top.p2premote.android.MainActivity { *; }
# Rust JNI 使用静态符号 Java_top_p2premote_android_Riperf3Native_* 绑定，类/方法名不可混淆。
-keep class top.p2premote.android.Riperf3Native { *; }

# ============ 依赖中的反射调用（如 JSON 解析） ============
# org.json 为系统库无需 keep；如后续引入 Gson/Moshi 需在此补充对应模型类。

# ============ OkHttp / Okio（WebSocket 保活） ============
# OkHttp 内部大量使用反射和平台检测，混淆会导致连接失败。
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# 保留 WebSocketListener 子类（PresenceService 匿名/内部类），OkHttp 通过反射回调。
-keep class top.p2premote.android.PresenceService$* { *; }
