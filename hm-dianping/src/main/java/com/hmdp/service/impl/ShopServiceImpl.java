package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private CacheClient cacheClient;


    @Override
    public Shop queryById(Long id) {
        // 测试使用存储null值的方式解决缓存穿透问题
        // return queryWithPassThrough(id);

        // 测试使用互斥锁方式解决缓存击穿问题
        // return queryWithMutex(id);

        // 测试使用逻辑过期方式解决缓存击穿问题
        // return queryWithLogicalExpire(id);

        // 测试Redis工具类解决缓存穿透
        // return cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 测试Redis工具类解决缓存击穿
        // return cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 根据id查询商户信息（已解决缓存穿透问题）
     * 1. 从Redis查询商铺缓存
     *    命中 --> 检查是否为空值
     *                  是 --> 返回空值
     *                不是 --> 返回商铺信息
     *    未命中 --> 继续往下
     * 2. 根据id从数据库查询商户信息
     *    未命中 --> 将空值写入Redis，返回空值
     *    命中 --> 继续往下
     * 3. 将商铺数据写入Redis
     * 4. 返回商铺信息
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从Redis查询商铺缓存
        HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
        Map<Object, Object> shopMap = ops.entries(key);  // 如果查询不到key对应的val，ops.entries会返回一个空的Map，而不是null

        // 命中
        if (!shopMap.isEmpty()) {
            // 检查是否为空值
            // 是空值 则返回null
            if (shopMap.size() == 1 && shopMap.containsKey("")) {
                // 更新过期时间
                redisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            } else {
                // 到这说明不是空值 命中 直接返回
                // 将map转化为bean
                Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
                // 刷新在redis的过期时间
                redisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }
        }
        // 如果未命中 继续往下

        // 2. 根据id从数据库查询商户信息
        Shop shop = getById(id);
        //  如果未命中 将空值写入Redis 返回404
        if (shop == null) {
            // 存入一个空值
            ops.put(key,"", "");
            // 设置空值的过期时间为2分钟
            redisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //  如果命中 继续往下

        // 3. 将商铺数据写入Redis
        Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> {
                            if (fieldValue != null) {
                                return fieldValue.toString();
                            } else {
                                return "";
                            }
                        }));
        ops.putAll(key, map);
        // 设置过期时间 避免一些不常被访问的商户长期占用内存空间
        redisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 4. 返回商铺信息
        return shop;
    }

    /**
     * 根据id查询商户信息（已解决缓存穿透、缓存击穿、缓存雪崩问题）
     * 缓存穿透：使用在缓存中存null值的方式解决
     * 缓存雪崩：在将数据存入redis时，TTL添加随机数的方式解决
     * 缓存击穿：互斥锁的方式解决
     * 1. 从Redis查询商铺缓存
     *    命中 --> 检查是否为空值
     *                  是 --> 返回空值
     *                不是 --> 返回商铺信息
     *    未命中 --> 2. 尝试获取互斥锁 是否获取成功
     *                 不是 --> 休眠 重新回到1
     *                  是  --> 继续往下
     * 3. 根据id从数据库查询商户信息
     *    未命中 --> 将空值写入Redis
     *    命中 --> 将商铺数据写入Redis（写入Redis时，TTL添加随机数，避免缓存雪崩）
     * 4. 释放互斥锁
     * 5. 返回空值/商铺信息
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY + id;
        // 1. 从Redis查询商铺缓存
        HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
        Map<Object, Object> shopMap = ops.entries(key);  // 如果查询不到key对应的val，ops.entries会返回一个空的Map，而不是null

        // 命中
        if (!shopMap.isEmpty()) {
            // 检查是否为空值
            // 是空值 则返回null
            if (shopMap.size() == 1 && shopMap.containsKey("")) {
                // 更新过期时间
                redisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            } else {
                // 到这说明不是空值 命中 直接返回
                // 将map转化为bean
                Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
                // 刷新在redis的过期时间
                redisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return shop;
            }
        }
        // 如果未命中 继续往下

        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 2. 尝试获取互斥锁
            if (!tryLock(lockKey)) {
                // 2.1 获取失败 休眠 回到步骤1
                Thread.sleep(50);
                return queryById(id);
            }

            // 2.2 获取成功 继续往下

            // 3. 根据id从数据库查询商户信息
            shop = getById(id);
            // 模拟重建缓存的延时
            Thread.sleep(200);

            //  如果未命中 将空值写入Redis 返回404
            if (shop == null) {
                // 存入一个空值
                ops.put(key,"", "");
                // 设置空值的过期时间为2分钟
                redisTemplate.expire(key, CACHE_NULL_TTL, TimeUnit.MINUTES);
            } else {
                //  如果命中 继续往下
                // 3. 将商铺数据写入Redis
                Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> {
                                    if (fieldValue != null) {
                                        return fieldValue.toString();
                                    } else {
                                        return "";
                                    }
                                }));
                ops.putAll(key, map);
                // 设置过期时间 避免一些不常被访问的商户长期占用内存空间
                redisTemplate.expire(key, CACHE_SHOP_TTL + RandomUtil.randomInt(0, 5), TimeUnit.MINUTES);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4. 释放互斥锁
            unlock(lockKey);
        }
        // 5. 返回商铺信息
        return shop;
    }

    /**
     * 根据id查询商铺信息
     * 使用逻辑过期方式解决缓存击穿问题
     * 【默认所有热点数据开发者都会提前写入redis中，而不是等到用户第一次访问该数据再写入redis。】
     * 【因此如果redis中查询不到该数据，说明该数据不存在，直接返回空】
     * 1. 根据id从redis查询商铺缓存
     *          未命中 --> 返回空
     *           命中 -->  继续往下
     * 2. 判断缓存是否过期
     *      未过期 --> 直接返回商铺信息
     *      已过期 --> 继续往下
     * 3. 尝试获取互斥锁
     *      是否获取互斥锁成功
     *      否 --> 直接返回商铺信息
     *      是 -->
     *              3.1 double check 再次检测redis缓存是否过期，如果未过期则无需重建缓存
     *              3.2 开启独立线程去执行【根据id查询数据库，将商铺数据写入redis并设置逻辑过期时间，释放互斥锁】重建缓存操作
     *             3.3 返回商铺信息
     * @param id
     * @return
     */
    // public Shop queryWithLogicalExpire(Long id) {
    //     String key = CACHE_SHOP_KEY + id;
    //     // 1. 从Redis查询商铺缓存
    //     HashOperations<String, Object, Object> ops = redisTemplate.opsForHash();
    //     Map<Object, Object> shopMap = ops.entries(key);  // 如果查询不到key对应的val，ops.entries会返回一个空的Map，而不是null
    //
    //     // 未命中 返回空
    //     if (shopMap.isEmpty()) {
    //         return null;
    //     }
    //     // 命中 继续往下
    //
    //     // 将map转化为bean
    //     Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //
    //     // 2. 判断缓存是否过期
    //     LocalDateTime expireTime = shop.getExpireTime();
    //     // 2.1 未过期 --> 直接返回商铺信息
    //     if (expireTime.isAfter(LocalDateTime.now())) {
    //         return shop;
    //     }
    //     // 2.2 已过期 继续往下
    //
    //     // 3. 尝试获取互斥锁
    //     String lockKey = LOCK_SHOP_KEY + id;
    //     boolean isLock = tryLock(lockKey);
    //
    //     // 3.1 获取互斥锁成功
    //     if (isLock) {
    //         // double check再次检测redis缓存是否过期，如果未过期则无需重建缓存
    //         // 根据key获取map
    //         shopMap = ops.entries(key);
    //         // 将map转化为bean
    //         shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
    //         // 判断缓存是否过期
    //         expireTime = shop.getExpireTime();
    //         // 未过期 直接返回shop对象
    //         if (expireTime.isAfter(LocalDateTime.now())) {
    //             return shop;
    //         }
    //         // 过期 继续往下
    //         // 3.2 开启独立线程 执行【根据id查询数据库，将商铺数据写入redis并设置逻辑过期时间，释放互斥锁】
    //         CACHE_REBUILD_EXECUTOR.submit(() -> {
    //             try {
    //                 // 重建缓存
    //                 saveShopToRedis(id, 20L);
    //             } catch (Exception e) {
    //                 throw new RuntimeException(e);
    //             } finally {
    //                 // 释放锁
    //                 unlock(lockKey);
    //             }
    //         });
    //     }
    //     // 3.3 返回商铺结果
    //     return shop;
    // }

    /**
     * 锁住对应的key
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean result = redisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    /**
     * 为对应的key解锁
     * @param key
     */
    private void unlock(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 更新店铺信息
     * 1. 校验id是否为空
     *      是 --> 报错
     *      否 --> 继续往下
     * 2. 更新数据库
     * 3. 删除缓存中店铺的信息
     * 4. 返回结果
     * （由于更新数据库和删除缓存两件事需要保证原子性，因此使用事务）
     * @param shop 店铺对象
     * @return 更新的店铺数据
     */
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        // 1. 校验id是否为空
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 2. 更新数据库
        updateById(shop);
        // 3. 删除缓存中店铺的信息
        String key = CACHE_SHOP_KEY + id;
        redisTemplate.delete(key);
        // 4. 返回结果
        return Result.ok();
    }

    /**
     * 将数据库中的商铺数据写入redis
     * 主要用于开发者提前将热点数据写入redis
     * @param id 商铺id
     * @param expireSeconds 逻辑过期秒数
     * @return
     */
    // public int saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
    //     // 1. 查询店铺数据
    //     Shop shop = shopMapper.selectById(id);
    //     // 模拟缓存重建延迟
    //     Thread.sleep(200);
    //     if (shop == null) {
    //         return 0;
    //     }
    //     // 2. 封装逻辑过期时间
    //     shop.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    //     // 3. 写入Redis
    //     HashOperations<String, Object, Object> opsForHash = redisTemplate.opsForHash();
    //     // 3.1 将shop转为map
    //     Map<String, Object> map = BeanUtil.beanToMap(shop, new HashMap<>(),
    //             CopyOptions.create()
    //                     .setIgnoreNullValue(true)
    //                     .setFieldValueEditor((fieldName, fieldValue) -> {
    //                         if (fieldValue != null) {
    //                             return fieldValue.toString();
    //                         } else {
    //                             return "";
    //                         }
    //                     }));
    //     String key = CACHE_SHOP_KEY + id;
    //     // 将map存入redis
    //     opsForHash.putAll(key, map);
    //     return 1;
    // }
}
