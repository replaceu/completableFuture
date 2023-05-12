package com.carter;

import java.util.concurrent.CountDownLatch;

public class CarterCountDownLatch {
    public static void main(String[] args) throws InterruptedException {
        //countDownLatch:这个类使一个线程等待其他线程各自执行完毕后再执行
        CountDownLatch countDownLatch = new CountDownLatch(6);
        for (int i = 0; i < countDownLatch.getCount()-1; i++) {
            new Thread(()->{
                System.out.println(Thread.currentThread().getName()+" 执行...");
                //每个线程都数量减一
                countDownLatch.countDown();
            },String.valueOf(i)).start();
        }
        new Thread(()->{
            System.out.println("最后一个线程,此时countDownLatch的数量"+countDownLatch.getCount());
            countDownLatch.countDown();
        },String.valueOf(Thread.currentThread().getId())).start();

        //等待计数器归0
        countDownLatch.await();
        System.out.println("countDownLatch的数量"+countDownLatch.getCount()+" 等待其他线程执行完，再执行...");
    }
}
