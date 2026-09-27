package com.wxy.career.common.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisUtil 数据类型操作测试。
 *
 * @author wxy
 * @date 2026-09-27
 */
@ExtendWith(MockitoExtension.class)
class RedisUtilTest {

    /**
     * 字符串 Redis 客户端。
     */
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    /**
     * String 类型操作。
     */
    @Mock
    private ValueOperations<String, String> valueOperations;

    /**
     * Hash 类型操作。
     */
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    /**
     * List 类型操作。
     */
    @Mock
    private ListOperations<String, String> listOperations;

    /**
     * Set 类型操作。
     */
    @Mock
    private SetOperations<String, String> setOperations;

    /**
     * ZSet 类型操作。
     */
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    /**
     * 被测 Redis 工具。
     */
    private RedisUtil redisUtil;

    /**
     * 初始化 RedisUtil。
     */
    @BeforeEach
    void setUp() {
        redisUtil = new RedisUtil();
        ReflectionTestUtils.setField(redisUtil, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(redisUtil, "objectMapper", new ObjectMapper());
    }

    /**
     * 验证 String 写入带数值和时间单位。
     */
    @Test
    void shouldSetStringWithTimeoutAndUnit() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        redisUtil.set("career:test:string", "value", 10L, TimeUnit.SECONDS);

        verify(valueOperations).set("career:test:string", "\"value\"", 10L, TimeUnit.SECONDS);
    }

    /**
     * 验证 String 读取。
     */
    @Test
    void shouldGetString() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("career:test:string")).thenReturn("\"value\"");

        assertThat(redisUtil.get("career:test:string", String.class)).isEqualTo("value");
    }

    /**
     * 验证 Hash 读取。
     */
    @Test
    void shouldGetHashValue() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("career:test:hash", "field")).thenReturn("\"value\"");

        assertThat(redisUtil.getHash("career:test:hash", "field", String.class)).isEqualTo("value");
    }

    /**
     * 验证 List 读取。
     */
    @Test
    void shouldGetListValue() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range("career:test:list", 0, -1)).thenReturn(List.of("\"a\"", "\"b\""));

        assertThat(redisUtil.getList("career:test:list", String.class)).containsExactly("a", "b");
    }

    /**
     * 验证 Set 读取。
     */
    @Test
    void shouldGetSetValue() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members("career:test:set")).thenReturn(Set.of("\"a\"", "\"b\""));

        assertThat(redisUtil.getSet("career:test:set", String.class)).containsExactlyInAnyOrder("a", "b");
    }

    /**
     * 验证 ZSet 范围读取。
     */
    @Test
    void shouldGetZSetRange() {
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.range("career:test:zset", 0, -1)).thenReturn(Set.of("\"a\"", "\"b\""));

        assertThat(redisUtil.getZSetRange("career:test:zset", 0, -1, String.class))
                .containsExactlyInAnyOrder("a", "b");
    }
}
