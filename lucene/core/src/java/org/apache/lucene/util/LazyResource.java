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

import static java.util.Objects.requireNonNull;
import static org.apache.lucene.util.VectroidLuceneUtil.sneakyThrow;

import java.io.Closeable;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A utility wrapping a {@link Closeable} resource, delaying its initialization until first needed.
 * You supply a resource factory, the resource is actually created when {@link #get()} is called for
 * the first time. The {@link #get()} method will throw the creation exception, even though no
 * exception is declared.
 *
 * <p>To close the resource, call this class' {@link #close()}, don't call <code>get().close()
 * </code>, because that will initialize the resource just to close it.
 *
 * <p>The class is thread-safe, the resource will be created at most once.
 *
 * @param <T> The resource type
 */
public class LazyResource<T extends Closeable> implements Closeable {
  private volatile Callable<T> resourceFactory;
  private volatile T resource;
  private final Lock lock = new ReentrantLock();

  public LazyResource(Callable<T> resourceFactory) {
    this.resourceFactory = requireNonNull(resourceFactory);
  }

  public T get() {
    T res;
    if ((res = resource) == null) {
      lock.lock();
      try {
        if ((res = resource) == null) {
          if (resourceFactory == null) {
            throw new RuntimeException("resource already closed");
          }
          try {
            this.resource = res = requireNonNull(resourceFactory.call());
            // allow GC for the supplier
            this.resourceFactory = null;
          } catch (Exception e) {
            throw sneakyThrow(e);
          }
        }
      } finally {
        lock.unlock();
      }
    }
    return res;
  }

  @Override
  public void close() {
    lock.lock();
    try {
      this.resourceFactory = null;
      try {
        if (resource != null) {
          resource.close();
        }
      } catch (Exception e) {
        throw sneakyThrow(e);
      } finally {
        resource = null;
      }
    } finally {
      lock.unlock();
    }
  }
}
