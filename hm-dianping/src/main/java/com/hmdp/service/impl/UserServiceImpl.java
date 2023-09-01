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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.jni.Local;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
        //与前端传入的验证码做比较，如果不一致则报错  验证码不正确
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
        //将user对象转换为UserDTO对象，为了防止返回的user属性过多，防止用户信息泄露
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

    //实现用户签到
    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    //统计连续签到
    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的所有的签到记录，返回的是一个十进制数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        int count = 0;
        //循环遍历
        while(true){
            //让这个数字与1做与运算,得到数字的最后一个bit位
            if((num & 1) == 0){
                //如果最后一位为0,说明未签到，结束
                break;
            }else{
                //如果最后一位为1,则计数器加1,并将该数字向右移一位,抛弃最后一个bit位,继续下一个bit位。
                count++;
            }
            num >>>= 1;
        }
        return Result.ok(count);
    }

    //根据手机号创建一个新的用户
    private User createUser(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
