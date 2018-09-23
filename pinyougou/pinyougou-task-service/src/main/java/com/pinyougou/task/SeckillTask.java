package com.pinyougou.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class SeckillTask {

    @Scheduled(cron = "* * * * * ?")
    public void refreshSeckillGoods(){
        System.out.println("当前时间为：" + new Date());
    }
}
