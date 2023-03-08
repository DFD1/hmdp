package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证手机号格式
        boolean ok  = RegexUtils.isPhoneInvalid(phone);

        //如果手机号格式不正确，返回错误信息
        if(ok == true){
            return Result.fail("手机号格式不正确");
        }
        //如果手机号格式正确.则生成验证码
        else{
            String code = RandomUtil.randomString(6);
//            //保存验证码到session中
//            session.setAttribute("code",code);

            //保存验证码到redis中
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,2,TimeUnit.MINUTES);
            //发送验证码(未实现)
            log.debug(code);
        }
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //获取登录的手机号和验证码
        String phone = loginForm.getPhone();
        String login_code = loginForm.getCode();
        //检验手机号是否合法
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        //Object cachecode = session.getAttribute("code"); 存在session里的code
        //获取存在redis里面的验证码code
        String redis_code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(redis_code == null || !redis_code.equals(login_code)){
            return Result.fail("验证码不正确");
        }
        //根据用户输入的手机号，去数据库查询是否有该用户的信息，如果没有该用户则创建一个新的用户。
        User user = query().eq("phone",phone).one();
        if (user == null){
           user =  createUser(phone);
        }
        //将user的信息保存在redis中
        //使用uuid生成token
        String token = UUID.randomUUID().toString(true);
        //拼接token_key
        String token_key = LOGIN_USER_KEY + token;
        //将user对象转换为UserDTO对象
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        //将userDto转换为Map，存入redis,因为userMap中的id为long类型，所以要使用setFieldValueEditor 将id字段转换为string
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                .setFieldValueEditor((fieldName,fieldValue)->{return fieldValue.toString();}));

        stringRedisTemplate.opsForHash().putAll(token_key,userMap);
        //设置过期时间
        stringRedisTemplate.expire(token_key,LOGIN_USER_TTL, TimeUnit.SECONDS);
        // session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        return Result.ok(token);
    }

    private User createUser(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
