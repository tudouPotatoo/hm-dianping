package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate redisTemplate;

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
    @Override
    public Shop queryById(Long id) {
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
}
