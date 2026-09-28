package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.AuthService;
import com.wxy.career.service.TokenService;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenPairVO;
import com.wxy.career.vo.TokenRefreshReqVO;
import com.wxy.career.vo.UserInfoRespVO;
import com.wxy.career.vo.UserLoginReqVO;
import com.wxy.career.vo.UserRegisterReqVO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号服务实现。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Service
public class AuthServiceImpl implements AuthService {

    /**
     * 令牌类型。
     */
    private static final String TOKEN_TYPE = "Bearer";

    /**
     * 用户 Mapper。
     */
    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * BCrypt 密码编码器。
     */
    @Resource
    private PasswordEncoder passwordEncoder;

    /**
     * 令牌服务。
     */
    @Resource
    private TokenService tokenService;

    /**
     * 注册用户并签发登录态。
     *
     * @param reqVO 注册请求
     * @return 登录响应
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthRespVO register(UserRegisterReqVO reqVO) {
        String username = reqVO.getUsername().trim();
        // 先做一次可读性更好的查重；并发窗口由唯一索引兜底。
        if (findByUsername(username) != null) {
            throw new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(reqVO.getPassword()));
        user.setNickname(reqVO.getNickname().trim());
        user.setStatus(SysUser.STATUS_ENABLED);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            // 并发注册同一用户名时，数据库唯一索引是最终一致性保障。
            throw new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);
        }
        return buildAuthResp(user, tokenService.issueTokens(user));
    }

    /**
     * 用户登录。
     *
     * @param reqVO 登录请求
     * @return 登录响应
     */
    @Override
    public AuthRespVO login(UserLoginReqVO reqVO) {
        SysUser user = findByUsername(reqVO.getUsername().trim());
        // 用户不存在与密码错误使用同一错误码，避免暴露账号是否存在。
        if (user == null || !passwordEncoder.matches(reqVO.getPassword(), user.getPassword())) {
            throw new BizException(ErrorConstant.USERNAME_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() == null || user.getStatus() == SysUser.STATUS_DISABLED) {
            throw new BizException(ErrorConstant.ACCOUNT_DISABLED);
        }
        return buildAuthResp(user, tokenService.issueTokens(user));
    }

    /**
     * 刷新登录态。
     *
     * @param reqVO 刷新请求
     * @return 新登录响应
     */
    @Override
    public AuthRespVO refresh(TokenRefreshReqVO reqVO) {
        return tokenService.refresh(reqVO.getRefreshToken().trim());
    }

    /**
     * 获取当前登录用户。
     *
     * @return 当前用户信息
     */
    @Override
    public UserInfoRespVO getCurrentUser() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        SysUser user = sysUserMapper.selectById(loginUser.getUserId());
        if (user == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        return UserInfoRespVO.from(user);
    }

    /**
     * 退出当前登录会话。
     */
    @Override
    public void logout() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        tokenService.revoke(loginUser.getJti(), loginUser.getUserId());
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户实体，不存在时返回 null
     */
    private SysUser findByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    /**
     * 构建登录响应。
     *
     * @param user 用户实体
     * @param tokenPair token 对
     * @return 登录响应
     */
    private AuthRespVO buildAuthResp(SysUser user, TokenPairVO tokenPair) {
        return new AuthRespVO(
                tokenPair.getAccessToken(),
                tokenPair.getRefreshToken(),
                TOKEN_TYPE,
                tokenPair.getExpiresIn(),
                UserInfoRespVO.from(user));
    }

}
