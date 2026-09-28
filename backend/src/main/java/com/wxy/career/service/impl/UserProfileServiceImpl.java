package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.UserProfileMapper;
import com.wxy.career.po.UserProfile;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.vo.UserProfileSaveReqVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 求职目标服务实现。
 *
 * <p>一人一份的语义由唯一键与「先查再写」共同保证：查询到记录就更新，查询不到就新增，
 * 不会为同一个用户产生第二行。所有写操作都带用户 ID，跨账号写不进去。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class UserProfileServiceImpl implements UserProfileService {

    /**
     * 求职目标 Mapper。
     */
    @Resource
    private UserProfileMapper userProfileMapper;

    /**
     * 查询当前登录用户的求职目标。
     *
     * @return 求职目标，未填写时返回 null
     */
    @Override
    public UserProfileRespVO getCurrentUserProfile() {
        return getUserProfileByUserId(requireUserId());
    }

    /**
     * 保存当前登录用户的求职目标。
     *
     * @param reqVO 保存请求
     * @return 保存后的求职目标
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileRespVO saveCurrentUserProfile(UserProfileSaveReqVO reqVO) {
        return saveUserProfileByUserId(requireUserId(), reqVO);
    }

    /**
     * 按用户 ID 查询求职目标。
     *
     * @param userId 用户 ID
     * @return 求职目标，用户 ID 为空或未填写时返回 null
     */
    @Override
    public UserProfileRespVO getUserProfileByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        UserProfile userProfile = userProfileMapper.selectByUserId(userId);
        return userProfile == null ? null : UserProfileRespVO.from(userProfile);
    }

    /**
     * 按用户 ID 保存求职目标，不存在则新增、存在则覆盖两个业务字段。
     *
     * @param userId 用户 ID
     * @param reqVO 保存请求
     * @return 保存后的求职目标
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileRespVO saveUserProfileByUserId(Long userId, UserProfileSaveReqVO reqVO) {
        if (userId == null) {
            // 身份缺失属于未登录，统一按 401 表达，避免写入无主数据。
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        // 岗位去向是用户输入的自由文本，入库前去掉首尾空白，避免同一岗位因空格产生两种写法。
        String targetPosition = reqVO.getTargetPosition().trim();
        UserProfile existing = userProfileMapper.selectByUserId(userId);
        if (existing == null) {
            UserProfile userProfile = new UserProfile();
            userProfile.setUserId(userId);
            userProfile.setTargetPosition(targetPosition);
            userProfile.setWorkYears(reqVO.getWorkYears());
            userProfileMapper.insert(userProfile);
            return UserProfileRespVO.from(userProfile);
        }
        // 只按主键更新两个业务字段：主键取自上面这次带用户 ID 的查询结果，跨账号写不进来；
        // 未赋值的字段不会被更新，用户 ID 与审计字段都不可能被覆盖。
        UserProfile update = new UserProfile();
        update.setId(existing.getId());
        update.setTargetPosition(targetPosition);
        update.setWorkYears(reqVO.getWorkYears());
        userProfileMapper.updateById(update);
        existing.setTargetPosition(targetPosition);
        existing.setWorkYears(reqVO.getWorkYears());
        log.info("更新求职目标，userId={}，targetPosition={}，workYears={}",
                userId, targetPosition, reqVO.getWorkYears());
        return UserProfileRespVO.from(existing);
    }

    /**
     * 取必填求职目标，档案为空时抛 1101。
     *
     * @param userId 用户 ID
     * @return 求职目标
     */
    @Override
    public UserProfileRespVO getRequiredUserProfile(Long userId) {
        UserProfileRespVO userProfile = getUserProfileByUserId(userId);
        if (userProfile == null) {
            throw new BizException(ErrorConstant.USER_PROFILE_REQUIRED);
        }
        return userProfile;
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID
     */
    private Long requireUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }
}
