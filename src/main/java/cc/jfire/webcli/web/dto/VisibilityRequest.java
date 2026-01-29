package cc.jfire.webcli.web.dto;

import lombok.Data;

/**
 * 设置终端远程可见性请求
 */
@Data
public class VisibilityRequest {
    private Boolean remoteViewable;
}
