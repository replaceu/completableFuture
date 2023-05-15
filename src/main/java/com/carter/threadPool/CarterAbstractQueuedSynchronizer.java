package com.carter.threadPool;

import sun.misc.Unsafe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

//AQS 是一个用于构建锁、同步器等线程协作工具类的框架

/**
 * AQS(AbstractQueuedSynchronizer)，AQS是JDK下提供的一套用于实现基于FIFO等待队列的阻塞锁和相关的同步器的一个同步框架。
 * 这个抽象类被设计为作为一些可用原子int值来表示状态的同步器的基类。
 *
 * ReentrantLock，ReentrantReadWriteLock，CountDownLatch，CyclicBarrier，Semaphore等都是基于AQS来实现的。
 *
 * AQS通过内置FIFO同步队列来完成资源获取线程的排队工作，如果当前线程获取同步状态失败（即获取锁失败）AQS则会将当前线程以及等待状态等信息构成一个节点（Node）
 * 并将其加如同步队列，同时会阻塞当前线程，当同步状态释放时，则会把节点中的线程唤醒，使其再次尝试获取同步状态
 */
public class CarterAbstractQueuedSynchronizer {
	private static final long serialVersionUID = 7373984972572414691L;

	/**
	 * Creates a new {@code CarterAbstractQueuedSynchronizer} instance
	 * with initial synchronization state of zero.
	 */
	protected CarterAbstractQueuedSynchronizer() {
	}

	static final class Node {

		//AQS类里有一个Node类，对线程进行了封装
		static final Node SHARED = new Node();
		//独占模式下等待的标记
		static final Node EXCLUSIVE = null;
		//线程的等待状态,表示节点已被取消,由于超时或中断此节点被取消,取消节点的线程不会阻塞
		static final int CANCELLED = 1;
		//线程的等待状态,表示节点需要被唤醒 挂起状态 等待被唤醒 一般是获取失败后线程被挂起的节点
		static final int SIGNAL = -1;
		//线程的等待状态 表示线程正在等待条件 此节点当前在条件队列中 不会用于同步队列 转到同步队列时状态值会改为0
		static final int CONDITION = -2;
		//表示下一个acquireShared需要无条件的传播 仅适用于头部节点
		static final int PROPAGATE = -3;
		//等待的状态
		volatile int waitStatus;
		//前一个节点
		volatile Node prev;
		//下一个节点
		volatile Node next;
		//当前节点的线程,初始化后使用,在使用后失效
		volatile Thread	thread;
		/**
		 * 链接到等在等待条件上的下一个节点,或特殊的值SHARED,因为条件队列只有在独占模式时才能被访问,
		 * 所以我们只需要一个简单的连接队列在等待的时候保存节点,然后把它们转移到队列中重新获取
		 * 因为条件只能是独占性的,我们通过使用特殊的值来表示共享模式
		 */
		Node			nextWaiter;

		//如果节点处于共享模式下等待直接返回true
		final boolean isShared() {
			return nextWaiter == SHARED;
		}

		//返回当前节点的前驱节点,如果为空,直接抛出空指针异常
		final Node predecessor() throws NullPointerException {
			Node p = prev;
			if (p == null) throw new NullPointerException();
			else return p;
		}

		//用来建立初始化的head 或 SHARED的标记
		Node() {
		}

		//指定线程和模式的构造方法
		Node(Thread thread, Node mode) {
			this.nextWaiter = mode;
			this.thread = thread;
		}

		// 指定线程和节点状态的构造方法
		Node(Thread thread, int waitStatus) {
			this.waitStatus = waitStatus;
			this.thread = thread;
		}
	}

	private transient volatile Node head;

	private transient volatile Node tail;

	//锁状态
	private volatile int state;

	//获取锁的状态
	protected final int getState() {
		return state;
	}

	//设置状态
	protected final void setState(int newState) {
		state = newState;
	}

	//CAS改变状态
	protected final boolean compareAndSetState(int expect, int update) {
		// See below for intrinsics setup to support this
		return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
	}

	// Queuing utilities
	static final long spinForTimeoutThreshold = 1000L;

	//自旋操作添加元素到队列尾部
	private Node enq(final Node node) {
		//无限循环
		for (;;) {
			//获取尾部节点
			Node t = tail;
			//如果尾节点为空，说明当前队列是空，需要初始化队列 随便加一个
			if (t == null) {
				//初始化当前队列
				if (compareAndSetHead(new Node())) {
					tail = head;
				}
			} else {
				//否则通过CAS操作插入Node，设置Node为队列的尾节点，并返回Node
				node.prev = t;
				if (compareAndSetTail(t, node)) {
					t.next = node;
					return t;
				}
			}
		}
	}

	/**
	 * addWaiter方法将创建一个独占线程节点，加入阻塞队列
	 */
	private Node addWaiter(Node mode) {
		//将当前线程和模式包装成一个Node
		Node node = new Node(Thread.currentThread(), mode);
		//获取当前队列尾部
		Node pred = tail;
		//如果尾节点不为空
		if (pred != null) {
			node.prev = pred;
			//CAS操作进行一次快速insert操作，如果成功就返回
			if (compareAndSetTail(pred, node)) {
				pred.next = node;
				return node;
			}
		}
		//如果添加失败，enq这里会做自旋操作，直到插入成功
		enq(node);
		return node;
	}

	/**
	 * Sets head of queue to be node, thus dequeuing. Called only by
	 * acquire methods.  Also nulls out unused fields for sake of GC
	 * and to suppress unnecessary signals and traversals.
	 *
	 * @param node the node
	 */
	private void setHead(Node node) {
		head = node;
		node.thread = null;
		node.prev = null;
	}

	//unparkSuccessor来释放并唤醒下一个节点
	private void unparkSuccessor(Node node) {
		//node一般为当前线程所在的节点
		int ws = node.waitStatus;
		if (ws < 0) {
			//ws的状态<0  CAS修改头节点状态修改为0
			compareAndSetWaitStatus(node, ws, 0);
		}
		//状态值 >0 只有取消状态Cancel
		//从队列里找出下一个需要唤醒的节点s
		Node s = node.next;
		//如果直接后继为空或者它的waitStatus大于0(已经放弃获取锁了)，我们就遍历整个队列，
		//获取第一个需要唤醒的节点
		if (s == null || s.waitStatus > 0) {
			s = null;
			//从tail开始倒着找，直到node停止
			for (Node t = tail; t != null && t != node; t = t.prev)
				//<=0的节点 都是有效节点
				if (t.waitStatus <= 0) s = t;
		}
		if (s != null) {
			//唤醒找到的符合条件的节点
			LockSupport.unpark(s.thread);
		}

	}

