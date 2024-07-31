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

package org.apache.hadoop.fs.s3a;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.s3a.api.RequestFactory;
import org.apache.hadoop.fs.s3a.impl.ClientManager;
import org.apache.hadoop.fs.s3a.impl.MultiObjectDeleteException;
import org.apache.hadoop.fs.s3a.impl.StoreContext;
import org.apache.hadoop.fs.s3a.statistics.S3AStatisticsContext;
import org.apache.hadoop.fs.statistics.DurationTrackerFactory;
import org.apache.hadoop.fs.statistics.IOStatisticsSource;

/**
 * Interface for the S3A Store;
 * S3 client interactions should be via this; mocking
 * is possible for unit tests.
 * <p>
 * The {@link ClientManager} interface is used to create the AWS clients;
 * the base implementation forwards to the implementation of this interface
 * passed in at construction time.
 */
@InterfaceAudience.LimitedPrivate("Extensions")
@InterfaceStability.Unstable
public interface S3AStore extends IOStatisticsSource, ClientManager {

  /**
   * Acquire write capacity for operations.
   * This should be done within retry loops.
   * @param capacity capacity to acquire.
   * @return time spent waiting for output.
   */
  Duration acquireWriteCapacity(int capacity);

  /**
   * Acquire read capacity for operations.
   * This should be done within retry loops.
   * @param capacity capacity to acquire.
   * @return time spent waiting for output.
   */
  Duration acquireReadCapacity(int capacity);

  StoreContext getStoreContext();

  DurationTrackerFactory getDurationTrackerFactory();

  S3AStatisticsContext getStatisticsContext();

  RequestFactory getRequestFactory();

  ClientManager clientManager();

  /**
   * Perform a bulk object delete operation against S3.
   * Increments the {@code OBJECT_DELETE_REQUESTS} and write
   * operation statistics
   * <p>
   * {@code OBJECT_DELETE_OBJECTS} is updated with the actual number
   * of objects deleted in the request.
   * <p>
   * Retry policy: retry untranslated; delete considered idempotent.
   * If the request is throttled, this is logged in the throttle statistics,
   * with the counter set to the number of keys, rather than the number
   * of invocations of the delete operation.
   * This is because S3 considers each key as one mutating operation on
   * the store when updating its load counters on a specific partition
   * of an S3 bucket.
   * If only the request was measured, this operation would under-report.
   * A write capacity will be requested proportional to the number of keys
   * preset in the request and will be re-requested during retries such that
   * retries throttle better. If the request is throttled, the time spent is
   * recorded in a duration IOStat named {@code STORE_IO_RATE_LIMITED_DURATION}.
   * @param deleteRequest keys to delete on the s3-backend
   * @return the AWS response
   * @throws MultiObjectDeleteException one or more of the keys could not
   * be deleted.
   * @throws SdkException amazon-layer failure.
   * @throws IOException IO problems.
   */
  @Retries.RetryRaw
  Map.Entry<Duration, DeleteObjectsResponse> deleteObjects(DeleteObjectsRequest deleteRequest)
      throws MultiObjectDeleteException, SdkException, IOException;

  /**
   * Delete an object.
   * Increments the {@code OBJECT_DELETE_REQUESTS} statistics.
   * <p>
   * Retry policy: retry untranslated; delete considered idempotent.
   * 404 errors other than bucket not found are swallowed;
   * this can be raised by third party stores (GCS).
   * <p>
   * A write capacity of 1 ( as it is signle object delete) will be requested before
   * the delete call and will be re-requested during retries such that
   * retries throttle better. If the request is throttled, the time spent is
   * recorded in a duration IOStat named {@code STORE_IO_RATE_LIMITED_DURATION}.
   * If an exception is caught and swallowed, the response will be empty;
   * otherwise it will be the response from the delete operation.
   * @param request request to make
   * @return the total duration and response.
   * @throws SdkException problems working with S3
   * @throws IllegalArgumentException if the request was rejected due to
   * a mistaken attempt to delete the root directory.
   */
  @Retries.RetryRaw
  Map.Entry<Duration, Optional<DeleteObjectResponse>> deleteObject(
      DeleteObjectRequest request) throws SdkException;

}
