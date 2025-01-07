package org.apache.lucene.util;

import static java.util.concurrent.CompletableFuture.runAsync;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.vectroid.SmallTaskWithResourcesExecutor;
import org.junit.Test;

public class TestSmallTaskWithResourcesExecutor extends LuceneTestCase {

  @Test
  public void test() throws Exception {
    int[] counter = {0};
    try (SmallTaskWithResourcesExecutor<String> executor = new SmallTaskWithResourcesExecutor<>(2, 2, 1,
        "test-thread-", () -> "res" + counter[0]++)) {
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean started = new AtomicBoolean();
      for (int i = 0; i < 3; i++) {
        // queue per worker is 2, but we have to add 3 tasks as the first one will be taken from the queue immediately.
        int finalI = i;
        executor.submit(res -> {
          started.set(true);
          assertEquals("res0", res);
          assertEquals("test-thread-0", Thread.currentThread().getName());
          latch.await();
          System.out.println("Task " + finalI + " with res " + res + " done");
        });
        // wait until the first task started to make sure all the three tasks in this loop go to the first worker.
        while (!started.get()) {
          //noinspection BusyWait
          Thread.sleep(1);
        }
      }
      for (int i = 0; i < 3; i++) {
        int finalI = i;
        executor.submit(res -> {
          assertEquals("res1", res);
          assertEquals("test-thread-1", Thread.currentThread().getName());
          latch.await();
          System.out.println("Task " + finalI + " with res " + res + " done");
        });
      }

      CompletableFuture<Void> f = runAsync(() ->
          executor.submit(res -> {
          }));

      Thread.sleep(10);
      assertFalse(f.isDone());

      latch.countDown();
      f.get();
      executor.shutdownAndWait();

      assertEquals("already shut down",
        assertThrows(IllegalStateException.class, () -> executor.submit(res -> {})).getMessage());
    }
  }

  @Test
  public void testShutdownWithNoTasks() {
    SmallTaskWithResourcesExecutor<String> executor = new SmallTaskWithResourcesExecutor<>(10, 100, 1, "foo-", () -> null);
    executor.shutdownAndWait();
  }
}