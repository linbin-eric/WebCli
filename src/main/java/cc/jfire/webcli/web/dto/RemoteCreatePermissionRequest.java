package cc.jfire.webcli.web.dto;

import lombok.Data;

/**
 * 本地控制：是否允许远端新建终端
 */
@Data
public class RemoteCreatePermissionRequest
{
    private Boolean enabled;
}

