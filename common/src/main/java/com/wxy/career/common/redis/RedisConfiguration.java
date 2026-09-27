package com.wxy.career.common.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 客户端配置。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Configuration
public class RedisConfiguration {

    /**
     * 配置字符串 Redis 客户端，JSON 编解码统一由 RedisUtil 处理。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 字符串 Redis 客户端
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
