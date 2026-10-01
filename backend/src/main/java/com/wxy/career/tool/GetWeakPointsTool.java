package com.wxy.career.tool;

import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.util.RuntimeContextUserUtil;
import com.wxy.career.vo.WeakPointsResultVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 读薄弱点工具。
 *
 * <p>F9 专项辅导的只读工具：助手上课前先用它定位「该讲哪一块」。数据源是 F6 的权威表
 * {@code knowledge_mastery}（掌握度与薄弱点只落 MySQL，不写记忆库）；不传关键词时返回标记为薄弱的知识点，
 * 传关键词时按知识点名称模糊匹配，让用户说「Redis 那块的」也能对上。
 *
 * <p>工具只读：不写库、不写长期记忆、不产生任何记忆写入；用户身份只从 {@code RuntimeContext.userId} 取，
 * 模型传入的任何用户标识都不被信任。没有数据时返回明确的空状态说明，由模型如实告诉用户。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Component
public class GetWeakPointsTool {

    /**
     * 掌握度服务，薄弱点的唯一读取入口。
     */
    @Resource
    private KnowledgeMasteryService knowledgeMasteryService;

    /**
     * 读取当前用户的知识点掌握度与薄弱点。
     *
     * @param keyword 知识点关键词，可为空
     * @param runtimeContext 运行时上下文，由框架注入
     * @return 薄弱点查询结果：是否有数据、条数、上限、说明与知识点条目
     */
    @Tool(name = "get_weak_points",
            description = "读取当前登录用户的知识点掌握度与薄弱点，用于就某个薄弱知识点讲解前先定位。"
                    + "不传 keyword 时只返回标记为薄弱的知识点，按掌握度从低到高排列；"
                    + "用户提到某个具体知识点（例如「Redis 那块的」「分布式锁再讲讲」）时传 keyword 做名称匹配，"
                    + "命中但不是薄弱点的知识点也会返回它当前的掌握度分数与等级。"
                    + "返回里的 message 说明空状态：没有面试记录、没有薄弱点、或关键词没有匹配到；"
                    + "遇到空状态要照实告诉用户，不要编造薄弱点。",
            readOnly = true)
    public WeakPointsResultVO getWeakPoints(
            @ToolParam(name = "keyword", required = false,
                    description = "知识点关键词或其中一段文字；用户点名了某个知识点时传入，"
                            + "例如 Redis、分布式锁、JVM 垃圾回收；不传则返回全部薄弱点")
            String keyword,
            RuntimeContext runtimeContext) {
        Long userId = RuntimeContextUserUtil.requireUserId(runtimeContext);
        WeakPointsResultVO result = knowledgeMasteryService.listWeakPoints(userId, keyword);
        log.info("读取薄弱点完成，userId={}，keyword={}，hasData={}，count={}/{}",
                userId, keyword, result.isHasData(), result.getCount(), result.getLimit());
        return result;
    }
}
