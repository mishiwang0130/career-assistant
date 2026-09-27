package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.SysToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * Access Token Mapper。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Mapper
public interface SysTokenMapper extends BaseMapper<SysToken> {

    /**
     * 按 jti 查询 Access Token 记录。
     *
     * @param jti JWT 唯一标识
     * @return Access Token 记录，不存在时返回 null
     */
    default SysToken selectByJti(String jti) {
        return selectOne(new LambdaQueryWrapper<SysToken>().eq(SysToken::getJti, jti));
    }
}
