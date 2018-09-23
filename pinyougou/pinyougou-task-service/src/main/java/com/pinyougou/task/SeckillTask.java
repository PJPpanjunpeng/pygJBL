package com.pinyougou.task;

import com.pinyougou.mapper.SeckillGoodsMapper;
import com.pinyougou.pojo.TbSeckillGoods;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tk.mybatis.mapper.entity.Example;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Component
public class SeckillTask {

    private static final String SECKILL_GOODS = "SECKILL_GOODS";

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private SeckillGoodsMapper seckillGoodsMapper;

    /**
     * 如果在数据库表tb_seckill_goods中有最新的已经审核通过，库存量大于0，开始时间小于等于当前时间，
     * 结束时间大于当前时间并且这些商品不在redis中的需要查询并更新到redis中
     */
    @Scheduled(cron = "0/3 * * * * ?")
    public void refreshSeckillGoods(){
        //1、查询数据
        //1.1、获取当前正在redis中的秒杀商品id集合
        List seckillGooodsIds = new ArrayList<>(redisTemplate.boundHashOps(SECKILL_GOODS).keys());

        //1.2、查询在mysql的最新可更新的商品数据

        Example example = new Example(TbSeckillGoods.class);

        Example.Criteria criteria = example.createCriteria();

        criteria.andEqualTo("status", "1");
        criteria.andGreaterThan("stockCount", 0);
        criteria.andLessThanOrEqualTo("startTime", new Date());
        criteria.andGreaterThan("endTime", new Date());

        //排除在redis中的秒杀商品
        criteria.andNotIn("id", seckillGooodsIds);

        //查询
        List<TbSeckillGoods> seckillGoodsList = seckillGoodsMapper.selectByExample(example);

        //2、更新到redis
        if (seckillGoodsList != null && seckillGoodsList.size() > 0) {

            for (TbSeckillGoods seckillGoods : seckillGoodsList) {
                redisTemplate.boundHashOps(SECKILL_GOODS).put(seckillGoods.getId(), seckillGoods);
            }

            System.out.println("更新了 " + seckillGoodsList.size() + " 条新的秒杀商品到缓存中...");
        }
    }

    /**
     * 在redis中的秒杀商品如果过时（结束时间小于当前时间）的话那么需要将该秒杀商品从redis中同步到mysql；再从redis中删除
     */
    @Scheduled(cron = "* * * * * ?")
    public void removeSeckillGooods(){
        //1、查询在redis中的秒杀商品列表
        List<TbSeckillGoods> seckillGoodsList = redisTemplate.boundHashOps(SECKILL_GOODS).values();

        if(seckillGoodsList != null && seckillGoodsList.size() >0) {
            //2、判断每个秒杀商品的结束时间是否小于当前时间
            for (TbSeckillGoods seckillGoods : seckillGoodsList) {
                if(seckillGoods.getEndTime().getTime() < System.currentTimeMillis()) {
                    //2.1、如果结束时间是否小于当前时间，将该秒杀商品更新回mysql
                    seckillGoodsMapper.updateByPrimaryKeySelective(seckillGoods);
                    //2.2、将该秒杀商品从redis中删除
                    redisTemplate.boundHashOps(SECKILL_GOODS).delete(seckillGoods.getId());

                    System.out.println("移除秒杀商品id为：" + seckillGoods.getId() );
                }

            }

        }
    }
}
