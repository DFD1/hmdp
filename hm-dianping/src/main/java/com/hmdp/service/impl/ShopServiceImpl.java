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

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

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
        //不存在,则在数据库里查询
        Shop shop = getById(id);
        //不存在返回错误
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //存在写入redis
        stringRedisTemplate.opsForValue().set(queryKey,JSONUtil.toJsonStr(shop));
        //返回
        return Result.ok(shop);
    }
}
