package com.carter.threadPool;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicStampedReference;

//解决atomic类cas操作aba问题，解决方式是在更新时设置版本号的方式来解决，每次更新就要设置一个不一样的版本号
//修改的时候，不但要比较值有没有变，还要比较版本号对不对，这个思想在zookeeper中也有体现
public class CarterAtomicStampedReference<V> {

	//它里面只有一个成员变量,要做原子更新的对象会被封装为Pair对象，并赋值给pair
	private static class Pair<T> {
		//存储的值
		final T reference;
		//时间戳（版本号）
		final int stamp;

		private Pair(T reference, int stamp) {
			this.reference = reference;
			this.stamp = stamp;
		}

		// 创建一个新的Pair对象, 每次值变化时都会创建一个新的对象
		static <T> Pair<T> of(T reference, int stamp) {
			return new Pair(reference, stamp);
		}
	}

	private volatile Pair<V> pair;

	private static final sun.misc.Unsafe	UNSAFE		= sun.misc.Unsafe.getUnsafe();
	private static final long				pairOffset	= objectFieldOffset(UNSAFE, "pair", CarterAtomicStampedReference.class);

	public CarterAtomicStampedReference(V initialRef, int initialStamp) {
		this.pair = Pair.of(initialRef, initialStamp);
	}

	//获得当前对象引用
	public V getReference() {
		return pair.reference;
	}

	//获得当前时间戳
	public int getStamp() {
		return pair.stamp;
	}

	public V get(int[] stampHolder) {
		Pair<V> pair = this.pair;
		stampHolder[0] = pair.stamp;
		return pair.reference;
	}

	public boolean weakCompareAndSet(V expectedReference, V newReference, int expectedStamp, int newStamp) {
		return this.compareAndSet(expectedReference, newReference, expectedStamp, newStamp);
	}

	//比较设置 参数依次为：期望值 写入新值 期望时间戳 新时间戳
	public boolean compareAndSet(V expectedReference, V newReference, int expectedStamp, int newStamp) {
		Pair<V> current = this.pair;
		return expectedReference == current.reference && expectedStamp == current.stamp && (newReference == current.reference && newStamp == current.stamp || casPair(current, Pair.of(newReference, newStamp)));
	}

	//设置当前对象引用和时间戳
	public void set(V newReference, int newStamp) {
		Pair<V> current = this.pair;
		if (newReference != current.reference || newStamp != current.stamp) {
			this.pair = Pair.of(newReference, newStamp);
		}

	}

	/**
	 * 以原子方式设置版本号为新的值
	 * 前提：引用值保持不变
	 * 当期望的引用值与当前引用值不相同时，操作失败，返回false
	 * 当期望的引用值与当前引用值相同时，操作成功，返回true
	 * @param expectedReference
	 * @param newStamp
	 * @return
	 */
	public boolean attemptStamp(V expectedReference, int newStamp) {
		Pair<V> current = this.pair;
		return expectedReference == current.reference && (newStamp == current.stamp || this.casPair(current, CarterAtomicStampedReference.Pair.of(expectedReference, newStamp)));
	}

	// 使用`sun.misc.Unsafe`类原子地交换两个对象
	private boolean casPair(Pair<V> cmp, Pair<V> val) {
		return UNSAFE.compareAndSwapObject(this, pairOffset, cmp, val);
	}


	static long objectFieldOffset(sun.misc.Unsafe UNSAFE, String field, Class<?> klazz) {
		try {
			return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
		} catch (NoSuchFieldException e) {
			// Convert Exception to corresponding Error
			NoSuchFieldError error = new NoSuchFieldError(field);
			error.initCause(e);
			throw error;
		}
	}
}
