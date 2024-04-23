package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

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
     * 根据id查询商户信息
     * 1. 从Redis查询商铺缓存
     *    命中 --> 直接返回
     *    未命中 --> 继续往下
     * 2. 根据id从数据库查询商户信息
     *    未命中 --> 返回404
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
        Map<Object, Object> shopMap = ops.entries(key);
        // 如果命中 直接返回
        if (shopMap != null && !shopMap.isEmpty()) {
            // 将map转化为bean
            Shop shop = BeanUtil.fillBeanWithMap(shopMap, new Shop(), false);
            // 刷新在redis的过期时间
            redisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }
        // 如果未命中 继续往下

        // 2. 根据id从数据库查询商户信息
        Shop shop = getById(id);
        //  如果未命中 返回404
        if (shop == null) {
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
}
