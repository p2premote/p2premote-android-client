package top.p2premote.android;

/** App 内动态广播的签名权限，阻止其它应用伪造隧道和在线状态。 */
final class InternalBroadcasts {
    static final String PERMISSION =
            "top.p2premote.android.permission.INTERNAL_BROADCAST";

    private InternalBroadcasts() {
    }
}
