package com.heima.model.wemedia.vo;

import com.heima.model.wemedia.pojo.WmNews;
import lombok.Data;
@Data
public class WmNewsVO  extends WmNews {
    /**
     * 作者名称
     */
    private String authorName;
}