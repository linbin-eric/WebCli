package cc.jfire.webcli.protocol;

import lombok.Data;

@Data
public class TcpMessage {
    private TcpMessageType type;
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
}
