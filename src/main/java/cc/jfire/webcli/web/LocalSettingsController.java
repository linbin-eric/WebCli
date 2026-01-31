package cc.jfire.webcli.web;

import cc.jfire.baseutil.Resource;
import cc.jfire.boot.forward.path.Path;
import cc.jfire.boot.http.HttpRequestExtend;
import cc.jfire.webcli.pty.PtyManager;
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
     * 获取/设置是否允许远端新建终端
     * GET /api/remote-create
     * PUT /api/remote-create
     */
    @Path("/api/remote-create")
    public ApiResponse<Boolean> remoteCreate(HttpRequestExtend request, RemoteCreatePermissionRequest body)
    {
        if (ptyManager == null)
        {
            return ApiResponse.error("PtyManager 未初始化");
        }
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method))
        {
            return ApiResponse.ok(ptyManager.isRemoteCreateEnabled());
        }
        if ("PUT".equalsIgnoreCase(method))
        {
            boolean enabled = body != null && body.getEnabled() != null && body.getEnabled();
            ptyManager.setRemoteCreateEnabled(enabled);
            log.info("本地设置：允许远端新建终端 = {}", enabled);
            return ApiResponse.ok(ptyManager.isRemoteCreateEnabled());
        }
        return ApiResponse.error("Method not allowed");
    }
}

