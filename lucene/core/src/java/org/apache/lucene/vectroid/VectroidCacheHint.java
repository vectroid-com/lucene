package org.apache.lucene.vectroid;

import org.apache.lucene.store.IOContext;

/// Vectroid-specific hint regarding caching the file in memory.
///
/// The hint only has effect with the custom `CachingDirectory` implementation.
public enum VectroidCacheHint implements IOContext.FileOpenHint {

  /// Directly load the file in memory, if possible.
  CACHE_IN_MEMORY,

  /// An opposite of the above. It's optional, meaning if there's no other [VectroidCacheHint], this
  /// behavior is assumed.
  DO_NOT_CACHE_IN_MEMORY
}
