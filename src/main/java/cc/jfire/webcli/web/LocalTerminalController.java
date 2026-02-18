package cc.jfire.webcli.web;

import cc.jfire.baseutil.Resource;
import cc.jfire.boot.forward.path.Path;
import cc.jfire.boot.http.HttpRequestExtend;
import cc.jfire.webcli.protocol.PtyInfo;
import cc.jfire.webcli.pty.PtyInstance;
import cc.jfire.webcli.pty.PtyManager;
import cc.jfire.webcli.web.dto.ApiResponse;
import cc.jfire.webcli.web.dto.CreateTerminalRequest;
import cc.jfire.webcli.web.dto.RenameTerminalRequest;
import cc.jfire.webcli.web.dto.VisibilityRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Local 模式终端管理 HTTP Controller
 */
@Resource
@Slf4j
public class LocalTerminalController {

    @Resource
    private PtyManager ptyManager;

    /**
     * 获取终端列表
     * GET /api/terminals
     */
    @Path("/api/terminals")
    public ApiResponse<List<PtyInfo>> listTerminals(HttpRequestExtend request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }
        if (ptyManager == null) {
            return ApiResponse.ok(Collections.emptyList());
        }
        List<PtyInfo> list = ptyManager.getAll().stream()
                .map(pty -> new PtyInfo(pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable(), pty.isRemoteCreated()))
                .toList();
        return ApiResponse.ok(list);
    }

    /**
     * 创建终端
     * POST /api/terminal
     */
    @Path("/api/terminal")
    public ApiResponse<PtyInfo> createTerminal(HttpRequestExtend request, CreateTerminalRequest body) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }
        if (ptyManager == null) {
            return ApiResponse.error("PtyManager 未初始化");
        }
        try {
            String name = body != null && body.getName() != null ? body.getName() : "终端";
            int cols = body != null && body.getCols() != null ? body.getCols() : 120;
            int rows = body != null && body.getRows() != null ? body.getRows() : 40;
            PtyInstance pty = ptyManager.create(name, cols, rows);
            pty.startReading();
            PtyInfo info = new PtyInfo(pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable(), pty.isRemoteCreated());
            log.info("通过 HTTP API 创建终端: {}, 名称: {}", pty.getId(), name);
            return ApiResponse.ok(info);
        } catch (IOException e) {
            log.error("创建终端失败", e);
            return ApiResponse.error("创建终端失败: " + e.getMessage());
        }
    }

    /**
     * 关闭终端
     * DELETE /api/terminal/${id}
     */
    @Path("/api/terminal/${id}")
    public ApiResponse<Void> terminalOperation(HttpRequestExtend request, String id) {
        String method = request.getMethod();
        if ("DELETE".equalsIgnoreCase(method)) {
            return closeTerminal(id);
        }
        return ApiResponse.error("Method not allowed");
    }

    private ApiResponse<Void> closeTerminal(String id) {
        if (ptyManager == null) {
            return ApiResponse.error("PtyManager 未初始化");
        }
        PtyInstance pty = ptyManager.get(id);
        if (pty == null) {
            return ApiResponse.error("终端不存在: " + id);
        }
        ptyManager.remove(id);
        log.info("通过 HTTP API 关闭终端: {}", id);
        return ApiResponse.ok();
    }

    /**
     * 重命名终端
     * PUT /api/terminal/${id}/name
     */
    @Path("/api/terminal/${id}/name")
    public ApiResponse<PtyInfo> renameTerminal(HttpRequestExtend request, String id, RenameTerminalRequest body) {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }
        if (ptyManager == null) {
            return ApiResponse.error("PtyManager 未初始化");
        }
        PtyInstance pty = ptyManager.get(id);
        if (pty == null) {
            return ApiResponse.error("终端不存在: " + id);
        }
        if (body == null || body.getName() == null || body.getName().isBlank()) {
            return ApiResponse.error("终端名称不能为空");
        }
        pty.setName(body.getName());
        PtyInfo info = new PtyInfo(pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable(), pty.isRemoteCreated());
        log.info("通过 HTTP API 重命名终端: {} -> {}", id, body.getName());
        return ApiResponse.ok(info);
    }

    /**
     * 设置终端远程可见性
     * PUT /api/terminal/${id}/visibility
     */
    @Path("/api/terminal/${id}/visibility")
    public ApiResponse<PtyInfo> setVisibility(HttpRequestExtend request, String id, VisibilityRequest body) {
        if (!"PUT".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }
        if (ptyManager == null) {
            return ApiResponse.error("PtyManager 未初始化");
        }
        PtyInstance pty = ptyManager.get(id);
        if (pty == null) {
            return ApiResponse.error("终端不存在: " + id);
        }
        boolean remoteViewable = body != null && body.getRemoteViewable() != null && body.getRemoteViewable();
        pty.setRemoteViewable(remoteViewable);
        PtyInfo info = new PtyInfo(pty.getId(), pty.getName(), pty.isAlive(), pty.isRemoteViewable(), pty.isRemoteCreated());
        log.info("通过 HTTP API 设置终端 {} 远程可见: {}", id, remoteViewable);
        return ApiResponse.ok(info);
    }
}
