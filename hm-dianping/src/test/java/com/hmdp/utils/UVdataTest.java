package com.hmdp.utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 单元测试 模拟UV统计
 * 测试HyperLogLog效果
 */
@SpringBootTest
@RunWith(SpringRunner.class)
public class UVdataTest {

    @Autowired
    private StringRedisTemplate redisTemplate;
    /**
     * 批量向Redis中写入数据，每次写入1000条
     * 一共写入100万条数据
     * 看到HyperLogLog记录的数据基数以及占用的内存
     *
     * 写入前redis内存占用情况：
     * used_memory:937328
     * used_memory_human:915.36K
     *
     * 写入后redis内存占用情况：
     * used_memory:953136
     * used_memory_human:930.80K
     * 增加了100万条数据只增加了30kb
     */
    @Test
    public void testHyperLogLog() {
        String[] users = new String[1000];
        String key = "HLL1";
        // 总共写入一百万条数据
        for (int i = 0; i < 1000000; i++) {
            int j = i % 1000;
            users[j] = "user_" + i;
            // 每次写入1000条数据
            if (j == 999) {
                redisTemplate.opsForHyperLogLog().add(key, users);
            }
        }
        System.out.println(redisTemplate.opsForHyperLogLog().size(key));  // 997593 仅有0.24%的误差
    }
}
