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

package org.apache.hadoop.fs.s3a.scale;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import org.assertj.core.api.Assertions;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileRange;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.contract.ContractTestUtils;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.hadoop.fs.s3a.S3AFileSystem;
import org.apache.hadoop.fs.s3a.S3ATestUtils;
import org.apache.hadoop.fs.s3a.Statistic;
import org.apache.hadoop.fs.s3a.impl.ProgressListener;
import org.apache.hadoop.fs.s3a.impl.ProgressListenerEvent;
import org.apache.hadoop.fs.s3a.statistics.BlockOutputStreamStatistics;
import org.apache.hadoop.fs.statistics.IOStatistics;
import org.apache.hadoop.io.ElasticByteBufferPool;
import org.apache.hadoop.io.WeakReferencedElasticByteBufferPool;
import org.apache.hadoop.util.DurationInfo;
import org.apache.hadoop.util.Progressable;

import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_BUFFER_SIZE;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_LENGTH;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_READ_POLICY;
import static org.apache.hadoop.fs.Options.OpenFileOptions.FS_OPTION_OPENFILE_READ_POLICY_WHOLE_FILE;
import static org.apache.hadoop.fs.contract.ContractTestUtils.*;
import static org.apache.hadoop.fs.contract.ContractTestUtils.validateVectoredReadResult;
import static org.apache.hadoop.fs.s3a.Constants.*;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.*;
import static org.apache.hadoop.fs.s3a.Statistic.MULTIPART_UPLOAD_COMPLETED;
import static org.apache.hadoop.fs.s3a.Statistic.STREAM_WRITE_BLOCK_UPLOADS_BYTES_PENDING;
import static org.apache.hadoop.fs.statistics.IOStatisticAssertions.assertThatStatisticCounter;
import static org.apache.hadoop.fs.statistics.IOStatisticAssertions.lookupCounterStatistic;
import static org.apache.hadoop.fs.statistics.IOStatisticAssertions.verifyStatisticCounterValue;
import static org.apache.hadoop.fs.statistics.IOStatisticAssertions.verifyStatisticGaugeValue;
import static org.apache.hadoop.fs.statistics.IOStatisticsLogging.ioStatisticsSourceToString;
import static org.apache.hadoop.fs.statistics.IOStatisticsLogging.ioStatisticsToPrettyString;
import static org.apache.hadoop.fs.statistics.StreamStatisticNames.STREAM_WRITE_BLOCK_UPLOADS;
import static org.apache.hadoop.util.functional.RemoteIterators.filteringRemoteIterator;

