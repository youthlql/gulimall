package com.atguigu.gulimall.product.vo;

import com.atguigu.gulimall.product.entity.SkuImagesEntity;
import com.atguigu.gulimall.product.entity.SkuInfoEntity;
import com.atguigu.gulimall.product.entity.SpuInfoDescEntity;
import lombok.Data;

import java.util.List;

/**
 * @author: maruimin
 * @date: 2020/5/13 21:25
 */
@Data
public class SkuItemVo {

    // sku基本信息获取 pms_sku_info
    SkuInfoEntity info;

    boolean hasStock = true;

    // sku的图片信息，pms_sku_images
    List<SkuImagesEntity> images;

    // 获取spu的销售属性组合
    List<SkuItemSaleAttrVo> saleAttr;

    // 4获取spu的介绍
    SpuInfoDescEntity desc;

    // 5. 获取spu的规格参数信息
    List<SpuItemAttrGroupVo> groupAttrs;

}