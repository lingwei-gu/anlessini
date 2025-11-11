package io.anlessini;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.anlessini.store.S3Directory;
import io.anserini.index.IndexArgs;
import io.anserini.search.query.BagOfWordsQueryGenerator;
import io.anserini.search.topicreader.TopicReader;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.*;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LocalSearchCollection<K> implements Closeable {
  private static final Logger LOG = LogManager.getLogger(LocalSearchCollection.class);

  public static class Args {
    @Option(name = "-index", required = true, usage = "Path to index (local path or s3://bucket/key)")
    public String index;

    @Option(name = "-topics", metaVar = "[file]", handler = StringArrayOptionHandler.class, required = true, usage = "topics file")
    public String[] topics;

    @Option(name = "-topic.reader", required = true, usage = "TopicReader to use.")
    public String topicReader;

    @Option(name = "-output", metaVar = "[file]", required = true, usage = "Output run file.")
    public String output;

    @Option(name = "-threads", metaVar = "[Number]", usage = "Number of Threads")
    public int threads = 1;

    @Option(name = "-topic.fields", handler = StringArrayOptionHandler.class, usage = "Which field of the query should be used, default \"title\"." +
        " For TREC ad hoc topics, description or narrative can be used.")
    public String[] topicFields = new String[]{"title"};

    @Option(name = "-hits", metaVar = "[number]", usage = "max number of hits to return")
    public int hits = 1000;

    @Option(name = "-bm25", usage = "Use BM25 similarity")
    public boolean bm25 = false;

    @Option(name = "-bm25.k1", metaVar = "[number]", usage = "BM25: k1 parameter")
    public float bm25k1 = 0.9f;

    @Option(name = "-bm25.b", metaVar = "[number]", usage = "BM25: b parameter")
    public float bm25b = 0.4f;

    @Option(name = "-remove.duplicates", usage = "Remove duplicate docids when writing final run output.")
    public Boolean removeDuplicates = false;

    @Option(name = "-strip.segment.id", usage = "Remove the .XXXXX suffix used to denote different segments from an document")
    public Boolean stripSegmentId = false;

    @Option(name = "-report.interval", metaVar = "[number]", usage = "The number of queries processed in between report log messages.")
    public int reportInterval = 200;

    @Option(name = "-runtag", metaVar = "[tag]", usage = "runtag")
    public String runtag = "anlessini";

    @Option(name = "-format", usage = "Output format (default: trec)")
    public String format = "trec";

    @Option(name = "-parallelism", metaVar = "[number]", usage = "Number of parallel threads (alias for -threads)")
    public void setParallelism(int parallelism) {
      this.threads = parallelism;
    }
  }

  private final Args args;
  private final IndexReader reader;
  private final Analyzer analyzer;
  private final Similarity similarity;
  private final SortedMap<K, Map<String, String>> topics;
  private final AtomicLong processedQueries = new AtomicLong();
  private final PrintWriter out;

  @SuppressWarnings("unchecked")
  public LocalSearchCollection(Args args) throws IOException {
    this.args = args;
    this.analyzer = new EnglishAnalyzer();
    
    // Open index - support both local and S3 paths
    Directory directory;
    if (args.index.startsWith("s3://")) {
      LOG.info("Opening S3 index: " + args.index);
      String[] parts = parseS3Path(args.index);
      String bucket = parts[0];
      String key = parts[1];
      AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
      directory = new S3Directory(s3Client, bucket, key);
    } else {
      LOG.info("Opening local index: " + args.index);
      directory = FSDirectory.open(Paths.get(args.index));
    }
    this.reader = DirectoryReader.open(directory);

    // Set similarity
    if (args.bm25) {
      this.similarity = new BM25Similarity(args.bm25k1, args.bm25b);
    } else {
      this.similarity = new BM25Similarity(args.bm25k1, args.bm25b); // Default to BM25
    }

    // Load topics
    this.topics = new TreeMap<>();
    for (String topicsFile : args.topics) {
      Path path = Paths.get(topicsFile);
      if (!Files.exists(path) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
        throw new IllegalArgumentException("Topics file " + path + " does not exist or is not a (readable) file.");
      }
      try {
        TopicReader<K> tr = (TopicReader<K>) Class.forName("io.anserini.search.topicreader." + args.topicReader + "TopicReader")
            .getConstructor(Path.class).newInstance(path);
        this.topics.putAll(tr.read());
      } catch (Exception e) {
        throw new IllegalArgumentException("Unable to load topic " + path + " using topic reader " + args.topicReader, e);
      }
    }

    this.out = new PrintWriter(Files.newBufferedWriter(Paths.get(args.output), StandardCharsets.US_ASCII));
  }

  private static String[] parseS3Path(String s3Path) {
    if (!s3Path.startsWith("s3://")) {
      throw new IllegalArgumentException("Invalid S3 path: " + s3Path);
    }
    String path = s3Path.substring(5); // Remove "s3://"
    int firstSlash = path.indexOf('/');
    if (firstSlash == -1) {
      return new String[]{path, ""};
    }
    String bucket = path.substring(0, firstSlash);
    String key = path.substring(firstSlash + 1);
    return new String[]{bucket, key};
  }

  public void runTopics() {
    final long start = System.nanoTime();
    final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(args.threads);
    final IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setSimilarity(similarity);
    searcher.setQueryCache(null); // disable query caching

    final Sort BREAK_SCORE_TIES_BY_DOCID =
        new Sort(SortField.FIELD_SCORE, new SortField(IndexArgs.ID, SortField.Type.STRING_VAL));

    for (Map.Entry<K, Map<String, String>> topicEntry : topics.entrySet()) {
      final K qid = topicEntry.getKey();
      final Map<String, String> fieldValues = topicEntry.getValue();
      executor.execute(() -> {
        try {
          StringBuilder sb = new StringBuilder();
          for (String field : args.topicFields) {
            String value = fieldValues.get(field.trim());
            if (value != null) {
              sb.append(" ").append(value);
            }
          }
          String queryString = sb.toString().trim();

          Query query = new BagOfWordsQueryGenerator().buildQuery(IndexArgs.CONTENTS, analyzer, queryString);
          TopDocs topDocs = searcher.search(query, args.hits, BREAK_SCORE_TIES_BY_DOCID, true);

          Set<String> docids = new HashSet<>();
          int rank = 1;
          StringBuilder buf = new StringBuilder();
          
          for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            org.apache.lucene.document.Document doc = reader.document(scoreDoc.doc);
            String docid = doc.get(IndexArgs.ID);
            
            if (args.stripSegmentId) {
              docid = docid.split("\\.")[0];
            }

            if (args.removeDuplicates) {
              if (docids.contains(docid)) {
                continue;
              } else {
                docids.add(docid);
              }
            }

            if ("msmarco".equals(args.format)) {
              buf.append(String.format(Locale.US, "%s\t%s\t%d\n", qid, docid, rank));
            } else {
              buf.append(String.format(Locale.US, "%s Q0 %s %d %f %s\n",
                  qid, docid, rank, scoreDoc.score, args.runtag));
            }

            rank++;
          }
          
          synchronized (out) {
            out.print(buf.toString());
          }
          
          long processed = processedQueries.incrementAndGet();
          if (processed % args.reportInterval == 0) {
            LOG.info(String.format("%d queries processed", processed));
          }
        } catch (IOException e) {
          throw new RuntimeException("Error processing query " + qid, e);
        }
      });
    }

    executor.shutdown();

    try {
      // Wait for existing tasks to terminate
      while (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
        LOG.info(String.format("%d queries processed", processedQueries.get()));
      }
    } catch (InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      executor.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }

    out.flush();
    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    LOG.info(processedQueries.get() + " topics processed in " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
  }

  @Override
  public void close() throws IOException {
    out.close();
    reader.close();
  }

  public static void main(String[] args) throws Exception {
    Args searchArgs = new Args();
    CmdLineParser parser = new CmdLineParser(searchArgs, ParserProperties.defaults().withUsageWidth(100));

    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.err.println("Example: LocalSearchCollection" + parser.printExample(OptionHandlerFilter.REQUIRED));
      return;
    }

    final long start = System.nanoTime();
    LocalSearchCollection searcher = new LocalSearchCollection(searchArgs);
    searcher.runTopics();
    searcher.close();
    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    LOG.info("Total run time: " + DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"));
  }
}

