package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 获取商铺类型列表
     * 默认根据sort值进行升序排序
     * 步骤：
     * 1. 从Redis中查找商铺类型列表
     *    命中 --> 直接返回
     *    不命中 --> 继续往下
     * 2. 从数据库中按序查询所有商铺类型
     * 3. 将数据库查到的数据用ZSet数据类型存入Redis中（根据sort字段进行升序排序）
     * 4. 返回排序后的商铺类型
     *
     * @return
     */
    @Override
    public List<ShopType> queryTypeList() {
        ZSetOperations<String, String> opsForZSet = redisTemplate.opsForZSet();
        // 1. 从Redis中查找商铺类型列表
        Set<String> shopTypeSet = opsForZSet.range(SHOP_TYPE_KEY, 0, -1);
        // 命中 --> 直接返回
        if (shopTypeSet != null && !shopTypeSet.isEmpty()) {
            // 将set转化为列表进行返回
            List<ShopType> shopTypeList = new ArrayList<>();
            for (String shopTypeJson : shopTypeSet) {
                ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
                shopTypeList.add(shopType);
            }
            return shopTypeList;
        }
        // 不命中 继续往下

        // 2. 从数据库中按序查询所有商铺类型
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        // 3. 将数据库查到的数据用ZSet数据类型存入Redis中（根据sort字段进行升序排序）
        for (ShopType shopType : shopTypeList) {
            // 将shopType转化为json字符串
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            // 将shopType存入redis，sort作为score
            opsForZSet.add(SHOP_TYPE_KEY, shopTypeJson, shopType.getSort());
        }

        // 4. 返回排序后的商铺类型
        return shopTypeList;
    }
}
