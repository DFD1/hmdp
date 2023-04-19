package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    /*
    * 开始时间戳
    * */
    private static final long BEGIN_TIMETAMP = 1640995200L;
    /*
    * 序列号的位数
    * */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMETAMP;
        //2.生成序列号
        //2.1获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2自增长，在keyPrefix后面拼接日期是因为不拼接日期的话，随着订单越来越多，这个key的值会越来越大，所以将每天作为一个key进行存储
        long count = stringRedisTemplate.opsForValue().increment("icr"+keyPrefix+":"+date);

        //拼接并返回
        return timestamp << COUNT_BITS | count;
    }

}
