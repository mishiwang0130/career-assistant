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
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    @Resource
    private JwtService jwtService;

    @Resource
    private LoginTokenValidator loginTokenValidator;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
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

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        LoginUserHolder.clear();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.error(ErrorConstant.UNAUTHORIZED));
    }
}
