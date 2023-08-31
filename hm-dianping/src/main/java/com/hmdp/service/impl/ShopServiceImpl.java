package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    //根据id查询商铺信息
    public Result queryById(Long id) {
        //缓存穿透
        // Shop shop = queryWithPassThrough(id);

        //用互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿问题
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        //拼接出来要在redis查询的key
        String queryKey = CACHE_SHOP_KEY+id;
        //先在redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(queryKey);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在,将商铺信息返回给前端
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if("".equals(shopJson)){
            return null;
        }
        //实现缓存重建
        //获取互斥锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            boolean isLock = trylock(lockKey);
            //判断是否获取成功
            if(!isLock){
                //失败，则休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //成功，根据id查询数据库
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);

            //不存在返回错误
            if(shop == null){
                stringRedisTemplate.opsForValue().set(queryKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在写入redis
            stringRedisTemplate.opsForValue().set(queryKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);


        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unlock(lockKey);
        }
        //返回
        return shop;
    }

    //解决缓存穿透
    public Shop queryWithPassThrough(Long id){
        //拼接出来要在redis查询的key
        String queryKey = CACHE_SHOP_KEY+id;
        //先在redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(queryKey);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在,将商铺信息返回给前端
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if("".equals(shopJson)){
            return null;
        }
        //不存在,则在数据库里查询
        Shop shop = getById(id);
        //不存在返回错误
        if(shop == null){
            stringRedisTemplate.opsForValue().set(queryKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在写入redis
        stringRedisTemplate.opsForValue().set(queryKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //通过逻辑过期来解决缓存击穿
    public Shop queryWithLogicalExpire(Long id){
        //拼接出来要在redis查询的key
        String queryKey = CACHE_SHOP_KEY+id;
        //先在redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(queryKey);
        //判断是否存在
        if(StrUtil.isBlank(shopJson)){
            Shop shop = getById(id);
            try {
                this.saveShop2Redis(id,20L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //不存在直接返回null
            return shop;
        }
        //命中，将json字符串反序列化成RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()),Shop.class);
        //获取该商铺缓存的过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //如果未过期，直接将商铺信息返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //如果已过期，则需要缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = trylock(lockKey);
        if(isLock){
            //成功获取互斥锁，开启独立线程，实现缓存重建。
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }


    public boolean trylock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    //更新商铺信息
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);
        return Result.ok();

    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if(x == null || y == null){
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //查询redis，按照距离排序、分页。结果:
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size() < from){
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        //截取from - end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result ->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
