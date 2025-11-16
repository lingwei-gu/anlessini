package io.anlessini.store;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.BufferedIndexInput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class S3IndexInput extends BufferedIndexInput {
  private static final Logger LOG = LogManager.getLogger(S3IndexInput.class);
  /**
   * The size of the buffer used by BufferedIndexInput, default to 512 KB
   * Increased to better match 4MB cache blocks and reduce number of requests
   */
  private static final int DEFAULT_BUFFER_SIZE = 512 * 1024; // 512KB
  
  /**
   * Enable caching only during query execution, not during index opening
   */
  private static volatile boolean cachingEnabled = false;
  
  /**
   * Locks to prevent concurrent downloads of the same block
   */
  private static final ConcurrentHashMap<S3FileBlock, Object> downloadLocks = new ConcurrentHashMap<>();
  
  /**
   * Enable caching (call this after index is opened, before queries)
   */
  public static void enableCaching() {
    cachingEnabled = true;
    LOG.info("S3IndexInput caching enabled");
  }
  
  /**
   * Disable caching (call this during index opening)
   */
  public static void disableCaching() {
    cachingEnabled = false;
    LOG.info("S3IndexInput caching disabled");
  }

  public static class ReadStats {
    public final AtomicLong readTotal = new AtomicLong();

    public final AtomicLong readFromS3 = new AtomicLong();
  }

  public static final ReadStats stats = new ReadStats();

  private final AmazonS3 s3Client;
  private final S3ObjectSummary summary;

  /**
   * The start offset in the entire file, non-zero in the slice case
   */
  private final long off;
  /**
   * The end offset
   */
  private final long end;

  public S3IndexInput(AmazonS3 s3Client, S3ObjectSummary summary) {
    this(s3Client, summary, 0, summary.getSize(), defaultBufferSize(summary.getSize()));
  }

  public S3IndexInput(AmazonS3 s3Client, S3ObjectSummary summary, long offset, long length, int bufferSize) {
    super(summary.getBucketName() + "/" + summary.getKey(), bufferSize);
    this.s3Client = s3Client;
    this.summary = summary;
    this.off = offset;
    this.end = offset + length;
    LOG.trace("Opened S3IndexInput " + toString() + "@" + hashCode() + " , bufferSize=" + getBufferSize());
  }

  private static int defaultBufferSize(long fileLength) {
    long bufferSize = fileLength;
    bufferSize = Math.max(bufferSize, MIN_BUFFER_SIZE);
    bufferSize = Math.min(bufferSize, DEFAULT_BUFFER_SIZE);
    return Math.toIntExact(bufferSize);
  }

  @Override
  public void close() throws IOException {
    // no-op
  }

  @Override
  public long length() {
    return end - off;
  }

  @Override
  public S3IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
    if (offset < 0 || length < 0 || offset + length > this.length()) {
      throw new IllegalArgumentException("Slice " + sliceDescription + " out of bounds: " +
          "offset=" + offset + ",length=" + length + ",fileLength=" + this.length() + ": " + toString());
    }
    LOG.trace("[slice][" + toString() + "@" + hashCode() + "] " + getFullSliceDescription(sliceDescription) + ", offset=" + offset + ", length=" + length + ", fileLength=" + this.length());
    return new S3IndexInput(s3Client, summary, off + offset, length, defaultBufferSize(length));
  }

  @Override
  public S3IndexInput clone() {
    S3IndexInput clone = (S3IndexInput) super.clone();
    LOG.trace("[clone][" + toString() + "@" + hashCode() + "], clone=" + clone.hashCode());
    return clone;
  }

  @Override
  protected void readInternal(ByteBuffer dst) throws IOException {
    final int length = dst.remaining();
    final long startPos = getFilePointer() + this.off;

    LOG.debug("[readInternal][" + summary.getKey() + "] Reading @ " + startPos + ":" + length + " bytes");

    if (startPos + length > end) {
      throw new EOFException("reading past EOF: " + toString() + "@" + hashCode());
    }

    // If caching is disabled (e.g., during index opening), do direct read
    if (!cachingEnabled) {
      readDirect(dst, startPos, length);
      return;
    }

    // Use cache: determine which blocks are needed
    PriorityQueue<S3FileBlock> blocks = S3FileBlock.of(summary, startPos, length);
    S3BlockCache cache = S3BlockCache.getInstance();
    long bytesRead = 0;
    long readEnd = startPos + length;
    
    while (!blocks.isEmpty() && bytesRead < length) {
      S3FileBlock block = blocks.poll();
      
      // Check cache first
      byte[] blockData = cache.getBlock(block);
      
      if (blockData == null) {
        // Cache miss - synchronize to prevent duplicate downloads
        Object lock = downloadLocks.computeIfAbsent(block, k -> new Object());
        synchronized (lock) {
          // Double-check cache after acquiring lock (another thread might have cached it)
          blockData = cache.getBlock(block);
          
          if (blockData == null) {
            // Cache miss - download the block
            LOG.debug("[readFromS3][" + summary.getKey() + "] Cache miss for block " + block.blockIndex + ", downloading");
            long downloadStart = System.currentTimeMillis();
            
            GetObjectRequest rangeRequest = new GetObjectRequest(summary.getBucketName(), summary.getKey())
                .withRange(block.offset, block.offset + block.length() - 1);
            
            try {
              S3Object object = s3Client.getObject(rangeRequest);
              blockData = new byte[block.length()];
              int bytesReadFromS3 = IOUtils.read(object.getObjectContent(), blockData);
              object.close();
              
              if (bytesReadFromS3 != block.length()) {
                throw new IOException("Expected " + block.length() + " bytes but read " + bytesReadFromS3);
              }
              
              // Cache the block
              cache.cacheBlock(block, blockData);
              
              stats.readFromS3.addAndGet(block.length());
              
              long downloadTime = System.currentTimeMillis() - downloadStart;
              LOG.debug("[readFromS3] Downloaded block " + block.blockIndex + " (" + block.length() + " bytes) in " + 
                       downloadTime + " ms (" + (block.length() * 1000.0 / downloadTime / 1024 / 1024) + " MB/s)");
            } catch (Exception e) {
              long downloadTime = System.currentTimeMillis() - downloadStart;
              LOG.error("[readFromS3] Failed to download block " + block.blockIndex + " after " + downloadTime + " ms", e);
              throw e;
            } finally {
              // Remove lock after download completes
              downloadLocks.remove(block);
            }
          } else {
            LOG.trace("[readFromS3][" + summary.getKey() + "] Block " + block.blockIndex + " was cached by another thread");
          }
        }
      } else {
        LOG.trace("[readFromS3][" + summary.getKey() + "] Cache hit for block " + block.blockIndex);
      }
      
      // Determine the overlap between the read request and this block
      long blockEnd = block.offset + block.length();
      long copyStart = Math.max(startPos, block.offset);
      long copyEnd = Math.min(readEnd, blockEnd);
      long bytesToCopy = copyEnd - copyStart;
      
      if (bytesToCopy > 0) {
        // Calculate offset within the block
        int blockOffset = (int)(copyStart - block.offset);
        dst.put(blockData, blockOffset, (int)bytesToCopy);
        bytesRead += bytesToCopy;
      }
    }
    
    stats.readTotal.addAndGet(length);
    
    if (bytesRead != length) {
      throw new IOException("Expected to read " + length + " bytes but read " + bytesRead);
    }
  }
  
  /**
   * Direct S3 read without caching (used when caching is disabled)
   */
  private void readDirect(ByteBuffer dst, long startPos, int length) throws IOException {
    LOG.debug("[readFromS3][" + summary.getKey() + "] Direct read (caching disabled) @" + startPos + ":" + length + " bytes");
    long downloadStart = System.currentTimeMillis();
    
    GetObjectRequest rangeRequest = new GetObjectRequest(summary.getBucketName(), summary.getKey())
        .withRange(startPos, startPos + length - 1);
    
    try {
      S3Object object = s3Client.getObject(rangeRequest);
      byte[] buffer = new byte[length];
      int bytesReadFromS3 = IOUtils.read(object.getObjectContent(), buffer);
      object.close();
      
      if (bytesReadFromS3 != length) {
        throw new IOException("Expected " + length + " bytes but read " + bytesReadFromS3);
      }
      
      dst.put(buffer);
      stats.readFromS3.addAndGet(length);
      stats.readTotal.addAndGet(length);
      
      long downloadTime = System.currentTimeMillis() - downloadStart;
      LOG.debug("[readFromS3] Downloaded " + length + " bytes (direct) in " + downloadTime + " ms (" + 
               (length * 1000.0 / downloadTime / 1024 / 1024) + " MB/s)");
    } catch (Exception e) {
      long downloadTime = System.currentTimeMillis() - downloadStart;
      LOG.error("[readFromS3] Failed to download direct read @" + startPos + ":" + length + 
                " after " + downloadTime + " ms", e);
      throw e;
    }
  }

  @Override
  protected void seekInternal(long pos) throws IOException {
    if (pos > length()) {
      throw new EOFException("read past EOF: pos=" + pos + ", length=" + length() + ": " + toString() + "@" + hashCode());
    }
  }

  public static void logStats() {
    LOG.trace("Total bytes read from S3: " + stats.readFromS3.get()
        + ", total bytes read: " + stats.readTotal.get());
  }

  public static void clearStats() {
    stats.readTotal.set(0);
    stats.readFromS3.set(0);
  }
}
