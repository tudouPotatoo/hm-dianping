package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SimpleRedisLock implements ILock {
    private static final String keyPrefix = "lock:";

    /**
     * 用于标识当前服务器/JVM/进程的uuid
     */
    private static final String ID_PREFIX = UUID.fastUUID().toString(true);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 尝试获取锁
     *      键名范例：lock:seckillVoucher:123(userId)
     *      val存储当前线程id
     * @param key 键名
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return
     */
    @Override
    public boolean tryLock(String key, long timeoutSec) {
        // 1. 获取当前线程id
        long threadId = Thread.currentThread().getId();
        String value = ID_PREFIX + "-" +threadId;  // uuid-threadId
        // 2. 尝试获取锁
        key = keyPrefix + key;
        Boolean isLock = redisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        // 3. 返回结果
        return BooleanUtil.isTrue(isLock);
    }

    /**
     * 解锁
     * @param key
     */
    @Override
    public void unlock(String key) {
        key = keyPrefix + key;
        // 检查是否是本线程添加的锁
        long threadId = Thread.currentThread().getId();
        String value = ID_PREFIX + "-" +threadId;  // uuid-threadId
        // 如果是本线程添加的锁才进行删除
        if (redisTemplate.opsForValue().get(key).equals(value)) {
            redisTemplate.delete(key);
        }
    }
}
