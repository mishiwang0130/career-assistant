package com.wxy.career.common.redis;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 常用操作。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Component
public class RedisUtil {

    /**
     * Redis 操作模板。
     */
    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 写入永久缓存。
     *
     * @param key 缓存 key
     * @param value 缓存值
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 写入带过期时间的缓存。
     *
     * @param key 缓存 key
     * @param value 缓存值
     * @param timeout 过期时间
     */
    public void set(String key, Object value, Duration timeout) {
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    /**
     * 获取缓存值。
     *
     * @param key 缓存 key
     * @param <T> 缓存值类型
     * @return 缓存值
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    /**
     * 删除缓存。
     *
     * @param key 缓存 key
     * @return 是否删除成功
     */
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    /**
     * 设置缓存过期时间。
     *
     * @param key 缓存 key
     * @param timeout 过期时间
     * @return 是否设置成功
     */
    public Boolean expire(String key, Duration timeout) {
        return redisTemplate.expire(key, timeout);
    }

    /**
     * 判断缓存 key 是否存在。
     *
     * @param key 缓存 key
     * @return 是否存在
     */
    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }
}
