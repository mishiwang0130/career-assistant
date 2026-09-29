package com.wxy.career.service;

import com.wxy.career.vo.InterviewAnswerResultVO;
import com.wxy.career.vo.InterviewAnswerSubmitVO;
import com.wxy.career.vo.InterviewStateRespVO;

/**
 * 模拟面试流程服务。
 *
 * <p>面试的流程规则（追问 / 换题 / 难度阶梯 / 进度 / 结束条件）全部在 Java 侧固化，不依赖模型自觉：
 * 模型只负责「问什么、怎么说」，是否追问、下一题用哪个难度由本服务说了算。整场面试的进度与当前难度
 * 由 {@code interview_qa} 的记录回放得出，因此离开再回来能接着答，也不需要额外的面试级表。
 *
 * <p>落库时机固定在流正常结束时（见 {@link #commitTurn}）：模型调用工具只把本回合暂存到运行态缓冲，
 * 这样既避开框架「写工具需要人工确认」的权限门，也保证客户端中途断开后跑完的这一轮照样落库。
 *
 * @author wxy
 * @date 2026-09-29
 */
public interface InterviewFlowService {

    /**
     * 面试回合准入校验并记下本回合的用户回答。
     *
     * <p>由对话通道在进流前调用：面试会话要校验求职目标必填（1101）与未结束（1501）；
     * 助手会话或会话元数据缺失时返回 null，让调用方按原链路继续。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param answer 本回合用户发送的消息内容
     * @return 面试状态；非面试会话返回 null
     */
    InterviewStateRespVO prepareTurn(Long userId, String sessionId, String answer);

    /**
     * 读取当前面试状态（进度、当前难度、是否结束）。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 面试状态
     */
    InterviewStateRespVO getState(Long userId, String sessionId);

    /**
     * 读取当前登录用户指定会话的面试状态，供面试状态接口使用。
     *
     * @param sessionId 会话 ID
     * @return 面试状态
     */
    InterviewStateRespVO getCurrentUserState(String sessionId);

    /**
     * 记录本回合的判定结果并算出下一步指令。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param submitVO 模型提交的判定结果
     * @return 下一步指令
     */
    InterviewAnswerResultVO recordAnswer(Long userId, String sessionId, InterviewAnswerSubmitVO submitVO);

    /**
     * 落库本回合记录并返回最新状态，由对话通道在流正常结束时调用。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @return 最新面试状态；本回合没有待落库记录时返回 null
     */
    InterviewStateRespVO commitTurn(Long userId, String sessionId);

    /**
     * 丢弃本回合的运行态缓冲，流异常结束或删除会话时调用。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    void discardTurn(Long userId, String sessionId);
}
