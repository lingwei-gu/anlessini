package io.anlessini.store;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.store.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class S3Directory extends BaseDirectory {
  private static final Logger LOG = LogManager.getLogger(S3Directory.class);

  private final AmazonS3 s3Client;

  private Map<String, S3ObjectSummary> objectSummaries;

  private final String bucket;
  private final String key;

  private final Lock lsLock = new ReentrantLock();

  public S3Directory(AmazonS3 s3Client, String bucket, String key) {
    super(new SingleInstanceLockFactory());
    this.s3Client = s3Client;
    this.bucket = bucket;
    this.key = key;

    LOG.info("Opened S3Directory - Full S3 path: s3://" + bucket + "/" + key);
    LOG.info("S3Directory - Bucket: " + bucket + ", Key: " + key);
  }

  @Override
  public String[] listAll() {
    lsLock.lock();
    if (objectSummaries == null) { // only ls if has not already done so, otherwise use cached result
      objectSummaries = new HashMap<>();
      String prefix = key + "/";
      LOG.info("S3Directory.listAll() - Querying S3 with prefix: " + prefix + " in bucket: " + bucket);
      String listingCursor = null;
      ListObjectsV2Result result;
      int totalObjects = 0;
      do {
        ListObjectsV2Request req = new ListObjectsV2Request()
            .withBucketName(bucket)
            .withPrefix(prefix)
            .withStartAfter(listingCursor);
        result = s3Client.listObjectsV2(req);
        List<S3ObjectSummary> listings = result.getObjectSummaries();
        LOG.info("S3Directory.listAll() - Found " + listings.size() + " objects in this batch");
        for (S3ObjectSummary objectSummary: listings) {
          String objectKey = objectSummary.getKey();
          LOG.debug("S3Directory.listAll() - Full S3 object key: " + objectKey);
          try {
            // Skip directory markers (keys ending with /)
            if (objectKey.endsWith("/")) {
              LOG.debug("S3Directory.listAll() - Skipping directory marker: " + objectKey);
              continue;
            }
            
            // Extract filename by removing the prefix
            if (!objectKey.startsWith(prefix)) {
              LOG.warn("S3Directory.listAll() - Object key doesn't start with prefix: " + objectKey + " (prefix: " + prefix + ")");
              continue;
            }
            
            String objectName = objectKey.substring(prefix.length());
            if (objectName.isEmpty()) {
              LOG.debug("S3Directory.listAll() - Skipping empty filename for key: " + objectKey);
              continue;
            }
            
            LOG.debug("S3Directory.listAll() - Extracted filename: " + objectName);
            objectSummaries.put(objectName, objectSummary);
            totalObjects++;
          } catch (Exception e) {
            LOG.warn("S3Directory.listAll() - Failed to extract filename from key: " + objectKey + " - " + e.getMessage());
          }
        }
        if (!listings.isEmpty()) {
          listingCursor = listings.get(listings.size() - 1).getKey();
        }
      } while (result.isTruncated());
      LOG.info("S3Directory.listAll() - Total objects found: " + totalObjects);
    }
    lsLock.unlock();

    String[] result = objectSummaries.keySet().toArray(new String[objectSummaries.size()]);
    Arrays.sort(result);
    LOG.info("S3Directory.listAll() - Returning " + result.length + " files: " + Arrays.toString(result));
    return result;
  }

  @Override
  public void deleteFile(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long fileLength(String name) {
    return objectSummaries.get(name).getSize();
  }

  @Override
  public Set<String> getPendingDeletions() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexOutput createOutput(String s, IOContext ioContext) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexOutput createTempOutput(String s, String s1, IOContext ioContext) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void sync(Collection<String> collection) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void syncMetaData() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void rename(String s, String s1) {
    throw new UnsupportedOperationException();
  }

  @Override
  public IndexInput openInput(String name, IOContext context) throws IOException {
    return new S3IndexInput(s3Client, objectSummaries.get(name));
  }

  @Override
  public void close() {
    s3Client.shutdown();
  }
}
