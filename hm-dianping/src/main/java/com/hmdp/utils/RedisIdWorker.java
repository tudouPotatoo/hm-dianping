package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 初始时间2022-01-01 00:00:00对应的时间戳
    private final static Long BEGIN_TIMESTAMP = 1640995200L;

    private final static int COUNT_BITS = 32;  // 序列号占用的位数

    @Autowired
    private StringRedisTemplate redisTemplate;


    /**
     * 生成全局唯一id
     * 1. 生成时间戳
     * 2. 生成序列号
     * 3. 将两者进行拼接 返回id
     * @param keyPrefix 业务对应的前缀
     * @return 生成的唯一id
     */
    public Long nextId(String keyPrefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2. 生成序列号
        // 2.1 拼接key [incr:order:2024:05:01]
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 每天一个key 便于统计每天的订单量
        String key = "incr:" + keyPrefix + ":" + date;
        long count = redisTemplate.opsForValue().increment(key);
        // 3. 将两者进行拼接
        Long resultId = timestamp << COUNT_BITS | count;
        // 返回id
        return resultId;
    }

    public static void main(String[] args) {
        // 生成初始时间 时间戳 2022-01-01 00:00:00
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long beginSecond = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println(beginSecond);
    }
}
