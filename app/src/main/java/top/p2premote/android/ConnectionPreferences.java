package top.p2premote.android;

import android.content.Context;
import android.content.SharedPreferences;

final class ConnectionPreferences {
    final boolean ipv6, tcp;
    ConnectionPreferences(boolean ipv6, boolean tcp) { this.ipv6 = ipv6; this.tcp = tcp; }
    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("connection_preferences", Context.MODE_PRIVATE);
    }
    static ConnectionPreferences load(Context context) {
        SharedPreferences p = store(context);
        return new ConnectionPreferences(p.getBoolean("prefer_ipv6", false), p.getBoolean("prefer_tcp", false));
    }
    boolean save(Context context) {
        return store(context).edit().putBoolean("prefer_ipv6", ipv6).putBoolean("prefer_tcp", tcp).commit();
    }
    String summary() { return (tcp ? "TCP 优先" : "UDP 优先") + " · " + (ipv6 ? "IPv6 优先" : "IPv4 优先"); }
}
