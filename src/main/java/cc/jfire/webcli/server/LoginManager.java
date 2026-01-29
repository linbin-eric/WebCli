package cc.jfire.webcli.server;

import cc.jfire.baseutil.Resource;
import cc.jfire.webcli.config.WebCliConfig;
import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录管理器，负责验证登录和 IP 锁定
 */
@Resource
@Slf4j
public class LoginManager
{
    private static final int                        MAX_FAILED_ATTEMPTS    = 5;           // 最大失败次数
    private static final long                       LOCK_DURATION_MS       = 5 * 60 * 1000; // 锁定时长：5分钟
    private static final long                       SESSION_DURATION_MS    = 24 * 60 * 60 * 1000; // Session 有效期：24小时

    @Resource
    private              WebCliConfig               config;
    // IP -> 失败记录
    private final        Map<String, FailedAttempt> failedAttempts         = new ConcurrentHashMap<>();
    // 已认证的 Pipeline ID 集合
    private final        Set<String>                authenticatedPipelines = ConcurrentHashMap.newKeySet();
    // Session token -> Session 信息
    private final        Map<String, SessionInfo>   sessions               = new ConcurrentHashMap<>();
    private final        SecureRandom               secureRandom           = new SecureRandom();

    /**
     * 检查 IP 是否被锁定
     */
    public boolean isIpLocked(String ip)
    {
        FailedAttempt attempt = failedAttempts.get(ip);
        if (attempt == null)
        {
            return false;
        }
        // 检查是否已过锁定期
        if (System.currentTimeMillis() - attempt.firstFailTime > LOCK_DURATION_MS)
        {
            failedAttempts.remove(ip);
            return false;
        }
        return attempt.count >= MAX_FAILED_ATTEMPTS;
    }

    /**
     * 获取 IP 剩余锁定时间（秒）
     */
    public long getRemainingLockTime(String ip)
    {
        FailedAttempt attempt = failedAttempts.get(ip);
        if (attempt == null)
        {
            return 0;
        }
        long elapsed   = System.currentTimeMillis() - attempt.firstFailTime;
        long remaining = LOCK_DURATION_MS - elapsed;
        return remaining > 0 ? remaining / 1000 : 0;
    }

    /**
     * 验证登录
     *
     * @param username     用户名
     * @param passwordHash 前端计算的 MD5(password + salt)
     * @param salt         前端生成的随机盐
     * @param ip           客户端 IP
     * @return 验证结果
     */
    public LoginResult verify(String username, String passwordHash, String salt, String ip)
    {
        // 检查 IP 是否被锁定
        if (isIpLocked(ip))
        {
            long remaining = getRemainingLockTime(ip);
            return new LoginResult(false, "IP 已被锁定，请 " + remaining + " 秒后重试");
        }
        // 验证用户名
        if (!config.getRemoteUsername().equals(username))
        {
            recordFailedAttempt(ip);
            return createFailedResult(ip);
        }
        // 计算服务端的 MD5(password + salt)
        String serverHash = md5(config.getRemotePassword() + salt);
        if (serverHash == null || !serverHash.equalsIgnoreCase(passwordHash))
        {
            recordFailedAttempt(ip);
            return createFailedResult(ip);
        }
        // 验证成功，清除失败记录
        failedAttempts.remove(ip);
        log.info("用户 {} 从 IP {} 登录成功", username, ip);
        return new LoginResult(true, "登录成功");
    }

    /**
     * 记录失败尝试
     */
    private void recordFailedAttempt(String ip)
    {
        failedAttempts.compute(ip, (k, v) -> {
            if (v == null)
            {
                return new FailedAttempt(1, System.currentTimeMillis());
            }
            // 如果已过期，重新开始计数
            if (System.currentTimeMillis() - v.firstFailTime > LOCK_DURATION_MS)
            {
                return new FailedAttempt(1, System.currentTimeMillis());
            }
            return new FailedAttempt(v.count + 1, v.firstFailTime);
        });
    }

    /**
     * 创建失败结果
     */
    private LoginResult createFailedResult(String ip)
    {
        FailedAttempt attempt   = failedAttempts.get(ip);
        int           remaining = MAX_FAILED_ATTEMPTS - (attempt != null ? attempt.count : 0);
        if (remaining <= 0)
        {
            long lockTime = getRemainingLockTime(ip);
            return new LoginResult(false, "IP 已被锁定，请 " + lockTime + " 秒后重试");
        }
        return new LoginResult(false, "用户名或密码错误，还剩 " + remaining + " 次尝试机会");
    }

    /**
     * 标记 Pipeline 已认证
     */
    public void markAuthenticated(String pipelineId)
    {
        authenticatedPipelines.add(pipelineId);
    }

    /**
     * 检查 Pipeline 是否已认证
     */
    public boolean isAuthenticated(String pipelineId)
    {
        return authenticatedPipelines.contains(pipelineId);
    }

    /**
     * 移除 Pipeline 认证状态
     */
    public void removeAuthentication(String pipelineId)
    {
        authenticatedPipelines.remove(pipelineId);
    }

    /**
     * 创建新的 Session
     *
     * @param clientIp 客户端 IP
     * @return session token
     */
    public String createSession(String clientIp)
    {
        // 生成随机 token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        // 创建 Session 信息
        SessionInfo session = new SessionInfo(clientIp, System.currentTimeMillis());
        sessions.put(token, session);
        log.info("创建新 Session: token={}, ip={}", token.substring(0, 8) + "...", clientIp);
        return token;
    }

    /**
     * 验证 Session 是否有效
     *
     * @param token session token
     * @return 是否有效
     */
    public boolean validateSession(String token)
    {
        if (token == null)
        {
            return false;
        }
        SessionInfo session = sessions.get(token);
        if (session == null)
        {
            return false;
        }
        // 检查是否过期
        if (System.currentTimeMillis() - session.createTime > SESSION_DURATION_MS)
        {
            sessions.remove(token);
            log.info("Session 已过期: token={}", token.substring(0, 8) + "...");
            return false;
        }
        return true;
    }

    /**
     * 获取 Session 信息
     *
     * @param token session token
     * @return Session 信息，如果不存在或已过期返回 null
     */
    public SessionInfo getSessionByToken(String token)
    {
        if (!validateSession(token))
        {
            return null;
        }
        return sessions.get(token);
    }

    /**
     * 移除 Session
     *
     * @param token session token
     */
    public void removeSession(String token)
    {
        if (token != null)
        {
            sessions.remove(token);
        }
    }

    /**
     * 通过 token 标记 Pipeline 已认证
     *
     * @param pipelineId Pipeline ID
     * @param token      session token
     * @return 是否成功
     */
    public boolean authenticateByToken(String pipelineId, String token)
    {
        if (validateSession(token))
        {
            authenticatedPipelines.add(pipelineId);
            return true;
        }
        return false;
    }

    /**
     * 计算 MD5
     */
    private String md5(String input)
    {
        try
        {
            MessageDigest md     = MessageDigest.getInstance("MD5");
            byte[]        digest = md.digest(input.getBytes());
            StringBuilder sb     = new StringBuilder();
            for (byte b : digest)
            {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            log.error("MD5 算法不可用", e);
            return null;
        }
    }

    /**
     * 失败尝试记录
     */
    private record FailedAttempt(int count, long firstFailTime)
    {
    }

    /**
     * 登录结果
     */
    public record LoginResult(boolean success, String message)
    {
    }

    /**
     * Session 信息
     */
    public record SessionInfo(String clientIp, long createTime)
    {
    }
}
