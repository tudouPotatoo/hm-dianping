package com.hmdp.utils;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
@RunWith(SpringRunner.class)
public class ShopGeoDataImport {
    @Autowired
    private IShopService shopService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 1. 获取所有店铺信息
     * 2. 将店铺按照店铺类型分类
     * 3. 将店铺id、经纬度信息存入redis
     */
    @Test
    public void importShopGEOData() {
        // 1. 获取所有店铺信息
        List<Shop> shops = shopService.list();
        // 2. 将店铺按照店铺类型分类
        Map<Long, List<Shop>> shopMapByType = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 将店铺id、经纬度信息存入redis
        for (Map.Entry<Long, List<Shop>> entry : shopMapByType.entrySet()) {
            // 3.1 获取店铺typeId
            Long typeId = entry.getKey();
            // 3.2 根据typeId拼接GEO key
            String key = SHOP_GEO_KEY + typeId;
            List<Shop> shopList = entry.getValue();
            // 3.3 将店铺id，店铺经纬度存入GEO
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
                locations.add(new RedisGeoCommands.GeoLocation<String>(
                                shop.getId().toString(),
                                new Point(shop.getX(), shop.getY())));
            }
            redisTemplate.opsForGeo().add(key, locations);
        }
    }
}
