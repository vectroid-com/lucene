package org.apache.lucene.vectroid;

import static java.util.Objects.requireNonNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.lucene.util.VectroidLuceneUtil.ConsumerEx;
import org.apache.lucene.util.VectroidLuceneUtil.SupplierEx;

/// VECTROID
///
/// An executor that starts with a single thread, and gradually adds more workers if the queues for the existing workers
/// are full. Workers are never removed if new tasks cease, they're terminated when the executor is shut down.
///
/// It's designed for many tiny tasks. One task queue per worker is used to minimize contention for high number of
/// tasks. All methods are single-threaded. The [#submit] method blocks if queues are full. This provides back-pressure
/// and ensures saturation of workers. Workers are also added only if there's sufficient influx of work - if the influx
/// is slower than the processing, there will be only one worker, saving on resources and context switching.
///
/// Additionally, it supports per-worker resources - the constructor takes resource factory, and the tasks receive
/// thread-local copy when called.
///
/// On error in any of the tasks the executor is terminated. Subsequent call to [#submit] or
/// [#shutdownAndWait()] will re-throw the error. Therefore, one executor instance should be used
/// for one umbrella task split into many tiny tasks.
public class SmallTaskWithResourcesExecutor<R> implements Closeable {

  private final int maxNumThreads;
  private final int queuePerWorker;
  private final Supplier<R> workerResourcesFactory;
  private final long backoffNs;

  private final Consumer<R> sentinel = r -> {};
  private final List<Queue<Consumer<R>>> queues = new ArrayList<>();
  private final List<Thread> workers = new ArrayList<>();
  private int lastWorker = -1;
  private final AtomicReference<Throwable> firstError = new AtomicReference<>();
  private final Thread.Builder threadBuilder;

  public SmallTaskWithResourcesExecutor(
      int maxNumThreads,
      int queuePerWorker,
      long backoffNs,
      String namePrefix,
      SupplierEx<R> workerResourcesFactory
  ) {
    if (maxNumThreads <= 0) {
      throw new IllegalArgumentException("maxNumThreads must be >0; got " + maxNumThreads);
    }
    if (queuePerWorker <= 0) {
      throw new IllegalArgumentException("tasksPerWorker must be >0; got " + queuePerWorker);
    }
    if (backoffNs < 0) {
      throw new IllegalArgumentException("backoffNs must be >=0; got " + backoffNs);
    }
    this.maxNumThreads = maxNumThreads;
    this.queuePerWorker = queuePerWorker;
    this.backoffNs = backoffNs;
    this.workerResourcesFactory = requireNonNull(workerResourcesFactory);
    this.threadBuilder = Thread.ofPlatform().name(namePrefix, 0);
  }

  /// Submit a task. Must be called from a single thread. Might block if all workers have full queues.
  public void submit(ConsumerEx<R> task) {
    if (queues.size() != workers.size()) {
      throw new IllegalStateException("already shut down");
    }
    checkError();

    for (;;) {
      // initially, or if we tried all queues and they're full, add one more worker or sleep
      if (lastWorker == -1) {
        if (workers.size() < maxNumThreads) {
          Queue<Consumer<R>> queue = new ArrayBlockingQueue<>(queuePerWorker);
          queues.add(queue);
          R resources = workerResourcesFactory.get();
          workers.add(threadBuilder.start(() -> taskRunner(resources, queue)));
          // set the new worker as the next to get work
          lastWorker = workers.size() - 1;
        } else {
          // We have reached the maximum number of workers, let's wait a while for some work to finish, and try again.
          LockSupport.parkNanos(backoffNs);
          checkError();
          lastWorker = 0;
        }
      }

      // try to add the task to next free worker
      int origLastWorker = lastWorker;
      do {
        if (queues.get(lastWorker).offer(task)) {
          // success, return
          return;
        }
        lastWorker++;
        if (lastWorker == workers.size()) {
          lastWorker = 0;
        }
      } while (origLastWorker != lastWorker);

      lastWorker = -1;
    }
  }

  private void checkError() {
    if (firstError.get() != null) {
      throw new RuntimeException("One of the workers failed", firstError.get());
    }
  }

  private void taskRunner(R resources, Queue<Consumer<R>> queue) {
    try {
      while (firstError.get() == null) {
        for (Consumer<R> task; (task = queue.poll()) != null; ) {
          if (task == sentinel) {
            // end of task stream
            return;
          }
          task.accept(resources);
        }

        LockSupport.parkNanos(backoffNs);
      }
    } catch (Throwable t) {
      firstError.compareAndSet(null, t);
    }
  }

  public void shutdownAndWait() {
    for (Queue<Consumer<R>> queue : queues) {
      while (!queue.offer(sentinel)) {
        LockSupport.parkNanos(backoffNs);
        checkError();
      }
    }
    queues.clear(); // this disrupts future calls to submit()
    for (Thread worker : workers) {
      do {
        try {
          worker.join(1000);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        checkError();
      } while (worker.isAlive());
    }
  }

  /// Interrupts all tasks, prevent submission of any new tasks, blocks until all workers terminate
  /// and returns. Doesn't re-throw any potential exception in the workers.
  @Override
  public void close() throws IOException {
    queues.clear(); // this disrupts future calls to submit()
    for (Thread worker : workers) {
      firstError.compareAndSet(null, new Exception("executor closed"));
      try {
        worker.interrupt();
        worker.join();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
