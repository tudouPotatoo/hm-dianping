package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final Long CACHE_NULL_TTL = 2L;  // 空值存储2分钟

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /**
     * 秒杀优惠券的库存key
     */
    public static final String SECKILL_STOCK_KEY = "stock:seckillvoucher:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String SHOP_TYPE_KEY = "shop:type";

    public static final String LOGICAL_EXPIRE_TIME_FIELD = "expireTime";

    public static final Long LOCK_TTL = 10L;
    public static final String LOCK_VAL = "1";
}
