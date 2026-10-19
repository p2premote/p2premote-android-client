package top.p2premote.android;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** In-memory persistence boundary; production SessionStore performs all decisions. */
final class TestPreferences {
    final Map<String,Object> values = new HashMap<>();
    boolean commitSucceeds = true;
    final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "edit": return editor();
                    case "contains": return values.containsKey(args[0]);
                    case "getAll": return new HashMap<>(values);
                    case "getString": case "getLong": case "getInt": case "getBoolean": case "getFloat": case "getStringSet":
                        return values.containsKey(args[0]) ? values.get(args[0]) : args[1];
                    default: throw new AssertionError("Unexpected preference call: " + method.getName());
                }
            });
    private Object editor() {
        Map<String,Object> updates = new HashMap<>();
        boolean[] clear = {false};
        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) { updates.put((String)args[0], args[1]); return proxy; }
                    if (name.equals("clear")) { clear[0]=true; return proxy; }
                    if (name.equals("apply") || name.equals("commit")) {
                        if (name.equals("commit") && !commitSucceeds) return false;
                        if (clear[0]) values.clear();
                        values.putAll(updates);
                        return name.equals("commit") ? true : null;
                    }
                    throw new AssertionError("Unexpected editor call: " + name);
                });
    }
    static Session session(String access, String refresh) {
        return new Session("https://unit.invalid",access,refresh,System.currentTimeMillis()+3600000,
                "user","pro","member-end","trial-end",3,true,7,"local-uuid","private-test","public-test");
    }
}
