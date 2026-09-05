package top.p2premote.android;

final class DeviceItem {
    final long id;
    final String name;
    final String alias;
    final String type;
    final String uuid;
    final String status;
    final String publicIp;
    final String lanIp;
    final int servicePort;
    /** 公网 IP 地理位置（服务端 device 模型新增字段）。 */
    final String publicIpLocation;
    final String systemVersion;
    final String clientVersion;
    final String remoteAccessProtocol;
    final boolean remoteAccessEnabled;
    final int remoteAccessPort;
	final boolean wakeAvailable;

    DeviceItem(
            long id,
            String name,
            String alias,
            String type,
            String uuid,
            String status,
            String publicIp,
            String lanIp,
            int servicePort
    ) {
        this(id, name, alias, type, uuid, status, publicIp, lanIp, servicePort, "", "", "", "", false, 0, false);
    }

    DeviceItem(
            long id, String name, String alias, String type, String uuid, String status,
            String publicIp, String lanIp, int servicePort, String publicIpLocation,
            String systemVersion, String clientVersion, String remoteAccessProtocol,
            boolean remoteAccessEnabled, int remoteAccessPort, boolean wakeAvailable
    ) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.alias = alias == null ? "" : alias;
        this.type = type == null ? "" : type;
        this.uuid = uuid == null ? "" : uuid;
        this.status = status == null ? "" : status;
        this.publicIp = publicIp == null ? "" : publicIp;
        this.lanIp = lanIp == null ? "" : lanIp;
        this.servicePort = servicePort;
        this.publicIpLocation = publicIpLocation == null ? "" : publicIpLocation;
        this.systemVersion = systemVersion == null ? "" : systemVersion;
        this.clientVersion = clientVersion == null ? "" : clientVersion;
        this.remoteAccessProtocol = remoteAccessProtocol == null ? "" : remoteAccessProtocol;
        this.remoteAccessEnabled = remoteAccessEnabled;
        this.remoteAccessPort = remoteAccessPort > 0 ? remoteAccessPort : servicePort;
		this.wakeAvailable = wakeAvailable;
    }

    String displayName() {
        return alias.isEmpty() ? name : alias;
    }

    boolean canAcceptP2P() {
        return !"android".equalsIgnoreCase(type.trim());
    }

    /** 返回带服务端下发 remote_access 信息的副本（字段为 final，用于 p2p/open 后修正）。 */
    DeviceItem withRemoteAccess(String protocol, boolean enabled, int port) {
        return new DeviceItem(id, name, alias, type, uuid, status, publicIp, lanIp,
                servicePort, publicIpLocation, systemVersion, clientVersion,
                protocol, enabled, port, wakeAvailable);
    }

    String remoteAccessAddress(String virtualIp) {
        if (!remoteAccessEnabled || remoteAccessProtocol.isEmpty() || virtualIp == null || virtualIp.isEmpty()) {
            return "";
        }
        return virtualIp + ":" + remoteAccessPort;
    }
}
