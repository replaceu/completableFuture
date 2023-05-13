package com.carter.threadPool;

import java.util.concurrent.*;
import java.util.function.*;

/**
 * Future<T>: 用于表示异步计算的结果。提供了检查计算是否完成、等待其完成以及取回计算结果的方法。T:get方法返回的结果的类型
 * CompletionStage<T>: 异步计算的某个阶段，前一个阶段完成时，该阶段开始执行操作或计算值。一个阶段在其计算终止时被称作完成，但是这个完成又可能触发其他的从属阶段。
 *   该接口定义了异步计算流程中的不同阶段间的联动操作规范
 * CarterCompletableFuture<T>: 可以显式地完成（设置它的值和状态），可以作为CompletionStage来使用，并且支持在完成时触发依赖函数和操作的一个Future的实现
 * @param <T>
 */
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

        public boolean claim() {
            Executor e=executor;
            /**
             *如果该Completion包含有Executor，那么此函数每次都会返回false。
             *CAS保证函数式接口只被提交一次给Executor
             *如果该Completion没有Executor，那么此函数第一次返回true，之后每次返回false。
             *CAS保证函数式接口只被同步执行一次
             */
            if (compareAndSetForkJoinTaskTag((short)0,(short)1)){
                if (e==null){
                    return true;
                }
                executor=null;
                e.execute(this);
            }
            return false;
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
        //如果executor不为null，说明当前stage是无论如何都需要异步执行的，所以短路后面的ccf.uniApply
        //如果executor为null，说明当前的stage是可以允许被同步执行的，所以要尝试一下ccf.uniApply
        if (executor!=null||!ccf.uniApply(this,function,null)){
            /**
             * 如果 e 不为 null：
             *     如果前一个 stage 已经执行完毕：当前线程在uniApply.tryFire(SYNC)中把接管的当前 stage 转交给 e 执行。
             *     如果前一个 stage 还没执行完毕：当前线程会直接返回，等到执行前一个 stage 的线程来把当前 stage 转交给 e 执行。
             * 如果 e 为 null：
             *     并且前一个 stage 还没执行完毕
             */
            UniApply<T, V> uniApply = new UniApply<T, V>(executor, ccf, this, function);
            push(uniApply);

            //避免此时前一个 stage 已经完成的情况。
            uniApply.tryFire(SYNC);
        }
        return ccf;
    }

    final void push(UniCompletion<?,?> uniCompletion){
        if (uniCompletion!=null){
            while (result==null&&!tryPushStack(uniCompletion)){
                lazySetNext(uniCompletion,null);
            }
        }
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

    static Object encodeThrowable(Throwable x,Object r){
        if (!(x instanceof CompletionException)){
            x=new CompletionException(x);
        }else if (r instanceof AltResult&&x==((AltResult)r).ex){
            return r;
        }
        return new AltResult(x);
    }

    final boolean completeValue(T t){
        return UNSAFE.compareAndSwapObject(this,RESULT,null,t==null?NIL:t);
    }

    static final class UniApply<T,V> extends UniCompletion<T,V>{
        Function<? super T,? extends V> function;
        //source表示前一个stage，dependent代表当前stage，UniApply对象将两个stage组合在一起
        UniApply(Executor executor,CarterCompletableFuture<V> dependent,CarterCompletableFuture<T> source,Function<? super T,? extends V> function){
            super(executor,dependent,source);
            this.function = function;
        }


        @Override
        CarterCompletableFuture<V> tryFire(int mode) {
            CarterCompletableFuture<V> d;
            CarterCompletableFuture<T> a;
            d=dependent;
            a=source;
            //1. 如果dependent为null，说明当前stage已经被执行过了
            //2. 如果uniApply返回false，说明当前线程无法执行当前stage。返回false有可能是因为
            //     1. 前一个stage没执行完呢
            //     2. 前一个stage执行完了，但当前stage已经被别的线程执行了，如果提供了线程池，那么肯定属于被别的线程执行了。
            if (d==null||!d.uniApply(a=source,function,mode>0?null:this)){
                return null;
            }
            //执行到这里，说明dep不为null，而且uniApply返回true，说明当前线程执行了当前stage
            dependent=null;
            source=null;
            function=null;
            return d.postFire(a,mode);
        }

        @Override
        boolean isLive() {
            return false;
        }
    }

    final CarterCompletableFuture<T> postFire(CarterCompletableFuture<?> ccf, int mode) {
        //前一个stage的后续任务还没做完
        if (ccf!=null||ccf.stack!=null){
            //1. mode为NESTED。说明就是postComplete调用过来的，那么只清理一下栈中无效节点即可。
            //2. mode为SYNC或ASYNC，但前一个stage还没执行完。不知道何时发生，因为调用postFire的前提就是前一个stage已经执行完
            if (mode<0||ccf.result==null){
                ccf.cleanStack();
                //3. mode为SYNC或ASYNC，但前一个stage已经执行完了。特殊时序可能发生的，那么帮忙完成前一个stage的的后续任务
            }else {
                ccf.postComplete();
            }
        }
        //当前stage的后续任务还没做完
        if (result!=null&&stack!=null){
            if (mode<0){
                //mode为NESTED。说明就是postComplete调用过来的
                return this;
            }else {
                //mode为SYNC或ASYNC，那么调用postComplete
                postComplete();
            }
        }
        return null;
    }

    final void cleanStack() {
        for (Completion p =null,q=stack;q!=null;){
            Completion s = q.next;
            if (q.isLive()){
                p=q;
                q=s;
            }else if (p==null){
                casStack(q,s);
                q=stack;
            }else {
                p.next = s;
                if (p.isLive()){
                    q = s;
                } else {
                    p = null;  // restart
                    q = stack;
                }
            }
        }
    }

    //this永远是当前stage，a参数永远是前一个stage
    final <S> boolean uniApply(CarterCompletableFuture<S> ccf,Function<? super S,? extends T> function,UniApply<S,T> uniApply){
        Object r;
        Throwable ex;
        //前后两个条件只是优雅的避免空指针异常，实际不可能发生。
        //如果 前一个stage的result为null，说明前一个stage还没执行完毕
        if (ccf==null||(r=ccf.result)==null||function==null){
            return false;
        }
        //执行到这里，说明前一个stage执行完毕
        //如果this即当前stage的result不为null，说当前stage还没执行
        tryComplete:if (result==null){
            //如果前一个stage的执行结果为null或者抛出异常
            if (r instanceof AltResult){
                if (((ex = ((AltResult)r).ex) != null)){
                    //如果前一个stage抛出异常，那么直接让当前stage的执行结果也为这个异常，都不用执行Function了
                    completeThrowable(ex,r);
                    break tryComplete;
                }
                //如果前一个stage的执行结果为null，那么让r变成null
                r=null;
            }
            try {
                //1. uniApply为null，这说明c还没有入栈，没有线程竞争。直接执行当前stage即f.apply(s)
                //2. uniApply不为null，这说明c已经入栈了，有线程竞争执行当前stage
                if (uniApply!=null&& !uniApply.claim()){
                    //claim返回了false，说明当前线程不允许执行当前stage，直接返回
                    return false;
                }
                //claim返回了true，说明当前线程允许接下来执行当前stage
                S s = (S) r;
                completeValue(function.apply(s));
            }catch (Throwable e){
                completeThrowable(e);
            }
        }
        //如果this即当前stage的result不为null，说当前stage已经执行完毕，那么直接返回true
        return true;
    }

    //compareAndSwapObject(Object var1, long var2, Object var3, Object var4)
    //var1:操作的对象，var2:操作的对象属性，var3:var2与var3比较，相等才更新，var4:更新值

    final boolean completeThrowable(Throwable x){
        return UNSAFE.compareAndSwapObject(this,RESULT,null,encodeThrowable(x));
    }

    final boolean completeThrowable(Throwable x,Object r){
        return UNSAFE.compareAndSwapObject(this,RESULT,null,encodeThrowable(x,r));
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
