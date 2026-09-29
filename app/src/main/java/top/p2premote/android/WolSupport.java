package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.*;
import java.util.*;

final class WolSupport {
    private WolSupport() {}

    static JSONObject capability() {
        try {
            JSONArray values = new JSONArray();
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nic = all.nextElement();
                String name = nic.getName().toLowerCase(Locale.ROOT);
                if (!nic.isUp() || nic.isLoopback() || name.contains("tun") || name.contains("p2p")) continue;
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address) || address.getAddress().isLoopbackAddress()) continue;
                    int prefix = address.getNetworkPrefixLength();
                    if (prefix < 1 || prefix > 32) continue;
                    values.put(new JSONObject().put("ipv4", address.getAddress().getHostAddress()).put("prefix_len", prefix));
                }
            }
            if (values.length() == 0) return null;
            return new JSONObject().put("version", 1).put("can_relay", true).put("interfaces", values);
        } catch (Exception ignored) { return null; }
    }

    static void send(JSONArray macs, String targetIp, int prefix) throws Exception {
        if (macs == null || macs.length() == 0 || macs.length() > 8 || prefix < 1 || prefix > 32) throw new IllegalArgumentException("invalid_wol_request");
        byte[] ip = InetAddress.getByName(targetIp).getAddress();
        if (ip.length != 4) throw new IllegalArgumentException("invalid_target_ip");
        int raw = ((ip[0]&255)<<24)|((ip[1]&255)<<16)|((ip[2]&255)<<8)|(ip[3]&255);
        int mask = prefix == 32 ? -1 : -1 << (32-prefix);
        int b = raw | ~mask;
        InetAddress directed = InetAddress.getByAddress(new byte[]{(byte)(b>>>24),(byte)(b>>>16),(byte)(b>>>8),(byte)b});
        InetAddress source = findSourceAddress(raw, mask);
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(source, 0))) {
            socket.setBroadcast(true);
            for (int m = 0; m < macs.length(); m++) {
                byte[] mac = parseMac(macs.getString(m));
                byte[] packet = new byte[102];
                Arrays.fill(packet, 0, 6, (byte) 0xff);
                for (int i = 1; i <= 16; i++) System.arraycopy(mac, 0, packet, i * 6, 6);
                for (InetAddress destination : new InetAddress[]{directed, InetAddress.getByName("255.255.255.255")}) {
                    for (int i = 0; i < 3; i++) socket.send(new DatagramPacket(packet, packet.length, destination, 9));
                }
            }
        }
    }

    private static InetAddress findSourceAddress(int target, int mask) throws Exception {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        while (all != null && all.hasMoreElements()) {
            NetworkInterface nic = all.nextElement();
            if (!nic.isUp() || nic.isLoopback()) continue;
            Enumeration<InetAddress> addresses = nic.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                byte[] ip = address.getAddress();
                int value = ((ip[0]&255)<<24)|((ip[1]&255)<<16)|((ip[2]&255)<<8)|(ip[3]&255);
                if ((value&mask) == (target&mask)) return address;
            }
        }
        throw new IllegalStateException("no_matching_interface");
    }

    private static byte[] parseMac(String value) {
        String hex = value.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("invalid_mac");
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
