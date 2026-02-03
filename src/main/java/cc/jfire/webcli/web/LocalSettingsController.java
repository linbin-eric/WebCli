package cc.jfire.webcli.web;

import cc.jfire.baseutil.Resource;
import cc.jfire.boot.forward.path.Path;import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.web.dto.ApiResponse;
import cc.jfire.webcli.web.dto.RemoteCreatePermissionRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Local 模式的一些开关配置
 */
@Resource
@Slf4j
public class LocalSettingsController
{
    @Resource
    private PtyManager ptyManager;

    /**
     * 查询是否允许远端新建终端
     * GET /api/remote-create/status
     */
    @Path("/api/remote-create/status")
    public ApiResponse<Boolean> getRemoteCreateStatus()
    {
        if (ptyManager == null)
        {
            return ApiResponse.error("PtyManager 未初始化");
        }
        return ApiResponse.ok(ptyManager.isRemoteCreateEnabled());
    }

    /**
     * 设置是否允许远端新建终端
     * POST /api/remote-create
     */
    @Path("/api/remote-create")
    public ApiResponse<Boolean> setRemoteCreate(RemoteCreatePermissionRequest body)
    {
        if (ptyManager == null)
        {
            return ApiResponse.error("PtyManager 未初始化");
        }
        boolean enabled = body != null && body.getEnabled() != null && body.getEnabled();
        ptyManager.setRemoteCreateEnabled(enabled);
        log.info("本地设置：允许远端新建终端 = {}", enabled);
        return ApiResponse.ok(ptyManager.isRemoteCreateEnabled());
    }
}

