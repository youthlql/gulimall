package com.atguigu.gulimall.ware.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.exception.NoStockException;
import com.atguigu.common.to.mq.OrderTo;
import com.atguigu.common.to.mq.StockDetailTo;
import com.atguigu.common.to.mq.StockLockedTo;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.ware.dao.WareSkuDao;
import com.atguigu.gulimall.ware.entity.WareOrderTaskDetailEntity;
import com.atguigu.gulimall.ware.entity.WareOrderTaskEntity;
import com.atguigu.gulimall.ware.entity.WareSkuEntity;
import com.atguigu.gulimall.ware.feign.OrderFeignService;
import com.atguigu.gulimall.ware.feign.ProductFeignService;
import com.atguigu.gulimall.ware.service.WareOrderTaskDetailService;
import com.atguigu.gulimall.ware.service.WareOrderTaskService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.atguigu.gulimall.ware.vo.OrderItemVo;
import com.atguigu.gulimall.ware.vo.OrderVo;
import com.atguigu.gulimall.ware.vo.SkuHasStockVo;
import com.atguigu.gulimall.ware.vo.WareSkuLockVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rabbitmq.client.Channel;
import lombok.Data;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RabbitListener(queues = "stock.release.stock.queue")
@Service("wareSkuService")
public class WareSkuServiceImpl extends ServiceImpl<WareSkuDao, WareSkuEntity> implements WareSkuService {

    @Autowired
    WareSkuDao wareSkuDao;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    WareOrderTaskService wareOrderTaskService;

    @Autowired
    WareOrderTaskDetailService wareOrderTaskDetailService;

    @Autowired
    OrderFeignService orderFeignService;

