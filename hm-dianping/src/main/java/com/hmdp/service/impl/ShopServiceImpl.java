package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
        //拼接出来要在redis查询的key
        String queryKey = CACHE_SHOP_KEY+id;
        //先在redis中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(queryKey);
        //判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //存在,将商铺信息返回给前端
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if("".equals(shopJson)){
            return Result.fail("店铺不存在");
        }
        //不存在,则在数据库里查询
        Shop shop = getById(id);
        //不存在返回错误
        if(shop == null){
            stringRedisTemplate.opsForValue().set(queryKey,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //存在写入redis
        stringRedisTemplate.opsForValue().set(queryKey,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shop);
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
}
