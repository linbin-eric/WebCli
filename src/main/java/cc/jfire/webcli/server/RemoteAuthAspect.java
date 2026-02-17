package cc.jfire.webcli.server;

import cc.jfire.baseutil.Resource;
import cc.jfire.boot.http.HttpRequestExtend;
import cc.jfire.jfire.core.aop.ProceedPoint;
import cc.jfire.jfire.core.aop.notated.Around;
import cc.jfire.jfire.core.aop.notated.EnhanceClass;
import cc.jfire.webcli.web.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 远端 HTTP API 认证切面
 * <p>
 * 拦截 {@link RemoteTerminalController} 的方法进行 token 验证。
 */
@EnhanceClass("cc.jfire.webcli.server.RemoteTerminalController")
@Slf4j
@Resource
public class RemoteAuthAspect
{
    @Resource
    private LoginManager loginManager;

    @Around("*(*)")
    public void checkAuth(ProceedPoint point)
    {
        String methodName = point.getMethod().methodName();
        // 登录和验证接口不需要 token 验证
        if ("login".equals(methodName) || "validateToken".equals(methodName))
        {
            point.invoke();
            return;
        }

        HttpRequestExtend request = null;
        for (Object arg : point.getParams())
        {
            if (arg instanceof HttpRequestExtend)
            {
                request = (HttpRequestExtend) arg;
                break;
            }
        }

        if (request == null)
        {
            log.warn("方法 {} 未找到 HttpRequestExtend 参数，跳过鉴权", methodName);
            point.invoke();
            return;
        }

        String token = getAuthToken(request);
        if (token == null || !loginManager.validateSession(token))
        {
            point.setResult(ApiResponse.error("未登录或登录已过期"));
            return;
        }

        point.invoke();
    }

    private String getAuthToken(HttpRequestExtend request)
    {
        String auth = request.getHeaders() != null ? request.getHeaders().get("Authorization") : null;
        if (auth != null && auth.startsWith("Bearer "))
        {
            return auth.substring(7);
        }
        Object tokenParam = request.getParamMap() != null ? request.getParamMap().get("token") : null;
        if (tokenParam != null)
        {
            return tokenParam.toString();
        }
        return null;
    }
}

