package com.carter;

import java.util.concurrent.atomic.AtomicInteger;

//cas
public class CarterCasAtomic {
    public static void main(String[] args) {
        //创建一个原子类
        AtomicInteger atomicInteger = new AtomicInteger(5);
        /**
         * 一个是期望值，一个是更新值，但是期望值和原来的值相同的时候，才能够更改
         */
        boolean isChanged = atomicInteger.compareAndSet(5, 2019);
        System.out.println(isChanged+"\t current data:"+atomicInteger.get());
        //第二次修改是失败的，因为此时的期望值已经变成了2019
        isChanged = atomicInteger.compareAndSet(5,2016);
        System.out.println(isChanged+"\t current data:"+atomicInteger.get());
    }
}
