package com.carter;

import java.util.concurrent.*;
import java.util.function.*;

public class CarterCompletableFuture<T> implements Future<T>, CompletionStage<T> {
    //计算结果或者已经包装的AltResult
    volatile Object result;
    //依赖操作的堆栈顶部
    volatile Completion stack;

    static final int SYNC   =  0;
    static final int ASYNC  =  1;
    static final int NESTED = -1;

    abstract static class Completion extends ForkJoinTask<Void> implements Runnable,AsynchronousCompletionTask{
        volatile Completion next;
        abstract CarterCompletableFuture<?> tryFire(int mode);
        abstract boolean isLive();

        public final void run(){
            tryFire(ASYNC);
        }

        public final boolean exec(){
            tryFire(ASYNC);
            return true;
        }
        public final Void getRawResult()       { return null; }
        public final void setRawResult(Void v) {}
    }

    abstract static class UniCompletion<T,V> extends Completion{
        Executor executor;
        CarterCompletableFuture<V> dependent;
        CarterCompletableFuture<T> source;

        public UniCompletion(Executor executor, CarterCompletableFuture<V> dependent, CarterCompletableFuture<T> source) {
            this.executor = executor;
            this.dependent = dependent;
            this.source = source;
        }
    }

    private static final boolean useCommonPool = ForkJoinPool.getCommonPoolParallelism() > 1;

    private static final Executor asyncPool = useCommonPool?ForkJoinPool.commonPool():new ThreadPerTaskExecutor();

    static final class ThreadPerTaskExecutor implements Executor {
        public void execute(Runnable r) { new Thread(r).start(); }
    }

    public static <U>CarterCompletableFuture<U> supplyAsync(Supplier<U> supplier){
        return asyncSupplyStage(asyncPool, supplier);
    }

    private static <U> CarterCompletableFuture<U> asyncSupplyStage(Executor executor, Supplier<U> f) {
        if (f==null){
            throw new NullPointerException();
        }
        CarterCompletableFuture<U> future = new CarterCompletableFuture<>();
        AsyncSupply<U> asyncSupply = new AsyncSupply<U>(future, f);
        executor.execute(asyncSupply);

        return future;
    }

    static final class AsyncSupply<T> extends ForkJoinTask<Void> implements Runnable, AsynchronousCompletionTask{
        CarterCompletableFuture<T> carterCompletableFuture;
        Supplier<T> supplier;

        public AsyncSupply(CarterCompletableFuture<T> carterCompletableFuture, Supplier<T> supplier) {
            this.carterCompletableFuture = carterCompletableFuture;
            this.supplier = supplier;
        }

        @Override
        public void run() {
            CarterCompletableFuture<T> ccf;
            Supplier<T> st;
            ccf=carterCompletableFuture;
            st=supplier;
            if (ccf!=null&&st!=null){
                /*为了防止内存泄露，方便GC同时carterCompletableFuture为null也是一种代表当前Completion对象关联的stage已完成的标志*/
                carterCompletableFuture = null;
                supplier = null;
                if (ccf.result==null){
                    try {
                        //执行task
                        ccf.completeValue(st.get());
                    }catch (Throwable ex){
                        //执行task期间抛出了异常
                        ccf.completeThrowable(ex);
                    }

                }
                ccf.postComplete();
            }
        }

        @Override
        public Void getRawResult() {
            return null;
        }

        @Override
        protected void setRawResult(Void value) {
        }

        @Override
        protected boolean exec() {
            run();
            return true;
        }
    }

    final void postComplete() {
        CarterCompletableFuture<?> ccf = this;
        Completion completion= ccf.stack;
        while (completion!=null||(ccf!=this&&completion!=null)){
            CarterCompletableFuture<?> d;
            Completion t;
            if (ccf.casStack(completion,t=completion.next)){
                if (t!=null){
                    if (ccf!=this){
                        pushStack(completion);
                        continue;
                    }
                    completion.next=null;
                }
                ccf=(d=completion.tryFire(NESTED))==null?this:d;
            }
        }
    }

