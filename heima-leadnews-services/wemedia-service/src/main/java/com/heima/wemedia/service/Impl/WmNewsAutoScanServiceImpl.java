package com.heima.wemedia.service.Impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.heima.aliyun.GreenImageScan;
import com.heima.aliyun.GreenTextScan;
import com.heima.common.exception.CustException;
import com.heima.feigns.AdminFeign;
import com.heima.model.common.constants.wemedia.WemediaConstants;
import com.heima.model.common.dtos.ResponseResult;
import com.heima.model.common.enums.AppHttpCodeEnum;
import com.heima.model.wemedia.pojo.WmNews;
import com.heima.utils.common.SensitiveWordUtil;
import com.heima.utils.common.SensitiveWordUtils;
import com.heima.wemedia.mapper.WmNewsMapper;
import com.heima.wemedia.service.WmNewsAutoScanService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WmNewsAutoScanServiceImpl implements WmNewsAutoScanService {
    @Autowired
    private WmNewsMapper wmNewsMapper;

    @Autowired
    private AdminFeign adminFeign;

    @Autowired
    GreenImageScan greenImageScan;

    @Autowired
    GreenTextScan greenTextScan;

    @Value("${file.oss.web-site}")
    String webSite;

    @Override
    public void autoScanWmNews(Integer id) {
        log.info("文章自动触发   待审核的文章：{}", id);
        //1. 判度文章id是否为空
        if(id == null){
            CustException.cust(AppHttpCodeEnum.PARAM_INVALID, "待审核的id为空");
        }
        //2.根据id查询自媒体文章
        WmNews wmNews = wmNewsMapper.selectById(id);
        if(wmNews == null){
            CustException.cust(AppHttpCodeEnum.DATA_NOT_EXIST, "对应文章不存在");
        }
        //3.判度文章状态，必须是1(待审核)(避免重复消费)
        Short status = wmNews.getStatus();
        if(!WemediaConstants.WM_NEWS_SUMMIT_STATUS.equals(status)){
            log.info("当前文章状态为：{}，不是待审核状态，无需审核", status);
            return;
        }
        //4.抽取文章中 所有文本内容 和 所有图片内容
        Map<String, Object> contentAndImages = handleTextAndImages(wmNews);
        //5.DFA 进行敏感词管理 2 有敏感词不通过 通过继续下一步
        boolean scanSensitive = handleSensitive((String)contentAndImages.get("content"), wmNews);
        if(!scanSensitive){
            //false 审核不通过
            log.info("文章审核未通过，内容中包含敏感词");
        }
        //6.阿里云文本审核 2有违规词汇 3.不确定/aliyun未调用成功 通过继续下一步
        boolean isTextScan = handleTextScan((String)contentAndImages.get("content"),wmNews);
        if(!isTextScan){
            return;
        }
        log.info(" 阿里云内容审核通过  =======   ");
        // 7  阿里云的图片审核   失败  状态2  不确定 状态3
        Object images = contentAndImages.get("images");
        if(images!=null){
            boolean isImageScan =handleImageScan((List<String>)images,wmNews);
            if(!isImageScan) {return;}
            log.info(" 阿里云图片审核通过  =======   ");
        }
    }

    /**
     * 抽取 文章中所有 文本内容  及 所有图片路径
     * @param wmNews  content  type:text     title
     * @return
     */
    private Map<String, Object> handleTextAndImages(WmNews wmNews) {
        Map<String, Object> result = new HashMap<>();
        //1.判度内容不能为空，并转为List<Map>
        if(!StringUtils.isNotBlank(wmNews.getContent())){
            CustException.cust(AppHttpCodeEnum.DATA_NOT_EXIST, "文章内容不存在");
        }
        //2.抽取文章中所有的文本内容
        List<Map> maps = JSONArray.parseArray(JSON.toJSONString(wmNews.getContent()),Map.class);
        //2.1抽取content中所有文本，拼接成一个字符串
       String content = maps.stream()
                .filter(map -> "text".equals(map.get("type")))
                .map(map -> map.get("value").toString())
                .collect(Collectors.joining("_hmtt_"));
        //2.2将文章内容和标题拼接成一个字符串
        String contents = wmNews.getTitle() + "_hmtt_" + content;
        //2.3将总的文本内容装入map
        result.put("content", contents);

        //3.抽取文章中所有的图片列表
        //3.1抽取content中的所有图片 得到图片列表
        List<String> imageList = maps.stream()
                .filter(m -> "images".equals(m.get("type")))
                .map(m -> m.get("value").toString())
                .collect(Collectors.toList());
        //3.2抽取封面中的所有图片，得到图片列表
        String coverStr = wmNews.getImages();
        if(StringUtils.isNotBlank(coverStr)){
            // 按照 逗号 切割封面字符串  得到数组   基于数组得到stream   将每一条数据都拼接一个前缀 收集成集合
            List<String> urls = Arrays.stream(coverStr.split(","))
                    .map(url -> webSite + url)
                    .collect(Collectors.toList());
            //3.3合并 内容图片 和 封面图片
            imageList.addAll(urls);
        }
        //3.4去除重复图片
        imageList = imageList.stream().distinct().collect(Collectors.toList());
        //3.5将所有图片装入map
        result.put("images",imageList);
        return result;
    }

    /**
     * 基于DFA 检测内容是否包含敏感词
     * @param content
     * @param wmNews
     * @return
     */
    private boolean handleSensitive(String content, WmNews wmNews) {
        boolean flag = true;
        //1.远程调用敏感词接口
        ResponseResult<List<String>> sensitives = adminFeign.sensitives();
        if (sensitives.checkCode()){
            CustException.cust(AppHttpCodeEnum.REMOTE_SERVER_ERROR);
        }
        List<String> sensitivesData = sensitives.getData();
        //2.敏感词 转为DFA数据模型
        SensitiveWordUtils.initMap(sensitivesData);
        //3.基于DFA扫描内容中是否包含敏感词
        Map<String, Integer> resultMap = SensitiveWordUtil.matchWords(content);
        if(resultMap!=null && resultMap.size() > 0){
            // 将文章状态改为2
            updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"内容中包含敏感词: " + resultMap);
            flag = false;
        }
        return flag;
    }

    /**
     * 修改文章状态
     * @param wmNews
     * @param status
     * @param reason
     */
    private void updateWmNews(WmNews wmNews, short status, String reason) {
        wmNews.setStatus(status);
        wmNews.setReason(reason);
        wmNewsMapper.updateById(wmNews);
    }

    /**
     * 阿里云文本
     * @param content    block: 状态2    review: 状态3    异常: 状态3
     * @param wmNews
     * @return
     */
    private boolean handleTextScan(String content, WmNews wmNews) {
        boolean flag = true;
        try {
            Map map = greenTextScan.greeTextScan(content);
            String suggestion = (String)map.get("suggestion");
            switch (suggestion){
                case "block":
                    updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"文本中有违规内容，审核失败");
                    flag = false;
                    break;
                case "review":
                    updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"文本中有不确定内容，转为人工审核");
                    flag = false;
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("阿里云文本审核出现异常 , 原因:{}",e.getMessage());
            updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"阿里云内容服务异常，转为人工审核");
            flag = false;
        }
        return flag;
    }

    /**
     * 阿里云图片审核
     * @param images  待审核的图片列表
     * @return
     */
    private boolean handleImageScan(List<String> images,WmNews wmNews) {
        List<byte[]> listcollect = images.stream()
                .map(m -> m.getBytes())
                .collect(Collectors.toList());

        boolean flag = true;
        try {
            Map map = greenImageScan.imageScan(listcollect);
            String suggestion = (String)map.get("suggestion");
            switch (suggestion){
                case "block":
                    updateWmNews(wmNews,WmNews.Status.FAIL.getCode(),"图片中有违规内容，审核失败");
                    flag = false;
                    break;
                case "review":
                    updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"图片中有不确定内容，转为人工审核");
                    flag = false;
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            log.error("阿里云图片审核出现异常 , 原因:{}",e.getMessage());
            updateWmNews(wmNews,WmNews.Status.ADMIN_AUTH.getCode(),"阿里云内容服务异常，转为人工审核");
            flag = false;
        }
        return flag;
    }

}
