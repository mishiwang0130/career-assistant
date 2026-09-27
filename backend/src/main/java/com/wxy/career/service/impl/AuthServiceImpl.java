package com.wxy.career.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private TokenService tokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthRespVO register(UserRegisterReqVO reqVO) {
        String username = reqVO.getUsername().trim();
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
            throw new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);
        }
        return buildAuthResp(user, tokenService.issueTokens(user));
    }

    @Override
    public AuthRespVO login(UserLoginReqVO reqVO) {
        SysUser user = findByUsername(reqVO.getUsername().trim());
        if (user == null || !passwordEncoder.matches(reqVO.getPassword(), user.getPassword())) {
            throw new BizException(ErrorConstant.USERNAME_OR_PASSWORD_ERROR, HttpStatus.UNAUTHORIZED);
        }
        if (user.getStatus() == null || user.getStatus() == SysUser.STATUS_DISABLED) {
            throw new BizException(ErrorConstant.ACCOUNT_DISABLED, HttpStatus.FORBIDDEN);
        }
        return buildAuthResp(user, tokenService.issueTokens(user));
    }

    @Override
    public AuthRespVO refresh(TokenRefreshReqVO reqVO) {
        return tokenService.refresh(reqVO.getRefreshToken().trim());
    }

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

    @Override
    public void logout() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
        }
        tokenService.revoke(loginUser.getJti(), loginUser.getUserId());
    }

    private SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    private AuthRespVO buildAuthResp(SysUser user, TokenPairVO tokenPair) {
        return new AuthRespVO(
                tokenPair.getAccessToken(),
                tokenPair.getRefreshToken(),
                TOKEN_TYPE,
                tokenPair.getExpiresIn(),
                UserInfoRespVO.from(user));
    }

}
