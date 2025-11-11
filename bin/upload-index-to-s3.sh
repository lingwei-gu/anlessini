#!/bin/bash

# Upload a local Lucene index to S3
# Usage: ./bin/upload-index-to-s3.sh <local-index-path> <s3-path>
# Example: ./bin/upload-index-to-s3.sh indexes/msmarco-passage/lucene-index-msmarco s3://my-bucket/indexes/msmarco-passage/lucene-index-msmarco

set -e

if [ $# -ne 2 ]; then
  echo "Usage: $0 <local-index-path> <s3-path>"
  echo "Example: $0 indexes/msmarco-passage/lucene-index-msmarco s3://my-bucket/indexes/msmarco-passage/lucene-index-msmarco"
  exit 1
fi

LOCAL_INDEX="$1"
S3_PATH="$2"

# Validate S3 path format
if [[ ! "$S3_PATH" =~ ^s3:// ]]; then
  echo "Error: S3 path must start with s3://"
  exit 1
fi

# Extract bucket and key from S3 path
S3_PATH_NO_PREFIX="${S3_PATH#s3://}"
BUCKET="${S3_PATH_NO_PREFIX%%/*}"
KEY="${S3_PATH_NO_PREFIX#*/}"

if [ -z "$BUCKET" ]; then
  echo "Error: Invalid S3 path format. Expected: s3://bucket/key"
  exit 1
fi

# Validate local index exists
if [ ! -d "$LOCAL_INDEX" ]; then
  echo "Error: Local index directory does not exist: $LOCAL_INDEX"
  exit 1
fi

echo "Uploading index from $LOCAL_INDEX to $S3_PATH"
echo "Bucket: $BUCKET"
echo "Key prefix: $KEY"

# Upload all files recursively
aws s3 cp "$LOCAL_INDEX" "$S3_PATH" --recursive

echo "Upload complete!"

