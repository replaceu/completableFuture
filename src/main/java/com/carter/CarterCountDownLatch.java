package com.carter;

import com.carter.threadPool.CarterAbstractQueuedSynchronizer;

import java.util.concurrent.TimeUnit;

public class CarterCountDownLatch {

	private static final class Sync extends CarterAbstractQueuedSynchronizer {
		//版本号
		private static final long serialVersionUID = 4982264981922014374L;
		//构造方法
		Sync(int count) {
			setState(count);
		}
		//返回当前计数
		int getCount() {
			return getState();
		}

		//在共享模式下获取锁，重写CarterAbstractQueuedSynchronizer中tryAcquireShared()方法
		protected int tryAcquireShared(int acquires) {
			return (getState() == 0) ? 1 : -1;
		}

		// 共享模式下的尝试释放锁  重写AQS中tryReleaseShared() 方法
		protected boolean tryReleaseShared(int releases) {
			//自旋，降低计数器; 至0时发出信号
			for (;;) {
				//获取同步状态==>当前计时器
				int c = getState();
				//如果同步状态已经为0则返回false，说明被之前线程减成0了
				if (c == 0){return false;}
				//否则的话就将同步状态减1
				int nextc = c - 1;
				//CSA更新值，如果更新失败自旋更新直至成功
				if (compareAndSetState(c, nextc)){ return nextc == 0;}
			}
		}
	}

	private final CarterCountDownLatch.Sync sync;

	//CountDownLatch只有一个带参构造器，必须传入一个大于0的值作为计数器初始值，否则会报错
	public CarterCountDownLatch(int count) {
		if (count < 0) throw new IllegalArgumentException("count < 0");
		this.sync = new CarterCountDownLatch.Sync(count);
	}

	public void await() throws InterruptedException {
		sync.acquireSharedInterruptibly(1);
	}

	public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
		return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
	}

	public void countDown() {
		sync.releaseShared(1);
	}

	public long getCount() {
		return sync.getCount();
	}

	public String toString() {
		return super.toString() + "[Count = " + sync.getCount() + "]";
	}

}
