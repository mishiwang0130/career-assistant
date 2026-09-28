package com.wxy.career.controller;

import com.wxy.career.common.result.Result;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.vo.UserProfileSaveReqVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 求职目标接口。
 *
 * <p>只认当前登录用户：userId 由 Service 从 {@code LoginUserHolder} 取，接口不接受前端传 userId。
 * 未填写时 GET 返回 data 为 null（不是 404，也不是字段全空的假对象），前端据此判断是否提示补填。
 *
 * @author wxy
 * @date 2026-09-28
 */
@RestController
@RequestMapping("/api/user-profile")
public class UserProfileController {

    /**
     * 求职目标服务。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 查询当前登录用户的求职目标。
     *
     * @return 求职目标，未填写时 data 为 null
     */
    @GetMapping
    public Result<UserProfileRespVO> get() {
        return Result.success(userProfileService.getCurrentUserProfile());
    }

    /**
     * 保存当前登录用户的求职目标。
     *
     * @param reqVO 保存请求
     * @return 保存后的求职目标
     */
    @PutMapping
    public Result<UserProfileRespVO> save(@Valid @RequestBody UserProfileSaveReqVO reqVO) {
        return Result.success(userProfileService.saveCurrentUserProfile(reqVO));
    }
}
