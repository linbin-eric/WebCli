package cc.jfire.webcli.web.dto;

import lombok.Data;

/**
 * 远端创建终端请求（需要指定 Agent）
 */
@Data
public class RemoteCreateTerminalRequest
{
    private String  agentId;
    private String  name;
    private Integer cols;
    private Integer rows;
}

