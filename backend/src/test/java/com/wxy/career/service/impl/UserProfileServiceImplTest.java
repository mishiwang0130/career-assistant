package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.mapper.UserProfileMapper;
import com.wxy.career.po.UserProfile;
import com.wxy.career.vo.UserProfileRespVO;
import com.wxy.career.vo.UserProfileSaveReqVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 求职目标服务测试。
 *
 * <p>Mapper 使用 mock，覆盖「新增 / 覆盖已有记录 / 未填写返回空 / 取必填档案抛 1101 / 按用户隔离」
 * 五类行为，不做真实数据库读写。
 *
 * @author wxy
 * @date 2026-09-28
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    /**
     * 求职目标 Mapper。
     */
    @Mock
    private UserProfileMapper userProfileMapper;

    /**
     * 被测求职目标服务。
     */
    @InjectMocks
    private UserProfileServiceImpl userProfileService;

    /**
     * 初始化登录用户上下文。
     */
    @BeforeEach
    void setUp() {
        LoginUserHolder.set(new LoginUser(1L, "alice", "jti-1"));
    }

    /**
     * 清理登录用户上下文。
     */
    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /**
     * 验证未填写时查询返回 null，而不是字段全空的假对象。
     */
    @Test
    void shouldReturnNullWhenProfileMissing() {
        when(userProfileMapper.selectByUserId(1L)).thenReturn(null);

        assertThat(userProfileService.getCurrentUserProfile()).isNull();
    }

    /**
     * 验证首次保存走新增，并把目标岗位去空白后落库。
     */
    @Test
    void shouldInsertWhenProfileMissing() {
        when(userProfileMapper.selectByUserId(1L)).thenReturn(null);

        UserProfileRespVO saved = userProfileService.saveCurrentUserProfile(buildReqVO("  后端开发  ", 3));

        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).insert(captor.capture());
        UserProfile inserted = captor.getValue();
        assertThat(inserted.getUserId()).isEqualTo(1L);
        assertThat(inserted.getTargetPosition()).isEqualTo("后端开发");
        assertThat(inserted.getWorkYears()).isEqualTo(3);
        assertThat(saved.getTargetPosition()).isEqualTo("后端开发");
        assertThat(saved.getWorkYears()).isEqualTo(3);
        verify(userProfileMapper, never()).updateById(any(UserProfile.class));
    }

    /**
     * 验证已有记录时走更新且不新增，避免同一用户出现第二行档案。
     */
    @Test
    void shouldUpdateExistingProfile() {
        UserProfile existing = new UserProfile();
        existing.setId(9L);
        existing.setUserId(1L);
        existing.setTargetPosition("测试开发");
        existing.setWorkYears(1);
        when(userProfileMapper.selectByUserId(1L)).thenReturn(existing);

        UserProfileRespVO saved = userProfileService.saveCurrentUserProfile(buildReqVO("数据开发", 6));

        ArgumentCaptor<UserProfile> captor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileMapper).updateById(captor.capture());
        UserProfile updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(9L);
        assertThat(updated.getUserId()).isNull();
        assertThat(updated.getTargetPosition()).isEqualTo("数据开发");
        assertThat(updated.getWorkYears()).isEqualTo(6);
        verify(userProfileMapper, never()).insert(any(UserProfile.class));
        assertThat(saved.getTargetPosition()).isEqualTo("数据开发");
        assertThat(saved.getWorkYears()).isEqualTo(6);
    }

    /**
     * 验证「取必填档案」在未填写时抛业务错误码 1101，供 F5 / F7 复用。
     */
    @Test
    void shouldThrowWhenRequiredProfileMissing() {
        when(userProfileMapper.selectByUserId(1L)).thenReturn(null);

        assertThatThrownBy(() -> userProfileService.getRequiredUserProfile(1L))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1101);
                    // 业务异常统一返回 HTTP 200，失败语义由 code 表达。
                    assertThat(bizException.getHttpStatus().value()).isEqualTo(200);
                });
    }

    /**
     * 验证按用户 ID 读取只取该用户的档案：另一个账号看不到前一个账号的数据。
     */
    @Test
    void shouldIsolateProfileBetweenUsers() {
        UserProfile otherUserProfile = new UserProfile();
        otherUserProfile.setUserId(2L);
        otherUserProfile.setTargetPosition("算法工程");
        otherUserProfile.setWorkYears(5);
        when(userProfileMapper.selectByUserId(2L)).thenReturn(otherUserProfile);
        when(userProfileMapper.selectByUserId(1L)).thenReturn(null);

        assertThat(userProfileService.getUserProfileByUserId(2L).getTargetPosition()).isEqualTo("算法工程");
        assertThat(userProfileService.getCurrentUserProfile()).isNull();
        verify(userProfileMapper).selectByUserId(2L);
        verify(userProfileMapper).selectByUserId(1L);
    }

    /**
     * 验证用户 ID 为空时直接返回 null，不发起无主查询。
     */
    @Test
    void shouldReturnNullWhenUserIdMissing() {
        assertThat(userProfileService.getUserProfileByUserId(null)).isNull();
        verifyNoInteractions(userProfileMapper);
    }

    /**
     * 构造保存请求。
     *
     * @param targetPosition 目标岗位
     * @param workYears 当前工作年限
     * @return 保存请求
     */
    private UserProfileSaveReqVO buildReqVO(String targetPosition, Integer workYears) {
        UserProfileSaveReqVO reqVO = new UserProfileSaveReqVO();
        reqVO.setTargetPosition(targetPosition);
        reqVO.setWorkYears(workYears);
        return reqVO;
    }
}
