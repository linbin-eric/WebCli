package cc.jfire.webcli.web.dto;

import lombok.Data;

/**
 * 创建终端请求
 */
@Data
public class CreateTerminalRequest {
    private String name;
    private Integer cols;
    private Integer rows;
}
