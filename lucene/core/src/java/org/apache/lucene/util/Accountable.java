/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util;

import java.util.Collection;
import java.util.Collections;

/**
 * An object whose RAM usage can be computed.
 *
 * @lucene.internal
 */
public interface Accountable {

  ThreadLocal<Boolean> forHard = new InheritableThreadLocal<>();

  /** Return the memory usage of this object in bytes. Negative values are illegal. */
  long ramBytesUsed();

  /**
   * Get the RAM bytes used for the per-thread hard limit, see {@link
   * org.apache.lucene.index.IndexWriterConfig#setRAMPerThreadHardLimitMB(int)}.
   * <p>
   * This limit is described to avoid overflowing a 32-bit integer somewhere. By testing we know that it doesn't happen for
   * vector-related data, which are dominant in our case (Vectroid). Therefore we want to exclude those bytes from the
   * calculation if evaluating the per-thread limit. We cannot remove this limit altogether, as it applies to other
   * parts of indexing.
   * <p>
   * This method sets the thread-local flag {@link #forHard} for the duration of the call and calls {@link
   * #ramBytesUsed()}. Implementations wishing to be excluded can check this flag and return 0. This assumes that the
   * work isn't distributed to other threads. Per my measurement, to build 1M vectors, the calculation takes around
   * 100ns, so I hope there's no point to parallelize.
   * <p>
   * See also <a href="https://lists.apache.org/thread/tntgcjh0hlrhm2fr6y6d4r83vf5mq6wh">this mailing list thread.</a>
   */
  default long ramBytesUsedHard() {
    forHard.set(true);
    try {
      return ramBytesUsed();
    } finally {
      forHard.remove();
    }
  }

  /**
   * Returns nested resources of this class. The result should be a point-in-time snapshot (to avoid
   * race conditions).
   *
   * @see Accountables
   */
  default Collection<Accountable> getChildResources() {
    return Collections.emptyList();
  }

  /** An accountable that always returns 0 */
  Accountable NULL_ACCOUNTABLE = () -> 0;
}
