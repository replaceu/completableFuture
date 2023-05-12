package com.carter;

import java.sql.Time;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CarterSemaphore {
    public static void main(String[] args) {
        //3个信号量
        Semaphore semaphore = new Semaphore(3);
        for (int i = 0; i < 10; i++) {
            int index=i;
            new Thread(()->{
                try{
                    semaphore.acquire();
                    System.out.println(Thread.currentThread().getName()+"抢到信号量"+index);
                    TimeUnit.SECONDS.sleep(2);
                    System.out.println(Thread.currentThread().getName()+"释放信号量...");
                }catch (Exception e){
                    e.printStackTrace();
                }finally {
                    semaphore.release();
                }
            },"线程"+i).start();
        }
    }
}
