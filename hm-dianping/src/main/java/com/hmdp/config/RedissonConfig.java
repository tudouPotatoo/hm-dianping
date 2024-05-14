package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson配置配
 * 温馨提示：此外还有一种引入方式，可以引入redission的starter依赖，然后在yml文件中配置Redisson，
 *          但是不推荐这种方式，因为他会替换掉 Spring官方提供的对Redis的配置
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加Redis地址，这里添加了单点的地址，也可以使用config.useClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis:// 192.168.160.128:6379");
        // 创建客户端
        return Redisson.create(config);
    }
}