/**
 * Scale test which creates a huge file.
 * <p>
 * <b>Important:</b> the order in which these tests execute is fixed to
 * alphabetical order. Test cases are numbered {@code test_123_} to impose
 * an ordering based on the numbers.
 * <p>
 * Having this ordering allows the tests to assume that the huge file
 * exists. Even so: they should all have a {@link #assumeHugeFileExists()}
 * check at the start, in case an individual test is executed.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public abstract class AbstractSTestS3AHugeFiles extends S3AScaleTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(
      AbstractSTestS3AHugeFiles.class);
  public static final int DEFAULT_UPLOAD_BLOCKSIZE = 128 * _1KB;

  private Path scaleTestDir;
  private Path hugefile;
  private Path hugefileRenamed;

  private int uploadBlockSize = DEFAULT_UPLOAD_BLOCKSIZE;
  private int partitionSize;
  private long filesize;

  @Override
  public void setup() throws Exception {
    super.setup();
    scaleTestDir = new Path(getTestPath(), getTestSuiteName());
    hugefile = new Path(scaleTestDir, "src/hugefile");
    hugefileRenamed = new Path(scaleTestDir, "dest/hugefile");
    uploadBlockSize = uploadBlockSize();
    filesize = getTestPropertyBytes(getConf(), KEY_HUGE_FILESIZE,
        DEFAULT_HUGE_FILESIZE);
  }

  /**
   * Test dir deletion is removed from test case teardown so the
   * subsequent tests see the output.
   * @throws IOException failure
   */
  @Override
  protected void deleteTestDirInTeardown() throws IOException {
    /* no-op */
  }

  /**
   * Get the name of this test suite, which is used in path generation.
   * Base implementation uses {@link #getBlockOutputBufferName()} for this.
   * @return the name of the suite.
   */
  public String getTestSuiteName() {
    return getBlockOutputBufferName();
  }

  /**
   * Note that this can get called before test setup.
   * @return the configuration to use.
   */
  @Override
  protected Configuration createScaleConfiguration() {
    Configuration conf = super.createScaleConfiguration();
    partitionSize = (int) getTestPropertyBytes(conf,
        KEY_HUGE_PARTITION_SIZE,
        DEFAULT_HUGE_PARTITION_SIZE);
    Assertions.assertThat(partitionSize)
        .describedAs("Partition size set in " + KEY_HUGE_PARTITION_SIZE)
        .isGreaterThanOrEqualTo(MULTIPART_MIN_SIZE);
    removeBaseAndBucketOverrides(conf,
        SOCKET_SEND_BUFFER,
        SOCKET_RECV_BUFFER,
        MIN_MULTIPART_THRESHOLD,
        MULTIPART_SIZE,
        USER_AGENT_PREFIX,
        FAST_UPLOAD_BUFFER);

    conf.setLong(SOCKET_SEND_BUFFER, _1MB);
    conf.setLong(SOCKET_RECV_BUFFER, _1MB);
    conf.setLong(MIN_MULTIPART_THRESHOLD, partitionSize);
    conf.setInt(MULTIPART_SIZE, partitionSize);
    conf.setInt(AWS_S3_VECTOR_ACTIVE_RANGE_READS, 32);
    conf.set(USER_AGENT_PREFIX, "STestS3AHugeFileCreate");
    conf.set(FAST_UPLOAD_BUFFER, getBlockOutputBufferName());
    S3ATestUtils.disableFilesystemCaching(conf);
    return conf;
  }

  /**
   * The name of the buffering mechanism to use.
   * @return a buffering mechanism
   */
  protected abstract String getBlockOutputBufferName();

  @Test
  public void test_010_CreateHugeFile() throws IOException {

    long filesizeMB = filesize / _1MB;

    // clean up from any previous attempts
    deleteHugeFile();

    Path fileToCreate = getPathOfFileToCreate();
    describe("Creating file %s of size %d MB" +
            " with partition size %d buffered by %s",
        fileToCreate, filesizeMB, partitionSize, getBlockOutputBufferName());

    // now do a check of available upload time, with a pessimistic bandwidth
    // (that of remote upload tests). If the test times out then not only is
    // the test outcome lost, as the follow-on tests continue, they will
    // overlap with the ongoing upload test, for much confusion.
    int timeout = getTestTimeoutSeconds();
    // assume 1 MB/s upload bandwidth
    int bandwidth = _1MB;
    long uploadTime = filesize / bandwidth;
    assertTrue(String.format("Timeout set in %s seconds is too low;" +
            " estimating upload time of %d seconds at 1 MB/s." +
            " Rerun tests with -D%s=%d",
        timeout, uploadTime, KEY_TEST_TIMEOUT, uploadTime * 2),
        uploadTime < timeout);
    assertEquals("File size set in " + KEY_HUGE_FILESIZE + " = " + filesize
            + " is not a multiple of " + uploadBlockSize,
        0, filesize % uploadBlockSize);

    byte[] data = new byte[uploadBlockSize];
    for (int i = 0; i < uploadBlockSize; i++) {
      data[i] = (byte) (i % 256);
    }

    long blocks = filesize / uploadBlockSize;
    long blocksPerMB = _1MB / uploadBlockSize;

    // perform the upload.
    // there's lots of logging here, so that a tail -f on the output log
    // can give a view of what is happening.
    S3AFileSystem fs = getFileSystem();
    IOStatistics iostats = fs.getIOStatistics();

    String putRequests = Statistic.OBJECT_PUT_REQUESTS.getSymbol();
    String multipartBlockUploads = Statistic.MULTIPART_UPLOAD_PART_PUT.getSymbol();
    String putBytes = Statistic.OBJECT_PUT_BYTES.getSymbol();
    Statistic putRequestsActive = Statistic.OBJECT_PUT_REQUESTS_ACTIVE;
    Statistic putBytesPending = Statistic.OBJECT_PUT_BYTES_PENDING;

    ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();
    BlockOutputStreamStatistics streamStatistics;
    long blocksPer10MB = blocksPerMB * 10;
    ProgressCallback progress = new ProgressCallback(timer);
    try (FSDataOutputStream out = fs.create(fileToCreate,
        true,
        uploadBlockSize,
        progress)) {
      streamStatistics = requireNonNull(getOutputStreamStatistics(out),
          () -> "No iostatistics in " + out);

      for (long block = 1; block <= blocks; block++) {
        out.write(data);
        long written = block * uploadBlockSize;
        // every 10 MB and on file upload @ 100%, print some stats
        if (block % blocksPer10MB == 0 || written == filesize) {
          long percentage = written * 100 / filesize;
          double elapsedTime = timer.elapsedTime() / 1.0e9;
          double writtenMB = 1.0 * written / _1MB;
          LOG.info(String.format("[%02d%%] Buffered %.2f MB out of %d MB;" +
                  " PUT %d bytes (%d pending) in %d operations (%d active);" +
                  " elapsedTime=%.2fs; write to buffer bandwidth=%.2f MB/s",
              percentage,
              writtenMB,
              filesizeMB,
              iostats.counters().get(putBytes),
              gaugeValue(putBytesPending),
              iostats.counters().get(putRequests),
              gaugeValue(putRequestsActive),
              elapsedTime,
              writtenMB / elapsedTime));
        }
      }
      if (!expectMultipartUpload()) {
        // it is required that no data has uploaded at this point on a
        // non-multipart upload
        Assertions.assertThat(progress.getUploadEvents())
            .describedAs("upload events in %s", progress)
            .isEqualTo(0);
      }
      // now close the file
      LOG.info("Closing stream {}", out);
      LOG.info("Statistics : {}", streamStatistics);
      ContractTestUtils.NanoTimer closeTimer
          = new ContractTestUtils.NanoTimer();
      out.close();
      closeTimer.end("time to close() output stream");
    }

    timer.end("time to write %d MB in blocks of %d",
        filesizeMB, uploadBlockSize);
    logFSState();
    bandwidth(timer, filesize);

    final IOStatistics streamIOstats = streamStatistics.getIOStatistics();
    LOG.info("Stream IOStatistics after stream closed: {}",
        ioStatisticsToPrettyString(streamIOstats));

    LOG.info("FileSystem IOStatistics after upload: {}",
        ioStatisticsToPrettyString(iostats));
    final String requestKey;
    long putByteCount = lookupCounterStatistic(iostats, putBytes);
    long putRequestCount;

    if (expectMultipartUpload()) {
      requestKey = multipartBlockUploads;
      putRequestCount = lookupCounterStatistic(streamIOstats, requestKey);
      assertThatStatisticCounter(streamIOstats, multipartBlockUploads)
          .isGreaterThanOrEqualTo(1);
      verifyStatisticCounterValue(streamIOstats, STREAM_WRITE_BLOCK_UPLOADS, putRequestCount);
      // non-magic uploads will have completed
      verifyStatisticCounterValue(streamIOstats, MULTIPART_UPLOAD_COMPLETED.getSymbol(),
          expectImmediateFileVisibility() ? 1 : 0);
    } else {
      // single put
      requestKey = putRequests;
      putRequestCount = lookupCounterStatistic(streamIOstats, requestKey);
      verifyStatisticCounterValue(streamIOstats, putRequests, 1);
      verifyStatisticCounterValue(streamIOstats, STREAM_WRITE_BLOCK_UPLOADS, 1);
      verifyStatisticCounterValue(streamIOstats, MULTIPART_UPLOAD_COMPLETED.getSymbol(), 0);
    }
    Assertions.assertThat(putByteCount)
        .describedAs("%s count from stream stats %s",
            putBytes, streamStatistics)
        .isGreaterThan(0);

    LOG.info("PUT {} bytes in {} operations; {} MB/operation",
        putByteCount, putRequestCount,
        putByteCount / (putRequestCount * _1MB));
    LOG.info("Time per PUT {} nS",
        toHuman(timer.nanosPerOperation(putRequestCount)));
    verifyStatisticGaugeValue(iostats, putRequestsActive.getSymbol(), 0);
    verifyStatisticGaugeValue(iostats, STREAM_WRITE_BLOCK_UPLOADS_BYTES_PENDING.getSymbol(), 0);

    progress.verifyNoFailures(
        "Put file " + fileToCreate + " of size " + filesize);
    assertEquals("actively allocated blocks in " + streamStatistics,
        0, streamStatistics.getBlocksActivelyAllocated());
  }

  /**
   * Get the path of the file which is to created. This is normally
   * {@link #hugefile}
   * @return the path to use when creating the file.
   */
  protected Path getPathOfFileToCreate() {
    return this.hugefile;
  }

  protected Path getScaleTestDir() {
    return scaleTestDir;
  }

  protected Path getHugefile() {
    return hugefile;
  }

  public void setHugefile(Path hugefile) {
    this.hugefile = hugefile;
  }

  protected Path getHugefileRenamed() {
    return hugefileRenamed;
  }

  public int getUploadBlockSize() {
    return uploadBlockSize;
  }

  /**
   * Get the desired upload block size for this test run.
   * @return the block size
   */
  protected int uploadBlockSize() {
    return DEFAULT_UPLOAD_BLOCKSIZE;
  }

  /**
   * Get the size of the file.
   * @return file size
   */
  public long getFilesize() {
    return filesize;
  }

  /**
   * Is this expected to be a multipart upload?
   * Assertions will change if not.
   * @return what the filesystem expects.
   */
  protected boolean expectMultipartUpload() {
    return getFileSystem().getS3AInternals().isMultipartCopyEnabled();
  }

  /**
   * Is this expected to be a normal file creation with
   * the output immediately visible?
   * Assertions will change if not.
   * @return true by default.
   */
  protected boolean expectImmediateFileVisibility() {
    return true;
  }

  protected int getPartitionSize() {
    return partitionSize;
  }

  /**
   * Progress callback.
   */
  private final class ProgressCallback implements Progressable, ProgressListener {
    private AtomicLong bytesTransferred = new AtomicLong(0);
    private AtomicLong uploadEvents = new AtomicLong(0);
    private AtomicInteger failures = new AtomicInteger(0);
    private final ContractTestUtils.NanoTimer timer;

    private ProgressCallback(NanoTimer timer) {
      this.timer = timer;
    }

    @Override
    public void progress() {
    }

    @Override
    public void progressChanged(ProgressListenerEvent eventType, long transferredBytes) {

      switch (eventType) {
      case TRANSFER_PART_FAILED_EVENT:
        // failure
        failures.incrementAndGet();
        LOG.warn("Transfer failure");
        break;
      case TRANSFER_PART_COMPLETED_EVENT:
        // completion
        bytesTransferred.addAndGet(transferredBytes);
        long elapsedTime = timer.elapsedTime();
        double elapsedTimeS = elapsedTime / 1.0e9;
        long written = bytesTransferred.get();
        long writtenMB = written / _1MB;
        LOG.info(String.format(
            "Event %s; total uploaded=%d MB in %.1fs;" +
                " effective upload bandwidth = %.2f MB/s",
            eventType,
            writtenMB, elapsedTimeS, writtenMB / elapsedTimeS));
        break;
      case REQUEST_BYTE_TRANSFER_EVENT:
        uploadEvents.incrementAndGet();
        break;
      default:
        // nothing
        break;
      }
    }

    public String toString() {
      String sb = "ProgressCallback{"
          + "bytesTransferred=" + bytesTransferred.get() +
          ", uploadEvents=" + uploadEvents.get() +
          ", failures=" + failures.get() +
          '}';
      return sb;
    }

    /**
     * Get the number of bytes transferred.
     * @return byte count
     */
    private long getBytesTransferred() {
      return bytesTransferred.get();
    }

    /**
     * Get the number of event callbacks.
     * @return count of byte transferred events.
     */
    private long getUploadEvents() {
      return uploadEvents.get();
    }

    private void verifyNoFailures(String operation) {
      assertEquals("Failures in " + operation + ": " + this, 0, failures.get());
    }
  }

  /**
   * Assume that the huge file exists; skip the test if it does not.
   * @throws IOException IO failure
   */
  void assumeHugeFileExists() throws IOException {
    assumeFileExists(this.hugefile);
  }

  /**
   * Assume a specific file exists.
   * @param file file to look for
   * @throws IOException IO problem
   */
  private void assumeFileExists(Path file) throws IOException {
    S3AFileSystem fs = getFileSystem();
    ContractTestUtils.assertPathExists(fs, "huge file not created",
        file);
    FileStatus status = fs.getFileStatus(file);
    ContractTestUtils.assertIsFile(file, status);
    assertTrue("File " + file + " is empty", status.getLen() > 0);
  }

  private void logFSState() {
    LOG.info("File System state after operation:\n{}", getFileSystem());
  }

  /**
   * This is the set of actions to perform when verifying the file actually
   * was created. With the S3A committer, the file doesn't come into
   * existence; a different set of assertions must be checked.
   */
  @Test
  public void test_030_postCreationAssertions() throws Throwable {
    S3AFileSystem fs = getFileSystem();
    ContractTestUtils.assertPathExists(fs, "Huge file", hugefile);
    FileStatus status = fs.getFileStatus(hugefile);
    ContractTestUtils.assertIsFile(hugefile, status);
    LOG.info("Huge File Status: {}", status);
    assertEquals("File size in " + status, filesize, status.getLen());

    // now do some etag status checks asserting they are always the same
    // across listing operations.
    final Path path = hugefile;
    final FileStatus listStatus = listFile(hugefile);
    LOG.info("List File Status: {}", listStatus);

    Assertions.assertThat(listStatus.getLen())
        .describedAs("List file status length %s", listStatus)
        .isEqualTo(filesize);
    Assertions.assertThat(etag(listStatus))
        .describedAs("List file status etag %s", listStatus)
        .isEqualTo(etag(status));
  }

  /**
   * Get a filestatus by listing the parent directory.
   * @param path path
   * @return status
   * @throws IOException failure to read, file not found
   */
  private FileStatus listFile(final Path path)
      throws IOException {
    try {
      return filteringRemoteIterator(
          getFileSystem().listStatusIterator(path.getParent()),
          st -> st.getPath().equals(path))
          .next();
    } catch (NoSuchElementException e) {
      throw (FileNotFoundException)(new FileNotFoundException("Not found: " + path)
          .initCause(e));
    }
  }

  /**
   * Read in the file using Positioned read(offset) calls.
   * @throws Throwable failure
   */
  @Test
  public void test_040_PositionedReadHugeFile() throws Throwable {
    assumeHugeFileExists();
    final String encryption = getConf().getTrimmed(
        Constants.S3_ENCRYPTION_ALGORITHM);
    boolean encrypted = encryption != null;
    if (encrypted) {
      LOG.info("File is encrypted with algorithm {}", encryption);
    }
    String filetype = encrypted ? "encrypted file" : "file";
    describe("Positioned reads of %s %s", filetype, hugefile);
    S3AFileSystem fs = getFileSystem();
    FileStatus status = listFile(hugefile);
    long size = status.getLen();
    int ops = 0;
    final int bufferSize = 8192;
    byte[] buffer = new byte[bufferSize];
    long eof = size - 1;

    ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();
    ContractTestUtils.NanoTimer readAtByte0, readAtByte0Again, readAtEOF;
    try (FSDataInputStream in = fs.openFile(hugefile)
        .withFileStatus(status)
        .opt(FS_OPTION_OPENFILE_READ_POLICY, "random")
        .opt(FS_OPTION_OPENFILE_BUFFER_SIZE, uploadBlockSize)
        .build().get()) {
      readAtByte0 = new ContractTestUtils.NanoTimer();
      in.readFully(0, buffer);
      readAtByte0.end("time to read data at start of file");
      ops++;

      readAtEOF = new ContractTestUtils.NanoTimer();
      in.readFully(eof - bufferSize, buffer);
      readAtEOF.end("time to read data at end of file");
      ops++;

      readAtByte0Again = new ContractTestUtils.NanoTimer();
      in.readFully(0, buffer);
      readAtByte0Again.end("time to read data at start of file again");
      ops++;
      LOG.info("Final stream state: {}", in);
    }
    long mb = Math.max(size / _1MB, 1);

    logFSState();
    timer.end("time to perform positioned reads of %s of %d MB ",
        filetype, mb);
    LOG.info("Time per positioned read = {} nS",
        toHuman(timer.nanosPerOperation(ops)));
  }

  /**
   * Should this test suite use direct buffers for
   * the Vector IO operations?
   * @return true if direct buffers are desired.
   */
  protected boolean isDirectVectorBuffer() {
    return false;
  }

  @Test
  public void test_045_vectoredIOHugeFile() throws Throwable {
    assumeHugeFileExists();
    final ElasticByteBufferPool pool =
              new WeakReferencedElasticByteBufferPool();
    boolean direct = isDirectVectorBuffer();
    IntFunction<ByteBuffer> allocate = size -> pool.getBuffer(direct, size);

    // build a list of ranges for both reads.
    final int rangeLength = 116770;
    long base = 1520861;
    long pos = base;
    List<FileRange> rangeList = range(pos, rangeLength);
    pos += rangeLength;
    range(rangeList, pos, rangeLength);
    pos += rangeLength;
    range(rangeList, pos, rangeLength);
    pos += rangeLength;
    range(rangeList, pos, rangeLength);
    pos += rangeLength;
    range(rangeList, pos, rangeLength);
    pos += rangeLength;
    range(rangeList, pos, rangeLength);

    FileSystem fs = getFileSystem();

    final int validateSize = (int) totalReadSize(rangeList);

    // read the same ranges using readFully into a buffer.
    // this is to both validate the range resolution logic,
    // and to compare performance of sequential GET requests
    // with the vector IO.
    byte[] readFullRes = new byte[validateSize];
    IOStatistics readIOStats, vectorIOStats;
    DurationInfo readFullyTime = new DurationInfo(LOG, true, "Sequential read of %,d bytes",
        validateSize);
    try (FSDataInputStream in = fs.openFile(hugefile)
        .optLong(FS_OPTION_OPENFILE_LENGTH, filesize)
        .opt(FS_OPTION_OPENFILE_READ_POLICY, "random")
        .opt(FS_OPTION_OPENFILE_BUFFER_SIZE, uploadBlockSize)
        .build().get()) {
      for (FileRange range : rangeList) {
        in.readFully(range.getOffset(),
            readFullRes,
            (int)(range.getOffset() - base),
            range.getLength());
      }
      readIOStats = in.getIOStatistics();
    } finally {
      readFullyTime.close();
    }

    // now do a vector IO read
    DurationInfo vectorTime = new DurationInfo(LOG, true, "Vector Read");
    try (FSDataInputStream in = fs.openFile(hugefile)
        .optLong(FS_OPTION_OPENFILE_LENGTH, filesize)
        .opt(FS_OPTION_OPENFILE_READ_POLICY, "vector, random")
        .build().get()) {
      // initiate the read.
      in.readVectored(rangeList, allocate);
      // Wait for the results and compare with read fully.
      validateVectoredReadResult(rangeList, readFullRes, base);
      vectorIOStats = in.getIOStatistics();
    } finally {
      vectorTime.close();
      // release the pool
      pool.release();
    }

    final Duration readFullyDuration = readFullyTime.asDuration();
    final Duration vectorDuration = vectorTime.asDuration();
    final Duration diff = readFullyDuration.minus(vectorDuration);
    double ratio = readFullyDuration.toNanos() / (double) vectorDuration.toNanos();
    String format = String.format("Vector read to %s buffer taking %s was %s faster than"
            + " readFully() (%s); ratio=%,.2fX",
        direct ? "direct" : "heap",
        vectorDuration, diff, readFullyDuration, ratio);
    LOG.info(format);
    LOG.info("Bulk read IOStatistics={}", ioStatisticsToPrettyString(readIOStats));
    LOG.info("Vector IOStatistics={}", ioStatisticsToPrettyString(vectorIOStats));
  }

  /**
   * Read in the entire file using read() calls.
   * @throws Throwable failure
   */
  @Test
  public void test_050_readHugeFile() throws Throwable {
    assumeHugeFileExists();
    describe("Reading %s", hugefile);
    S3AFileSystem fs = getFileSystem();
    FileStatus status = fs.getFileStatus(hugefile);
    long size = status.getLen();
    long blocks = size / uploadBlockSize;
    byte[] data = new byte[uploadBlockSize];

    ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();
    try (FSDataInputStream in = fs.openFile(hugefile)
        .withFileStatus(status)
        .opt(FS_OPTION_OPENFILE_BUFFER_SIZE, uploadBlockSize)
        .opt(FS_OPTION_OPENFILE_READ_POLICY, FS_OPTION_OPENFILE_READ_POLICY_WHOLE_FILE)
        .build().get();
         DurationInfo ignored = new DurationInfo(LOG, "Vector Read")) {
      for (long block = 0; block < blocks; block++) {
        in.readFully(data);
      }
      LOG.info("Final stream state: {}", in);
    }

    long mb = Math.max(size / _1MB, 1);
    timer.end("time to read file of %d MB ", mb);
    LOG.info("Time per MB to read = {} nS",
        toHuman(timer.nanosPerOperation(mb)));
    bandwidth(timer, size);
    logFSState();
  }

  /**
   * Test to verify source file encryption key.
   * @throws IOException
   */
  @Test
  public void test_090_verifyRenameSourceEncryption() throws IOException {
    if(isEncrypted(getFileSystem())) {
      assertEncrypted(getHugefile());
    }
  }

  protected void assertEncrypted(Path hugeFile) throws IOException {
    //Concrete classes will have implementation.
  }

  /**
   * Checks if the encryption is enabled for the file system.
   * @param fileSystem
   * @return
   */
  protected boolean isEncrypted(S3AFileSystem fileSystem) {
    return false;
  }

  @Test
  public void test_100_renameHugeFile() throws Throwable {
    assumeHugeFileExists();
    describe("renaming %s to %s", hugefile, hugefileRenamed);
    S3AFileSystem fs = getFileSystem();
    FileStatus status = fs.getFileStatus(hugefile);
    long size = status.getLen();
    ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();
    renameFile(hugefile, hugefileRenamed);
    long mb = Math.max(size / _1MB, 1);
    timer.end("time to rename file of %d MB", mb);
    LOG.info("Time per MB to rename = {} nS",
        toHuman(timer.nanosPerOperation(mb)));
    bandwidth(timer, size);
    assertPathExists("renamed file", hugefileRenamed);
    logFSState();
    FileStatus destFileStatus = fs.getFileStatus(hugefileRenamed);
    assertEquals(size, destFileStatus.getLen());

    // rename back
    ContractTestUtils.NanoTimer timer2 = new ContractTestUtils.NanoTimer();
    renameFile(hugefileRenamed, hugefile);

    timer2.end("Renaming back");
    LOG.info("Time per MB to rename = {} nS",
        toHuman(timer2.nanosPerOperation(mb)));
    bandwidth(timer2, size);
  }

  /**
   * Rename a file.
   * Subclasses may do this differently.
   * @param src source file
   * @param dest dest file
   * @throws IOException IO failure
   */
  protected void renameFile(final Path src,
      final Path dest) throws IOException {
    final S3AFileSystem fs = getFileSystem();
    fs.delete(dest, false);
    final boolean renamed = fs.rename(src, dest);
    Assertions.assertThat(renamed)
        .describedAs("rename(%s, %s)", src, dest)
        .isTrue();
  }

  /**
   * Test to verify target file encryption key.
   * @throws IOException
   */
  @Test
  public void test_110_verifyRenameDestEncryption() throws IOException {
    if(isEncrypted(getFileSystem())) {
      /**
       * Using hugeFile again as hugeFileRenamed is renamed back
       * to hugeFile.
       */
      assertEncrypted(hugefile);
    }
  }
  /**
   * Cleanup: delete the files.
   */
  @Test
  public void test_800_DeleteHugeFiles() throws IOException {
    try {
      deleteHugeFile();
      delete(hugefileRenamed, false);
    } finally {
      ContractTestUtils.rm(getFileSystem(), getTestPath(), true, false);
    }
  }

  /**
   * After all the work, dump the statistics.
   */
  @Test
  public void test_900_dumpStats() {
    LOG.info("Statistics\n{}", ioStatisticsSourceToString(getFileSystem()));
  }

  protected void deleteHugeFile() throws IOException {
    delete(hugefile, false);
  }

  /**
   * Delete any file, time how long it took.
   * @param path path to delete
   * @param recursive recursive flag
   */
  protected void delete(Path path, boolean recursive) throws IOException {
    describe("Deleting %s", path);
    ContractTestUtils.NanoTimer timer = new ContractTestUtils.NanoTimer();
    getFileSystem().delete(path, recursive);
    timer.end("time to delete %s", path);
  }



}