	//具体的节点状态检测和唤醒
	private void doReleaseShared() {
		for (;;) {
			Node h = head;
			if (h != null && h != tail) {
				int ws = h.waitStatus;
				//如果节点的状态是SINGAL，且CAS成功就唤醒该节点
				if (ws == Node.SIGNAL) {
					if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0)) {
						continue; // loop to recheck cases
					}
					unparkSuccessor(h);
				} else if (ws == 0 && !compareAndSetWaitStatus(h, 0, Node.PROPAGATE)) {
					//如果节点的状态是0，尝试CAS设置为PROPAGATE，以便下次共享锁传播
					continue;
				}
			}
			//如果其他线程修改了头结点，那么再执行一次唤醒的逻辑
			if (h == head) {
				break;
			}
		}
	}

	//对共享锁进行扩散
	private void setHeadAndPropagate(Node node, int propagate) {
		Node h = head; // Record old head for check below
		setHead(node);
		//如果还有剩余量，继续唤醒下一个邻居线程
		if (propagate > 0 || h == null || h.waitStatus < 0 || (h = head) == null || h.waitStatus < 0) {
			Node s = node.next;
			// 如果后继节点是共享的，或者是出现了空，都会执行唤醒的操作
			if (s == null || s.isShared()) {
				//唤醒节点
				doReleaseShared();
			}

		}
	}

	// Utilities for various versions of acquire

	/**
	 * Cancels an ongoing attempt to acquire.
	 *
	 * @param node the node
	 */
	private void cancelAcquire(Node node) {
		// Ignore if node doesn't exist
		if (node == null) return;

		node.thread = null;

		// Skip cancelled predecessors
		Node pred = node.prev;
		while (pred.waitStatus > 0)
			node.prev = pred = pred.prev;

		// predNext is the apparent node to unsplice. CASes below will
		// fail if not, in which case, we lost race vs another cancel
		// or signal, so no further action is necessary.
		Node predNext = pred.next;

		// Can use unconditional write instead of CAS here.
		// After this atomic step, other Nodes can skip past us.
		// Before, we are free of interference from other threads.
		node.waitStatus = Node.CANCELLED;

		// If we are the tail, remove ourselves.
		if (node == tail && compareAndSetTail(node, pred)) {
			compareAndSetNext(pred, predNext, null);
		} else {
			// If successor needs signal, try to set pred's next-link
			// so it will get one. Otherwise wake it up to propagate.
			int ws;
			if (pred != head && ((ws = pred.waitStatus) == Node.SIGNAL || (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) && pred.thread != null) {
				Node next = node.next;
				if (next != null && next.waitStatus <= 0) compareAndSetNext(pred, predNext, next);
			} else {
				unparkSuccessor(node);
			}

			node.next = node; // help GC
		}
	}

	//判断失败获取锁后是否可以挂起当前线程
	private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
		//ws为node前置节点的状态
		int ws = pred.waitStatus;
		if (ws == Node.SIGNAL) {
			//如果前置节点状态为SIGNAL，当前节点可以挂起
			return true;
		}

		if (ws > 0) {
			//ws>0  只有CANCELLED =1 前驱状态是取消
			//通过循环跳过所有的CANCELLED节点，找到一个正常的节点，将当前节点排在它后面
			//GC会将这些CANCELLED节点回收
			do {
				node.prev = pred = pred.prev;
			} while (pred.waitStatus > 0);
			pred.next = node;
		} else {
			//若ws<=0 前驱状态正常 将前置节点的状态修改为SIGNAL
			compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
		}
		return false;
	}

	/**
	 * Convenience method to interrupt current thread.
	 */
	static void selfInterrupt() {
		Thread.currentThread().interrupt();
	}

	//通过LockSupport挂起线程，等待唤醒 停顿并检查中断
	private final boolean parkAndCheckInterrupt() {
		LockSupport.park(this);
		return Thread.interrupted();
	}

	/*
	 * Various flavors of acquire, varying in exclusive/shared and
	 * control modes.  Each is mostly the same, but annoyingly
	 * different.  Only a little bit of factoring is possible due to
	 * interactions of exception mechanics (including ensuring that we
	 * cancel if tryAcquire throws exception) and other control, at
	 * least not without hurting performance too much.
	 */

	//如果插入的节点前面是head，尝试获取锁
	final boolean acquireQueued(final Node node, int arg) {
		//是否失败
		boolean failed = true;
		try {
			//是否中断
			boolean interrupted = false;
			//自旋操作，会一直尝试
			for (;;) {
				//获取当前插入节点的前置节点
				final Node p = node.predecessor();
				//前置节点是head，尝试获取锁
				if (p == head && tryAcquire(arg)) {
					//设置head为当前节点，表示获取锁成功
					setHead(node);
					p.next = null; // help GC
					failed = false;
					return interrupted;
				}
				//是否挂起当前线程，如果是，则挂起线程
				//park（阻塞）是通过unsafe包来实现的，native方法
				if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
					//在park中出现中断，会执行到此，但是不会响应中断
					interrupted = true;
				}
			}
		} finally {
			//线程中断，需要取消获取锁
			if (failed) {
				cancelAcquire(node);
			}
		}
	}

	/**
	 * Acquires in exclusive interruptible mode.
	 *
	 * @param arg the acquire argument
	 */
	private void doAcquireInterruptibly(int arg) throws InterruptedException {
		final Node node = addWaiter(Node.EXCLUSIVE);
		boolean failed = true;
		try {
			for (;;) {
				final Node p = node.predecessor();
				if (p == head && tryAcquire(arg)) {
					setHead(node);
					p.next = null; // help GC
					failed = false;
					return;
				}
				if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) { throw new InterruptedException(); }
			}
		} finally {
			if (failed) {
				cancelAcquire(node);
			}

		}
	}

	/**
	 * Acquires in exclusive timed mode.
	 *
	 * @param arg          the acquire argument
	 * @param nanosTimeout max wait time
	 * @return {@code true} if acquired
	 */
	private boolean doAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
		if (nanosTimeout <= 0L) return false;
		final long deadline = System.nanoTime() + nanosTimeout;
		final Node node = addWaiter(Node.EXCLUSIVE);
		boolean failed = true;
		try {
			for (;;) {
				final Node p = node.predecessor();
				if (p == head && tryAcquire(arg)) {
					setHead(node);
					p.next = null; // help GC
					failed = false;
					return true;
				}
				nanosTimeout = deadline - System.nanoTime();
				if (nanosTimeout <= 0L) return false;
				if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) {
					LockSupport.parkNanos(this, nanosTimeout);
				}
				if (Thread.interrupted()) { throw new InterruptedException(); }

			}
		} finally {
			if (failed) cancelAcquire(node);
		}
	}

	private void doAcquireShared(int arg) {
		//加入队列尾部
		final Node node = addWaiter(Node.SHARED);
		//是否成功标志
		boolean failed = true;
		try {
			//等待过程中是否被中断过的标志
			boolean interrupted = false;
			for (;;) {
				//前驱节点
				final Node p = node.predecessor();
				//如果到head的下一个，因为head是拿到资源的线程，此时node被唤醒，很可能是head用完资源来唤醒自己的
				if (p == head) {
					//尝试获取资源
					int r = tryAcquireShared(arg);
					//成功
					if (r >= 0) {
						//将head指向自己，还有剩余资源可以再唤醒之后的线程
						setHeadAndPropagate(node, r);
						p.next = null; // help GC
						if (interrupted) {
							//如果等待过程中被打断过，此时将中断补上
							selfInterrupt();
						}
						failed = false;
						return;
					}
				}
				//判断状态，寻找安全点，进入waiting状态，等着被unpark()或interrupt()
				if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) {
					interrupted = true;
				}

			}
		} finally {
			if (failed) cancelAcquire(node);
		}
	}

	/**
	 * Acquires in shared interruptible mode.
	 *
	 * @param arg the acquire argument
	 */
	private void doAcquireSharedInterruptibly(int arg) throws InterruptedException {
		final Node node = addWaiter(Node.SHARED);
		boolean failed = true;
		try {
			for (;;) {
				final Node p = node.predecessor();
				if (p == head) {
					int r = tryAcquireShared(arg);
					if (r >= 0) {
						setHeadAndPropagate(node, r);
						p.next = null; // help GC
						failed = false;
						return;
					}
				}
				if (shouldParkAfterFailedAcquire(p, node) && parkAndCheckInterrupt()) throw new InterruptedException();
			}
		} finally {
			if (failed) cancelAcquire(node);
		}
	}

	/**
	 * Acquires in shared timed mode.
	 *
	 * @param arg          the acquire argument
	 * @param nanosTimeout max wait time
	 * @return {@code true} if acquired
	 */
	private boolean doAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
		if (nanosTimeout <= 0L) return false;
		final long deadline = System.nanoTime() + nanosTimeout;
		final Node node = addWaiter(Node.SHARED);
		boolean failed = true;
		try {
			for (;;) {
				final Node p = node.predecessor();
				if (p == head) {
					int r = tryAcquireShared(arg);
					if (r >= 0) {
						setHeadAndPropagate(node, r);
						p.next = null; // help GC
						failed = false;
						return true;
					}
				}
				nanosTimeout = deadline - System.nanoTime();
				if (nanosTimeout <= 0L) return false;
				if (shouldParkAfterFailedAcquire(p, node) && nanosTimeout > spinForTimeoutThreshold) LockSupport.parkNanos(this, nanosTimeout);
				if (Thread.interrupted()) throw new InterruptedException();
			}
		} finally {
			if (failed) cancelAcquire(node);
		}
	}

	// Main exported methods

	/**
	 * Attempts to acquire in exclusive mode. This method should query
	 * if the state of the object permits it to be acquired in the
	 * exclusive mode, and if so to acquire it.
	 *
	 * <p>This method is always invoked by the thread performing
	 * acquire.  If this method reports failure, the acquire method
	 * may queue the thread, if it is not already queued, until it is
	 * signalled by a release from some other thread. This can be used
	 * to implement method {@link Lock#tryLock()}.
	 *
	 * <p>The default
	 * implementation throws {@link UnsupportedOperationException}.
	 *
	 * @param arg the acquire argument. This value is always the one
	 *            passed to an acquire method, or is the value saved on entry
	 *            to a condition wait.  The value is otherwise uninterpreted
	 *            and can represent anything you like.
	 * @return {@code true} if successful. Upon success, this object has
	 * been acquired.
	 * @throws IllegalMonitorStateException  if acquiring would place this
	 *                                       synchronizer in an illegal state. This exception must be
	 *                                       thrown in a consistent fashion for synchronization to work
	 *                                       correctly.
	 * @throws UnsupportedOperationException if exclusive mode is not supported
	 */
	protected boolean tryAcquire(int arg) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Attempts to set the state to reflect a release in exclusive
	 * mode.
	 *
	 * <p>This method is always invoked by the thread performing release.
	 *
	 * <p>The default implementation throws
	 * {@link UnsupportedOperationException}.
	 *
	 * @param arg the release argument. This value is always the one
	 *            passed to a release method, or the current state value upon
	 *            entry to a condition wait.  The value is otherwise
	 *            uninterpreted and can represent anything you like.
	 * @return {@code true} if this object is now in a fully released
	 * state, so that any waiting threads may attempt to acquire;
	 * and {@code false} otherwise.
	 * @throws IllegalMonitorStateException  if releasing would place this
	 *                                       synchronizer in an illegal state. This exception must be
	 *                                       thrown in a consistent fashion for synchronization to work
	 *                                       correctly.
	 * @throws UnsupportedOperationException if exclusive mode is not supported
	 */
	protected boolean tryRelease(int arg) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Attempts to acquire in shared mode. This method should query if
	 * the state of the object permits it to be acquired in the shared
	 * mode, and if so to acquire it.
	 *
	 * <p>This method is always invoked by the thread performing
	 * acquire.  If this method reports failure, the acquire method
	 * may queue the thread, if it is not already queued, until it is
	 * signalled by a release from some other thread.
	 *
	 * <p>The default implementation throws {@link
	 * UnsupportedOperationException}.
	 *
	 * @param arg the acquire argument. This value is always the one
	 *            passed to an acquire method, or is the value saved on entry
	 *            to a condition wait.  The value is otherwise uninterpreted
	 *            and can represent anything you like.
	 * @return a negative value on failure; zero if acquisition in shared
	 * mode succeeded but no subsequent shared-mode acquire can
	 * succeed; and a positive value if acquisition in shared
	 * mode succeeded and subsequent shared-mode acquires might
	 * also succeed, in which case a subsequent waiting thread
	 * must check availability. (Support for three different
	 * return values enables this method to be used in contexts
	 * where acquires only sometimes act exclusively.)  Upon
	 * success, this object has been acquired.
	 * @throws IllegalMonitorStateException  if acquiring would place this
	 *                                       synchronizer in an illegal state. This exception must be
	 *                                       thrown in a consistent fashion for synchronization to work
	 *                                       correctly.
	 * @throws UnsupportedOperationException if shared mode is not supported
	 */
	protected int tryAcquireShared(int arg) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Attempts to set the state to reflect a release in shared mode.
	 *
	 * <p>This method is always invoked by the thread performing release.
	 *
	 * <p>The default implementation throws
	 * {@link UnsupportedOperationException}.
	 *
	 * @param arg the release argument. This value is always the one
	 *            passed to a release method, or the current state value upon
	 *            entry to a condition wait.  The value is otherwise
	 *            uninterpreted and can represent anything you like.
	 * @return {@code true} if this release of shared mode may permit a
	 * waiting acquire (shared or exclusive) to succeed; and
	 * {@code false} otherwise
	 * @throws IllegalMonitorStateException  if releasing would place this
	 *                                       synchronizer in an illegal state. This exception must be
	 *                                       thrown in a consistent fashion for synchronization to work
	 *                                       correctly.
	 * @throws UnsupportedOperationException if shared mode is not supported
	 */
	protected boolean tryReleaseShared(int arg) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns {@code true} if synchronization is held exclusively with
	 * respect to the current (calling) thread.  This method is invoked
	 * upon each call to a non-waiting {@link CarterAbstractQueuedSynchronizer.ConditionObject} method.
	 * (Waiting methods instead invoke {@link #release}.)
	 *
	 * <p>The default implementation throws {@link
	 * UnsupportedOperationException}. This method is invoked
	 * internally only within {@link CarterAbstractQueuedSynchronizer.ConditionObject} methods, so need
	 * not be defined if conditions are not used.
	 *
	 * @return {@code true} if synchronization is held exclusively;
	 * {@code false} otherwise
	 * @throws UnsupportedOperationException if conditions are not supported
	 */
	protected boolean isHeldExclusively() {
		throw new UnsupportedOperationException();
	}

	/**
	 * 获取独占锁
	 * 1. 根据if短路，当获取锁成功后就不执行后续代码
	 * 2. 获取锁失败，先通过addWaiter方法将创建一个独占线程节点，加入阻塞队列
	 * 3. 然后让阻塞队列中的所有线程都自旋方式来获取锁
	 * 4. 如果获取锁失败，并且加入队列失败，或者超时等，会进入if体，中断自己
	 * @param arg
	 */
	public final void acquire(int arg) {
		//通过tryAcquire获取锁，如果成功获取到锁直接终止(selfInterrupt),否则将当前线程插入队列
		//这里的Node.EXCLUSIVE表示创建一个独占模式的节点
		if (!tryAcquire(arg) && acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
			//自我中断
			selfInterrupt();
	}

	/**
	 * Acquires in exclusive mode, aborting if interrupted.
	 * Implemented by first checking interrupt status, then invoking
	 * at least once {@link #tryAcquire}, returning on
	 * success.  Otherwise the thread is queued, possibly repeatedly
	 * blocking and unblocking, invoking {@link #tryAcquire}
	 * until success or the thread is interrupted.  This method can be
	 * used to implement method {@link Lock#lockInterruptibly}.
	 *
	 * @param arg the acquire argument.  This value is conveyed to
	 *            {@link #tryAcquire} but is otherwise uninterpreted and
	 *            can represent anything you like.
	 * @throws InterruptedException if the current thread is interrupted
	 */
	public final void acquireInterruptibly(int arg) throws InterruptedException {
		if (Thread.interrupted()) throw new InterruptedException();
		if (!tryAcquire(arg)) doAcquireInterruptibly(arg);
	}

	/**
	 * Attempts to acquire in exclusive mode, aborting if interrupted,
	 * and failing if the given timeout elapses.  Implemented by first
	 * checking interrupt status, then invoking at least once {@link
	 * #tryAcquire}, returning on success.  Otherwise, the thread is
	 * queued, possibly repeatedly blocking and unblocking, invoking
	 * {@link #tryAcquire} until success or the thread is interrupted
	 * or the timeout elapses.  This method can be used to implement
	 * method {@link Lock#tryLock(long, TimeUnit)}.
	 *
	 * @param arg          the acquire argument.  This value is conveyed to
	 *                     {@link #tryAcquire} but is otherwise uninterpreted and
	 *                     can represent anything you like.
	 * @param nanosTimeout the maximum number of nanoseconds to wait
	 * @return {@code true} if acquired; {@code false} if timed out
	 * @throws InterruptedException if the current thread is interrupted
	 */
	public final boolean tryAcquireNanos(int arg, long nanosTimeout) throws InterruptedException {
		if (Thread.interrupted()) throw new InterruptedException();
		return tryAcquire(arg) || doAcquireNanos(arg, nanosTimeout);
	}

	//释放独占锁
	public final boolean release(int arg) {
		//尝试释放锁，这里tryRelease同样由子类实现，如果失败直接返回false
		if (tryRelease(arg)) {
			Node h = head;
			if (h != null && h.waitStatus != 0) {
				//唤醒头结点的后继节点
				unparkSuccessor(h);
			}
			return true;
		}
		return false;
	}

	/**
	 * 获取共享锁
	 * tryAcquireShared留给用户重写获取共享锁方法
	 * 返回值负数表示获取失败
	 * 0表示获取成功，但是后续节点无法获取成功
	 * 正数表示获取成功且后续节点也可能获取成功
	 * @param arg
	 */
	public final void acquireShared(int arg) {

		if (tryAcquireShared(arg) < 0) {
			doAcquireShared(arg);
		}

	}

	/**
	 * Acquires in shared mode, aborting if interrupted.  Implemented
	 * by first checking interrupt status, then invoking at least once
	 * {@link #tryAcquireShared}, returning on success.  Otherwise the
	 * thread is queued, possibly repeatedly blocking and unblocking,
	 * invoking {@link #tryAcquireShared} until success or the thread
	 * is interrupted.
	 *
	 * @param arg the acquire argument.
	 *            This value is conveyed to {@link #tryAcquireShared} but is
	 *            otherwise uninterpreted and can represent anything
	 *            you like.
	 * @throws InterruptedException if the current thread is interrupted
	 */
	public final void acquireSharedInterruptibly(int arg) throws InterruptedException {
		if (Thread.interrupted()) throw new InterruptedException();
		if (tryAcquireShared(arg) < 0) doAcquireSharedInterruptibly(arg);
	}

	/**
	 * Attempts to acquire in shared mode, aborting if interrupted, and
	 * failing if the given timeout elapses.  Implemented by first
	 * checking interrupt status, then invoking at least once {@link
	 * #tryAcquireShared}, returning on success.  Otherwise, the
	 * thread is queued, possibly repeatedly blocking and unblocking,
	 * invoking {@link #tryAcquireShared} until success or the thread
	 * is interrupted or the timeout elapses.
	 *
	 * @param arg          the acquire argument.  This value is conveyed to
	 *                     {@link #tryAcquireShared} but is otherwise uninterpreted
	 *                     and can represent anything you like.
	 * @param nanosTimeout the maximum number of nanoseconds to wait
	 * @return {@code true} if acquired; {@code false} if timed out
	 * @throws InterruptedException if the current thread is interrupted
	 */
	public final boolean tryAcquireSharedNanos(int arg, long nanosTimeout) throws InterruptedException {
		if (Thread.interrupted()) throw new InterruptedException();
		return tryAcquireShared(arg) >= 0 || doAcquireSharedNanos(arg, nanosTimeout);
	}

	//释放共享锁
	public final boolean releaseShared(int arg) {
		//尝试释放资源
		if (tryReleaseShared(arg)) {
			//唤醒后继节点
			doReleaseShared();
			return true;
		}
		return false;
	}

	// Queue inspection methods

	/**
	 * Queries whether any threads are waiting to acquire. Note that
	 * because cancellations due to interrupts and timeouts may occur
	 * at any time, a {@code true} return does not guarantee that any
	 * other thread will ever acquire.
	 *
	 * <p>In this implementation, this operation returns in
	 * constant time.
	 *
	 * @return {@code true} if there may be other threads waiting to acquire
	 */
	public final boolean hasQueuedThreads() {
		return head != tail;
	}

	/**
	 * Queries whether any threads have ever contended to acquire this
	 * synchronizer; that is if an acquire method has ever blocked.
	 *
	 * <p>In this implementation, this operation returns in
	 * constant time.
	 *
	 * @return {@code true} if there has ever been contention
	 */
	public final boolean hasContended() {
		return head != null;
	}

	/**
	 * Returns the first (longest-waiting) thread in the queue, or
	 * {@code null} if no threads are currently queued.
	 *
	 * <p>In this implementation, this operation normally returns in
	 * constant time, but may iterate upon contention if other threads are
	 * concurrently modifying the queue.
	 *
	 * @return the first (longest-waiting) thread in the queue, or
	 * {@code null} if no threads are currently queued
	 */
	public final Thread getFirstQueuedThread() {
		// handle only fast path, else relay
		return (head == tail) ? null : fullGetFirstQueuedThread();
	}

	/**
	 * Version of getFirstQueuedThread called when fastpath fails
	 */
	private Thread fullGetFirstQueuedThread() {
		/*
		 * The first node is normally head.next. Try to get its
		 * thread field, ensuring consistent reads: If thread
		 * field is nulled out or s.prev is no longer head, then
		 * some other thread(s) concurrently performed setHead in
		 * between some of our reads. We try this twice before
		 * resorting to traversal.
		 */
		Node h, s;
		Thread st;
		if (((h = head) != null && (s = h.next) != null && s.prev == head && (st = s.thread) != null) || ((h = head) != null && (s = h.next) != null && s.prev == head && (st = s.thread) != null)) return st;

		/*
		 * Head's next field might not have been set yet, or may have
		 * been unset after setHead. So we must check to see if tail
		 * is actually first node. If not, we continue on, safely
		 * traversing from tail back to head to find first,
		 * guaranteeing termination.
		 */

		Node t = tail;
		Thread firstThread = null;
		while (t != null && t != head) {
			Thread tt = t.thread;
			if (tt != null) firstThread = tt;
			t = t.prev;
		}
		return firstThread;
	}

	/**
	 * Returns true if the given thread is currently queued.
	 *
	 * <p>This implementation traverses the queue to determine
	 * presence of the given thread.
	 *
	 * @param thread the thread
	 * @return {@code true} if the given thread is on the queue
	 * @throws NullPointerException if the thread is null
	 */
	public final boolean isQueued(Thread thread) {
		if (thread == null) throw new NullPointerException();
		for (Node p = tail; p != null; p = p.prev)
			if (p.thread == thread) return true;
		return false;
	}

	/**
	 * Returns {@code true} if the apparent first queued thread, if one
	 * exists, is waiting in exclusive mode.  If this method returns
	 * {@code true}, and the current thread is attempting to acquire in
	 * shared mode (that is, this method is invoked from {@link
	 * #tryAcquireShared}) then it is guaranteed that the current thread
	 * is not the first queued thread.  Used only as a heuristic in
	 * ReentrantReadWriteLock.
	 */
	final boolean apparentlyFirstQueuedIsExclusive() {
		Node h, s;
		return (h = head) != null && (s = h.next) != null && !s.isShared() && s.thread != null;
	}

	/**
	 * Queries whether any threads have been waiting to acquire longer
	 * than the current thread.
	 *
	 * <p>An invocation of this method is equivalent to (but may be
	 * more efficient than):
	 * <pre> {@code
	 * getFirstQueuedThread() != Thread.currentThread() &&
	 * hasQueuedThreads()}</pre>
	 *
	 * <p>Note that because cancellations due to interrupts and
	 * timeouts may occur at any time, a {@code true} return does not
	 * guarantee that some other thread will acquire before the current
	 * thread.  Likewise, it is possible for another thread to win a
	 * race to enqueue after this method has returned {@code false},
	 * due to the queue being empty.
	 *
	 * <p>This method is designed to be used by a fair synchronizer to
	 * avoid <a href="CarterAbstractQueuedSynchronizer#barging">barging</a>.
	 * Such a synchronizer's {@link #tryAcquire} method should return
	 * {@code false}, and its {@link #tryAcquireShared} method should
	 * return a negative value, if this method returns {@code true}
	 * (unless this is a reentrant acquire).  For example, the {@code
	 * tryAcquire} method for a fair, reentrant, exclusive mode
	 * synchronizer might look like this:
	 *
	 * <pre> {@code
	 * protected boolean tryAcquire(int arg) {
	 *   if (isHeldExclusively()) {
	 *     // A reentrant acquire; increment hold count
	 *     return true;
	 *   } else if (hasQueuedPredecessors()) {
	 *     return false;
	 *   } else {
	 *     // try to acquire normally
	 *   }
	 * }}</pre>
	 *
	 * @return {@code true} if there is a queued thread preceding the
	 * current thread, and {@code false} if the current thread
	 * is at the head of the queue or the queue is empty
	 * @since 1.7
	 */
	public final boolean hasQueuedPredecessors() {
		// The correctness of this depends on head being initialized
		// before tail and on head.next being accurate if the current
		// thread is first in queue.
		Node t = tail; // Read fields in reverse initialization order
		Node h = head;
		Node s;
		return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
	}

	// Instrumentation and monitoring methods

	/**
	 * Returns an estimate of the number of threads waiting to
	 * acquire.  The value is only an estimate because the number of
	 * threads may change dynamically while this method traverses
	 * internal data structures.  This method is designed for use in
	 * monitoring system state, not for synchronization
	 * control.
	 *
	 * @return the estimated number of threads waiting to acquire
	 */
	public final int getQueueLength() {
		int n = 0;
		for (Node p = tail; p != null; p = p.prev) {
			if (p.thread != null) ++n;
		}
		return n;
	}

	/**
	 * Returns a collection containing threads that may be waiting to
	 * acquire.  Because the actual set of threads may change
	 * dynamically while constructing this result, the returned
	 * collection is only a best-effort estimate.  The elements of the
	 * returned collection are in no particular order.  This method is
	 * designed to facilitate construction of subclasses that provide
	 * more extensive monitoring facilities.
	 *
	 * @return the collection of threads
	 */
	public final Collection<Thread> getQueuedThreads() {
		ArrayList<Thread> list = new ArrayList<Thread>();
		for (Node p = tail; p != null; p = p.prev) {
			Thread t = p.thread;
			if (t != null) list.add(t);
		}
		return list;
	}

	/**
	 * Returns a collection containing threads that may be waiting to
	 * acquire in exclusive mode. This has the same properties
	 * as {@link #getQueuedThreads} except that it only returns
	 * those threads waiting due to an exclusive acquire.
	 *
	 * @return the collection of threads
	 */
	public final Collection<Thread> getExclusiveQueuedThreads() {
		ArrayList<Thread> list = new ArrayList<Thread>();
		for (Node p = tail; p != null; p = p.prev) {
			if (!p.isShared()) {
				Thread t = p.thread;
				if (t != null) list.add(t);
			}
		}
		return list;
	}

	/**
	 * Returns a collection containing threads that may be waiting to
	 * acquire in shared mode. This has the same properties
	 * as {@link #getQueuedThreads} except that it only returns
	 * those threads waiting due to a shared acquire.
	 *
	 * @return the collection of threads
	 */
	public final Collection<Thread> getSharedQueuedThreads() {
		ArrayList<Thread> list = new ArrayList<Thread>();
		for (Node p = tail; p != null; p = p.prev) {
			if (p.isShared()) {
				Thread t = p.thread;
				if (t != null) list.add(t);
			}
		}
		return list;
	}

	/**
	 * Returns a string identifying this synchronizer, as well as its state.
	 * The state, in brackets, includes the String {@code "State ="}
	 * followed by the current value of {@link #getState}, and either
	 * {@code "nonempty"} or {@code "empty"} depending on whether the
	 * queue is empty.
	 *
	 * @return a string identifying this synchronizer, as well as its state
	 */
	public String toString() {
		int s = getState();
		String q = hasQueuedThreads() ? "non" : "";
		return super.toString() + "[State = " + s + ", " + q + "empty queue]";
	}

	// Internal support methods for Conditions

	/**
	 * Returns true if a node, always one that was initially placed on
	 * a condition queue, is now waiting to reacquire on sync queue.
	 *
	 * @param node the node
	 * @return true if is reacquiring
	 */
	final boolean isOnSyncQueue(Node node) {
		if (node.waitStatus == Node.CONDITION || node.prev == null) return false;
		if (node.next != null) // If has successor, it must be on queue
			return true;
		/*
		 * node.prev can be non-null, but not yet on queue because
		 * the CAS to place it on queue can fail. So we have to
		 * traverse from tail to make sure it actually made it.  It
		 * will always be near the tail in calls to this method, and
		 * unless the CAS failed (which is unlikely), it will be
		 * there, so we hardly ever traverse much.
		 */
		return findNodeFromTail(node);
	}

	/**
	 * Returns true if node is on sync queue by searching backwards from tail.
	 * Called only when needed by isOnSyncQueue.
	 *
	 * @return true if present
	 */
	private boolean findNodeFromTail(Node node) {
		Node t = tail;
		for (;;) {
			if (t == node) return true;
			if (t == null) return false;
			t = t.prev;
		}
	}

	/**
	 * Transfers a node from a condition queue onto sync queue.
	 * Returns true if successful.
	 *
	 * @param node the node
	 * @return true if successfully transferred (else the node was
	 * cancelled before signal)
	 */
	final boolean transferForSignal(Node node) {
		/*
		 * If cannot change waitStatus, the node has been cancelled.
		 */
		if (!compareAndSetWaitStatus(node, Node.CONDITION, 0)) return false;

		/*
		 * Splice onto queue and try to set waitStatus of predecessor to
		 * indicate that thread is (probably) waiting. If cancelled or
		 * attempt to set waitStatus fails, wake up to resync (in which
		 * case the waitStatus can be transiently and harmlessly wrong).
		 */
		Node p = enq(node);
		int ws = p.waitStatus;
		if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL)) LockSupport.unpark(node.thread);
		return true;
	}

	/**
	 * Transfers node, if necessary, to sync queue after a cancelled wait.
	 * Returns true if thread was cancelled before being signalled.
	 *
	 * @param node the node
	 * @return true if cancelled before the node was signalled
	 */
	final boolean transferAfterCancelledWait(Node node) {
		if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
			enq(node);
			return true;
		}
		/*
		 * If we lost out to a signal(), then we can't proceed
		 * until it finishes its enq().  Cancelling during an
		 * incomplete transfer is both rare and transient, so just
		 * spin.
		 */
		while (!isOnSyncQueue(node))
			Thread.yield();
		return false;
	}

	/**
	 * Invokes release with current state value; returns saved state.
	 * Cancels node and throws exception on failure.
	 *
	 * @param node the condition node for this wait
	 * @return previous sync state
	 */
	final int fullyRelease(Node node) {
		boolean failed = true;
		try {
			int savedState = getState();
			if (release(savedState)) {
				failed = false;
				return savedState;
			} else {
				throw new IllegalMonitorStateException();
			}
		} finally {
			if (failed) node.waitStatus = Node.CANCELLED;
		}
	}

	// Instrumentation methods for conditions

	/**
	 * Queries whether the given ConditionObject
	 * uses this synchronizer as its lock.
	 *
	 * @param condition the condition
	 * @return {@code true} if owned
	 * @throws NullPointerException if the condition is null
	 */
	public final boolean owns(CarterAbstractQueuedSynchronizer.ConditionObject condition) {
		return condition.isOwnedBy(this);
	}

	/**
	 * Queries whether any threads are waiting on the given condition
	 * associated with this synchronizer. Note that because timeouts
	 * and interrupts may occur at any time, a {@code true} return
	 * does not guarantee that a future {@code signal} will awaken
	 * any threads.  This method is designed primarily for use in
	 * monitoring of the system state.
	 *
	 * @param condition the condition
	 * @return {@code true} if there are any waiting threads
	 * @throws IllegalMonitorStateException if exclusive synchronization
	 *                                      is not held
	 * @throws IllegalArgumentException     if the given condition is
	 *                                      not associated with this synchronizer
	 * @throws NullPointerException         if the condition is null
	 */
	public final boolean hasWaiters(CarterAbstractQueuedSynchronizer.ConditionObject condition) {
		if (!owns(condition)) throw new IllegalArgumentException("Not owner");
		return condition.hasWaiters();
	}

	/**
	 * Returns an estimate of the number of threads waiting on the
	 * given condition associated with this synchronizer. Note that
	 * because timeouts and interrupts may occur at any time, the
	 * estimate serves only as an upper bound on the actual number of
	 * waiters.  This method is designed for use in monitoring of the
	 * system state, not for synchronization control.
	 *
	 * @param condition the condition
	 * @return the estimated number of waiting threads
	 * @throws IllegalMonitorStateException if exclusive synchronization
	 *                                      is not held
	 * @throws IllegalArgumentException     if the given condition is
	 *                                      not associated with this synchronizer
	 * @throws NullPointerException         if the condition is null
	 */
	public final int getWaitQueueLength(CarterAbstractQueuedSynchronizer.ConditionObject condition) {
		if (!owns(condition)) throw new IllegalArgumentException("Not owner");
		return condition.getWaitQueueLength();
	}

	/**
	 * Returns a collection containing those threads that may be
	 * waiting on the given condition associated with this
	 * synchronizer.  Because the actual set of threads may change
	 * dynamically while constructing this result, the returned
	 * collection is only a best-effort estimate. The elements of the
	 * returned collection are in no particular order.
	 *
	 * @param condition the condition
	 * @return the collection of threads
	 * @throws IllegalMonitorStateException if exclusive synchronization
	 *                                      is not held
	 * @throws IllegalArgumentException     if the given condition is
	 *                                      not associated with this synchronizer
	 * @throws NullPointerException         if the condition is null
	 */
	public final Collection<Thread> getWaitingThreads(CarterAbstractQueuedSynchronizer.ConditionObject condition) {
		if (!owns(condition)) throw new IllegalArgumentException("Not owner");
		return condition.getWaitingThreads();
	}

	/**
	 * Condition implementation for a {@link
	 * CarterAbstractQueuedSynchronizer} serving as the basis of a {@link
	 * Lock} implementation.
	 *
	 * <p>Method documentation for this class describes mechanics,
	 * not behavioral specifications from the point of view of Lock
	 * and Condition users. Exported versions of this class will in
	 * general need to be accompanied by documentation describing
	 * condition semantics that rely on those of the associated
	 * {@code CarterAbstractQueuedSynchronizer}.
	 *
	 * <p>This class is Serializable, but all fields are transient,
	 * so deserialized conditions have no waiters.
	 */
	public class ConditionObject implements Condition, java.io.Serializable {
		private static final long	serialVersionUID	= 1173984872572414699L;
		/**
		 * First node of condition queue.
		 */
		private transient Node		firstWaiter;
		/**
		 * Last node of condition queue.
		 */
		private transient Node		lastWaiter;

		/**
		 * Creates a new {@code ConditionObject} instance.
		 */
		public ConditionObject() {
		}

		// Internal methods

		/**
		 * Adds a new waiter to wait queue.
		 *
		 * @return its new wait node
		 */
		private Node addConditionWaiter() {
			Node t = lastWaiter;
			// If lastWaiter is cancelled, clean out.
			if (t != null && t.waitStatus != Node.CONDITION) {
				unlinkCancelledWaiters();
				t = lastWaiter;
			}
			Node node = new Node(Thread.currentThread(), Node.CONDITION);
			if (t == null) firstWaiter = node;
			else t.nextWaiter = node;
			lastWaiter = node;
			return node;
		}

		/**
		 * Removes and transfers nodes until hit non-cancelled one or
		 * null. Split out from signal in part to encourage compilers
		 * to inline the case of no waiters.
		 *
		 * @param first (non-null) the first node on condition queue
		 */
		private void doSignal(Node first) {
			do {
				if ((firstWaiter = first.nextWaiter) == null) lastWaiter = null;
				first.nextWaiter = null;
			} while (!transferForSignal(first) && (first = firstWaiter) != null);
		}

		/**
		 * Removes and transfers all nodes.
		 *
		 * @param first (non-null) the first node on condition queue
		 */
		private void doSignalAll(Node first) {
			lastWaiter = firstWaiter = null;
			do {
				Node next = first.nextWaiter;
				first.nextWaiter = null;
				transferForSignal(first);
				first = next;
			} while (first != null);
		}

		/**
		 * Unlinks cancelled waiter nodes from condition queue.
		 * Called only while holding lock. This is called when
		 * cancellation occurred during condition wait, and upon
		 * insertion of a new waiter when lastWaiter is seen to have
		 * been cancelled. This method is needed to avoid garbage
		 * retention in the absence of signals. So even though it may
		 * require a full traversal, it comes into play only when
		 * timeouts or cancellations occur in the absence of
		 * signals. It traverses all nodes rather than stopping at a
		 * particular target to unlink all pointers to garbage nodes
		 * without requiring many re-traversals during cancellation
		 * storms.
		 */
		private void unlinkCancelledWaiters() {
			Node t = firstWaiter;
			Node trail = null;
			while (t != null) {
				Node next = t.nextWaiter;
				if (t.waitStatus != Node.CONDITION) {
					t.nextWaiter = null;
					if (trail == null) firstWaiter = next;
					else trail.nextWaiter = next;
					if (next == null) lastWaiter = trail;
				} else trail = t;
				t = next;
			}
		}

		// public methods

		/**
		 * Moves the longest-waiting thread, if one exists, from the
		 * wait queue for this condition to the wait queue for the
		 * owning lock.
		 *
		 * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
		 *                                      returns {@code false}
		 */
		public final void signal() {
			if (!isHeldExclusively()) throw new IllegalMonitorStateException();
			Node first = firstWaiter;
			if (first != null) doSignal(first);
		}

		/**
		 * Moves all threads from the wait queue for this condition to
		 * the wait queue for the owning lock.
		 *
		 * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
		 *                                      returns {@code false}
		 */
		public final void signalAll() {
			if (!isHeldExclusively()) throw new IllegalMonitorStateException();
			Node first = firstWaiter;
			if (first != null) doSignalAll(first);
		}

		/**
		 * Implements uninterruptible condition wait.
		 * <ol>
		 * <li> Save lock state returned by {@link #getState}.
		 * <li> Invoke {@link #release} with saved state as argument,
		 *      throwing IllegalMonitorStateException if it fails.
		 * <li> Block until signalled.
		 * <li> Reacquire by invoking specialized version of
		 *      {@link #acquire} with saved state as argument.
		 * </ol>
		 */
		public final void awaitUninterruptibly() {
			Node node = addConditionWaiter();
			int savedState = fullyRelease(node);
			boolean interrupted = false;
			while (!isOnSyncQueue(node)) {
				LockSupport.park(this);
				if (Thread.interrupted()) interrupted = true;
			}
			if (acquireQueued(node, savedState) || interrupted) selfInterrupt();
		}

		/*
		 * For interruptible waits, we need to track whether to throw
		 * InterruptedException, if interrupted while blocked on
		 * condition, versus reinterrupt current thread, if
		 * interrupted while blocked waiting to re-acquire.
		 */

		/**
		 * Mode meaning to reinterrupt on exit from wait
		 */
		private static final int	REINTERRUPT	= 1;
		/**
		 * Mode meaning to throw InterruptedException on exit from wait
		 */
		private static final int	THROW_IE	= -1;

		/**
		 * Checks for interrupt, returning THROW_IE if interrupted
		 * before signalled, REINTERRUPT if after signalled, or
		 * 0 if not interrupted.
		 */
		private int checkInterruptWhileWaiting(Node node) {
			return Thread.interrupted() ? (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) : 0;
		}

		/**
		 * Throws InterruptedException, reinterrupts current thread, or
		 * does nothing, depending on mode.
		 */
		private void reportInterruptAfterWait(int interruptMode) throws InterruptedException {
			if (interruptMode == THROW_IE) throw new InterruptedException();
			else if (interruptMode == REINTERRUPT) selfInterrupt();
		}

		/**
		 * Implements interruptible condition wait.
		 * <ol>
		 * <li> If current thread is interrupted, throw InterruptedException.
		 * <li> Save lock state returned by {@link #getState}.
		 * <li> Invoke {@link #release} with saved state as argument,
		 *      throwing IllegalMonitorStateException if it fails.
		 * <li> Block until signalled or interrupted.
		 * <li> Reacquire by invoking specialized version of
		 *      {@link #acquire} with saved state as argument.
		 * <li> If interrupted while blocked in step 4, throw InterruptedException.
		 * </ol>
		 */
		public final void await() throws InterruptedException {
			if (Thread.interrupted()) throw new InterruptedException();
			Node node = addConditionWaiter();
			int savedState = fullyRelease(node);
			int interruptMode = 0;
			while (!isOnSyncQueue(node)) {
				LockSupport.park(this);
				if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
			}
			if (acquireQueued(node, savedState) && interruptMode != THROW_IE) interruptMode = REINTERRUPT;
			if (node.nextWaiter != null) // clean up if cancelled
				unlinkCancelledWaiters();
			if (interruptMode != 0) reportInterruptAfterWait(interruptMode);
		}

		/**
		 * Implements timed condition wait.
		 * <ol>
		 * <li> If current thread is interrupted, throw InterruptedException.
		 * <li> Save lock state returned by {@link #getState}.
		 * <li> Invoke {@link #release} with saved state as argument,
		 *      throwing IllegalMonitorStateException if it fails.
		 * <li> Block until signalled, interrupted, or timed out.
		 * <li> Reacquire by invoking specialized version of
		 *      {@link #acquire} with saved state as argument.
		 * <li> If interrupted while blocked in step 4, throw InterruptedException.
		 * </ol>
		 */
		public final long awaitNanos(long nanosTimeout) throws InterruptedException {
			if (Thread.interrupted()) throw new InterruptedException();
			Node node = addConditionWaiter();
			int savedState = fullyRelease(node);
			final long deadline = System.nanoTime() + nanosTimeout;
			int interruptMode = 0;
			while (!isOnSyncQueue(node)) {
				if (nanosTimeout <= 0L) {
					transferAfterCancelledWait(node);
					break;
				}
				if (nanosTimeout >= spinForTimeoutThreshold) LockSupport.parkNanos(this, nanosTimeout);
				if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
				nanosTimeout = deadline - System.nanoTime();
			}
			if (acquireQueued(node, savedState) && interruptMode != THROW_IE) interruptMode = REINTERRUPT;
			if (node.nextWaiter != null) unlinkCancelledWaiters();
			if (interruptMode != 0) reportInterruptAfterWait(interruptMode);
			return deadline - System.nanoTime();
		}

		/**
		 * Implements absolute timed condition wait.
		 * <ol>
		 * <li> If current thread is interrupted, throw InterruptedException.
		 * <li> Save lock state returned by {@link #getState}.
		 * <li> Invoke {@link #release} with saved state as argument,
		 *      throwing IllegalMonitorStateException if it fails.
		 * <li> Block until signalled, interrupted, or timed out.
		 * <li> Reacquire by invoking specialized version of
		 *      {@link #acquire} with saved state as argument.
		 * <li> If interrupted while blocked in step 4, throw InterruptedException.
		 * <li> If timed out while blocked in step 4, return false, else true.
		 * </ol>
		 */
		public final boolean awaitUntil(Date deadline) throws InterruptedException {
			long abstime = deadline.getTime();
			if (Thread.interrupted()) throw new InterruptedException();
			Node node = addConditionWaiter();
			int savedState = fullyRelease(node);
			boolean timedout = false;
			int interruptMode = 0;
			while (!isOnSyncQueue(node)) {
				if (System.currentTimeMillis() > abstime) {
					timedout = transferAfterCancelledWait(node);
					break;
				}
				LockSupport.parkUntil(this, abstime);
				if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
			}
			if (acquireQueued(node, savedState) && interruptMode != THROW_IE) interruptMode = REINTERRUPT;
			if (node.nextWaiter != null) unlinkCancelledWaiters();
			if (interruptMode != 0) reportInterruptAfterWait(interruptMode);
			return !timedout;
		}

		/**
		 * Implements timed condition wait.
		 * <ol>
		 * <li> If current thread is interrupted, throw InterruptedException.
		 * <li> Save lock state returned by {@link #getState}.
		 * <li> Invoke {@link #release} with saved state as argument,
		 *      throwing IllegalMonitorStateException if it fails.
		 * <li> Block until signalled, interrupted, or timed out.
		 * <li> Reacquire by invoking specialized version of
		 *      {@link #acquire} with saved state as argument.
		 * <li> If interrupted while blocked in step 4, throw InterruptedException.
		 * <li> If timed out while blocked in step 4, return false, else true.
		 * </ol>
		 */
		public final boolean await(long time, TimeUnit unit) throws InterruptedException {
			long nanosTimeout = unit.toNanos(time);
			if (Thread.interrupted()) throw new InterruptedException();
			Node node = addConditionWaiter();
			int savedState = fullyRelease(node);
			final long deadline = System.nanoTime() + nanosTimeout;
			boolean timedout = false;
			int interruptMode = 0;
			while (!isOnSyncQueue(node)) {
				if (nanosTimeout <= 0L) {
					timedout = transferAfterCancelledWait(node);
					break;
				}
				if (nanosTimeout >= spinForTimeoutThreshold) LockSupport.parkNanos(this, nanosTimeout);
				if ((interruptMode = checkInterruptWhileWaiting(node)) != 0) break;
				nanosTimeout = deadline - System.nanoTime();
			}
			if (acquireQueued(node, savedState) && interruptMode != THROW_IE) interruptMode = REINTERRUPT;
			if (node.nextWaiter != null) unlinkCancelledWaiters();
			if (interruptMode != 0) reportInterruptAfterWait(interruptMode);
			return !timedout;
		}

		//  support for instrumentation

		/**
		 * Returns true if this condition was created by the given
		 * synchronization object.
		 *
		 * @return {@code true} if owned
		 */
		final boolean isOwnedBy(CarterAbstractQueuedSynchronizer sync) {
			return sync == CarterAbstractQueuedSynchronizer.this;
		}

		/**
		 * Queries whether any threads are waiting on this condition.
		 * Implements {@link CarterAbstractQueuedSynchronizer#hasWaiters(CarterAbstractQueuedSynchronizer.ConditionObject)}.
		 *
		 * @return {@code true} if there are any waiting threads
		 * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
		 *                                      returns {@code false}
		 */
		protected final boolean hasWaiters() {
			if (!isHeldExclusively()) throw new IllegalMonitorStateException();
			for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
				if (w.waitStatus == Node.CONDITION) return true;
			}
			return false;
		}

		/**
		 * Returns an estimate of the number of threads waiting on
		 * this condition.
		 * Implements {@link CarterAbstractQueuedSynchronizer#getWaitQueueLength(CarterAbstractQueuedSynchronizer.ConditionObject)}.
		 *
		 * @return the estimated number of waiting threads
		 * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
		 *                                      returns {@code false}
		 */
		protected final int getWaitQueueLength() {
			if (!isHeldExclusively()) throw new IllegalMonitorStateException();
			int n = 0;
			for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
				if (w.waitStatus == Node.CONDITION) ++n;
			}
			return n;
		}

		/**
		 * Returns a collection containing those threads that may be
		 * waiting on this Condition.
		 * Implements {@link CarterAbstractQueuedSynchronizer#getWaitingThreads(CarterAbstractQueuedSynchronizer.ConditionObject)}.
		 *
		 * @return the collection of threads
		 * @throws IllegalMonitorStateException if {@link #isHeldExclusively}
		 *                                      returns {@code false}
		 */
		protected final Collection<Thread> getWaitingThreads() {
			if (!isHeldExclusively()) throw new IllegalMonitorStateException();
			ArrayList<Thread> list = new ArrayList<Thread>();
			for (Node w = firstWaiter; w != null; w = w.nextWaiter) {
				if (w.waitStatus == Node.CONDITION) {
					Thread t = w.thread;
					if (t != null) list.add(t);
				}
			}
			return list;
		}
	}

	/**
	 * Setup to support compareAndSet. We need to natively implement
	 * this here: For the sake of permitting future enhancements, we
	 * cannot explicitly subclass AtomicInteger, which would be
	 * efficient and useful otherwise. So, as the lesser of evils, we
	 * natively implement using hotspot intrinsics API. And while we
	 * are at it, we do the same for other CASable fields (which could
	 * otherwise be done with atomic field updaters).
	 */
	private static final Unsafe	unsafe	= Unsafe.getUnsafe();
	private static final long	stateOffset;
	private static final long	headOffset;
	private static final long	tailOffset;
	private static final long	waitStatusOffset;
	private static final long	nextOffset;

	static {
		try {
			stateOffset = unsafe.objectFieldOffset(CarterAbstractQueuedSynchronizer.class.getDeclaredField("state"));
			headOffset = unsafe.objectFieldOffset(CarterAbstractQueuedSynchronizer.class.getDeclaredField("head"));
			tailOffset = unsafe.objectFieldOffset(CarterAbstractQueuedSynchronizer.class.getDeclaredField("tail"));
			waitStatusOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("waitStatus"));
			nextOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("next"));

		} catch (Exception ex) {
			throw new Error(ex);
		}
	}

	/**
	 * CAS head field. Used only by enq.
	 */
	private final boolean compareAndSetHead(Node update) {
		return unsafe.compareAndSwapObject(this, headOffset, null, update);
	}

	/**
	 * CAS tail field. Used only by enq.
	 */
	private final boolean compareAndSetTail(Node expect, Node update) {
		return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
	}

	/**
	 * CAS waitStatus field of a node.
	 */
	private static final boolean compareAndSetWaitStatus(Node node, int expect, int update) {
		return unsafe.compareAndSwapInt(node, waitStatusOffset, expect, update);
	}

	/**
	 * CAS next field of a node.
	 */
	private static final boolean compareAndSetNext(Node node, Node expect, Node update) {
		return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
	}
}
