package cc.jfire.webcli.server;

import cc.jfire.baseutil.Resource;
import cc.jfire.boot.forward.path.Path;
import cc.jfire.boot.http.HttpRequestExtend;
import cc.jfire.webcli.protocol.PtyInfo;
import cc.jfire.webcli.web.dto.ApiResponse;
import cc.jfire.webcli.web.dto.LoginRequest;
import cc.jfire.webcli.web.dto.LoginResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Remote 模式终端管理 HTTP Controller
 */
@Resource
@Slf4j
public class RemoteTerminalController {

    @Resource
    private LoginManager loginManager;

    @Resource
    private AgentManager agentManager;

    /**
     * 登录
     * POST /api/remote/login
     */
    @Path("/api/remote/login")
    public LoginResponse login(HttpRequestExtend request, LoginRequest body) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return LoginResponse.error("Method not allowed");
        }
        if (body == null || body.getUsername() == null || body.getPasswordHash() == null || body.getSalt() == null) {
            return LoginResponse.error("请提供用户名和密码");
        }

        String clientIp = getClientIp(request);
        LoginManager.LoginResult result = loginManager.verify(
                body.getUsername(),
                body.getPasswordHash(),
                body.getSalt(),
                clientIp
        );

        if (result.success()) {
            String token = loginManager.createSession(clientIp);
            log.info("用户 {} 从 IP {} 通过 HTTP API 登录成功", body.getUsername(), clientIp);
            return LoginResponse.success(token);
        } else {
            return LoginResponse.error(result.message());
        }
    }

    /**
     * 获取远程终端列表
     * GET /api/remote/terminals
     */
    @Path("/api/remote/terminals")
    public ApiResponse<List<PtyInfo>> listTerminals(HttpRequestExtend request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }

        if (agentManager == null) {
            return ApiResponse.error("服务未初始化");
        }

        String token = getAuthToken(request);
        if (token == null || !loginManager.validateSession(token)) {
            return ApiResponse.error("未登录或登录已过期");
        }

        // 刷新所有 Agent 的 PTY 列表
        agentManager.refreshAllPtyLists();

        // 等待短暂时间让 Agent 响应
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        List<PtyInfo> list = agentManager.getAllRemotePtys();
        return ApiResponse.ok(list);
    }

    /**
     * 关闭远程终端
     * DELETE /api/remote/terminal/${id}
     */
    @Path("/api/remote/terminal/${id}")
    public ApiResponse<Void> closeTerminal(HttpRequestExtend request, String id) {
        if (!"DELETE".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }

        if (agentManager == null) {
            return ApiResponse.error("服务未初始化");
        }

        String token = getAuthToken(request);
        if (token == null || !loginManager.validateSession(token)) {
            return ApiResponse.error("未登录或登录已过期");
        }

        String[] parts = agentManager.parseFullPtyId(id);
        if (parts == null) {
            return ApiResponse.error("无效的终端 ID");
        }

        String agentId = parts[0];
        String ptyId = parts[1];
        ServerTcpHandler handler = agentManager.getAgentHandler(agentId);
        if (handler == null) {
            return ApiResponse.error("Agent 不存在");
        }

        handler.sendPtyClose(ptyId);
        agentManager.unregisterPtyOutputListener(id);
        agentManager.removePtyAttach(agentId, ptyId);
        log.info("通过 HTTP API 关闭远程终端: {}", id);
        return ApiResponse.ok();
    }

    /**
     * 验证 token 是否有效
     * GET /api/remote/validate
     */
    @Path("/api/remote/validate")
    public ApiResponse<Boolean> validateToken(HttpRequestExtend request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return ApiResponse.error("Method not allowed");
        }

        String token = getAuthToken(request);
        if (token == null) {
            return ApiResponse.ok(false);
        }
        return ApiResponse.ok(loginManager.validateSession(token));
    }

    private String getClientIp(HttpRequestExtend request) {
        // 尝试从 X-Forwarded-For 或 X-Real-IP 获取真实 IP
        String ip = request.getHeaders() != null ? request.getHeaders().get("X-Forwarded-For") : null;
        if (ip != null && !ip.isBlank()) {
            // X-Forwarded-For 可能包含多个 IP，取第一个
            int idx = ip.indexOf(',');
            if (idx > 0) {
                ip = ip.substring(0, idx).trim();
            }
            return ip;
        }
        ip = request.getHeaders() != null ? request.getHeaders().get("X-Real-IP") : null;
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        // 从 Pipeline 获取
        String address = request.getPipeline() != null ? request.getPipeline().getRemoteAddressWithoutException() : null;
        return address != null ? address : "unknown";
    }

    private String getAuthToken(HttpRequestExtend request) {
        // 从 Authorization header 获取 token
        String auth = request.getHeaders() != null ? request.getHeaders().get("Authorization") : null;
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        // 也支持从 URL 参数获取
        Object tokenParam = request.getParamMap() != null ? request.getParamMap().get("token") : null;
        if (tokenParam != null) {
            return tokenParam.toString();
        }
        return null;
    }
}
