package com.wxy.career.service;

/**
 * 系统提示词提供者。
 *
 * <p>把提示词来源抽象成接口：当前实现读取 classpath 下的 Markdown 文件，后续如需改成数据库或
 * 管理后台配置，只需要替换实现，Agent 装配与调用链不需要改动。
 *
 * <p>一个 Agent 一份提示词：调用方传 Agent 标识，由实现决定读哪个文件、如何缓存。多 Agent 接入后
 * 不再有「当前提示词」这种单槽位概念，避免子 Agent 读到助手的提示词。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface SystemPromptProvider {

    /**
     * 按 Agent 标识获取系统提示词。
     *
     * @param agentId Agent 标识，与 {@code HarnessAgent} 的名字一致
     * @return 系统提示词
     */
    String prompt(String agentId);
}
