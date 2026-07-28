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
        this(id, name, alias, type, uuid, status, publicIp, lanIp, servicePort, "");
    }

    DeviceItem(
            long id,
            String name,
            String alias,
            String type,
            String uuid,
            String status,
            String publicIp,
            String lanIp,
            int servicePort,
            String publicIpLocation
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
    }

    String displayName() {
        return alias.isEmpty() ? name : alias;
    }

    boolean canAcceptP2P() {
        return !"android".equalsIgnoreCase(type.trim());
    }
}
