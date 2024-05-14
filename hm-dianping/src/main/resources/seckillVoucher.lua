-- 1. 参数列表
-- 1.1 当前用户的id
local userId = ARGV[1]
-- 1.2 当前优惠券的id
local voucherId = ARGV[2]
-- 1.3 当前订单的id
local orderId = ARGV[3]

-- 2. 数据key
-- 2.1 优惠券库存key[value为该优惠券的库存]
local voucherStockKey = "stock:seckillvoucher:" .. voucherId
-- 2.2 优惠券订单key[value为购买了该优惠券的用户]
local voucherUserKey = "order:seckillvoucher:" .. voucherId

-- 3. 脚本业务
-- 3.1 判断库存是否充足
if (tonumber(redis.call("get", voucherStockKey)) <= 0) then
    -- 库存不足 返回1
    return 1
end
-- 3.2 判断用户是否下单
if (redis.call("sismember", voucherUserKey, userId) == 1) then
    -- 用户已经下过单 返回2
    return 2
end
-- 3.3 扣减库存 将当前优惠券的库存量-1
redis.call("incrby", voucherStockKey, -1);
-- 3.4 将userId存入当前优惠券的set集合
redis.call("sadd", voucherUserKey, userId)
-- 3.5 将用户id、优惠券id、订单id放入stream.orders消息队列 【XADD stream.orders * k1 v1 k2 v2 …】
-- 这里orderId的key设置为id，是为了便于取的时候能够直接将取出的map转化为一个VoucherOrder对象
redis.call("xadd", "stream.orders", "*", "userId", userId, "voucherId", voucherId, "id", orderId)
-- 下单成功 返回0
return 0