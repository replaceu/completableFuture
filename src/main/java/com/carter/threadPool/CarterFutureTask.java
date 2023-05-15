
package com.carter.threadPool;

import java.lang.invoke.MethodHandles;

import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

public class CarterFutureTask<V> implements RunnableFuture<V> {
	//任务的运行状态
	private volatile int state;
	//初始状态
	private static final int NEW = 0;
	//结果计算完成或响应中断到赋值给返回值之间的状态
	private static final int COMPLETING = 1;
	//任务正常完成，结果被set
	private static final int NORMAL = 2;
	//任务抛出异常
	private static final int EXCEPTIONAL = 3;
	//任务已被取消
	private static final int CANCELLED = 4;
	//线程中断状态被设置ture，但线程未响应中断
	private static final int INTERRUPTING = 5;
	//线程已被中断
	private static final int INTERRUPTED = 6;
	//将要执行的任务
	private Callable<V> callable;
	//用于get()返回的结果，也可能用于get()方法抛出的异常
	private Object outcome;
	//执行callable的线程，调用FutureTask.run()方法通过CAS设置
	private volatile Thread runner;
	//栈结构的等待队列，该节点是栈中的最顶层节点
	private volatile WaitNode waiters;

	private V report(int s) throws ExecutionException {
		Object x = this.outcome;
		if (s == 2) {
			//如果任务正常执行完成直接返回结果
			return (V) x;
		} else if (s >= 4) {
			throw new CancellationException();
		} else {
			//如果任务有未捕获的异常则将异常包装到ExecutionException并抛出
			throw new ExecutionException((Throwable) x);
		}
	}

	//传入一个Callable任务
	public CarterFutureTask(Callable<V> callable) {
		if (callable == null) {
			throw new NullPointerException();
		} else {
			this.callable = callable;
			this.state = 0;
		}
	}

	//传入一个Runnable和返回结果(任务完成后调用get方法的返回值)
	public CarterFutureTask(Runnable runnable, V result) {
		this.callable = Executors.callable(runnable, result);
		this.state = 0;
	}

	public boolean isCancelled() {
		return this.state >= 4;
	}

	public boolean isDone() {
		return this.state != 0;
	}

	//当调用cancel(true)方法时，状态才有可能被置于INTERRUPTING。
	public boolean cancel(boolean mayInterruptIfRunning) {
		//如果任务状态为NEW并且成功通过CAS将state状态由NEW改为INTERRUPTING或CANCELLED（视参数而定）
		//那么方法继续执行，否则返回false
		if (!(state == NEW && UNSAFE.compareAndSwapInt(this, stateOffset, NEW, mayInterruptIfRunning ? INTERRUPTING : CANCELLED))) { return false; }
		try {
			if (mayInterruptIfRunning) {
				try {
					//获取执行run方法的线程(执行任务的线程)
					Thread t = this.runner;
					//调用interrupt中断
					if (t != null) {
						t.interrupt();
					}
				} finally {
					//将state状态设为INTERRUPTED(已中断)
					UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
				}
			}
		} finally {
			//激活所有在等待队列中的线程
			this.finishCompletion();
		}
		return true;
	}

	public V get() throws InterruptedException, ExecutionException {
		int s = state;
		//如果任务尚未执行完成，调用awaitDone使当前线程等待
		if (s <= 1) {
			s = this.awaitDone(false, 0L);
		}
		//任务执行完成后，调用report返回执行结果或抛出异常
		return this.report(s);
	}

