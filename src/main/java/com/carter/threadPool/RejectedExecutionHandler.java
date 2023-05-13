package com.carter.threadPool;

public interface RejectedExecutionHandler {
    void rejectedExecution(Runnable var1, CarterThreadPoolExecutor var2);
}
