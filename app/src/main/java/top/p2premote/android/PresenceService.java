package top.p2premote.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 在线保活服务（ForegroundService）。
 *
 * 职责精简为生命周期管理 + 通知栏展示：实际的 WebSocket 连接、保活、重连、p2p_notify
 * 收发全部委托给 {@link WsConnection} 单例。WgvpnService 建链时直接通过 WsConnection
 * 发收业务消息，无需跨 Service IPC。
 *
 * 生命周期：登录后/已认证冷启动启动，退出登录停止，独立于 WgvpnService。
 */
public final class PresenceService extends Service {

    private static final String TAG = "PresenceService";

    static final String ACTION_START = "top.p2premote.android.action.PRESENCE_START";
    static final String ACTION_STOP = "top.p2premote.android.action.PRESENCE_STOP";
    /** 状态广播：服务向外推送连接状态变化。 */
    static final String ACTION_STATUS = "top.p2premote.android.action.PRESENCE_STATUS";

    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";

    /** 正在建立 WebSocket 连接。 */
    static final String STATE_CONNECTING = "connecting";
    /** WebSocket 已连接，保活中。 */
    static final String STATE_CONNECTED = "connected";
    /** WebSocket 断开，等待重连。 */
    static final String STATE_DISCONNECTED = "disconnected";

    private static final String CHANNEL_ID = "p2premote_presence";
    private static final int NOTIFICATION_ID = 2002;

    private SessionStore sessionStore;
    /** 最近一次广播的状态，用于检测变化时才发广播。 */
    private String lastBroadcastState = STATE_DISCONNECTED;

    @Override
    public void onCreate() {
        super.onCreate();
        sessionStore = new SessionStore(this);
        ensureChannel();
        // 注册状态回调：WsConnection 连接状态变化时更新通知栏 + 广播 UI。
        WsConnection.get(this).setStateListener((state, message) -> {
            updateNotification(state);
            emit(state, message);
        });
        Log.i(TAG, "PresenceService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // 系统重建时 intent 为 null；App 可能在后台，startForeground 会受限，直接退出。
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            WsConnection.get(this).stop();
            emit(STATE_DISCONNECTED, "已停止在线保活");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            try {
                startForeground(NOTIFICATION_ID, notification("正在连接服务..."));
            } catch (Exception e) {
                Log.w(TAG, "startForeground denied, stopping: " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
            // 委托 WsConnection 建立 WS 连接。
            try {
                Session session = sessionStore.require();
                WsConnection.get(this).start(session);
            } catch (Exception e) {
                Log.w(TAG, "no valid session: " + e.getMessage());
                emit(STATE_DISCONNECTED, "未登录，无法保活");
            }
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        WsConnection.get(this).stop();
        super.onDestroy();
    }

    /**
     * 推送连接状态广播并更新通知栏。仅当状态变化时才广播，避免冗余。
     */
    private void emit(String state, String message) {
        if (state.equals(lastBroadcastState)) {
            return;
        }
        lastBroadcastState = state;
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATE, state);
        update.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        sendBroadcast(update);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "p2pRemote 在线保活", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // contentIntent：点击通知跳转到 App 主界面（MainActivity）。
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("p2pRemote")
                .setContentText(text == null || text.isEmpty() ? "在线保活" : text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    /** 根据 WS 连接状态更新通知栏文字。 */
    private void updateNotification(String state) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        String text;
        if (STATE_CONNECTED.equals(state)) {
            text = "在线保活中";
        } else if (STATE_CONNECTING.equals(state)) {
            text = "正在连接服务...";
        } else {
            text = "连接断开，重连中";
        }
        manager.notify(NOTIFICATION_ID, notification(text));
    }
}
