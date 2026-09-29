package com.wxy.career.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 初始化脚本的契约测试。
 *
 * <p>真实取数需要 MySQL（测试环境不允许依赖），因此这里只固定脚本本身的约定：技能表存在、写入幂等、
 * 技能名与子 Agent 声明里的 resume-analysis 一致、正文非空且含维度与评分口径。
 * 真实环境按「重复执行脚本 → 表里只有一行 → 按名字能取到技能」的可复现步骤验收。
 *
 * @author wxy
 * @date 2026-09-29
 */
class SkillSeedScriptTest {

    /**
     * 建库脚本相对模块目录的位置。
     */
    private static final Path SCRIPT_PATH = Path.of("..", "sql", "career_assistant.sql");

    /**
     * 验证建库脚本包含幂等的 resume-analysis 技能与框架要求的技能表。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedResumeAnalysisSkillIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `agentscope_skills`");
        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `agentscope_skill_resources`");
        assertThat(script).contains("INSERT INTO `agentscope_skills`");
        assertThat(script).contains("'resume-analysis'");
        // 幂等写法：重复执行不产生重复数据（命中 name 唯一键时只做同值更新，不覆盖人工调整）。
        assertThat(script).contains("ON DUPLICATE KEY UPDATE");

        String skillSection = script.substring(
                script.indexOf("INSERT INTO `agentscope_skills`"),
                script.indexOf("ON DUPLICATE KEY UPDATE"));
        assertThat(skillSection).contains("诊断维度").contains("评分口径").contains("输出结构");
        assertThat(skillSection).contains("submit_resume_diagnosis");
        assertThat(skillSection.length()).isGreaterThan(500);
    }

    /**
     * 验证建库脚本包含 F5 的面试问答表与两个幂等技能。
     *
     * <p>真实取数同样需要 MySQL，因此这里只固定脚本契约：面试问答表带账号与会话索引、两个技能都写成幂等插入、
     * 内容覆盖三类题型配比 / 难度阶梯 / 追问换题（含答错记错题）与三档判定。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedInterviewSkillsIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("CREATE TABLE IF NOT EXISTS `interview_qa`");
        assertThat(script).contains("idx_interview_qa_user_session");
        assertThat(script).contains("'interview-questioning'");
        assertThat(script).contains("'answer-evaluation'");
        // 面试结果的逐题明细与标准答案存在这一列，技能也要求评分子 Agent 给出 referenceAnswer。
        assertThat(script).contains("evaluation_json");
        assertThat(script).contains("referenceAnswer");

        int questioning = script.indexOf("'interview-questioning'");
        String questioningSection = script.substring(
                questioning, script.indexOf("ON DUPLICATE KEY UPDATE", questioning));
        assertThat(questioningSection).contains("4:2:2").contains("难度阶梯")
                .contains("追问").contains("换题").contains("错题");
        assertThat(questioningSection.length()).isGreaterThan(500);

        int evaluation = script.indexOf("'answer-evaluation'");
        String evaluationSection = script.substring(
                evaluation, script.indexOf("ON DUPLICATE KEY UPDATE", evaluation));
        assertThat(evaluationSection).contains("CORRECT").contains("PARTIAL").contains("WRONG")
                .contains("掌握度");
        assertThat(evaluationSection.length()).isGreaterThan(500);
    }
}
