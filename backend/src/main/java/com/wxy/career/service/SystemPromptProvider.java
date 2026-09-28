package com.wxy.career.service;

/**
 * 系统提示词提供者。
 *
 * <p>把提示词来源抽象成接口：当前实现读取 YAML 配置，后续如需改成数据库或管理后台配置，
 * 只需要替换实现，Agent 装配与调用链不需要改动。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface SystemPromptProvider {

    /**
     * 获取当前生效的系统提示词。
     *
     * @return 系统提示词
     */
    String currentPrompt();
}
