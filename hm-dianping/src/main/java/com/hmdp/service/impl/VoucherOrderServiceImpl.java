package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private VoucherOrderMapper voucherOrderMapper;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private SimpleRedisLock simpleRedisLock;



    /**
     * 秒杀优惠券
     * 1. 根据id查询优惠券信息
     *      查询不到 --> 返回错误消息
     *        查询到 --> 继续往下
     * 2. 判断是否在秒杀时间范围内
     *      不在 --> 返回错误消息
     *        在 --> 继续往下
     * 3. 判断库存是否充足
     *      不是 --> 返回错误消息
     *        是 --> 继续往下
     * 4. 尝试获取锁
     *      获取失败 --> 说明当前用户正在执行购买 返回错误信息
     *      获取成功 --> 继续往下
     * 5. 检查当前用户是否购买过该优惠券（保证一人一单）
     *      是 --> 返回错误信息
     *    不是 --> 继续往下
     * 6. 扣减库存
     * 7. 创建订单
     * 8. 返回订单id
     * @param voucherId 优惠券id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 根据id查询优惠券信息
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("该优惠券不存在！");
        }
        // 2. 判断是否在秒杀时间范围内
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("秒杀还未开始！");
        }
        if (now.isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("秒杀已经结束！");
        }
        // 3. 判断库存是否充足
        Integer stock = seckillVoucher.getStock();
        if (stock < 1) {
            return Result.fail("优惠券已经被抢光了，下次早点来吧~");
        }
        UserDTO user = UserHolder.getUser();
        // 单体项目加锁方式
        // // 锁加在这里可以保证事务提交之后才释放锁
        // synchronized (user.getId().toString().intern()) {
        //     // 获取代理对象（事务）
        //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
        //     return proxy.createVoucherOrder(voucherId);
        // }

        // 分布式项目加锁方式
        // 4. 尝试获取锁
        String key = "secKillVoucher:" + user.getId();
        boolean isLock = simpleRedisLock.tryLock(key, 1200);
        // 获取锁失败
        if (!isLock) {
            return Result.fail("您只能购买该优惠券一次！");
        }
        // 获取锁成功
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            simpleRedisLock.unlock(key);
        }
    }

    @Transactional  // 由于涉及多张表的修改，因此使用事务
    public Result createVoucherOrder(Long voucherId) {
        // 5. 检查当前用户是否购买过该优惠券 （保证一人一单）
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("您已抢购过该优惠券！");
        }
        // 6. 扣减库存
        boolean updateResult = seckillVoucherService.update().setSql("stock = stock - 1").  // set stock = stock - 1
                eq("voucher_id", voucherId).gt("stock", 0).  // where voucher_id = ? and stock > 0
                update();
        if (updateResult == false) {
            return Result.fail("优惠券已经被抢光了，下次早点来吧~");
        }
        // 7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1 设置订单id
        Long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2 设置用户id
        voucherOrder.setUserId(userId);
        // 7.3 设置代金券id
        voucherOrder.setVoucherId(voucherId);
        // 7.4 存入数据库
        voucherOrderService.save(voucherOrder);

        // 8. 返回订单id
        return Result.ok(orderId);
    }
}
