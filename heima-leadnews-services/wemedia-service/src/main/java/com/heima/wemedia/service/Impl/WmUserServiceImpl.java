package com.heima.wemedia.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.heima.common.exception.CustException;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.dto.WmUserDTO;
import com.heima.model.wemedia.pojo.WmUser;
import com.heima.model.wemedia.vo.WmUserVO;
import com.heima.utils.common.AppJwtUtil;
import com.heima.wemedia.mapper.WmUserMapper;
import com.heima.wemedia.service.WmUserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.Date;
import java.util.HashMap;

@Service
public class WmUserServiceImpl extends ServiceImpl<WmUserMapper, WmUser> implements WmUserService {
    @Override
    public ResponseResult login(WmUserDTO dto) {
        //1.校验参数是否合法
        if(!(StringUtils.isNotBlank(dto.getName()) && StringUtils.isNotBlank(dto.getPassword()))){
            CustException.cust(AppHttpCodeEnum.PARAM_INVALID);
        }
        //2.根据name查询是否存在并校验状态
        WmUser wmUser = getOne(Wrappers.<WmUser>lambdaQuery()
                .eq(WmUser::getName, dto.getName()));
        if(wmUser == null){
            CustException.cust(AppHttpCodeEnum.DATA_NOT_EXIST);
        }
        if(wmUser.getStatus() != 9){
            CustException.cust(AppHttpCodeEnum.DATA_NOT_ALLOW,"该用户状态异常，请联系管理员");
        }
        //3.校验密码
        String password = wmUser.getPassword();
        String salt = wmUser.getSalt();
        String pswd = DigestUtils.md5DigestAsHex((dto.getPassword() + wmUser.getSalt()).getBytes());
        if (!wmUser.getPassword().equals(pswd)) {
            CustException.cust(AppHttpCodeEnum.LOGIN_PASSWORD_ERROR);
        }
        //4.更新状态
        Date date = new Date();
        wmUser.setLoginTime(date);
        updateById(wmUser);
        //5.生成token
        String token = AppJwtUtil.getToken(wmUser.getId().longValue());
        //6.返回响应
        WmUserVO wmUserVO = new WmUserVO();
        BeanUtils.copyProperties(wmUser, wmUserVO);
        HashMap<String, Object> hashMap = new HashMap<>();
        hashMap.put("user", wmUserVO);
        hashMap.put("token", token);
        return  ResponseResult.okResult(hashMap);
    }
}