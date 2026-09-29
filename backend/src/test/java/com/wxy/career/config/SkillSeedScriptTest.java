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
     * 验证建库脚本幂等追加了 F3 的 job-match 技能，并且没有重复建表。
     *
     * <p>F3 复用 F2 建好的技能表，脚本里只允许出现一行技能写入：表结构重复建会掩盖框架托管表的
     * 真实结构，写入不幂等则每次执行都会多出一条规则。
     *
     * @throws Exception 读取脚本失败
     */
    @Test
    void shouldSeedJobMatchSkillIdempotently() throws Exception {
        String script = Files.readString(SCRIPT_PATH, StandardCharsets.UTF_8);

        assertThat(script).contains("'job-match'");
        assertThat(script).contains("'f3-job-match'");
        assertThat(script).contains("ON DUPLICATE KEY UPDATE");

        // F3 段从自己的分隔注释起，到本段的幂等收尾止：段内只写技能行，不重复建表。
        int sectionStart = script.indexOf("F3 岗位匹配");
        int sectionEnd = script.lastIndexOf("ON DUPLICATE KEY UPDATE");
        assertThat(sectionStart).isGreaterThan(0);
        assertThat(sectionEnd).isGreaterThan(sectionStart);

        String jobMatchSection = script.substring(sectionStart, sectionEnd);
        assertThat(jobMatchSection).doesNotContain("CREATE TABLE");
        assertThat(jobMatchSection)
                .contains("匹配维度")
                .contains("命中关键词")
                .contains("缺失关键词")
                .contains("匹配度")
                .contains("输出结构")
                .contains("底线")
                // 差距补齐建议最容易滑向「替用户编数字」，这条底线必须写进技能正文。
                .contains("如果你确实做过");
        assertThat(jobMatchSection.length()).isGreaterThan(500);
    }
}
