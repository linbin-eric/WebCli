package cc.jfire.webcli.protocol;

import lombok.Data;

@Data
public class TcpMessage {
    private TcpMessageType type;
    /** 请求 ID，用于请求-响应匹配（创建/重命名等需要同步返回的操作） */
    private String requestId;
    /** 终端名称（创建/重命名等元数据操作） */
    private String name;
    // 握手字段（Base64 字符串）
    private String clientNonce;
    private String serverNonce;
    private String clientPubKey;
    private String serverPubKey;
    private String clientMac;
    private String serverMac;
    private String finishMac;

    private String ptyId;
    private String data;
    private Integer cols;
    private Integer rows;
    private String agentId;  // Agent 标识
    private Boolean remoteViewable;  // 远端可见性
}
