package com.carter.threadPool;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CarterThreadPoolExecutor extends AbstractQueuedSynchronizer {
	//用来标记线程池状态（高3位）, 线程个数（低29位）  默认是RUNNING状态, 线程个数为0
	private final AtomicInteger ctl;
	//线程个数掩码位数，并不是所有平台int类型是32位，所以准确说是具体平台下Integer的二进制位数-3后的剩余位数才是线程的个数29位
	private static final int	COUNT_BITS	= 29;
	private static final int	COUNT_MASK	= 536870911;
	//线程池状态
	//（高3位）：11100000000000000000000000000000 往左移29位
	// 接受新任务并且处理阻塞队列里的任务
	// 当创建线程池后，初始时，线程池处于RUNNING状态
	private static final int RUNNING = -536870912;
	//（高3位）：00000000000000000000000000000000 往左移29位
	// 拒绝新任务但是处理阻塞队列里的任务
	// 调用了shutdown()方法，则线程池处于SHUTDOWN状态
	private static final int SHUTDOWN = 0;
	//（高3位）：00100000000000000000000000000000 往左移29位
	// 拒绝新任务并且抛弃阻塞队列里的任务，同时会中断正在处理的任务
	// 调用了shutdownNow()方法，则线程池处于STOP状态
	private static final int STOP = 536870912;
	//（高3位）：01000000000000000000000000000000
	//所有任务都执行完（包含阻塞队列里面任务）当前线程池活动线程为 0，将要调用 terminated 方法
	private static final int TIDYING = 1073741824;
	//（高3位）：01100000000000000000000000000000
	//终止状态，terminated方法调用完成以后的状态
	//任务缓存队列已经清空或执行结束后，线程池被设置为TERMINATED状态
	private static final int TERMINATED = 1610612736;
	//用于保存等待执行的任务的阻塞队列
	// ArrayBlockingQueue;
	// LinkedBlockingQueue;
	// SynchronousQueue;
	private final BlockingQueue<Runnable> workQueue;
	//可重入锁，用于线程池对象并发被多线程调用
	//线程池的主要状态锁，对线程池状态（比如线程池大小、runState等）的改变都要使用这个锁
	private final ReentrantLock mainLock;
	//已在运行的线程，标记在HashSet中，用以监控线程
	private final HashSet<Worker> workers;
	//条件对象，控制等待任务执行结束调用
	private final Condition termination;
	//用来记录线程池中曾经出现过的最大线程数
	private int largestPoolSize;
	//用来记录已经执行完毕的任务个数
	private long completedTaskCount;
	//创建线程的工厂 volatile
	private volatile ThreadFactory threadFactory;
	//拒绝策略 volatile
	private volatile RejectedExecutionHandler handler;
	//非核心线程的阻塞在poll方法上最长等待时间超时 volatile
	private volatile long keepAliveTime;
	//1.6引入的额外可控制核心线程是否可以超时，且超时时间为keepAliveTime volatile
	private volatile boolean allowCoreThreadTimeOut;
	//线程池核心线程个数  volatile
	private volatile int corePoolSize;
	//线程池最大线程数量。  volatile
	private volatile int maximumPoolSize;
	//饱和策略，当队列满了并且线程个数达到 maximunPoolSize 后采取的策略
	//默认 AbortPolicy （抛出异常）
	private static final RejectedExecutionHandler	defaultHandler	= new CarterThreadPoolExecutor.AbortPolicy();
	private static final RuntimePermission			shutdownPerm	= new RuntimePermission("modifyThread");
	private static final boolean					ONLY_ONE		= true;

	private static int runStateOf(int c) {
		return c & -536870912;
	}

	private static int workerCountOf(int c) {
		return c & 536870911;
	}

	private static int ctlOf(int rs, int wc) {
		return rs | wc;
	}

	private static boolean runStateLessThan(int c, int s) {
		return c < s;
	}

	private static boolean runStateAtLeast(int c, int s) {
		return c >= s;
	}

	private static boolean isRunning(int c) {
		return c < 0;
	}

	private boolean compareAndIncrementWorkerCount(int expect) {
		return this.ctl.compareAndSet(expect, expect + 1);
	}

	private boolean compareAndDecrementWorkerCount(int expect) {
		return this.ctl.compareAndSet(expect, expect - 1);
	}

	private void decrementWorkerCount() {
		this.ctl.addAndGet(-1);
	}

	private void advanceRunState(int targetState) {
		int c;
		do {
			c = this.ctl.get();
		} while (!runStateAtLeast(c, targetState) && !this.ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))));

	}

	final void tryTerminate() {
		while (true) {
			int c = this.ctl.get();
			if (isRunning(c) || runStateAtLeast(c, 1073741824) || runStateLessThan(c, 536870912) && !this.workQueue.isEmpty()) { return; }

			if (workerCountOf(c) != 0) {
				this.interruptIdleWorkers(true);
				return;
			}

			ReentrantLock mainLock = this.mainLock;
			mainLock.lock();

			try {
				if (!this.ctl.compareAndSet(c, ctlOf(1073741824, 0))) {
					continue;
				}

				try {
					this.terminated();
				} finally {
					this.ctl.set(ctlOf(1610612736, 0));
					this.termination.signalAll();
				}
			} finally {
				mainLock.unlock();
			}

			return;
		}
	}

	private void checkShutdownAccess() {
		SecurityManager security = System.getSecurityManager();
		if (security != null) {
			security.checkPermission(shutdownPerm);
			Iterator var2 = this.workers.iterator();

			while (var2.hasNext()) {
				CarterThreadPoolExecutor.Worker w = (CarterThreadPoolExecutor.Worker) var2.next();
				security.checkAccess(w.thread);
			}
		}

	}

	private void interruptWorkers() {
		Iterator var1 = this.workers.iterator();

		while (var1.hasNext()) {
			CarterThreadPoolExecutor.Worker w = (CarterThreadPoolExecutor.Worker) var1.next();
			w.interruptIfStarted();
		}

	}

	private void interruptIdleWorkers(boolean onlyOne) {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			Iterator var3 = this.workers.iterator();

			while (var3.hasNext()) {
				CarterThreadPoolExecutor.Worker w = (CarterThreadPoolExecutor.Worker) var3.next();
				Thread t = w.thread;
				if (!t.isInterrupted() && w.tryLock()) {
					try {
						t.interrupt();
					} catch (SecurityException var15) {
					} finally {
						w.unlock();
					}
				}

				if (onlyOne) {
					break;
				}
			}
		} finally {
			mainLock.unlock();
		}

	}

	private void interruptIdleWorkers() {
		this.interruptIdleWorkers(false);
	}

	final void reject(Runnable command) {
		this.handler.rejectedExecution(command, this);
	}

	void onShutdown() {
	}

	private List<Runnable> drainQueue() {
		BlockingQueue<Runnable> q = this.workQueue;
		ArrayList<Runnable> taskList = new ArrayList();
		q.drainTo(taskList);
		if (!q.isEmpty()) {
			Runnable[] var3 = (Runnable[]) q.toArray(new Runnable[0]);
			int var4 = var3.length;

			for (int var5 = 0; var5 < var4; ++var5) {
				Runnable r = var3[var5];
				if (q.remove(r)) {
					taskList.add(r);
				}
			}
		}

		return taskList;
	}

	//创建新的线程并执行任务
	private boolean addWorker(Runnable firstTask, boolean core) {
		int threadPoolState = this.ctl.get();
		label247: while (!runStateAtLeast(threadPoolState, 0) || !runStateAtLeast(threadPoolState, 536870912) && firstTask == null && !this.workQueue.isEmpty()) {
			while (workerCountOf(threadPoolState) < ((core ? this.corePoolSize : this.maximumPoolSize) & 536870911)) {
				//cas增加线程个数，同时只有一个线程成功
				if (compareAndIncrementWorkerCount(threadPoolState)) {
					//到这里说明cas成功了
					boolean workerStarted = false;
					boolean workerAdded = false;
					Worker worker = null;
					try {
						//创建worker
						worker = new Worker(firstTask);
						Thread thread = worker.thread;
						if (thread != null) {
							//可重入的互斥锁
							ReentrantLock mainLock = this.mainLock;
							//加独占锁，为了workers同步，因为可能多个线程调用了线程池的execute方法
							mainLock.lock();
							try {
								//重新检查线程池状态，为了避免在获取锁前调用了shutdown接口
								threadPoolState = this.ctl.get();
								if (isRunning(threadPoolState) || runStateLessThan(threadPoolState, 536870912) && firstTask == null) {
									if (thread.getState() != Thread.State.NEW) { throw new IllegalThreadStateException(); }
									//添加任务到HashSet
									this.workers.add(worker);
									workerAdded = true;
									int s = this.workers.size();
									if (s > this.largestPoolSize) {
										this.largestPoolSize = s;
									}
								}
							} finally {
								mainLock.unlock();
							}
							//添加成功则启动任务
							if (workerAdded) {
								thread.start();
								workerStarted = true;
							}
						}
					} finally {
						if (!workerStarted) {
							this.addWorkerFailed(worker);
						}

					}
					return workerStarted;
				}
				threadPoolState = this.ctl.get();
				if (runStateAtLeast(threadPoolState, 0)) {
					continue label247;
				}
			}
			return false;
		}
		return false;
	}

	private void addWorkerFailed(CarterThreadPoolExecutor.Worker w) {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			if (w != null) {
				this.workers.remove(w);
			}

			this.decrementWorkerCount();
			this.tryTerminate();
		} finally {
			mainLock.unlock();
		}

	}

	private void processWorkerExit(CarterThreadPoolExecutor.Worker w, boolean completedAbruptly) {
		if (completedAbruptly) {
			this.decrementWorkerCount();
		}
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();
		try {
			this.completedTaskCount += w.completedTasks;
			this.workers.remove(w);
		} finally {
			mainLock.unlock();
		}
		this.tryTerminate();
		int c = this.ctl.get();
		if (runStateLessThan(c, 536870912)) {
			if (!completedAbruptly) {
				int min = this.allowCoreThreadTimeOut ? 0 : this.corePoolSize;
				if (min == 0 && !this.workQueue.isEmpty()) {
					min = 1;
				}

				if (workerCountOf(c) >= min) { return; }
			}
			this.addWorker((Runnable) null, false);
		}

	}

	private Runnable getTask() {
		boolean timedOut = false;

		while (true) {
			int c = this.ctl.get();
			if (runStateAtLeast(c, 0) && (runStateAtLeast(c, 536870912) || this.workQueue.isEmpty())) {
				this.decrementWorkerCount();
				return null;
			}

			int wc = workerCountOf(c);
			boolean timed = this.allowCoreThreadTimeOut || wc > this.corePoolSize;
			if (wc <= this.maximumPoolSize && (!timed || !timedOut) || wc <= 1 && !this.workQueue.isEmpty()) {
				try {
					Runnable r = timed ? (Runnable) this.workQueue.poll(this.keepAliveTime, TimeUnit.NANOSECONDS) : (Runnable) this.workQueue.take();
					if (r != null) { return r; }

					timedOut = true;
				} catch (InterruptedException var6) {
					timedOut = false;
				}
			} else if (this.compareAndDecrementWorkerCount(c)) { return null; }
		}
	}

	final void runWorker(Worker worker) {
		Thread workerThread = Thread.currentThread();
		Runnable task = worker.firstTask;
		worker.firstTask = null;
		//线程启动之后，通过unlock方法释放锁，设置AQS的state为0，表示运行可中断
		worker.unlock();
		boolean completedAbruptly = true;
		try {
			//先执行firstTask，再从workerQueue中取task
			//Worker执行firstTask或从workQueue中获取任务
			while (task != null || (task = this.getTask()) != null) {
				//进行加锁操作，保证thread不被其他线程中断（除非线程池被中断）
				worker.lock();
				//检查线程池状态,倘若线程池处于中断状态,当前线程将中断
				if ((runStateAtLeast(this.ctl.get(), 536870912) || Thread.interrupted() && runStateAtLeast(this.ctl.get(), 536870912)) && !workerThread.isInterrupted()) {
					workerThread.interrupt();
				}
				try {
					//执行beforeExecute
					beforeExecute(workerThread, task);
					try {
						//todo:执行任务的run方法
						task.run();
						afterExecute(task, (Throwable) null);
					} catch (Throwable var14) {
						// 执行afterExecute方法
						afterExecute(task, var14);
						throw var14;
					}
				} finally {
					task = null;
					//统计当前worker完成了多少个任务
					++worker.completedTasks;
					//解锁操作
					worker.unlock();
				}
			}
			completedAbruptly = false;
		} finally {
			//执行清工作
			processWorkerExit(worker, completedAbruptly);
		}

	}

	/**
	 * @param corePoolSize:核心线程数量
	 * @param maximumPoolSize：最大线程数量
	 * @param keepAliveTime：保持活跃时间（参照后续源码,这里应该是：当线程数大于核心时，此为终止前多余的空闲线程等待新任务的最长时间）
	 * @param unit：参数的时间单位
	 * @param workQueue：执行前用于保持任务的队列。此队列仅保持由 execute方法提交的 Runnable任务
	 */
	public CarterThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(), defaultHandler);
	}

	public CarterThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, defaultHandler);
	}

	public CarterThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
		this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(), handler);
	}

	/**
	 * @param corePoolSize:核心线程数量
	 * @param maximumPoolSize：最大线程数量
	 * @param keepAliveTime：保持活跃时间（参照后续源码,这里应该是：当线程数大于核心时，此为终止前多余的空闲线程等待新任务的最长时间）
	 * @param unit：参数的时间单位
	 * @param workQueue：执行前用于保持任务的队列。此队列仅保持由 execute方法提交的 Runnable任务
	 * @param threadFactory：默认线程工厂
	 * @param handler：超出线程和任务队列的任务的处理程序
	 */
	public CarterThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit, BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
		this.ctl = new AtomicInteger(ctlOf(-536870912, 0));
		this.mainLock = new ReentrantLock();
		this.workers = new HashSet();
		this.termination = this.mainLock.newCondition();
		if (corePoolSize >= 0 && maximumPoolSize > 0 && maximumPoolSize >= corePoolSize && keepAliveTime >= 0L) {
			if (workQueue != null && threadFactory != null && handler != null) {
				this.corePoolSize = corePoolSize;
				this.maximumPoolSize = maximumPoolSize;
				this.workQueue = workQueue;
				this.keepAliveTime = unit.toNanos(keepAliveTime);
				this.threadFactory = threadFactory;
				this.handler = handler;
			} else {
				throw new NullPointerException();
			}
		} else {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * execute()方法实际上是Executor中申明的方法，在CarterThreadPoolExecutor进行了具体实现
	 * 通过这个方法可以向线程池提交一个任务，交由线程池去执行
	 * @param task
	 */
	public void execute(Runnable task) {
		//1.判断提交的任务task是否为null，如果是null，就会抛出空指针异常
		if (task == null) {
			throw new NullPointerException();
		} else {
			//2.获取当前线程池的状态+线程个数变量的组合值
			int threadPoolState = this.ctl.get();
			//3.正在执行的线程数<核心线程数，则立即执行任务
			if (workerCountOf(threadPoolState) < this.corePoolSize) {
				if (this.addWorker(task, true)) { return; }
				threadPoolState = this.ctl.get();
			}
			//4. 线程池处于RUNNING状态&&添加任务到阻塞队列进行判断是否满了
			if (isRunning(threadPoolState) && this.workQueue.offer(task)) {
				//二次检查, 因为在多线程环境下，线程池的状态时刻在变化 , ctl.get()是非原子操作 ,很有可能刚获取了线程池状态后线程池状态就改变了
				int recheck = this.ctl.get();
				//如果线程池没有RUNNING并成功从阻塞队列中删除任务，执行reject方法处理任务
				if (!isRunning(recheck) && this.remove(task)) {
					reject(task);
				} else if (workerCountOf(recheck) == 0) {
					//线程池处于running状态，但是没有线程，则创建线程
					addWorker((Runnable) null, false);
				}
			} else if (addWorker(task, false)) {
				//往线程池中创建新的线程失败，则reject任务
				reject(task);
			}

		}
	}

	public void shutdown() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			this.checkShutdownAccess();
			this.advanceRunState(0);
			this.interruptIdleWorkers();
			this.onShutdown();
		} finally {
			mainLock.unlock();
		}

		this.tryTerminate();
	}

	public List<Runnable> shutdownNow() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		List tasks;
		try {
			this.checkShutdownAccess();
			this.advanceRunState(536870912);
			this.interruptWorkers();
			tasks = this.drainQueue();
		} finally {
			mainLock.unlock();
		}

		this.tryTerminate();
		return tasks;
	}

	public boolean isShutdown() {
		return runStateAtLeast(this.ctl.get(), 0);
	}

	boolean isStopped() {
		return runStateAtLeast(this.ctl.get(), 536870912);
	}

	public boolean isTerminating() {
		int c = this.ctl.get();
		return runStateAtLeast(c, 0) && runStateLessThan(c, 1610612736);
	}

	public boolean isTerminated() {
		return runStateAtLeast(this.ctl.get(), 1610612736);
	}

	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long nanos = unit.toNanos(timeout);
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			boolean var7;
			while (runStateLessThan(this.ctl.get(), 1610612736)) {
				if (nanos <= 0L) {
					var7 = false;
					return var7;
				}

				nanos = this.termination.awaitNanos(nanos);
			}

			var7 = true;
			return var7;
		} finally {
			mainLock.unlock();
		}
	}

	/** @deprecated */
	protected void finalize() {
	}

	public void setThreadFactory(ThreadFactory threadFactory) {
		if (threadFactory == null) {
			throw new NullPointerException();
		} else {
			this.threadFactory = threadFactory;
		}
	}

	public ThreadFactory getThreadFactory() {
		return this.threadFactory;
	}

	public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
		if (handler == null) {
			throw new NullPointerException();
		} else {
			this.handler = handler;
		}
	}

	public RejectedExecutionHandler getRejectedExecutionHandler() {
		return this.handler;
	}

	public void setCorePoolSize(int corePoolSize) {
		if (corePoolSize >= 0 && this.maximumPoolSize >= corePoolSize) {
			int delta = corePoolSize - this.corePoolSize;
			this.corePoolSize = corePoolSize;
			if (workerCountOf(this.ctl.get()) > corePoolSize) {
				this.interruptIdleWorkers();
			} else if (delta > 0) {
				int var3 = Math.min(delta, this.workQueue.size());

				while (var3-- > 0 && this.addWorker((Runnable) null, true) && !this.workQueue.isEmpty()) {
				}
			}

		} else {
			throw new IllegalArgumentException();
		}
	}

	public int getCorePoolSize() {
		return this.corePoolSize;
	}

	public boolean prestartCoreThread() {
		return workerCountOf(this.ctl.get()) < this.corePoolSize && this.addWorker((Runnable) null, true);
	}

	void ensurePrestart() {
		int wc = workerCountOf(this.ctl.get());
		if (wc < this.corePoolSize) {
			this.addWorker((Runnable) null, true);
		} else if (wc == 0) {
			this.addWorker((Runnable) null, false);
		}

	}

	public int prestartAllCoreThreads() {
		int n;
		for (n = 0; this.addWorker((Runnable) null, true); ++n) {
		}

		return n;
	}

	public boolean allowsCoreThreadTimeOut() {
		return this.allowCoreThreadTimeOut;
	}

	public void allowCoreThreadTimeOut(boolean value) {
		if (value && this.keepAliveTime <= 0L) {
			throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
		} else {
			if (value != this.allowCoreThreadTimeOut) {
				this.allowCoreThreadTimeOut = value;
				if (value) {
					this.interruptIdleWorkers();
				}
			}

		}
	}

	public void setMaximumPoolSize(int maximumPoolSize) {
		if (maximumPoolSize > 0 && maximumPoolSize >= this.corePoolSize) {
			this.maximumPoolSize = maximumPoolSize;
			if (workerCountOf(this.ctl.get()) > maximumPoolSize) {
				this.interruptIdleWorkers();
			}

		} else {
			throw new IllegalArgumentException();
		}
	}

	public int getMaximumPoolSize() {
		return this.maximumPoolSize;
	}

	public void setKeepAliveTime(long time, TimeUnit unit) {
		if (time < 0L) {
			throw new IllegalArgumentException();
		} else if (time == 0L && this.allowsCoreThreadTimeOut()) {
			throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
		} else {
			long keepAliveTime = unit.toNanos(time);
			long delta = keepAliveTime - this.keepAliveTime;
			this.keepAliveTime = keepAliveTime;
			if (delta < 0L) {
				this.interruptIdleWorkers();
			}

		}
	}

	public long getKeepAliveTime(TimeUnit unit) {
		return unit.convert(this.keepAliveTime, TimeUnit.NANOSECONDS);
	}

	public BlockingQueue<Runnable> getQueue() {
		return this.workQueue;
	}

	public boolean remove(Runnable task) {
		boolean removed = this.workQueue.remove(task);
		this.tryTerminate();
		return removed;
	}

	public void purge() {
		BlockingQueue q = this.workQueue;

		try {
			Iterator it = q.iterator();

			while (it.hasNext()) {
				Runnable r = (Runnable) it.next();
				if (r instanceof Future && ((Future) r).isCancelled()) {
					it.remove();
				}
			}
		} catch (ConcurrentModificationException var7) {
			Object[] var3 = q.toArray();
			int var4 = var3.length;

			for (int var5 = 0; var5 < var4; ++var5) {
				Object r = var3[var5];
				if (r instanceof Future && ((Future) r).isCancelled()) {
					q.remove(r);
				}
			}
		}

		this.tryTerminate();
	}

	public int getPoolSize() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		int var2;
		try {
			var2 = runStateAtLeast(this.ctl.get(), 1073741824) ? 0 : this.workers.size();
		} finally {
			mainLock.unlock();
		}

		return var2;
	}

	public int getActiveCount() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			int n = 0;
			Iterator var3 = this.workers.iterator();

			while (var3.hasNext()) {
				CarterThreadPoolExecutor.Worker w = (CarterThreadPoolExecutor.Worker) var3.next();
				if (w.isLocked()) {
					++n;
				}
			}

			int var8 = n;
			return var8;
		} finally {
			mainLock.unlock();
		}
	}

	public int getLargestPoolSize() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		int var2;
		try {
			var2 = this.largestPoolSize;
		} finally {
			mainLock.unlock();
		}

		return var2;
	}

	public long getTaskCount() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			long n = this.completedTaskCount;
			Iterator var4 = this.workers.iterator();

			while (var4.hasNext()) {
				CarterThreadPoolExecutor.Worker w = (CarterThreadPoolExecutor.Worker) var4.next();
				n += w.completedTasks;
				if (w.isLocked()) {
					++n;
				}
			}

			long var9 = n + (long) this.workQueue.size();
			return var9;
		} finally {
			mainLock.unlock();
		}
	}

	public long getCompletedTaskCount() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		try {
			long n = this.completedTaskCount;

			Worker w;
			for (Iterator var4 = this.workers.iterator(); var4.hasNext(); n += w.completedTasks) {
				w = (CarterThreadPoolExecutor.Worker) var4.next();
			}

			long var9 = n;
			return var9;
		} finally {
			mainLock.unlock();
		}
	}

	public String toString() {
		ReentrantLock mainLock = this.mainLock;
		mainLock.lock();

		long ncompleted;
		int nworkers;
		int nactive;
		try {
			ncompleted = this.completedTaskCount;
			nactive = 0;
			nworkers = this.workers.size();
			Iterator var6 = this.workers.iterator();

			while (var6.hasNext()) {
				Worker w = (Worker) var6.next();
				ncompleted += w.completedTasks;
				if (w.isLocked()) {
					++nactive;
				}
			}
		} finally {
			mainLock.unlock();
		}

		int c = this.ctl.get();
		String runState = isRunning(c) ? "Running" : (runStateAtLeast(c, 1610612736) ? "Terminated" : "Shutting down");
		return super.toString() + "[" + runState + ", pool size = " + nworkers + ", active threads = " + nactive + ", queued tasks = " + this.workQueue.size() + ", completed tasks = " + ncompleted + "]";
	}

	protected void beforeExecute(Thread t, Runnable r) {
	}

	protected void afterExecute(Runnable r, Throwable t) {
	}

	protected void terminated() {
	}
	public static class DiscardOldestPolicy implements RejectedExecutionHandler {
		public DiscardOldestPolicy() {
		}

		public void rejectedExecution(Runnable r, CarterThreadPoolExecutor e) {
			if (!e.isShutdown()) {
				e.getQueue().poll();
				e.execute(r);
			}

		}
	}

	public static class DiscardPolicy implements RejectedExecutionHandler {
		public DiscardPolicy() {
		}

		public void rejectedExecution(Runnable r, CarterThreadPoolExecutor e) {
		}
	}

	public static class AbortPolicy implements RejectedExecutionHandler {
		public AbortPolicy() {
		}

		public void rejectedExecution(Runnable r, CarterThreadPoolExecutor e) {
			throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + e.toString());
		}
	}

	public static class CallerRunsPolicy implements RejectedExecutionHandler {
		public CallerRunsPolicy() {
		}

		public void rejectedExecution(Runnable r, CarterThreadPoolExecutor e) {
			if (!e.isShutdown()) {
				r.run();
			}

		}
	}

	//内部类Worker 继承了AQS类，可以方便的实现工作线程的中止操作
	//实现了Runnable接口，可以将自身作为一个任务在工作线程中执行
	private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
		private static final long	serialVersionUID	= 6138294804551838833L;
		//当前线程
		final Thread				thread;
		//第一个任务
		Runnable					firstTask;
		//总的任务
		volatile long				completedTasks;
		//当前提交的任务firstTask作为参数传入Worker的构造方法
		Worker(Runnable firstTask) {
			//设置 Worker 的状态为 -1，是为了避免当前 worker在调用 runWorker方法前被中断
			//当其它线程调用了线程池的 shutdownNow 时候，如果 worker 状态 >= 0 则会中断该线程
			this.setState(-1);
			this.firstTask = firstTask;
			//创建一个线程
			this.thread = getThreadFactory().newThread(this);
		}

		public void run() {
			runWorker(this);
		}

		protected boolean isHeldExclusively() {
			return this.getState() != 0;
		}

		protected boolean tryAcquire(int unused) {
			if (this.compareAndSetState(0, 1)) {
				this.setExclusiveOwnerThread(Thread.currentThread());
				return true;
			} else {
				return false;
			}
		}

		protected boolean tryRelease(int unused) {
			this.setExclusiveOwnerThread((Thread) null);
			this.setState(0);
			return true;
		}

		public void lock() {
			this.acquire(1);
		}

		public boolean tryLock() {
			return this.tryAcquire(1);
		}

		public void unlock() {
			this.release(1);
		}

		public boolean isLocked() {
			return this.isHeldExclusively();
		}

		void interruptIfStarted() {
			Thread t;
			if (this.getState() >= 0 && (t = this.thread) != null && !t.isInterrupted()) {
				try {
					t.interrupt();
				} catch (SecurityException var3) {
				}
			}

		}
	}

}
