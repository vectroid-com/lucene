package org.apache.lucene.util;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.vectroid.SwMrList;

public class TestSwMrList extends LuceneTestCase {

  public void testConcurrentStress() throws Exception {
    final int NUM_ELEMENTS = 1_000_000;
    final int NUM_THREADS = 16;

    int[] indices = new int[NUM_ELEMENTS];
    Arrays.parallelSetAll(indices, i -> i);
    // Partially shuffle the indices
    for (int i = indices.length - 1; i > 0; i--) {
      int j = random().nextInt(Math.max(0, i - 100),i + 1);
      int temp = indices[i];
      indices[i] = indices[j];
      indices[j] = temp;
    }

    // Use small growth step to increase contention
    final SwMrList<Integer> array = new SwMrList<>(6);

    final int elementsPerThread = NUM_ELEMENTS / NUM_THREADS;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch doneLatch = new CountDownLatch(NUM_THREADS);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    Thread[] threads = new Thread[NUM_THREADS];
    for (int t = 0; t < NUM_THREADS; t++) {
      final int startIdx = t * elementsPerThread;
      final int endIdx = (t == NUM_THREADS - 1) ? NUM_ELEMENTS : (t + 1) * elementsPerThread;

      threads[t] = new Thread(() -> {
        try {
          startLatch.await();

          // Each thread processes its portion of the shuffled indices
          for (int i = startIdx; i < endIdx; i++) {
            int index = indices[i];
            array.set(index, index);
          }
        } catch (Throwable e) {
          error.set(e);
        } finally {
          doneLatch.countDown();
        }
      });
      threads[t].start();
    }

    startLatch.countDown();
    doneLatch.await();

    if (error.get() != null) {
      throw new AssertionError("Thread encountered error", error.get());
    }

    // Verify all values are correct
    for (int i = 0; i < NUM_ELEMENTS; i++) {
      assertEquals(Integer.valueOf(i), array.get(i));
    }
  }
}