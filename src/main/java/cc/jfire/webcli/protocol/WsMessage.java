package cc.jfire.webcli.protocol;

import lombok.Data;

@Data
public class WsMessage {
    private MessageType type;
    private String ptyId;
    private String name;
    private String data;
    private Integer cols;
    private Integer rows;
    private Boolean remoteViewable;
    // 登录相关字段
    private String username;
    private String passwordHash;  // MD5(password + salt)
    private String salt;          // 前端生成的随机盐
}
