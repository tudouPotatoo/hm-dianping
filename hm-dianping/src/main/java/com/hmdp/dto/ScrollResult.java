package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    /**
     * 滚动分页查询到的结果
     */
    private List<?> list;
    /**
     * 本次分页查询的推送的最小时间戳
     */
    private Long minTime;
    /**
     * 偏移量
     * 本次分页查询到的推送的最小时间戳 在本次查询到的推送的出现次数
     */
    private Integer offset;
}
