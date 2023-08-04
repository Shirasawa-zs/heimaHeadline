package com.heima.wemedia.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.wemedia.dto.NewsAuthDTO;
import com.heima.model.wemedia.pojo.WmNews;
import com.heima.model.wemedia.vo.WmNewsVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WmNewsMapper extends BaseMapper<WmNews> {
    List<WmNewsVO> findListAndPage(@Param("dto") NewsAuthDTO dto);

    long findListCount(@Param("dto") NewsAuthDTO dto);
}