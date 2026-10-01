package com.wxy.career.service.impl;

import com.wxy.career.config.TutoringProperties;
import com.wxy.career.mapper.KnowledgeMasteryMapper;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.vo.WeakPointVO;
import com.wxy.career.vo.WeakPointsResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 专项辅导读薄弱点（F9）的服务层测试。
 *
 * <p>只校验本模块新增的查询口径：不传关键词只回薄弱点、关键词命中非薄弱点也回、排序由服务层定死、
 * 上限截断、三类空状态文案。不依赖 MySQL：掌握度 Mapper 用 mock 注入。
 *
 * @author wxy
 * @date 2026-10-01
 */
class KnowledgeMasteryServiceImplTest {

    /**
     * 掌握度 Mapper mock。
     */
    private KnowledgeMasteryMapper knowledgeMasteryMapper;

    /**
     * 被测服务。
     */
    private KnowledgeMasteryServiceImpl knowledgeMasteryService;

    /**
     * 初始化被测服务，默认上限沿用配置默认值 20。
     */
    @BeforeEach
    void setUp() {
        knowledgeMasteryMapper = mock(KnowledgeMasteryMapper.class);
        knowledgeMasteryService = new KnowledgeMasteryServiceImpl();
        ReflectionTestUtils.setField(knowledgeMasteryService, "knowledgeMasteryMapper", knowledgeMasteryMapper);
        ReflectionTestUtils.setField(knowledgeMasteryService, "tutoringProperties", new TutoringProperties());
    }

    /**
     * 不传关键词时只返回薄弱点，并按掌握度从低到高排列。
     */
    @Test
    void shouldReturnOnlyWeakPointsOrderedByScore() {
        // 故意打乱顺序，验证排序由服务层定死，不依赖 Mapper 的排序。
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "JVM 垃圾回收", 80, "PROFICIENT", 0),
                row(2L, "Redis 分布式锁", 55, "NEEDS_WORK", 1),
                row(3L, "MySQL 索引", 24, "WEAK", 1)));

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, null);

        assertThat(result.isHasData()).isTrue();
        assertThat(result.getCount()).isEqualTo(2);
        assertThat(result.getLimit()).isEqualTo(20);
        assertThat(result.getMessage()).isEmpty();
        assertThat(result.getPoints()).extracting(WeakPointVO::getKnowledgePoint)
                .containsExactly("MySQL 索引", "Redis 分布式锁");
    }

    /**
     * 关键词命中但不是薄弱点的知识点也要返回，用户问到的知识点不该「查不到」。
     */
    @Test
    void shouldReturnMatchedNonWeakPointWhenKeywordGiven() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "Redis 持久化", 82, "PROFICIENT", 0),
                row(2L, "Redis 分布式锁", 45, "NEEDS_WORK", 1),
                row(3L, "MySQL 索引", 24, "WEAK", 1)));

        // 关键词大小写与空白都应该被归一化：用户常写 redis。
        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, "  redis  ");

        assertThat(result.getPoints()).extracting(WeakPointVO::getKnowledgePoint)
                .containsExactly("Redis 分布式锁", "Redis 持久化");
        assertThat(result.getPoints()).extracting(WeakPointVO::isWeak)
                .containsExactly(true, false);
    }

    /**
     * 关键词归一化后再拼进说明文本：折叠空白、截断长度，不把原文整段带出去。
     */
    @Test
    void shouldNormalizeKeywordInMessage() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "MySQL 索引", 24, "WEAK", 1)));
        String longKeyword = "Redis\n".repeat(20) + "分布式锁";

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, longKeyword);

        assertThat(result.getCount()).isZero();
        assertThat(result.getMessage()).startsWith("没有找到与「").doesNotContain("\n");
        assertThat(result.getMessage().length()).isLessThan(100);
    }

    /**
     * 关键词没有匹配到任何知识点时给出说明，而不是当成「没有数据」。
     */
    @Test
    void shouldExplainKeywordMiss() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "MySQL 索引", 24, "WEAK", 1)));

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, "Kafka");

        assertThat(result.isHasData()).isTrue();
        assertThat(result.getCount()).isZero();
        assertThat(result.getMessage()).contains("Kafka");
    }

    /**
     * 有掌握度记录但没有薄弱点时，说明「没有标记为薄弱」，并保留 hasData=true。
     */
    @Test
    void shouldExplainNoWeakPoint() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "JVM 垃圾回收", 88, "PROFICIENT", 0)));

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, null);

        assertThat(result.isHasData()).isTrue();
        assertThat(result.getPoints()).isEmpty();
        assertThat(result.getMessage()).contains("没有标记为薄弱");
    }

    /**
     * 完全没有掌握度记录时给出去练一场的空状态，hasData=false 且不抛异常。
     */
    @Test
    void shouldExplainNoData() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of());

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, null);

        assertThat(result.isHasData()).isFalse();
        assertThat(result.getPoints()).isEmpty();
        assertThat(result.getMessage()).contains("还没有面试记录");
    }

    /**
     * Mapper 返回 null 时按没有数据处理，不抛空指针。
     */
    @Test
    void shouldTreatNullRowsAsNoData() {
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(null);

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, null);

        assertThat(result.isHasData()).isFalse();
        assertThat(result.getMessage()).contains("还没有面试记录");
    }

    /**
     * 超过上限时只返回掌握度最低的前几条，并用 message 说明被截断。
     */
    @Test
    void shouldTruncateToConfiguredLimit() {
        TutoringProperties tutoringProperties = new TutoringProperties();
        tutoringProperties.setMaxWeakPoints(2);
        ReflectionTestUtils.setField(knowledgeMasteryService, "tutoringProperties", tutoringProperties);
        when(knowledgeMasteryMapper.selectByUser(1L)).thenReturn(List.of(
                row(1L, "知识点一", 50, "NEEDS_WORK", 1),
                row(2L, "知识点二", 40, "NEEDS_WORK", 1),
                row(3L, "知识点三", 30, "NEEDS_WORK", 1),
                row(4L, "知识点四", 20, "WEAK", 1)));

        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(1L, null);

        assertThat(result.getCount()).isEqualTo(2);
        assertThat(result.getLimit()).isEqualTo(2);
        assertThat(result.getPoints()).extracting(WeakPointVO::getKnowledgePoint)
                .containsExactly("知识点四", "知识点三");
        assertThat(result.getMessage()).contains("只返回最需要补的前 2 个");
    }

    /**
     * 构造一条掌握度记录。
     *
     * @param id 主键
     * @param knowledgePoint 知识点
     * @param score 掌握度分数
     * @param level 掌握度等级
     * @param weak 是否薄弱：1 是、0 否
     * @return 掌握度记录
     */
    private KnowledgeMastery row(Long id, String knowledgePoint, int score, String level, int weak) {
        KnowledgeMastery record = new KnowledgeMastery();
        record.setId(id);
        record.setUserId(1L);
        record.setKnowledgePoint(knowledgePoint);
        record.setMasteryScore(score);
        record.setMasteryLevel(level);
        record.setWeak(weak);
        record.setEvidenceCount(3);
        record.setLastOutcome(weak == 1 ? "WRONG" : "CORRECT");
        return record;
    }
}
