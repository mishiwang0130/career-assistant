package com.wxy.career.service;

import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.vo.UserProfileSaveReqVO;

/**
 * 求职目标服务。
 *
 * <p>提供三条入口：面向 HTTP 接口的「当前登录用户」读写、面向 Agent 工具的按用户 ID 读写
 * （工具在非请求线程执行，拿不到 {@code LoginUserHolder}，身份取自 {@code RuntimeContext}），
 * 以及面向 F5 / F7 的「取必填档案」（档案为空时抛 1101），避免下游模块各写一套校验。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface UserProfileService {

    /**
     * 查询当前登录用户的求职目标。
     *
     * @return 求职目标，未填写时返回 null
     */
    UserProfileRespVO getCurrentUserProfile();

    /**
     * 保存当前登录用户的求职目标，一人一份，不存在则新增。
     *
     * @param reqVO 保存请求
     * @return 保存后的求职目标
     */
    UserProfileRespVO saveCurrentUserProfile(UserProfileSaveReqVO reqVO);

    /**
     * 按用户 ID 查询求职目标，供 Agent 工具使用。
     *
     * @param userId 用户 ID
     * @return 求职目标，未填写时返回 null
     */
    UserProfileRespVO getUserProfileByUserId(Long userId);

    /**
     * 按用户 ID 保存求职目标，供 Agent 工具使用。
     *
     * @param userId 用户 ID
     * @param reqVO 保存请求
     * @return 保存后的求职目标
     */
    UserProfileRespVO saveUserProfileByUserId(Long userId, UserProfileSaveReqVO reqVO);

    /**
     * 取必填求职目标，供模拟面试（F5）与训练计划（F7）复用。
     *
     * @param userId 用户 ID
     * @return 求职目标
     * @throws com.wxy.career.common.exception.BizException 档案未填写时抛出 1101
     */
    UserProfileRespVO getRequiredUserProfile(Long userId);
}
