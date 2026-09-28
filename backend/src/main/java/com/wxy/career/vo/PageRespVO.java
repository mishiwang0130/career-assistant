package com.wxy.career.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应。
 *
 * <p>由 M2 冻结，后续所有分页接口统一使用该结构，避免各模块返回不同字段名。
 *
 * @param <T> 记录类型
 * @author wxy
 * @date 2026-09-28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageRespVO<T> {

    /**
     * 总记录数。
     */
    private Long total;

    /**
     * 当前页码，从 1 开始。
     */
    private Long pageNum;

    /**
     * 每页条数。
     */
    private Long pageSize;

    /**
     * 当前页记录。
     */
    private List<T> records;

    /**
     * 构建分页响应。
     *
     * @param total 总记录数
     * @param pageNum 当前页码
     * @param pageSize 每页条数
     * @param records 当前页记录
     * @param <T> 记录类型
     * @return 分页响应
     */
    public static <T> PageRespVO<T> of(long total, long pageNum, long pageSize, List<T> records) {
        return new PageRespVO<>(total, pageNum, pageSize, records);
    }
}
