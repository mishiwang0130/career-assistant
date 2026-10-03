package com.wxy.career.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长期记忆与会话归档配置的契约测试。
 *
 * <p>归档配置是新加的，四份 Profile 都要同步补齐，写错 key 或漏掉一个文件不会编译失败，只会在部署时静默
 * 退回默认值。这里把「四份文件都写了」与「按 application.yml 能绑定出期望值」两条固定下来，
 * 不依赖 Spring 上下文、MySQL、Redis 与模型密钥。
 *
 * @author wxy
 * @date 2026-10-03
 */
class MemoryPropertiesBindingTest {

    /**
     * 归档配置必须在四份 Profile 文件里同步出现。
     */
    private static final List<String> PROFILE_FILES = List.of(
            "application.yml", "application-local.yml", "application-dev.yml", "application-prod.yml");

    /**
     * 四份文件都要写全的归档配置片段（值与公共默认值一致；prod 只允许覆盖调度参数）。
     */
    private static final List<String> REQUIRED_ARCHIVE_LINES = List.of(
            "archive:",
            "MEMORY_ARCHIVE_ENABLED",
            "MEMORY_ARCHIVE_CRON",
            "MEMORY_ARCHIVE_ZONE",
            "quiet-minutes: 30",
            "batch-size: 20",
            "max-attempts: 5",
            "max-memories: 3",
            "max-transcript-chars: 12000",
            "timeout-seconds: 120");

    /**
     * 校验四份 Profile 都同步补齐了归档配置项。
     *
     * @throws IOException 读取配置文件失败
     */
    @Test
    @DisplayName("四份 Profile 都补齐了归档配置")
    void shouldDeclareArchiveConfigInEveryProfile() throws IOException {
        for (String file : PROFILE_FILES) {
            String content = new String(new ClassPathResource(file).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            REQUIRED_ARCHIVE_LINES.forEach(line ->
                    assertThat(content).as("%s 缺少归档配置：%s", file, line).contains(line));
        }
    }

    /**
     * 校验公共配置能绑定出预期的归档参数（含默认 CRON 与安静阈值）。
     *
     * @throws IOException 读取配置文件失败
     */
    @Test
    @DisplayName("归档配置能绑定到 MemoryProperties")
    void shouldBindArchiveConfig() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        // 只加载公共配置：绑定校验关心的是 key 与默认值，profile 只是把同样的值再写一遍。
        loader.load("application.yml", new ClassPathResource("application.yml"))
                .forEach(propertySource -> sources.addLast(propertySource));

        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
        MemoryProperties properties = binder.bind("app.memory", Bindable.of(MemoryProperties.class))
                .orElseThrow(() -> new IllegalStateException("app.memory 配置绑定失败"));

        assertThat(properties.isEnabled()).isTrue();
        MemoryProperties.Archive archive = properties.getArchive();
        assertThat(archive.isEnabled()).isTrue();
        assertThat(archive.getCron()).isEqualTo("0 0/30 * * * ?");
        assertThat(archive.getZoneId()).isEqualTo("Asia/Shanghai");
        assertThat(archive.getQuietMinutes()).isEqualTo(30);
        assertThat(archive.getBatchSize()).isEqualTo(20);
        assertThat(archive.getMaxAttempts()).isEqualTo(5);
        assertThat(archive.getMaxMemories()).isEqualTo(3);
        assertThat(archive.getMaxTranscriptChars()).isEqualTo(12000);
        assertThat(archive.getTimeoutSeconds()).isEqualTo(120);
    }
}
