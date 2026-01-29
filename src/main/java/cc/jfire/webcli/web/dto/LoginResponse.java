package cc.jfire.webcli.web.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {
    private boolean success;
    private String message;
    private String token;  // 登录成功后返回的 session token

    public static LoginResponse success(String token) {
        return new LoginResponse(true, "登录成功", token);
    }

    public static LoginResponse error(String message) {
        return new LoginResponse(false, message, null);
    }
}
