#!/usr/bin/env bash
set -euo pipefail

# Usage:
# LocalStack:
#   ./make_artifact_bucket.sh --endpoint-url=http://localhost:4566 us-east-1
#
# Real AWS (example region ca-central-1):
#   ./make_artifact_bucket.sh "" ca-central-1
#
# Arg1 = extra aws args (local: endpoint-url, real AWS: empty)
# Arg2 = region (defaults to us-east-1 for LocalStack convenience)

MB_ARGS="${1:-}"                # AWS CLI extra arguments (endpoint for LocalStack)
REGION="${2:-us-east-1}"        # Region to create bucket in (LocalStack default: us-east-1)
BUCKET_FILE="artifact-bucket.txt"

# LocalStack dummy credentials (ignored by real AWS if AWS_PROFILE or IAM role is used)
: "${AWS_ACCESS_KEY_ID:=test}"
: "${AWS_SECRET_ACCESS_KEY:=test}"

# Generate or reuse bucket name (makes script idempotent)
if [[ ! -f "$BUCKET_FILE" ]]; then
  # Create a random suffix to guarantee a globally unique bucket name
  BUCKET_ID="$(dd if=/dev/random bs=8 count=1 2>/dev/null | od -An -tx1 | tr -d ' \t\n')"
  BUCKET_NAME="anlessini-lambda-artifacts-${BUCKET_ID}"
  echo "$BUCKET_NAME" > "$BUCKET_FILE"
else
  BUCKET_NAME="$(cat "$BUCKET_FILE")"
fi

echo "Bucket Name: $BUCKET_NAME"
echo "Region: $REGION"
echo "AWS Args: ${MB_ARGS:-<none>}"

# If bucket already exists, skip creation
if aws $MB_ARGS --region "$REGION" s3 ls "s3://$BUCKET_NAME" >/dev/null 2>&1; then
  echo "Bucket already exists, skipping creation."
  exit 0
fi

# Create bucket:
# S3 special-case: in us-east-1, we MUST NOT pass LocationConstraint
if [[ "$REGION" == "us-east-1" ]]; then
  aws $MB_ARGS --region "$REGION" s3 mb "s3://$BUCKET_NAME"
else
  aws $MB_ARGS --region "$REGION" s3 mb "s3://$BUCKET_NAME" \
    --create-bucket-configuration "LocationConstraint=$REGION"
fi

echo "✅ Bucket created: s3://$BUCKET_NAME (region: $REGION)"
