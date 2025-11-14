#!/bin/bash

# Compare latency between local index and S3 index
# Usage: ./bin/compare-latency.sh [options]
# Example: ./bin/compare-latency.sh -topics collections/msmarco-passage/queries.dev.small.tsv -topicReader TsvInt

set -e

# Default values
TOPICS="collections/msmarco-passage/queries.dev.small.tsv"
TOPIC_READER="TsvInt"
OUTPUT_DIR="runs"
PARALLELISM=4
HITS=1000
BM25_K1=0.82
BM25_B=0.68
LOCAL_INDEX="indexes/msmarco-passage/lucene-index-msmarco"
S3_INDEX=""

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    -topics)
      TOPICS="$2"
      shift 2
      ;;
    -topicReader)
      TOPIC_READER="$2"
      shift 2
      ;;
    -output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    -parallelism)
      PARALLELISM="$2"
      shift 2
      ;;
    -hits)
      HITS="$2"
      shift 2
      ;;
    -bm25.k1)
      BM25_K1="$2"
      shift 2
      ;;
    -bm25.b)
      BM25_B="$2"
      shift 2
      ;;
    -local-index)
      LOCAL_INDEX="$2"
      shift 2
      ;;
    -s3-index)
      S3_INDEX="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: $0 [options]"
      echo ""
      echo "Options:"
      echo "  -topics <file>              Topics file (default: $TOPICS)"
      echo "  -topicReader <reader>        Topic reader (default: $TOPIC_READER)"
      echo "  -output-dir <dir>            Output directory (default: $OUTPUT_DIR)"
      echo "  -parallelism <num>           Number of threads (default: $PARALLELISM)"
      echo "  -hits <num>                  Max hits (default: $HITS)"
      echo "  -bm25.k1 <float>             BM25 k1 parameter (default: $BM25_K1)"
      echo "  -bm25.b <float>              BM25 b parameter (default: $BM25_B)"
      echo "  -local-index <path>          Local index path (default: $LOCAL_INDEX)"
      echo "  -s3-index <s3://path>        S3 index path (required)"
      echo ""
      echo "Example:"
      echo "  $0 -s3-index s3://my-bucket/indexes/msmarco-passage/lucene-index-msmarco"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      echo "Use -h or --help for usage information"
      exit 1
      ;;
  esac
done

if [ -z "$S3_INDEX" ]; then
  echo "Error: -s3-index is required"
  echo "Use -h or --help for usage information"
  exit 1
fi

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

# Create output directory
mkdir -p "$PROJECT_DIR/$OUTPUT_DIR"

# Generate unique output filenames
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOCAL_OUTPUT="$OUTPUT_DIR/run.local.$TIMESTAMP.tsv"
S3_OUTPUT="$OUTPUT_DIR/run.s3.$TIMESTAMP.tsv"

echo "=========================================="
echo "Latency Comparison: Local vs S3 Index"
echo "=========================================="
echo ""
echo "Configuration:"
echo "  Topics: $TOPICS"
echo "  Topic Reader: $TOPIC_READER"
echo "  Parallelism: $PARALLELISM"
echo "  Hits: $HITS"
echo "  BM25 k1: $BM25_K1, b: $BM25_B"
echo "  Local Index: $LOCAL_INDEX"
echo "  S3 Index: $S3_INDEX"
echo ""

# Run local index search
echo "----------------------------------------"
echo "Running LOCAL index search..."
echo "----------------------------------------"
LOCAL_START=$(date +%s.%N)
./bin/run.sh io.anserini.search.SearchCollection \
  -index "$LOCAL_INDEX" \
  -topics "$TOPICS" \
  -topicReader "$TOPIC_READER" \
  -output "$LOCAL_OUTPUT" \
  -format msmarco \
  -parallelism "$PARALLELISM" \
  -bm25 -bm25.k1 "$BM25_K1" -bm25.b "$BM25_B" -hits "$HITS" 2>&1 | tee /tmp/local_search.log
LOCAL_END=$(date +%s.%N)
LOCAL_TIME=$(echo "$LOCAL_END - $LOCAL_START" | bc)

echo ""
echo "Local search completed in: ${LOCAL_TIME} seconds"
echo ""

# Wait a bit between runs
sleep 2

# Run S3 index search
echo "----------------------------------------"
echo "Running S3 index search..."
echo "----------------------------------------"
S3_START=$(date +%s.%N)
./bin/run.sh io.anserini.search.SearchCollection \
  -index "$S3_INDEX" \
  -topics "$TOPICS" \
  -topicReader "$TOPIC_READER" \
  -output "$S3_OUTPUT" \
  -format msmarco \
  -parallelism "$PARALLELISM" \
  -bm25 -bm25.k1 "$BM25_K1" -bm25.b "$BM25_B" -hits "$HITS" 2>&1 | tee /tmp/s3_search.log
S3_END=$(date +%s.%N)
S3_TIME=$(echo "$S3_END - $S3_START" | bc)

echo ""
echo "S3 search completed in: ${S3_TIME} seconds"
echo ""

# Calculate difference
DIFF=$(echo "$S3_TIME - $LOCAL_TIME" | bc)
PERCENT_DIFF=$(echo "scale=2; ($S3_TIME - $LOCAL_TIME) / $LOCAL_TIME * 100" | bc)

# Print comparison
echo "=========================================="
echo "Results Summary"
echo "=========================================="
printf "%-30s %12s\n" "Metric" "Time (seconds)"
echo "----------------------------------------"
printf "%-30s %12.2f\n" "Local Index" "$LOCAL_TIME"
printf "%-30s %12.2f\n" "S3 Index" "$S3_TIME"
printf "%-30s %12.2f\n" "Difference" "$DIFF"
printf "%-30s %12.2f%%\n" "Percent Difference" "$PERCENT_DIFF"
echo "----------------------------------------"
echo ""
echo "Output files:"
echo "  Local: $LOCAL_OUTPUT"
echo "  S3:    $S3_OUTPUT"
echo ""

# Check if results are identical
if [ -f "$LOCAL_OUTPUT" ] && [ -f "$S3_OUTPUT" ]; then
  if diff -q "$LOCAL_OUTPUT" "$S3_OUTPUT" > /dev/null 2>&1; then
    echo "✓ Results are identical"
  else
    echo "⚠ Results differ (this may be expected due to timing/ordering)"
    echo "  Use 'diff $LOCAL_OUTPUT $S3_OUTPUT' to see differences"
  fi
fi

# Extract query-level timing from logs if available
echo ""
echo "For detailed query-level timing, check the logs:"
echo "  Local: /tmp/local_search.log"
echo "  S3:    /tmp/s3_search.log"



