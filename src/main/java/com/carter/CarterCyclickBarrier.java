package com.carter;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

public class CarterCyclickBarrier {
    public static void main(String[] args) {
        CyclicBarrier cyclicBarrier = new CyclicBarrier(7, () -> {
            System.out.println("这是一个召唤神龙的线程...");
        });

        for (int i = 0; i < cyclicBarrier.getParties(); i++) {
            int index=i;
            new Thread(()->{
                try{
                    System.out.println(Thread.currentThread().getName()+" 收集了第" + index +"颗龙珠"+cyclicBarrier.getNumberWaiting());
                    cyclicBarrier.await();
                }catch (Exception e){
                    e.printStackTrace();
                }
            },"线程"+i).start();
        }
    }
}
