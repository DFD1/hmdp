package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getList() {
        //先从redis里面查询商铺类型信息
        List<String> shopTypeList = stringRedisTemplate.opsForList().range("list", 0, -1);
        //若存在直接返回
        if(shopTypeList.size() != 0){
            List<ShopType> res = new ArrayList<>();
            for (String shopType : shopTypeList) {
                ShopType shopType_ = JSONUtil.toBean(shopType, ShopType.class);
                res.add(shopType_);
            }
            return Result.ok(res);
        }
        //若不存在从数据库中查询
        List<ShopType> shopTypeList_mysql = query().orderByAsc("sort").list();
        //数据库中也不存在，则返回错误信息
        if(shopTypeList_mysql.isEmpty()){
            return Result.fail("店铺类型不存在");
        }
        //数据库中存在，返回商铺类型信息，并在存储到redis中
        for (ShopType shopType : shopTypeList_mysql) {
            String shopType_json = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForList().rightPush("list",shopType_json);
        }
        return Result.ok(shopTypeList_mysql);
    }
}
