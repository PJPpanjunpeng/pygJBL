package com.pinyougou.seckill.service.impl;

import com.alibaba.dubbo.config.annotation.Service;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.pinyougou.common.util.IdWorker;
import com.pinyougou.common.util.RedisLock;
import com.pinyougou.mapper.SeckillGoodsMapper;
import com.pinyougou.mapper.SeckillOrderMapper;
import com.pinyougou.pojo.TbSeckillGoods;
import com.pinyougou.pojo.TbSeckillOrder;
import com.pinyougou.seckill.service.SeckillOrderService;
import com.pinyougou.service.impl.BaseServiceImpl;
import com.pinyougou.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import tk.mybatis.mapper.entity.Example;

import java.util.Date;
import java.util.List;

@Service(interfaceClass = SeckillOrderService.class)
public class SeckillOrderServiceImpl extends BaseServiceImpl<TbSeckillOrder> implements SeckillOrderService {

    //秒杀存储在redis中的key的名称
    private static final String SECKILL_ORDER = "SECKILL_ORDER";
    @Autowired
    private SeckillOrderMapper seckillOrderMapper;

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private IdWorker idWorker;

    @Override
    public PageResult search(Integer page, Integer rows, TbSeckillOrder seckillOrder) {
        PageHelper.startPage(page, rows);

        Example example = new Example(TbSeckillOrder.class);
        Example.Criteria criteria = example.createCriteria();
        /*if(!StringUtils.isEmpty(seckillOrder.get***())){
            criteria.andLike("***", "%" + seckillOrder.get***() + "%");
        }*/

        List<TbSeckillOrder> list = seckillOrderMapper.selectByExample(example);
        PageInfo<TbSeckillOrder> pageInfo = new PageInfo<>(list);

        return new PageResult(pageInfo.getTotal(), pageInfo.getList());
    }

    @Override
    public String submitOrder(Long seckillId, String username) throws InterruptedException {
        String seckillOrderId = "";
        //在秒杀系统的商品详情页面中点击了 立即抢购；判断当前商品是否存在，库存是否足够，
        // 将存在redis中的商品库存减1；生成具体的秒杀商品订单保存到redis中；
        // 如果在秒杀商品库存减1之后的库存量为0的时候；需要将redis中的秒杀商品同步保存回到mysql中

        //创建分布式锁
        RedisLock redisLock = new RedisLock(redisTemplate);
        if(redisLock.lock(seckillId.toString())) {
            //1、查询在redis中的秒杀商品并判断库存
            TbSeckillGoods seckillGoods = (TbSeckillGoods) redisTemplate.boundHashOps(SeckillGoodsServiceImpl.SECKILL_GOODS).get(seckillId);

            if (seckillGoods == null) {
                throw new RuntimeException("秒杀商品不存在");
            }

            if(seckillGoods.getStockCount() == 0){
                throw new RuntimeException("已秒杀完");
            }
            //2、递减库存
            seckillGoods.setStockCount(seckillGoods.getStockCount()-1);

            //2.1、如果库存为0则写回mysql并删除redis中的秒杀商品
            if(seckillGoods.getStockCount() < 1){
                //保存秒杀商品到mysql
                seckillGoodsMapper.updateByPrimaryKeySelective(seckillGoods);
                //删除redis中的数据
                redisTemplate.boundHashOps(SeckillGoodsServiceImpl.SECKILL_GOODS).delete(seckillId);
            } else {
                //2.0、将最新的秒杀商品更新回redis
                redisTemplate.boundHashOps(SeckillGoodsServiceImpl.SECKILL_GOODS).put(seckillId, seckillGoods);
            }

            //2.2、释放分布式锁
            redisLock.unlock(seckillId.toString());

            //3、生成秒杀订单并保存到redis
            TbSeckillOrder seckillOrder = new TbSeckillOrder();
            seckillOrder.setId(idWorker.nextId());
            seckillOrderId = seckillOrder.getId().toString();

            seckillOrder.setCreateTime(new Date());
            //秒杀商品的秒杀价
            seckillOrder.setMoney(seckillGoods.getCostPrice());
            seckillOrder.setSeckillId(seckillId);
            //未支付
            seckillOrder.setStatus("0");
            seckillOrder.setSellerId(seckillGoods.getSellerId());
            //当前秒杀该商品的用户
            seckillOrder.setUserId(username);

            redisTemplate.boundHashOps(SECKILL_ORDER).put(seckillOrderId, seckillOrder);
        }

        //4、返回秒杀订单的id
        return seckillOrderId;
    }

    @Override
    public TbSeckillOrder findSeckillOrderInRedisByOrderId(String outTradeNo) {
        return (TbSeckillOrder) redisTemplate.boundHashOps(SECKILL_ORDER).get(outTradeNo);
    }

    @Override
    public void updateSeckillOrderInRedisToDb(String outTradeNo, String transaction_id) {
        TbSeckillOrder seckillOrder = findSeckillOrderInRedisByOrderId(outTradeNo);

        if (seckillOrder != null) {
            //已支付
            seckillOrder.setStatus("1");
            seckillOrder.setPayTime(new Date());
            seckillOrder.setTransactionId(transaction_id);

            //保存redis中的订单到mysql中
            seckillOrderMapper.insertSelective(seckillOrder);

            //删除redis中的订单
            redisTemplate.boundHashOps(SECKILL_ORDER).delete(outTradeNo);
        }
    }

    @Override
    public void deleteSeckillOrderInRedis(String outTradeNo) throws InterruptedException {
        /**
         * 在秒杀系统中删除存储在redis中的秒杀订单，将该秒杀商品的库存加1（如果能在redis中找到商品则库存加1，
         * 如果找不到则到mysql中查询该商品并库存加1再存入redis）
         */

        //1、查询秒杀订单并获取秒杀商品id
        TbSeckillOrder seckillOrder = findSeckillOrderInRedisByOrderId(outTradeNo);

        RedisLock redisLock = new RedisLock(redisTemplate);
        if (redisLock.lock(seckillOrder.getSeckillId().toString())) {
            //2、将秒杀商品的库存加1
            TbSeckillGoods seckillGoods = (TbSeckillGoods) redisTemplate.boundHashOps(SeckillGoodsServiceImpl.SECKILL_GOODS).get(seckillOrder.getSeckillId());

            if (seckillGoods == null) {
                //从Mysql查询秒杀商品
                 seckillGoods = seckillGoodsMapper.selectByPrimaryKey(seckillOrder.getSeckillId());
            }

            //将库存加1
            seckillGoods.setStockCount(seckillGoods.getStockCount() + 1);

            //更新最新的秒杀商品到redis
            redisTemplate.boundHashOps(SeckillGoodsServiceImpl.SECKILL_GOODS).put(seckillGoods.getId(), seckillGoods);

            //释放该商品的分布式锁
            redisLock.unlock(seckillOrder.getSeckillId().toString());

            //3、将redis中的秒杀订单删除
            redisTemplate.boundHashOps(SECKILL_ORDER).delete(outTradeNo);
        }

    }
}
