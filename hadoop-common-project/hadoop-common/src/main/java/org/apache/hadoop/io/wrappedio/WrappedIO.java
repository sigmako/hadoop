/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.io.wrappedio;

import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.BulkDelete;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import static org.apache.hadoop.util.functional.FunctionalIO.uncheckIOExceptions;

/**
 * Reflection-friendly access to APIs which are not available in
 * some of the older Hadoop versions which libraries still
 * compile against.
 * <p>
 * The intent is to avoid the need for complex reflection operations
 * including wrapping of parameter classes, direct instantiation of
 * new classes etc.
 */
@InterfaceAudience.Public
@InterfaceStability.Unstable
public final class WrappedIO {

  private WrappedIO() {
  }

  /**
   * Get the maximum number of objects/files to delete in a single request.
   * @param fs filesystem
   * @param path path to delete under.
   * @return a number greater than or equal to zero.
   * @throws UnsupportedOperationException bulk delete under that path is not supported.
   * @throws IllegalArgumentException path not valid.
   * @throws UncheckedIOException if an IOE was raised.
   */
  public static int bulkDelete_pageSize(FileSystem fs, Path path) {

    return uncheckIOExceptions(() -> {
      try (BulkDelete bulk = fs.createBulkDelete(path)) {
        return bulk.pageSize();
      }
    });
  }

  /**
   * Delete a list of files/objects.
   * <ul>
   *   <li>Files must be under the path provided in {@code base}.</li>
   *   <li>The size of the list must be equal to or less than the page size.</li>
   *   <li>Directories are not supported; the outcome of attempting to delete
   *       directories is undefined (ignored; undetected, listed as failures...).</li>
   *   <li>The operation is not atomic.</li>
   *   <li>The operation is treated as idempotent: network failures may
   *        trigger resubmission of the request -any new objects created under a
   *        path in the list may then be deleted.</li>
   *    <li>There is no guarantee that any parent directories exist after this call.
   *    </li>
   * </ul>
   * @param fs filesystem
   * @param base path to delete under.
   * @param paths list of paths which must be absolute and under the base path.
   * @return a list of all the paths which couldn't be deleted for a reason other than "not found" and any associated error message.
   * @throws UnsupportedOperationException bulk delete under that path is not supported.
   * @throws UncheckedIOException if an IOE was raised.
   * @throws IllegalArgumentException if a path argument is invalid.
   */
  public static List<Map.Entry<Path, String>> bulkDelete_delete(FileSystem fs,
      Path base,
      Collection<Path> paths) {

    return uncheckIOExceptions(() -> {
      try (BulkDelete bulk = fs.createBulkDelete(base)) {
        return bulk.bulkDelete(paths);
      }
    });
  }
}