    /**
     * 1. 库存自动解锁。
     * 下订单成功，库存锁定成功，接下来的业务调用失败，导致订单回滚，之前锁定的库存就要自动解锁
     * 2. 订单失败
     * 锁库存失败
     *
     * @param to
     * @param message
     */
    @RabbitHandler
    public void handleStockLockedRelease(StockLockedTo to, Message message, Channel channel) throws IOException {
        System.out.println("收到解锁库存的消息");
        StockDetailTo detail = to.getDetailTo();
        Long detailId = detail.getId();
        /**
         * 解锁
         * 1. 查询数据库关于这个订单的锁定库存信息。
         * 有：证明库存锁定成功了
         * 解锁：需要判断订单状态
         *      1. 没有这个订单，必须解锁
         *      2. 有这个订单，不是解锁库存
         *              订单状态：已取消：解锁库存
         *                               没取消：不能解锁库存
         *
         * 没有：库存锁定失败了，库存回滚了。这种情况无需解锁
         */
        WareOrderTaskDetailEntity byId = wareOrderTaskDetailService.getById(detailId);
        if (byId != null) {
            // 说明锁定成功了，这种情况需要解锁库存
            Long id = to.getId();
            WareOrderTaskEntity taskEntity = wareOrderTaskService.getById(id);
            String orderSn = taskEntity.getOrderSn();
            R r = orderFeignService.getOrderstatus(orderSn);
            if (r.getCode() == 0) {
                // 订单数据查询成功
                OrderVo data = r.getData(new TypeReference<OrderVo>() {
                });
                if (data == null || data.getStatus() == 4) {
                    // 订单已经取消了或者订单不存在。才能解锁库存
                    unLockStock(detail.getSkuId(), detail.getWareId(), detail.getSkuNum(), detailId);
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                }
            } else {
                // 消息拒绝以后重新放回队列，让别人来解锁
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), true);
            }

        } else {
            // 没有，说明库存锁定失败了，库存回滚了。这种情况无需解锁
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        }

    }

    private void unLockStock(Long skuId, Long wareId, Integer num, Long taskDetailId) {
        // 库存解锁
        wareSkuDao.unlockStock(skuId, wareId, num);
        // 更新库存工作单的状态
        WareOrderTaskDetailEntity entity = new WareOrderTaskDetailEntity();
        entity.setId(taskDetailId);
        entity.setLockStatus(2); // 变为已解锁
        wareOrderTaskDetailService.save(entity);

    }

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        /**
         * skuId: 1
         * wareId: 2
         */
        QueryWrapper<WareSkuEntity> queryWrapper = new QueryWrapper<>();
        String skuId = (String) params.get("skuId");
        if (!StringUtils.isEmpty(skuId)) {
            queryWrapper.eq("sku_id", skuId);
        }

        String wareId = (String) params.get("wareId");
        if (!StringUtils.isEmpty(wareId)) {
            queryWrapper.eq("ware_id", wareId);
        }


        IPage<WareSkuEntity> page = this.page(
                new Query<WareSkuEntity>().getPage(params),
                queryWrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void addStock(Long skuId, Long wareId, Integer skuNum) {

        //1、判断如果还没有这个库存记录新增
        List<WareSkuEntity> entities = wareSkuDao.selectList(new QueryWrapper<WareSkuEntity>().eq("sku_id", skuId).eq("ware_id", wareId));
        if (entities == null || entities.size() == 0) {
            WareSkuEntity skuEntity = new WareSkuEntity();
            skuEntity.setSkuId(skuId);
            skuEntity.setStock(skuNum);
            skuEntity.setWareId(wareId);
            skuEntity.setStockLocked(0);
            //TODO 远程查询sku的名字，如果失败，整个事务无需回滚
            //1、自己catch异常
            //TODO 还可以用什么办法让异常出现以后不回滚？高级
            try {
                R info = productFeignService.info(skuId);
                Map<String, Object> data = (Map<String, Object>) info.get("skuInfo");

                if (info.getCode() == 0) {
                    skuEntity.setSkuName((String) data.get("skuName"));
                }
            } catch (Exception e) {
            }
            wareSkuDao.insert(skuEntity);
        } else {
            wareSkuDao.addStock(skuId, wareId, skuNum);
        }

    }

    @Override
    public List<SkuHasStockVo> getSkuHasStock(List<Long> skuIds) {
        List<SkuHasStockVo> collect = skuIds.stream().map(skuId -> {
            SkuHasStockVo vo = new SkuHasStockVo();
            // 查询当前sku的总库存量
            // select sum(stock - stock_locked) from `wms_ware_sku` where sku_id = 1
            Long count = baseMapper.getSkuStock(skuId);
            vo.setSkuId(skuId);
            vo.setHasStock(count != null && count > 0);
            return vo;
        }).collect(Collectors.toList());
        return collect;
    }


    /**
     * 为某个订单锁定库存
     * (rollbackFor = NoStockException.class)
     * 默认只要是RuntimeException都会回滚
     * <p>
     * 解锁库存的场景
     * 1). 下订单成功，订单过期没有支付被系统自动取消、被用户手动取消。都要解锁库存
     * 2). 下订单成功，库存锁定成功。接下来的业务调用失败。导致订单回滚，之前锁定的库存就要自动解锁
     *
     * @param vo
     * @return
     */
    @Transactional(rollbackFor = NoStockException.class)
    @Override
    public Boolean orderLockStock(WareSkuLockVo vo) {
        /**
         * 保存库存工作单详情
         * 追溯
         */
        WareOrderTaskEntity taskEntity = new WareOrderTaskEntity();
        taskEntity.setOrderSn(vo.getOrderSn());
        wareOrderTaskService.save(taskEntity);

        // 1. 按照下单的收货地址，找到一个就近仓库，锁定库存

        // 1. 找到每个商品在哪个仓库都有库存
        List<OrderItemVo> locks = vo.getLocks();

        List<SkuWareHasStock> collect = locks.stream().map(item -> {
            SkuWareHasStock stock = new SkuWareHasStock();
            Long skuId = item.getSkuId();
            stock.setSkuId(skuId);
            stock.setNum(item.getCount());
            // 查询这个商品在哪里有库存
            List<Long> wareIds = wareSkuDao.listWareIdHasSkuStock(skuId);
            stock.setWareId(wareIds);
            return stock;
        }).collect(Collectors.toList());

        // 2. 锁定库存
        for (SkuWareHasStock hasStock : collect) {
            Boolean skuStocked = false;
            Long skuId = hasStock.getSkuId();
            List<Long> wareIds = hasStock.getWareId();
            if (wareIds == null || wareIds.size() == 0) {
                // 没有任何仓库有这个商品的库存，直接抛出错误
                throw new NoStockException(skuId);
            }

            // 1. 如果每一个商品都锁定成功，将当前商品锁定了几件的工作单记录发送给MQ
            // 2. 锁定失败，前面保存的工作单信息就回滚了，发送出去的消息，即使要解锁记录，由于去数据库查不到id, 所以就不用解锁
            for (Long wareId : wareIds) {
                Long count = wareSkuDao.lockSkuStock(skuId, wareId, hasStock.getNum());
                if (count == 1) {
                    // 锁定成功就返回1，否则就是返回0
                    WareOrderTaskDetailEntity entity = new WareOrderTaskDetailEntity(null, skuId, "", hasStock.getNum(), taskEntity.getId(), wareId, 1);
                    wareOrderTaskDetailService.save(entity);
                    StockLockedTo lockedTo = new StockLockedTo();
                    lockedTo.setId(taskEntity.getId());
                    StockDetailTo stockDetailTo = new StockDetailTo();
                    BeanUtils.copyProperties(entity, stockDetailTo);
                    // 只发ID不行，防止回滚以后找不到数据
                    lockedTo.setDetailTo(stockDetailTo);
                    rabbitTemplate.convertAndSend("stock-event-exchange", "stock.locked", lockedTo);
                    skuStocked = true;
                    break;
                } else {
                    // 当前仓库所失败，重试下一个仓库
                }
            }
            if (!skuStocked) {
                // 当前商品所有的仓库都没有锁住
                throw new NoStockException(skuId);
            }
        }

        return true;
    }

    @Override
    public void unlockStock(StockLockedTo to) {
        System.out.println("收到解锁库存的消息");
        StockDetailTo detail = to.getDetailTo();
        Long detailId = detail.getId();
        /**
         * 解锁
         * 1. 查询数据库关于这个订单的锁定库存信息。
         * 有：证明库存锁定成功了
         * 解锁：需要判断订单状态
         *      1. 没有这个订单，必须解锁
         *      2. 有这个订单，不是解锁库存
         *              订单状态：已取消：解锁库存
         *                               没取消：不能解锁库存
         *
         * 没有：库存锁定失败了，库存回滚了。这种情况无需解锁
         */
        WareOrderTaskDetailEntity byId = wareOrderTaskDetailService.getById(detailId);
        if (byId != null) {
            // 说明锁定成功了，这种情况需要解锁库存
            Long id = to.getId();
            WareOrderTaskEntity taskEntity = wareOrderTaskService.getById(id);
            String orderSn = taskEntity.getOrderSn();
            R r = orderFeignService.getOrderstatus(orderSn);
            if (r.getCode() == 0) {
                // 订单数据查询成功
                OrderVo data = r.getData(new TypeReference<OrderVo>() {
                });
                if (data == null || data.getStatus() == 4) {
                    // 订单已经取消了或者订单不存在。才能解锁库存
                    if (byId.getLockStatus() == 1) {
                        // 当前库存工作单详情, 状态1 已锁定但是未解锁才可以解锁
                        unLockStock(detail.getSkuId(), detail.getWareId(), detail.getSkuNum(), detailId);
                    }
                }
            } else {
                throw new RuntimeException("远程服务解锁失败");
            }
        }

    }

    /**
     * 防止订单服务卡顿，导致订单状态消息一直改不了，库存消息优先到期。查订单状态新建状态，什么都不做就走了
     * 导致卡顿的订单，永远不能解锁库存
     *
     * @param orderTo
     */
    @Transactional
    @Override
    public void unlockStock(OrderTo orderTo) {
        String orderSn = orderTo.getOrderSn();
        // 查一下最新库存的状态，防止重复解锁库存
        WareOrderTaskEntity task = wareOrderTaskService.getOrderTaskByOrderSn(orderSn);
        Long id = task.getId();
        // 按照工作单找到所有没有解锁的库存，进行解锁
        List<WareOrderTaskDetailEntity> entities = wareOrderTaskDetailService.list(
                new QueryWrapper<WareOrderTaskDetailEntity>()
                        .eq("task_id", id)
                        .eq("lock_status", 1));
        for (WareOrderTaskDetailEntity entity : entities) {
            unLockStock(entity.getSkuId(), entity.getWareId(), entity.getSkuNum(), entity.getId());
        }
    }

    @Data
    class SkuWareHasStock {
        private Long skuId;
        private Integer num;
        private List<Long> wareId;
    }

}

