package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private IVoucherOrderService voucherOrderService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private SimpleRedisLock simpleRedisLock;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;


    private IVoucherOrderService proxy;

    /**
     * Redis执行的seckillVoucher方法的lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    // 加载seckillVoucher方法对应的lua脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillVoucher.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 阻塞队列 用来存放下单任务（改为使用Redis的Stream消息队列实现）
    // private static BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 线程池 该线程池中的线程专门用来处理下单任务
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 当应用一启动，线程池中的线程就开始不断从消息队列中获取任务，并执行下单任务
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 存储下单任务的消息队列的名称
    private static final String STREAM = "stream.orders";
    /**
     * 下单任务
     * 1. 从阻塞队列获取订单信息
     * 2. 创建订单
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 从消息队列获取订单
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    // g1为消费组组名 c1为消费组中的消费者
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
                    // 1.1 获取失败 重试
                    if (list == null || list.size() == 0) {
                        continue;
                    }
                    // 1.2 获取成功 继续往下
                    // 2. 根据消息中的内容创建VoucherOrder对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId msgId = record.getId();  // 消息的id
                    Map<Object, Object> recordValue = record.getValue();  // 消息的内容
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), false);
                    // 3. 执行下单业务
                    handlerVoucherOrder(voucherOrder);
                    // 4. XACK进行消息确认
                    redisTemplate.opsForStream().acknowledge(STREAM, "g1", msgId);
                } catch (Exception e) {
                    // 1. 记录异常
                    log.error("下单出现异常：" + e.getMessage());
                    // 2. 从pending-List获取消息重新处理
                    handlerPendingList();
                }
            }
        }

        private void handlerPendingList() {
            while (true) {
                try {
                    // 1. 从pending-list队列获取订单
                    // XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders 0
                    // g1为消费组组名 c1为消费组中的消费者
                    List<MapRecord<String, Object, Object>> list = redisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM, ReadOffset.from("0")));
                    // 1.1 获取失败 结束循环
                    if (list == null || list.size() == 0) {
                        break;
                    }
                    // 1.2 获取成功 继续往下
                    // 2. 根据消息中的内容创建VoucherOrder对象
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId msgId = record.getId();  // 消息的id
                    Map<Object, Object> recordValue = record.getValue();  // 消息的内容
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(recordValue, new VoucherOrder(), false);
                    // 3. 执行下单业务
                    handlerVoucherOrder(voucherOrder);
                    // 4. XACK进行消息确认
                    redisTemplate.opsForStream().acknowledge(STREAM, "g1", msgId);
                } catch (Exception e) {
                    // 1. 记录异常
                    log.error("下单出现异常：" + e.getMessage());
                }
            }
        }
    }

    // /**
    //  * 下单任务
    //  * 1. 从阻塞队列获取订单信息
    //  * 2. 创建订单
    //  */
    // private class VoucherOrderHandler implements Runnable {
    //     @Override
    //     public void run() {
    //         while (true) {
    //             try {
    //                 // 1. 从阻塞队列获取订单信息
    //                 VoucherOrder voucherOrder = orderTasks.take();
    //                 // 2. 创建订单
    //                 handlerVoucherOrder(voucherOrder);
    //             } catch (Exception e) {
    //                 log.error("下单出现异常：", e.getMessage());
    //             }
    //
    //         }
    //     }
    // }

    /**
     * 处理创建订单业务
     * 1. 获取用户id
     * 2. 创建锁对象
     * 3. 尝试获取锁 key为lock:secKillVoucher:userId
     *      3.1 获取锁失败 返回
     *      3.2 获取锁成功 继续往下
     * 4. 创建订单
     *
     * 注意：这里的锁其实可以不加（因为lua脚本已经保证了原子性、安全性），加了可以作为兜底保险方案
     * @param voucherOrder
     */
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户id
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        String key = "secKillVoucher:" + userId;
        RLock lock = redissonClient.getLock("lock:" + key);
        // 3. 尝试获取锁 key为lock:secKillVoucher:userId
        boolean isLock = lock.tryLock();
        // 3.1 获取锁失败 返回
        if (!isLock) {
            log.error("不允许重复下单！");
            return;
        }
        // 3.2 获取锁成功 继续往下
        try {
            // 4. 创建订单
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    /**
     * 创建订单
     * 1. 检查当前用户是否购买过该优惠券 （保证一人一单）
     *      1.1 是 结束
     *      1.2 不是 继续往下
     * 2. 在数据库扣减库存
     * 3. 插入订单信息到数据库
     * @param voucherOrder
     */
    @Transactional  // 由于涉及多张表的修改，因此使用事务
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 检查当前用户是否购买过该优惠券 （保证一人一单）
        Long userId = voucherOrder.getUserId();  // 注意 这里不能从UserHolder中获取用户再获取用户id（UserDTO user = UserHolder.getUser();），因为UserHolder.getUser()是从主线程的ThreadLocal中获取，而该方法是子线程调用。子线程无法获取到主线程的ThreadLocal中的对象
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 说明用户已经下过单了
            log.error("该用户已经购买过一次！");
            return;
        }
        // 2. 在数据库扣减库存
        boolean updateResult = seckillVoucherService.update().setSql("stock = stock - 1").  // set stock = stock - 1
                eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).  // where voucher_id = ? and stock > 0
                update();
        if (updateResult == false) {
            // 说明更新数据库失败
            log.error("库存不足！");
            return;
        }
        // 3. 插入订单信息到数据库
        voucherOrderService.save(voucherOrder);
    }


    /**
     * 秒杀优惠券
     * 1. 执行秒杀lua脚本 lua脚本包含以下步骤：
     *    1.1 判断是否超卖
     *    1.2 判断是否符合一人一单
     *    1.3 将用户id、优惠券id、订单id放入消息队列
     * 2. 根据lua脚本执行结果判断是否下单成功
     *    2.1 下单失败 返回失败信息
     *    2.2 下单成功 返回订单id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户
        UserDTO user = UserHolder.getUser();
        // 生成订单id
        Long orderId = redisIdWorker.nextId("seckillOrder");
        // 1. 执行秒杀lua脚本
        long result = redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                user.getId().toString(),
                voucherId.toString(),
                orderId.toString()
                );
        // 2. 根据lua脚本执行结果判断是否下单成功
        // 2.1 下单失败 返回失败信息
        if (result == 1) {
            return Result.fail("优惠券已经卖光啦！");
        }
        if (result == 2) {
            return Result.fail("您只能购买一次该优惠券！");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 2.2 下单成功 返回订单id
        return Result.ok(orderId);
    }

    // /**
    //  * 秒杀优惠券
    //  * 1. 执行秒杀lua脚本 判断用户是否有购买资格
    //  *     1.1 秒杀失败 返回失败信息
    //  *     1.2 秒杀成功 继续往下
    //  * 2. 将优惠券id、用户id、订单id封装后存入阻塞队列
    //  * 3. 返回订单id
    //  * @param voucherId 优惠券id
    //  */
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     UserDTO user = UserHolder.getUser();
    //     // 1. 执行秒杀lua脚本 判断用户是否有购买资格
    //     long result = redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), user.getId().toString(), voucherId.toString());
    //     // 1.1 秒杀失败 返回失败信息
    //     if (result == 1) {
    //         return Result.fail("优惠券已经卖光啦！");
    //     }
    //     if (result == 2) {
    //         return Result.fail("您只能购买一次该优惠券！");
    //     }
    //     // 1.2 秒杀成功 继续往下
    //
    //     // 2. 将优惠券id、用户id、订单id封装后存入阻塞队列
    //     // 2.1 创建订单
    //     VoucherOrder voucherOrder = new VoucherOrder();
    //     // 2.1.1 设置订单id
    //     Long orderId = redisIdWorker.nextId("seckillOrder");
    //     voucherOrder.setId(orderId);
    //     // 2.1.2 设置用户id
    //     voucherOrder.setUserId(user.getId());
    //     // 2.1.3 设置代金券id
    //     voucherOrder.setVoucherId(voucherId);
    //     // 2.2 将订单信息加入阻塞队列
    //     orderTasks.add(voucherOrder);
    //
    //     // 获取代理对象
    //     proxy = (IVoucherOrderService) AopContext.currentProxy();
    //
    //     // 3. 返回订单id
    //     return Result.ok(orderId);
    // }

    // /**
    //  * 秒杀优惠券
    //  * 1. 根据id查询优惠券信息
    //  *      查询不到 --> 返回错误消息
    //  *        查询到 --> 继续往下
    //  * 2. 判断是否在秒杀时间范围内
    //  *      不在 --> 返回错误消息
    //  *        在 --> 继续往下
    //  * 3. 判断库存是否充足
    //  *      不是 --> 返回错误消息
    //  *        是 --> 继续往下
    //  * 4. 尝试获取锁
    //  *      获取失败 --> 说明当前用户正在执行购买 返回错误信息
    //  *      获取成功 --> 继续往下
    //  * 5. 检查当前用户是否购买过该优惠券（保证一人一单）
    //  *      是 --> 返回错误信息
    //  *    不是 --> 继续往下
    //  * 6. 扣减库存
    //  * 7. 创建订单
    //  * 8. 返回订单id
    //  * @param voucherId 优惠券id
    //  */
    // @Override
    // public Result seckillVoucher(Long voucherId) {
    //     // 1. 根据id查询优惠券信息
    //     SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
    //     if (seckillVoucher == null) {
    //         return Result.fail("该优惠券不存在！");
    //     }
    //     // 2. 判断是否在秒杀时间范围内
    //     LocalDateTime now = LocalDateTime.now();
    //     if (now.isBefore(seckillVoucher.getBeginTime())) {
    //         return Result.fail("秒杀还未开始！");
    //     }
    //     if (now.isAfter(seckillVoucher.getEndTime())) {
    //         return Result.fail("秒杀已经结束！");
    //     }
    //     // 3. 判断库存是否充足
    //     Integer stock = seckillVoucher.getStock();
    //     if (stock < 1) {
    //         return Result.fail("优惠券已经被抢光了，下次早点来吧~");
    //     }
    //     UserDTO user = UserHolder.getUser();
    //     // 单体项目加锁方式
    //     // // 锁加在这里可以保证事务提交之后才释放锁
    //     // synchronized (user.getId().toString().intern()) {
    //     //     // 获取代理对象（事务）
    //     //     IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //     //     return proxy.createVoucherOrder(voucherId);
    //     // }
    //
    //     // 分布式项目加锁方式
    //     // 4. 尝试获取锁
    //     String key = "secKillVoucher:" + user.getId();
    //     // boolean isLock = simpleRedisLock.tryLock(key, 1200);
    //     RLock lock = redissonClient.getLock("lock:" + key);
    //     boolean isLock = lock.tryLock();
    //     // 获取锁失败
    //     if (!isLock) {
    //         return Result.fail("您只能购买该优惠券一次！");
    //     }
    //     // 获取锁成功
    //     try {
    //         IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    //         return proxy.createVoucherOrder(voucherId);
    //     } finally {
    //         // simpleRedisLock.unlock(key);
    //         lock.unlock();
    //     }
    // }

    // @Transactional  // 由于涉及多张表的修改，因此使用事务
    // public Result createVoucherOrder(Long voucherId) {
    //     // 5. 检查当前用户是否购买过该优惠券 （保证一人一单）
    //     UserDTO user = UserHolder.getUser();
    //     Long userId = user.getId();
    //     int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
    //     if (count > 0) {
    //         return Result.fail("您已抢购过该优惠券！");
    //     }
    //     // 6. 扣减库存
    //     boolean updateResult = seckillVoucherService.update().setSql("stock = stock - 1").  // set stock = stock - 1
    //             eq("voucher_id", voucherId).gt("stock", 0).  // where voucher_id = ? and stock > 0
    //             update();
    //     if (updateResult == false) {
    //         return Result.fail("优惠券已经被抢光了，下次早点来吧~");
    //     }
    //     // 7. 创建订单
    //     VoucherOrder voucherOrder = new VoucherOrder();
    //     // 7.1 设置订单id
    //     Long orderId = redisIdWorker.nextId("order");
    //     voucherOrder.setId(orderId);
    //     // 7.2 设置用户id
    //     voucherOrder.setUserId(userId);
    //     // 7.3 设置代金券id
    //     voucherOrder.setVoucherId(voucherId);
    //     // 7.4 存入数据库
    //     voucherOrderService.save(voucherOrder);
    //
    //     // 8. 返回订单id
    //     return Result.ok(orderId);
    // }
}
