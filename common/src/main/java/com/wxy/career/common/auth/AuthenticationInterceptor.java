package com.wxy.career.common.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.result.Result;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录态拦截器。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    /**
     * Bearer Token 前缀。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * JWT 服务。
     */
    @Resource
    private JwtService jwtService;

    /**
     * 登录令牌有效性校验器。
     */
    @Resource
    private LoginTokenValidator loginTokenValidator;

    /**
     * JSON 序列化组件。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 请求进入 Controller 前校验登录态。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler 处理器
     * @return true 表示放行
     * @throws IOException 写响应失败
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检请求不携带业务 Token，直接放行给框架处理。
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return false;
        }
        try {
            LoginUser loginUser = jwtService.parseToken(authorization.substring(BEARER_PREFIX.length()));
            // JWT 本身有效不代表会话仍有效，还需要校验数据库中的 jti 状态。
            if (!loginTokenValidator.isValid(loginUser)) {
                writeUnauthorized(response);
                return false;
            }
            LoginUserHolder.set(loginUser);
            return true;
        } catch (RuntimeException exception) {
            writeUnauthorized(response);
            return false;
        }
    }

    /**
     * 请求结束后清理 ThreadLocal。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler 处理器
     * @param exception 请求异常
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        LoginUserHolder.clear();
    }

    /**
     * 输出统一 401 响应。
     *
     * @param response HTTP 响应
     * @throws IOException 写响应失败
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.error(ErrorConstant.UNAUTHORIZED));
    }
}
