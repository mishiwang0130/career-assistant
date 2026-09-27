package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.SysRefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Refresh Token Mapper。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Mapper
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {

    /**
     * 按摘要查询 Refresh Token 记录。
     *
     * @param tokenHash Refresh Token 摘要
     * @return Refresh Token 记录，不存在时返回 null
     */
    default SysRefreshToken selectByTokenHash(String tokenHash) {
        return selectOne(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getTokenHash, tokenHash));
    }

    /**
     * 按 Access Token jti 查询有效的 Refresh Token 记录。
     *
     * @param accessJti Access Token 唯一标识
     * @return Refresh Token 记录，不存在时返回 null
     */
    default SysRefreshToken selectActiveByAccessJti(String accessJti) {
        return selectOne(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getAccessJti, accessJti)
                .eq(SysRefreshToken::getRevoked, SysRefreshToken.STATUS_ACTIVE));
    }

    /**
     * 条件撤销 Refresh Token，用受影响行数防止并发重复轮换。
     *
     * @param id 主键 ID
     * @param userId 操作用户 ID
     * @return 受影响行数
     */
    @Update("""
            UPDATE sys_refresh_token
            SET revoked = 1,
                revoked_at = NOW(),
                update_by = #{userId},
                update_time = NOW()
            WHERE id = #{id}
              AND revoked = 0
              AND is_delete = 0
            """)
    int revokeIfActive(@Param("id") Long id, @Param("userId") Long userId);
}
