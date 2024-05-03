package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {
    public VoucherOrder selectByUserIdAndVoucherId(@Param("userId") Long userId, @Param("voucherId") Long voucherId);
}
