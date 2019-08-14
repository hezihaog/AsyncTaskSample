package me.zh.asynctask.sample;

import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import java.util.ArrayDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>Package:</b> me.zh.asynctask.sample <br>
 * <b>Create Date:</b> 2019-08-13  16:26 <br>
 * <b>@author:</b> zihe <br>
 * <b>Description:</b>  <br>
 */
public abstract class AsyncTask<Params, Progress, Result> {
    private static final String LOG_TAG = "AsyncTask";

    /**
     * 获得当前CPU的核心数
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    /**
     * 设置线程池的核心线程数2-4之间,但是取决于CPU核数
     */
    private static final int CORE_POOL_SIZE = Math.max(2, Math.min(CPU_COUNT - 1, 4));
    /**
     * 设置线程池的最大线程数为 CPU核数*2+1
     */
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    /**
     * 设置线程池空闲线程存活时间30s
     */
    private static final int KEEP_ALIVE_SECONDS = 30;

    /**
     * 线程工厂，统一创建线程并配置
     */
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            //给每个生成的线程命名
            return new Thread(runnable, "AsyncTask #" + mCount.getAndIncrement());
        }
    };

    /**
     * 任务队列，最大容量为128，就是最多支持128个任务并发
     */
    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    /**
     * 任务线程池，static修饰，所以是类共享，所以多个AsyncTask会共享一个线程池
     */
    public static final Executor THREAD_POOL_EXECUTOR;

    static {
        //定义线程池，使用sPoolWorkQueue作为队列，如果超出任务执行数量，则抛出RejectedExecutionException
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                sPoolWorkQueue, sThreadFactory);
        //允许线程池在没有任务时销毁线程
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        THREAD_POOL_EXECUTOR = threadPoolExecutor;
    }

    /**
     * 串行线程池
     */
    private static final Executor SERIAL_EXECUTOR = new SerialExecutor();

    /**
     * Handler的消息类型Code，发送结果的消息类型
     */
    private static final int MESSAGE_POST_RESULT = 0x1;
    /**
     * Handler的消息类型Code，更新进度的消息类型
     */
    private static final int MESSAGE_POST_PROGRESS = 0x2;

    /**
     * 默认线程池，默认为串行
     */
    private static volatile Executor sDefaultExecutor = SERIAL_EXECUTOR;
    /**
     * 主线程Handler
     */
    private static InternalHandler sHandler;

    /**
     * 任务实现
     */
    private final WorkerRunnable<Params, Result> mWorker;
    /**
     * 任务
     */
    private final FutureTask<Result> mFuture;

    /**
     * 当前的任务状态
     */
    private volatile AsyncTask.Status mStatus = AsyncTask.Status.PENDING;

    /**
     * 是否取消
     */
    private final AtomicBoolean mCancelled = new AtomicBoolean();
    /**
     * 任务是否执行了
     */
    private final AtomicBoolean mTaskInvoked = new AtomicBoolean();

    /**
     * 回调Handler
     */
    private final Handler mHandler;

    /**
     * 顺序执行器，将线程池的执行包裹，所以即使线程池是有容量的线程池，经过SerialExecutor包裹，都是串行执行，相当于单线程执行
     */
    private static class SerialExecutor implements Executor {
        /**
         * 任务队列，让任务串行
         */
        final ArrayDeque<Runnable> mTasks = new ArrayDeque<>();
        /**
         * 当前执行的任务
         */
        Runnable mActive;

        @Override
        public synchronized void execute(@NonNull final Runnable runnable) {
            //创建一个代理任务，包裹真正的任务，再将代理任务进队
            mTasks.offer(new Runnable() {
                @Override
                public void run() {
                    try {
                        runnable.run();
                    } finally {
                        //执行完一个任务后，自动执行下一个任务
                        scheduleNext();
                    }
                }
            });
            //第一次执行，马上执行任务
            if (mActive == null) {
                scheduleNext();
            }
        }

        /**
         * 执行下一个任务
         */
        protected synchronized void scheduleNext() {
            //获取下一个任务，如果有则执行
            if ((mActive = mTasks.poll()) != null) {
                THREAD_POOL_EXECUTOR.execute(mActive);
            }
        }
    }

    /**
     * 运行状态
     */
    public enum Status {
        /**
         * 等待
         */
        PENDING,

        /**
         * 运行
         */
        RUNNING,

        /**
         * 结束
         */
        FINISHED,
    }

    /**
     * 获取主线程Handler
     */
    private static Handler getMainHandler() {
        synchronized (AsyncTask.class) {
            if (sHandler == null) {
                sHandler = new InternalHandler(Looper.getMainLooper());
            }
            return sHandler;
        }
    }

    private Handler getHandler() {
        return mHandler;
    }

    /**
     * 外部配置任务执行器，可外部定制
     *
     * @param exec 执行器
     */
    public static void setDefaultExecutor(Executor exec) {
        sDefaultExecutor = exec;
    }

    //-------------------- AsyncTask构造方法 start --------------------

    /**
     * 空参构造，取主线程的Handler回调
     */
    public AsyncTask() {
        this((Looper) null);
    }

    /**
     * 指定回调的Handler
     */
    public AsyncTask(@Nullable Handler handler) {
        this(handler != null ? handler.getLooper() : null);
    }

    /**
     * 可指定消息轮训器
     *
     * @param callbackLooper 轮训器
     */
    public AsyncTask(@Nullable Looper callbackLooper) {
        //没指定，或者指定为主线程的轮训器，则构造主线程的Handler，否则使用指定的轮训器创建
        mHandler = callbackLooper == null || callbackLooper == Looper.getMainLooper()
                ? getMainHandler()
                : new Handler(callbackLooper);
        //任务的具体执行体，WorkerRunnable实现了Callable接口，就是说可以返回结果的任务
        mWorker = new WorkerRunnable<Params, Result>() {
            @Override
            public Result call() throws Exception {
                //标识任务为已经执行了
                mTaskInvoked.set(true);
                Result result = null;
                try {
                    //设置线程优先级为后台线程，多个线程并发时，无关紧要的线程分配的CPU时间将会减少，有利于主线程的处理
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    //执行任务，并获取执行结果
                    result = doInBackground(mParams);
                    //将进程中未执行的命令，一并送往CPU处理
                    Binder.flushPendingCommands();
                } catch (Throwable throwable) {
                    //抛出了异常，设置任务已经被取消
                    mCancelled.set(true);
                    throw throwable;
                } finally {
                    //发送结果
                    postResult(result);
                }
                //返回结果
                return result;
            }
        };
        //将WorkerRunnable作为FutureTask任务去执行
        mFuture = new FutureTask<Result>(mWorker) {
            @Override
            protected void done() {
                //任务结束，任务结束了，call还没有被调用
                try {
                    postResultIfNotInvoked(get());
                } catch (InterruptedException e) {
                    //被取消时会抛出异常
                    android.util.Log.w(LOG_TAG, e);
                } catch (ExecutionException e) {
                    //执行发生异常异常
                    throw new RuntimeException("An error occurred while executing doInBackground()",
                            e.getCause());
                } catch (CancellationException e) {
                    //取消失败
                    postResultIfNotInvoked(null);
                }
            }
        };
    }

    //-------------------- AsyncTask构造方法 end --------------------

    /**
     * 发送任务执行结果，如果没有发送过则发送
     */
    private void postResultIfNotInvoked(Result result) {
        //这里有点奇怪，这个方法调用在FutureTask的done方法，而mTaskInvoked标志在call()回调时就会设置为true
        //所以后面的if判断一直都是进不去的，感觉这个方法是多余的，除非call方法执行还没到mTaskInvoked设置成功之前就抛出了异常
        //这个if判断才成立
        final boolean wasTaskInvoked = mTaskInvoked.get();
        if (!wasTaskInvoked) {
            postResult(result);
        }
    }

    /**
     * 发送任务执行结果到主线程进行回调
     */
    private Result postResult(Result result) {
        @SuppressWarnings("unchecked")
        Message message = getHandler().obtainMessage(MESSAGE_POST_RESULT,
                new AsyncTaskResult<>(this, result));
        message.sendToTarget();
        return result;
    }

    /**
     * 获取任务运行状态
     */
    public final AsyncTask.Status getStatus() {
        return mStatus;
    }

    /**
     * 执行前回调
     */
    @MainThread
    protected void onPreExecute() {
    }

    /**
     * 任务执行回调（子线程执行）
     *
     * @param params 任务参数
     * @return 任务结果
     */
    @WorkerThread
    protected abstract Result doInBackground(Params... params);

    /**
     * 执行后回调
     *
     * @param result 执行结果
     */
    @SuppressWarnings({"UnusedDeclaration"})
    @MainThread
    protected void onPostExecute(Result result) {
    }

    /**
     * 进度更新
     *
     * @param values 进度
     */
    @SuppressWarnings({"UnusedDeclaration"})
    @MainThread
    protected void onProgressUpdate(Progress... values) {
    }

    /**
     * 任务已经执行完毕了，却发现被取消，则回调，并带上结果
     *
     * @param result 结果
     */
    @SuppressWarnings({"UnusedParameters"})
    @MainThread
    protected void onCancelled(Result result) {
        onCancelled();
    }

    /**
     * 任务未执行完，被取消，则回调
     */
    @MainThread
    protected void onCancelled() {
    }

    /**
     * 是否被取消了
     */
    public final boolean isCancelled() {
        return mCancelled.get();
    }

    /**
     * 取消任务
     *
     * @param mayInterruptIfRunning 是否强制取消，不等待执行完毕后再取消（马上打断）
     */
    public final boolean cancel(boolean mayInterruptIfRunning) {
        //设置任务取消的标志
        mCancelled.set(true);
        return mFuture.cancel(mayInterruptIfRunning);
    }

    /**
     * 获取执行结果
     *
     * @return 执行结果
     */
    public final Result get() throws InterruptedException, ExecutionException {
        return mFuture.get();
    }

    /**
     * 获取结果，并设定超时，如果超时还没获取到结果，则抛出异常
     *
     * @param timeout 超时时间
     * @param unit    超时时间单位
     */
    public final Result get(long timeout, TimeUnit unit) throws InterruptedException,
            ExecutionException, TimeoutException {
        return mFuture.get(timeout, unit);
    }

    /**
     * 执行任务
     *
     * @param params 执行参数
     */
    @MainThread
    public final AsyncTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(sDefaultExecutor, params);
    }

    /**
     * 提供执行器，执行任务
     *
     * @param exec   执行器，可以设置为内部的THREAD_POOL_EXECUTOR来实现并行，否则默认使用串行的执行器
     * @param params 任务参数
     */
    @MainThread
    public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
        //处理状态，RUNNING正在执行不允许再调用执行，FINISHED已经结束了也不允许执行，所以AsyncTask只允许执行一次
        if (mStatus != AsyncTask.Status.PENDING) {
            switch (mStatus) {
                case RUNNING:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task is already running.");
                case FINISHED:
                    throw new IllegalStateException("Cannot execute task:"
                            + " the task has already been executed "
                            + "(a task can be executed only once)");
                default:
                    break;
            }
        }
        //切换状态为运行中
        mStatus = AsyncTask.Status.RUNNING;
        //任务执行前，回调，onPreExecute是直接调用的，所以如果在子线程中调用AsyncTask的execute
        //onPreExecute的回调也是在子线程！
        onPreExecute();
        //配置任务参数
        mWorker.mParams = params;
        //将任务交给线程池执行
        exec.execute(mFuture);
        return this;
    }

    /**
     * 执行Runnable
     */
    @MainThread
    public static void execute(Runnable runnable) {
        sDefaultExecutor.execute(runnable);
    }

    /**
     * 更新进度，需要手动调用
     *
     * @param values 进度
     */
    @WorkerThread
    protected final void publishProgress(Progress... values) {
        //没有取消才更新
        if (!isCancelled()) {
            getHandler().obtainMessage(MESSAGE_POST_PROGRESS,
                    new AsyncTaskResult<>(this, values)).sendToTarget();
        }
    }

    /**
     * 结束任务
     *
     * @param result 结果
     */
    private void finish(Result result) {
        //结束任务时，如果被标记取消，则回调取消
        if (isCancelled()) {
            onCancelled(result);
        } else {
            //获取到结果了，并且没有取消，则回调任务结束
            onPostExecute(result);
        }
        //设置状态为结束
        mStatus = AsyncTask.Status.FINISHED;
    }

    /**
     * 内部Handler，负责从子线程中发送消息会主线程进行方法回调
     */
    private static class InternalHandler extends Handler {
        InternalHandler(Looper looper) {
            super(looper);
        }

        @SuppressWarnings({"unchecked", "RawUseOfParameterizedType"})
        @Override
        public void handleMessage(Message msg) {
            AsyncTaskResult<?> result = (AsyncTaskResult<?>) msg.obj;
            switch (msg.what) {
                //主线程通知，任务结果，获取到结果
                case MESSAGE_POST_RESULT:
                    // There is only one result
                    result.mTask.finish(result.mData[0]);
                    break;
                case MESSAGE_POST_PROGRESS:
                    //更新进度
                    result.mTask.onProgressUpdate(result.mData);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 任务
     *
     * @param <Params> 参数类型
     * @param <Result> 结果类型
     */
    private static abstract class WorkerRunnable<Params, Result> implements Callable<Result> {
        /**
         * 任务参数
         */
        Params[] mParams;
    }

    /**
     * 任务执行结果包裹类，包装结果作为Handler发送的数据
     *
     * @param <Data> 任务结果类型
     */
    @SuppressWarnings({"RawUseOfParameterizedType"})
    private static class AsyncTaskResult<Data> {
        final AsyncTask mTask;
        final Data[] mData;

        /**
         * @param task 任务对象
         * @param data 执行结果
         */
        AsyncTaskResult(AsyncTask task, Data... data) {
            mTask = task;
            mData = data;
        }
    }
}