package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;


/**
 * 方法1：将任意Java对象以Hash方式存储在redis中，并且可以设置TTL过期时间
 * 方法2：将任意Java对象以Hash方式存储在redis中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
 * 方法3：根据指定的key查询缓存，并转化为指定类型，利用缓存空值的方式解决缓存穿透问题
 * 方法4：根据指定的key查询缓存，并转化为指定类型，需要利用逻辑过期解决缓存击穿问题
 */
@Slf4j
@Component
public class CacheClient {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 将任意Java对象以Hash方式存储在redis中，并且可以设置TTL过期时间
     * @param key 存入redis时的key
     * @param object 需要存入redis的对象
     * @param timeout 过期时间
     * @param unit 过期时间的单位
     */
    public void set(String key, Object object, Long timeout, TimeUnit unit) {
        HashOperations<String, Object, Object> opsForHash = redisTemplate.opsForHash();
        // 将object转化为map
        Map<String, Object> objectMap = BeanUtil.beanToMap(object, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue != null) {
                                return fieldValue.toString();
                            } else {
                                return "";
                            }
        }));
        // 将map存入redis
        opsForHash.putAll(key, objectMap);
        // 设置ttl
        redisTemplate.expire(key, timeout, unit);
    }

    /**
     * 将任意Java对象以Hash方式存储在redis中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * @param key 存入redis时的key
     * @param object 需要存入redis的对象
     * @param timeout 逻辑过期时间
     * @param unit 逻辑过期时间的单位
     */
    public void setWithLogicalExpire(String key, Object object, Long timeout, TimeUnit unit) {
        HashOperations<String, Object, Object> opsForHash = redisTemplate.opsForHash();
        // 将object转化为map
        Map<String, Object> objectMap = BeanUtil.beanToMap(object, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue != null) {
                                if (fieldValue instanceof LocalDateTime) {
                                    LocalDateTime localDateTime = (LocalDateTime) fieldValue;
                                    return localDateTime.format(formatter);
                                }
                                return String.valueOf(fieldValue);
                            } else {
                                return "";
                            }
                        }));
        // 设置逻辑过期时间
        String expireTime = LocalDateTime.now().plusSeconds(unit.toSeconds(timeout)).format(formatter);
        objectMap.put(LOGICAL_EXPIRE_TIME_FIELD, expireTime);
        // 将map存入redis
        opsForHash.putAll(key, objectMap);
    }

    /**
     * 根据指定的key查询缓存，并转化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * 1. 根据key从Redis查询缓存
     *    命中 --> 检查是否为空值
     *                  是 --> 返回空值
     *                不是 --> 返回对象信息
     *    未命中 --> 继续往下
     * 2. 根据id从数据库查询对于对象信息
     *    未命中 --> 将空值写入Redis，返回空值
     *    命中 --> 继续往下
     * 3. 将对象数据写入Redis
     * 4. 返回对象信息
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long timeout, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从Redis查询对象缓存
        HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
        // 如果查询不到key对应的val，ops.entries会返回一个空的Map，而不是null
        Map<Object, Object> shopMap = ops.entries(key);

        // 缓存命中
        if (!shopMap.isEmpty()) {
            // 检查是否为空值
            // 是空值 则返回null
            if (shopMap.size() == 1 && shopMap.containsKey("")) {
                return null;
            } else {
                // 不是空值
                // 使用反射创建对象
                R r = BeanUtils.instantiateClass(type);
                // 填充对象的属性
                r = BeanUtil.fillBeanWithMap(shopMap, r, false);
                // 刷新在redis的过期时间
                redisTemplate.expire(key, timeout, unit);
                return r;
            }
        }
        // 缓存未命中 继续往下

        // 2. 根据id从数据库查询对应信息
        R r = dbFallBack.apply(id);
        //  如果未命中 将空值写入Redis 返回404
        if (r == null) {
            // 存入一个空值
            ops.put(key,"", "");
            // 设置空值的过期时间为2分钟
            redisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //  如果命中 继续往下

        // 3. 将商铺数据写入Redis（设置ttl）
        this.set(key, r, timeout, unit);

        // 4. 返回商铺信息
        return r;
    }

    /**
     * 根据指定的key查询缓存，并转化为指定类型，需要利用逻辑过期解决缓存击穿问题
     * 【默认所有热点数据开发者都会提前写入redis中，而不是等到用户第一次访问该数据再写入redis。】
     * 【因此如果redis中查询不到该数据，说明该数据不存在，直接返回空】
     * 1. 根据id从redis查询对象缓存
     *          未命中 --> 返回空
     *           命中 -->  继续往下
     * 2. 判断缓存是否过期
     *      未过期 --> 直接返回对象信息
     *      已过期 --> 继续往下
     * 3. 尝试获取互斥锁
     *      是否获取互斥锁成功
     *      否 --> 直接返回对象信息
     *      是 -->
     *              3.1 double check 再次检测redis缓存是否过期
     *                      未过期  --> 直接返回对象信息 释放互斥锁
     *                      已过期  --> 继续往下
     *              3.2 开启独立线程去执行【根据id查询数据库，将商铺数据写入redis并设置逻辑过期时间，释放互斥锁】重建缓存操作
     *              3.3 返回对象信息
     *              3.4 释放互斥锁
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long timeout, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 根据id从redis查询对象缓存
        HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
        // 如果查询不到key对应的val，ops.entries会返回一个空的Map，而不是null
        Map<Object, Object> objectMap = ops.entries(key);

        // 缓存未命中 返回空
        if (objectMap.isEmpty()) {
            return null;
        }
        // 缓存命中 继续往下

        // 将map转化为bean
        R r = BeanUtils.instantiateClass(type);
        r = BeanUtil.fillBeanWithMap(objectMap, r, false);

        // 2. 判断缓存是否过期
        String expireTimeStr = (String) objectMap.get(LOGICAL_EXPIRE_TIME_FIELD);
        // TODO 如果没有逻辑过期时间 则说明一直有效 直接返回对象即可
        LocalDateTime expireTime = LocalDateTime.parse(expireTimeStr, formatter);

        // 2.1 未过期 --> 直接返回商铺信息
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 2.2 已过期 进行缓存重建

        // 3. 尝试获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);

        // 3.1 获取互斥锁成功
        if (isLock) {
            // double check再次检测redis缓存是否过期，如果未过期则无需重建缓存
            // 根据key获取对象信息
            objectMap = ops.entries(key);
            // 获取逻辑过期时间
            expireTimeStr = (String) objectMap.get(LOGICAL_EXPIRE_TIME_FIELD);
            // TODO 如果没有逻辑过期时间 则说明一直有效 直接返回对象即可
            expireTime = LocalDateTime.parse(expireTimeStr, formatter);

            // 检查是否过期
            // 未过期 直接返回shop对象
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 根据objectMap封装R对象
                r = BeanUtils.instantiateClass(type);
                r = BeanUtil.fillBeanWithMap(objectMap, r, false);
                // 返回对象
                return r;
            }
            // 过期 继续往下

            // 3.2 开启独立线程 执行【根据id查询数据库，将商铺数据写入redis并设置逻辑过期时间，释放互斥锁】
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                System.out.println("重建缓存...");
                try {
                    // 重建缓存
                    R obj = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key, obj, timeout, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 3.3 返回商铺结果
        return r;
    }

    /**
     * 锁住对应的key
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, LOCK_VAL, LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    /**
     * 为对应的key解锁
     * @param key
     */
    private void unlock(String key) {
        redisTemplate.delete(key);
    }
}
