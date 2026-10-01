package com.wxy.career.tool;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.vo.WeakPointVO;
import com.wxy.career.vo.WeakPointsResultVO;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读薄弱点工具测试。
 *
 * <p>固定三件事：用户身份只从运行时上下文取（模型无法指定 userId）、关键词原样透传给服务层、
 * 以及服务层返回的空状态原样回到模型（工具不把「没有数据」变成异常）。
 *
 * @author wxy
 * @date 2026-10-01
 */
class GetWeakPointsToolTest {

    /**
     * 掌握度服务 mock。
     */
    private KnowledgeMasteryService knowledgeMasteryService;

    /**
     * 被测工具。
     */
    private GetWeakPointsTool getWeakPointsTool;

    /**
     * 初始化被测工具。
     */
    @BeforeEach
    void setUp() {
        knowledgeMasteryService = mock(KnowledgeMasteryService.class);
        getWeakPointsTool = new GetWeakPointsTool();
        ReflectionTestUtils.setField(getWeakPointsTool, "knowledgeMasteryService", knowledgeMasteryService);
    }

    /**
     * 验证按运行时上下文里的用户身份与用户给出的关键词查询。
     */
    @Test
    void shouldQueryWithRuntimeUserIdAndKeyword() {
        WeakPointsResultVO expected = resultWithOnePoint();
        when(knowledgeMasteryService.listWeakPoints(7L, "Redis")).thenReturn(expected);

        WeakPointsResultVO result = getWeakPointsTool.getWeakPoints(
                "Redis", RuntimeContext.builder().userId("7").sessionId("100").build());

        assertThat(result.getCount()).isEqualTo(1);
        assertThat(result.getPoints().get(0).getKnowledgePoint()).isEqualTo("Redis 分布式锁");
        verify(knowledgeMasteryService).listWeakPoints(7L, "Redis");
    }

    /**
     * 验证不带关键词时按空关键词查询，返回的是「全部薄弱点」而不是排除。
     */
    @Test
    void shouldQueryWithoutKeyword() {
        when(knowledgeMasteryService.listWeakPoints(7L, null)).thenReturn(new WeakPointsResultVO());

        getWeakPointsTool.getWeakPoints(null, RuntimeContext.builder().userId("7").sessionId("100").build());

        verify(knowledgeMasteryService).listWeakPoints(7L, null);
    }

    /**
     * 验证空状态原样返回给模型：没有面试记录时不抛异常，只把说明带回去。
     */
    @Test
    void shouldReturnEmptyStateInsteadOfThrowing() {
        WeakPointsResultVO empty = new WeakPointsResultVO();
        empty.setMessage("还没有面试记录，暂时没有薄弱点数据，建议先完成一场模拟面试再来复盘。");
        when(knowledgeMasteryService.listWeakPoints(7L, null)).thenReturn(empty);

        WeakPointsResultVO result = getWeakPointsTool.getWeakPoints(
                null, RuntimeContext.builder().userId("7").sessionId("100").build());

        assertThat(result.isHasData()).isFalse();
        assertThat(result.getPoints()).isEmpty();
        assertThat(result.getMessage()).contains("还没有面试记录");
    }

    /**
     * 验证缺少运行时用户身份时按未登录处理，且不访问任何掌握度数据。
     */
    @Test
    void shouldRejectMissingRuntimeUserId() {
        assertThatThrownBy(() -> getWeakPointsTool.getWeakPoints(
                null, RuntimeContext.builder().sessionId("100").build()))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(
                        ((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(ErrorConstant.UNAUTHORIZED.getCode()));
        assertThatThrownBy(() -> getWeakPointsTool.getWeakPoints(null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> getWeakPointsTool.getWeakPoints(
                null, RuntimeContext.builder().userId("abc").sessionId("100").build()))
                .isInstanceOf(BizException.class);

        verify(knowledgeMasteryService, never()).listWeakPoints(anyLong(), any());
    }

    /**
     * 构造一条命中记录的结果，供断言使用。
     *
     * @return 查询结果
     */
    private WeakPointsResultVO resultWithOnePoint() {
        WeakPointVO point = new WeakPointVO();
        point.setKnowledgePoint("Redis 分布式锁");
        point.setMasteryScore(42);
        point.setMasteryLevel("NEEDS_WORK");
        point.setWeak(true);
        List<WeakPointVO> points = new ArrayList<>();
        points.add(point);
        WeakPointsResultVO result = new WeakPointsResultVO();
        result.setHasData(true);
        result.setCount(points.size());
        result.setLimit(20);
        result.setPoints(points);
        return result;
    }
}
