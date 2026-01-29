package cc.jfire.webcli.web.dto;

import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {
    private String username;
    private String passwordHash;  // MD5(password + salt)
    private String salt;          // 前端生成的随机盐
}
