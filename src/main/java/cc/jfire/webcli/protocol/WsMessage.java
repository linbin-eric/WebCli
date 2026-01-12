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
}
