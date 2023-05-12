package com.carter;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CarterThreadPool {
    public static void main(String[] args) {
        int maximumPoolSize = Runtime.getRuntime().availableProcessors();
        System.out.println(maximumPoolSize);
        ThreadPoolExecutor poolExecutor = new ThreadPoolExecutor(2,8,3, TimeUnit.SECONDS,new LinkedBlockingDeque<>(3), Executors.defaultThreadFactory(),new ThreadPoolExecutor.AbortPolicy());
        try{
            for (int i = 0; i < 80; i++) {
                //使用线程池创建线程
                poolExecutor.execute(()->{
                    System.out.println(Thread.currentThread().getName()+" ok!");
                });
            }
        }finally {
            poolExecutor.shutdown();
        }
    }
}
