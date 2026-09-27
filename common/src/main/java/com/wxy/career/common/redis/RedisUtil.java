package com.wxy.career.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 常用操作。
 *
 * <p>统一使用 StringRedisTemplate 保存 JSON 字符串，调用方无需感知序列化细节。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Component
public class RedisUtil {

    /**
     * 字符串 Redis 客户端。
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * JSON 编解码器。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 写入 String 类型缓存。
     *
     * @param key 缓存 key
     * @param value 缓存值
     */
    public void set(String key, Object value) {
        stringRedisTemplate.opsForValue().set(key, toJson(value));
    }

    /**
     * 写入带过期时间的 String 类型缓存。
     *
     * @param key 缓存 key
     * @param value 缓存值
     * @param timeout 过期时间数值
     * @param unit 过期时间单位
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, toJson(value), timeout, unit);
    }

    /**
     * 获取 String 类型缓存。
     *
     * @param key 缓存 key
     * @param clazz 缓存值类型
     * @param <T> 缓存值泛型
     * @return 缓存值
     */
    public <T> T get(String key, Class<T> clazz) {
        return fromJson(stringRedisTemplate.opsForValue().get(key), clazz);
    }

    /**
     * 仅当缓存不存在时写入 String 类型缓存。
     *
     * @param key 缓存 key
     * @param value 缓存值
     * @param timeout 过期时间数值
     * @param unit 过期时间单位
     * @return true 表示写入成功
     */
    public Boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        return stringRedisTemplate.opsForValue().setIfAbsent(key, toJson(value), timeout, unit);
    }

    /**
     * 删除缓存。
     *
     * @param key 缓存 key
     * @return 是否删除成功
     */
    public Boolean delete(String key) {
        return stringRedisTemplate.delete(key);
    }

    /**
     * 设置缓存过期时间。
     *
     * @param key 缓存 key
     * @param timeout 过期时间数值
     * @param unit 过期时间单位
     * @return 是否设置成功
     */
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return stringRedisTemplate.expire(key, timeout, unit);
    }

    /**
     * 判断缓存 key 是否存在。
     *
     * @param key 缓存 key
     * @return 是否存在
     */
    public Boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    /**
     * 写入 Hash 类型的一个字段。
     *
     * @param key 缓存 key
     * @param hashKey Hash 字段名
     * @param value Hash 字段值
     */
    public void setHash(String key, String hashKey, Object value) {
        stringRedisTemplate.opsForHash().put(key, hashKey, toJson(value));
    }

    /**
     * 获取 Hash 类型的一个字段。
     *
     * @param key 缓存 key
     * @param hashKey Hash 字段名
     * @param clazz 字段值类型
     * @param <T> 字段值泛型
     * @return 字段值
     */
    public <T> T getHash(String key, String hashKey, Class<T> clazz) {
        Object value = stringRedisTemplate.opsForHash().get(key, hashKey);
        return value == null ? null : fromJson(value.toString(), clazz);
    }

    /**
     * 获取 Hash 类型全部字段。
     *
     * @param key 缓存 key
     * @param clazz 字段值类型
     * @param <T> 字段值泛型
     * @return Hash 字段映射
     */
    public <T> Map<String, T> getHashAll(String key, Class<T> clazz) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(key);
        Map<String, T> result = new LinkedHashMap<>(entries.size());
        entries.forEach((hashKey, value) ->
                result.put(hashKey.toString(), fromJson(value.toString(), clazz)));
        return result;
    }

    /**
     * 判断 Hash 类型字段是否存在。
     *
     * @param key 缓存 key
     * @param hashKey Hash 字段名
     * @return 是否存在
     */
    public Boolean hasHashKey(String key, String hashKey) {
        return stringRedisTemplate.opsForHash().hasKey(key, hashKey);
    }

    /**
     * 删除 Hash 类型字段。
     *
     * @param key 缓存 key
     * @param hashKeys Hash 字段名
     * @return 删除数量
     */
    public Long deleteHash(String key, String... hashKeys) {
        return stringRedisTemplate.opsForHash().delete(key, (Object[]) hashKeys);
    }

    /**
     * 覆盖写入 List 类型缓存。
     *
     * @param key 缓存 key
     * @param values 列表值
     */
    public void setList(String key, Collection<?> values) {
        delete(key);
        if (values == null || values.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForList().rightPushAll(key, toJsonCollection(values));
    }

    /**
     * 覆盖写入带过期时间的 List 类型缓存。
     *
     * @param key 缓存 key
     * @param values 列表值
     * @param timeout 过期时间数值
     * @param unit 过期时间单位
     */
    public void setList(String key, Collection<?> values, long timeout, TimeUnit unit) {
        setList(key, values);
        expire(key, timeout, unit);
    }

    /**
     * 获取 List 类型缓存。
     *
     * @param key 缓存 key
     * @param clazz 元素类型
     * @param <T> 元素泛型
     * @return 列表值
     */
    public <T> List<T> getList(String key, Class<T> clazz) {
        List<String> values = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> fromJson(value, clazz)).collect(Collectors.toList());
    }

    /**
     * 从右侧追加一个 List 元素。
     *
     * @param key 缓存 key
     * @param value 元素值
     * @return 列表长度
     */
    public Long pushList(String key, Object value) {
        return stringRedisTemplate.opsForList().rightPush(key, toJson(value));
    }

    /**
     * 获取 List 长度。
     *
     * @param key 缓存 key
     * @return 列表长度
     */
    public Long sizeList(String key) {
        return stringRedisTemplate.opsForList().size(key);
    }

    /**
     * 写入 Set 类型缓存。
     *
     * @param key 缓存 key
     * @param values 集合值
     * @return 新增元素数量
     */
    public Long addSet(String key, Collection<?> values) {
        String[] jsonValues = toJsonCollection(values).toArray(String[]::new);
        return stringRedisTemplate.opsForSet().add(key, jsonValues);
    }

    /**
     * 获取 Set 类型缓存。
     *
     * @param key 缓存 key
     * @param clazz 元素类型
     * @param <T> 元素泛型
     * @return 集合值
     */
    public <T> Set<T> getSet(String key, Class<T> clazz) {
        Set<String> values = stringRedisTemplate.opsForSet().members(key);
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(value -> fromJson(value, clazz))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 判断 Set 成员是否存在。
     *
     * @param key 缓存 key
     * @param value 成员值
     * @return 是否存在
     */
    public Boolean hasSetMember(String key, Object value) {
        return stringRedisTemplate.opsForSet().isMember(key, toJson(value));
    }

    /**
     * 删除 Set 成员。
     *
     * @param key 缓存 key
     * @param values 成员值
     * @return 删除数量
     */
    public Long removeSet(String key, Collection<?> values) {
        return stringRedisTemplate.opsForSet().remove(key, toJsonCollection(values).toArray());
    }

    /**
     * 获取 Set 元素数量。
     *
     * @param key 缓存 key
     * @return 元素数量
     */
    public Long sizeSet(String key) {
        return stringRedisTemplate.opsForSet().size(key);
    }

    /**
     * 写入 ZSet 类型缓存。
     *
     * @param key 缓存 key
     * @param value 成员值
     * @param score 分值
     * @return true 表示新增成功
     */
    public Boolean addZSet(String key, Object value, double score) {
        return stringRedisTemplate.opsForZSet().add(key, toJson(value), score);
    }

    /**
     * 获取 ZSet 指定排名范围。
     *
     * @param key 缓存 key
     * @param start 起始排名，从 0 开始
     * @param end 结束排名，-1 表示最后一位
     * @param clazz 成员类型
     * @param <T> 成员泛型
     * @return 成员集合
     */
    public <T> Set<T> getZSetRange(String key, long start, long end, Class<T> clazz) {
        Set<String> values = stringRedisTemplate.opsForZSet().range(key, start, end);
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .map(value -> fromJson(value, clazz))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 获取 ZSet 成员分值。
     *
     * @param key 缓存 key
     * @param value 成员值
     * @return 分值
     */
    public Double getZSetScore(String key, Object value) {
        return stringRedisTemplate.opsForZSet().score(key, toJson(value));
    }

    /**
     * 删除 ZSet 成员。
     *
     * @param key 缓存 key
     * @param values 成员值
     * @return 删除数量
     */
    public Long removeZSet(String key, Collection<?> values) {
        return stringRedisTemplate.opsForZSet().remove(key, toJsonCollection(values).toArray());
    }

    /**
     * 获取 ZSet 成员数量。
     *
     * @param key 缓存 key
     * @return 成员数量
     */
    public Long sizeZSet(String key) {
        return stringRedisTemplate.opsForZSet().size(key);
    }

    /**
     * 将集合元素转换为 JSON 字符串列表。
     *
     * @param values 集合元素
     * @return JSON 字符串列表
     */
    private List<String> toJsonCollection(Collection<?> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::toJson).collect(Collectors.toList());
    }

    /**
     * 序列化对象为 JSON 字符串。
     *
     * @param value 对象值
     * @return JSON 字符串
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis value serialization failed", exception);
        }
    }

    /**
     * 从 JSON 字符串反序列化对象。
     *
     * @param json JSON 字符串
     * @param clazz 目标类型
     * @param <T> 目标泛型
     * @return 反序列化结果
     */
    private <T> T fromJson(String json, Class<T> clazz) {
        if (json == null) {
            return null;
        }
        try {
            JavaType javaType = objectMapper.getTypeFactory().constructType(clazz);
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis value deserialization failed", exception);
        }
    }
}