    final void pushStack(Completion completion) {
        do {

        }while (!tryPushStack(completion));
    }

    final boolean tryPushStack(Completion completion) {
        Completion stack = this.stack;
        lazySetNext(completion,stack);
        return UNSAFE.compareAndSwapObject(this,STACK,stack,completion);
    }

    static void lazySetNext(Completion completion, Completion next) {
        UNSAFE.putOrderedObject(completion,NEXT,next);
    }

    public static interface AsynchronousCompletionTask{};

    @Override
    public <U> CarterCompletableFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return uniApplyStage(null,fn);
    }

    private <V> CarterCompletableFuture<V> uniApplyStage(Executor executor, Function<? super T, ? extends V> function) {
        //如果function为空，直接抛出异常
        if (function==null){
            throw new NullPointerException();
        }
        CarterCompletableFuture<V> ccf = new CarterCompletableFuture<>();
        if (executor!=null||!ccf.uniApply(this,function,null)){
            UniApply<T, V> uniApply = new UniApply<T, V>(executor, ccf, this, function);
            push(uniApply);
            uniApply.tryFire(SYNC);
        }
        return ccf;
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenAccept(Consumer<? super T> action) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenRun(Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> thenRunAsync(Runnable action, Executor executor) {
        return null;
    }

    @Override
    public <U, V> CompletionStage<V> thenCombine(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        return null;
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn) {
        return null;
    }

    @Override
    public <U, V> CompletionStage<V> thenCombineAsync(CompletionStage<? extends U> other, BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
        return null;
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return null;
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action) {
        return null;
    }

    @Override
    public <U> CompletionStage<Void> thenAcceptBothAsync(CompletionStage<? extends U> other, BiConsumer<? super T, ? super U> action, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return null;
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return null;
    }

    @Override
    public CompletionStage<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return null;
    }

    @Override
    public CompletionStage<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        return null;
    }

    @Override
    public CompletionStage<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return null;
    }

    @Override
    public CompletionStage<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return null;
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return null;
    }

    @Override
    public CompletionStage<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return null;
    }

    @Override
    public <U> CompletionStage<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return null;
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        return null;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        return null;
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return null;
    }

    static final class AltResult { // See above
        final Throwable ex;        // null only for NIL
        AltResult(Throwable x) { this.ex = x; }
    }
    static final AltResult NIL = new AltResult(null);

    static AltResult encodeThrowable(Throwable x){
        return new AltResult((x instanceof CompletionException)?x:new CompletionException(x));
    }

    final boolean completeValue(T t){
        return UNSAFE.compareAndSwapObject(this,RESULT,null,t==null?NIL:t);
    }

    static final class UniApply<T,V> extends UniCompletion<T,V>{
        Function<? super T,? extends V> function;

    }

    //compareAndSwapObject(Object var1, long var2, Object var3, Object var4)
    //var1:操作的对象，var2:操作的对象属性，var3:var2与var3比较，相等才更新，var4:更新值

    final boolean completeThrowable(Throwable x){
        return UNSAFE.compareAndSwapObject(this,RESULT,null,encodeThrowable(x));
    }

    final boolean casStack(Completion cmp,Completion val){
        return UNSAFE.compareAndSwapObject(this,STACK,cmp,val);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long RESULT;
    private static final long STACK;
    private static final long NEXT;
    static {
        try {
            final sun.misc.Unsafe u;
            UNSAFE = u = sun.misc.Unsafe.getUnsafe();
            Class<?> k = CompletableFuture.class;
            RESULT = u.objectFieldOffset(k.getDeclaredField("result"));
            STACK = u.objectFieldOffset(k.getDeclaredField("stack"));
            NEXT = u.objectFieldOffset(Completion.class.getDeclaredField("next"));
        } catch (Exception x) {
            throw new Error(x);
        }
    }
}