	public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (unit == null) {
			throw new NullPointerException();
		} else {
			int s = state;
			//如果任务没有完成那么调用awaitDone使当前线程等待，如果超时任务依然没有完成抛出TimeoutException
			if (s <= 1 && (s = this.awaitDone(true, unit.toNanos(timeout))) <= 1) {
				throw new TimeoutException();
			} else {
				//任务执行完成后，调用report返回执行结果或抛出异常
				return report(s);
			}
		}
	}

	protected void done() {
	}

	//如果任务正常结束，会调用set方法：
	protected void set(V v) {
		//通过CAS将NEW设为COMPLETING(即将完成)状态
		if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
			//将任务的返回值设置为outcome
			this.outcome = v;
			UNSAFE.putOrderedInt(this, stateOffset, NORMAL);
			//激活所有等待队列中的线程
			this.finishCompletion();
		}

	}

	//任务执行期间有未捕获的异常，那么会调用setException()
	protected void setException(Throwable t) {
		//通过CAS将state由NEW设为COMPLETING
		if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
			//将异常对象赋给outcome实例变量
			this.outcome = t;
			//将state设为EXCEPTIONAL（有异常抛出状态）
			UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL);
			//激活所有等待队列中的线程
			this.finishCompletion();
		}

	}

	public void run() {
		if (state != NEW || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread())) { return; }
		try {
			//获取构造时传入的Callable任务对象
			Callable call = this.callable;
			if (call != null && state == NEW) {
				Object result;
				//任务是否正常完成
				boolean finish;
				try {
					//调用Callable的call方法执行任务
					result = call.call();
					//如果没有未捕获的异常
					finish = true;
				} catch (Throwable ex) {
					//没有返回值
					result = null;
					finish = false;
					//设置异常对象，由调用get方法的线程处理这个异常
					this.setException(ex);
				}
				if (finish) {
					//如果正常，就设置返回值
					this.set((V) result);
				}
			}
		} finally {
			this.runner = null;
			//获取state状态
			int state = this.state;
			//如果处于任务正在中断状态，则等待直到任务处于已中断状态位置
			if (state >= INTERRUPTING) {
				this.handlePossibleCancellationInterrupt(state);
			}
		}
	}

	protected boolean runAndReset() {
		if (state != NEW || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread())) { return false; }

		boolean ran = false;
		int s = state;
		try {
			Callable<V> c = this.callable;
			if (c != null && s == 0) {
				try {
					c.call();
					ran = true;
				} catch (Throwable var8) {
					this.setException(var8);
				}
			}
		} finally {
			this.runner = null;
			s = this.state;
			if (s >= 5) {
				this.handlePossibleCancellationInterrupt(s);
			}

		}
		return ran && s == 0;
	}

	private void handlePossibleCancellationInterrupt(int s) {
		if (s == 5) {
			//等待到INTERRUPTED时跳出循环
			while (this.state == 5) {
				Thread.yield();
			}
		}

	}

	//激活所有在等待队列中的线程
	private void finishCompletion() {
		//不断获取队首
		for (WaitNode q; (q = waiters) != null;) {
			//通过CAS删除队列头部
			if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
				//如果删除成功，那么开始遍历这个队列
				for (;;) {
					//获取队列结点上的等待线程
					Thread t = q.thread;
					if (t != null) {
						q.thread = null;
						//激活该线程
						LockSupport.unpark(t);
					}
					//如果已经达到队列尾部跳出循环
					WaitNode next = q.next;
					if (next == null) {
						break;
					}
					q.next = null;
					q = next;
				}
				break;
			}
		}
		done();
		callable = null;
	}

	//调用了awaitDone方法将线程加入等待队列
	private int awaitDone(boolean timed, long nanos) throws InterruptedException {
		long startTime = 0L;
		WaitNode q = null;
		//是否成功入队
		boolean queued = false;

		while (true) {
			int s = this.state;
			//若线程已执行完毕，则直接返回执行结果
			if (s > 1) {
				if (q != null) {
					q.thread = null;
				}
				return s;
			}
			//若正在执行中，则让出CPU，等待执行完毕
			if (s == 1) {
				Thread.yield();
			} else {
				//如果线程中断
				if (Thread.interrupted()) {
					//移除这个结点并抛出异常
					this.removeWaiter(q);
					throw new InterruptedException();
				}
				//状态为NEW，创建节点保存当前线程,并在下一次循环时，将节点添加到waiters队列中
				if (q == null) {
					if (timed && nanos <= 0L) { return s; }

					q = new WaitNode();
				} else if (!queued) {
					queued = UNSAFE.compareAndSwapObject(this, waitersOffset, q.next = waiters, q);
				}
				//若设置有超时，则进行超时判断
				else if (timed) {
					long parkNanos;
					if (startTime == 0L) {
						startTime = System.nanoTime();
						if (startTime == 0L) {
							startTime = 1L;
						}
						parkNanos = nanos;
					} else {
						long elapsed = System.nanoTime() - startTime;
						//若已超时，移除等待节点
						if (elapsed >= nanos) {
							this.removeWaiter(q);
							//返回执行状态
							return this.state;
						}
						parkNanos = nanos - elapsed;
					}

					if (this.state < 1) {
						LockSupport.parkNanos(this, parkNanos);
					}
				} else {
					//阻塞当前线程，run()方法结束后，会在finishCompletion()方法中唤醒所有阻塞的线程
					LockSupport.park(this);
				}
			}
		}
	}

	private void removeWaiter(CarterFutureTask.WaitNode node) {
		if (node != null) {
			node.thread = null;
			retry: for (;;) { // restart on removeWaiter race
				for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
					s = q.next;
					if (q.thread != null) pred = q;
					else if (pred != null) {
						pred.next = s;
						if (pred.thread == null) // check for race
							continue retry;
					} else if (!UNSAFE.compareAndSwapObject(this, waitersOffset, q, s)) continue retry;
				}
				break;
			}
		}
	}

	public String toString() {
		String status;
		switch (this.state) {
		case 2:
			status = "[Completed normally]";
			break;
		case 3:
			status = "[Completed exceptionally: " + this.outcome + "]";
			break;
		case 4:
		case 5:
		case 6:
			status = "[Cancelled]";
			break;
		default:
			Callable<?> callable = this.callable;
			status = callable == null ? "[Not completed]" : "[Not completed, task = " + callable + "]";
		}

		return super.toString() + status;
	}

	// Unsafe mechanics
	private static final sun.misc.Unsafe	UNSAFE;
	private static final long				stateOffset;
	private static final long				runnerOffset;
	private static final long				waitersOffset;
	static {
		try {
			UNSAFE = sun.misc.Unsafe.getUnsafe();
			Class<?> k = FutureTask.class;
			stateOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("state"));
			runnerOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("runner"));
			waitersOffset = UNSAFE.objectFieldOffset(k.getDeclaredField("waiters"));
		} catch (Exception e) {
			throw new Error(e);
		}
	}

	static final class WaitNode {
		volatile Thread						thread	= Thread.currentThread();
		volatile CarterFutureTask.WaitNode	next;

		WaitNode() {
		}
	}
}
