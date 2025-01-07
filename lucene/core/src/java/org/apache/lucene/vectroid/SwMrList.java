package org.apache.lucene.vectroid;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.ListIterator;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.RamUsageEstimator;

/// A single-writer multiple-reader list with limited functionality and unbounded concurrently
/// growable capacity.
///
/// Unlike [java.util.ArrayList], it's concurrently growable, that is, if more underlying capacity
/// is needed, it is expanded in a thread-safe way. Unlike a true concurrent list, not other
/// operation is synchronized. No happens-before edge exists between `set(N)` and `get(N)`. This is
/// akin to a plain Java array.
///
/// Element access is wait-free, unless the internal array needs to grow. Growing affects only
/// threads also needing to grow the array; it doesn't block any readers, it also doesn't block
/// writers writing to already allocated parts of the array. Use `growthStepExp` to trade between
/// potential overallocation and contention.
///
/// The above characteristics are peculiar, but they should be exactly what
/// [org.apache.lucene.util.hnsw.OnHeapHnswGraph] needs for its `graph` field.
public class SwMrList<E> extends AbstractList<E> {

  private static final long SHALLOW_SIZE = RamUsageEstimator.shallowSizeOfInstance(SwMrList.class);

  private final int growthStepExp;
  private final int offsetMask;

  private volatile Object[][] elements;
  private volatile long subElementsShallowSize;
  private volatile int size;

  public SwMrList(int growthStepExp) {
    elements = new Object[64][];
    this.growthStepExp = growthStepExp;
    this.offsetMask = (1 << growthStepExp) - 1;
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  public boolean add(E e) {
    set(size, e);
    return true;
  }

  @SuppressWarnings("unchecked")
  @Override
  public E set(int index, E value) {
    if (size <= index) {
      size = index + 1;
    }
    int subIndex = index >> growthStepExp;
    int subOffset = index & offsetMask;

    Object[][] elementsLocal = elements;
    if (elementsLocal.length <= subIndex) {
      synchronized (this) {
        if ((elementsLocal = elements).length <= subIndex) {
          elementsLocal = elements = ArrayUtil.growExact(elements, subIndex + 1);
        }
      }
    }

    Object[] subArray = elementsLocal[subIndex];
    if (subArray == null) {
      synchronized (this) {
        if ((subArray = (elementsLocal = elements)[subIndex]) == null) {
          subArray = elementsLocal[subIndex] = new Object[1 << growthStepExp];
          subElementsShallowSize += RamUsageEstimator.shallowSizeOf(subArray);
        }
      }
    }

    try {
      return (E) subArray[subOffset];
    } finally {
      subArray[subOffset] = value;
    }
  }

  /// Return the value at `index`.
  @SuppressWarnings("unchecked")
  @Override
  public E get(int index) {
    int subIndex = index >> growthStepExp;
    int subOffset = index & offsetMask;
    Object[][] elementsLocal = elements;
    if (elementsLocal.length <= subIndex) {
      return null;
    }
    Object[] subArray = elementsLocal[subIndex];
    return subArray == null ? null : (E) subArray[subOffset];
  }

  public long ramBytesUsedShallow() {
    return SHALLOW_SIZE + RamUsageEstimator.shallowSizeOf(elements) + subElementsShallowSize;
  }

  /// Thread safety: iterators are guaranteed to see items added/modified before the iterator was
  /// created, those added/modified later might or might not be seen.
  @Override
  public Iterator<E> iterator() {
    return new Itr();
  }

  @Override
  public ListIterator<E> listIterator(int index) {
    throw new UnsupportedOperationException();
  }

  private class Itr implements Iterator<E> {
    private final int size;
    private int cursor;

    public Itr() {
      // Save size to our own copy. We don't need to check the volatile field, we don't care about
      // items added after this iterator was created.
      this.size = size();
    }

    @Override
    public boolean hasNext() {
      return cursor < size;
    }

    @Override
    public E next() {
      return get(cursor++);
    }
  }
}
