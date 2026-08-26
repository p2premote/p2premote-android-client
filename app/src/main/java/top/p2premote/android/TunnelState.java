package top.p2premote.android;

/**
 * 隧道状态常量。
 *
 * 对齐桌面端 wgvpn job/session 状态，适配移动端（含 VPN 授权阶段）。
 */
final class TunnelState {
    /** 未连接。 */
    static final String IDLE = "idle";
    /** 正在请求 VPN 权限（Android 特有）。 */
    static final String REQUESTING_VPN = "requesting_vpn";
    /** 准备环境、申请 p2p/start。 */
    static final String PREPARING = "preparing";
    /** gonc 密钥+IP 交换中。 */
    static final String EXCHANGING = "exchanging";
    /** gonc 打洞中。 */
    static final String PUNCHING = "punching";
    /** WireGuard 握手中。 */
    static final String CONNECTING = "connecting";
    /** WireGuard 已握手，但被动端尚未允许业务数据。 */
    static final String WAITING_APPROVAL = "waiting_approval";
    /** 隧道已建立。 */
    static final String CONNECTED = "connected";
    /** 网络异常，等待恢复。 */
    static final String DEGRADED = "degraded";
    /** 失败。 */
    static final String FAILED = "failed";
    /** 已断开。 */
    static final String STOPPED = "stopped";
    /** 用户拒绝 VPN 授权，已取消。 */
    static final String ABORTED = "aborted";

    private TunnelState() {
    }

    /** 判断当前状态是否表示隧道正在建立或已建立（可用于判断是否需要先断开）。 */
    static boolean isActive(String state) {
        return PREPARING.equals(state)
                || EXCHANGING.equals(state)
                || PUNCHING.equals(state)
                || CONNECTING.equals(state)
                || WAITING_APPROVAL.equals(state)
                || CONNECTED.equals(state)
                || REQUESTING_VPN.equals(state)
                || DEGRADED.equals(state);
    }

    /**
     * 判断当前状态是否为终态（不会再变化，无需再 emit 覆盖）。
     * IDLE(未启动)/FAILED(失败)/STOPPED(已断开)/ABORTED(已取消) 均为终态。
     * 用于 WgvpnService.onDestroy：已是终态时只清理 native 资源，不再 emit(STOPPED)，
     * 以免覆盖 catch 块刚设置的 FAILED 通知（否则用户永远只看到「服务已停止」而非真实失败原因）。
     */
    static boolean isTerminal(String state) {
        return IDLE.equals(state)
                || FAILED.equals(state)
                || STOPPED.equals(state)
                || ABORTED.equals(state);
    }
}
