#!/bin/bash

# Run search collection with the provided arguments
# This script supports both local indexes and S3 indexes (s3://bucket/key format)
# Usage: ./bin/run.sh <class-name> [arguments...]
# Example: ./bin/run.sh io.anlessini.LocalSearchCollection -index s3://bucket/key -topics file.tsv ...

set -e

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_DIR="$( cd "$SCRIPT_DIR/.." && pwd )"

if [ $# -eq 0 ]; then
  echo "Usage: $0 <class-name> [arguments...]"
  echo "Example: $0 io.anlessini.LocalSearchCollection -index s3://bucket/key -topics file.tsv ..."
  exit 1
fi

CLASS_NAME="$1"
shift

# Convert class name to executable name
# io.anlessini.LocalSearchCollection -> LocalSearchCollection
# io.anserini.search.SearchCollection -> LocalSearchCollection (use our version for S3 support)
EXEC_NAME="${CLASS_NAME##*.}"

# Special case: always use our LocalSearchCollection for SearchCollection (supports S3 and local)
if [ "$CLASS_NAME" = "io.anserini.search.SearchCollection" ]; then
  EXEC_NAME="LocalSearchCollection"
  SEARCH_EXEC="$PROJECT_DIR/search-lambda-function/target/appassembler/bin/$EXEC_NAME"
# Check if this is another Anserini tool (like IndexCollection)
elif [[ "$CLASS_NAME" =~ ^io\.anserini\. ]]; then
  # Try to find Anserini in common locations
  ANSERINI_PATHS=(
    "../anserini"
    "../../anserini"
    "$HOME/anserini"
    "$ANSERINI_HOME"
  )
  
  ANSERINI_DIR=""
  for path in "${ANSERINI_PATHS[@]}"; do
    if [ -d "$path" ] && [ -f "$path/target/appassembler/bin/$EXEC_NAME" ]; then
      ANSERINI_DIR="$path"
      break
    fi
  done
  
  if [ -z "$ANSERINI_DIR" ]; then
    echo "Error: Anserini not found. Please either:"
    echo "  1. Clone Anserini in a sibling directory (../anserini)"
    echo "  2. Set ANSERINI_HOME environment variable to your Anserini path"
    echo "  3. Or run $EXEC_NAME directly from your Anserini directory:"
    echo "     cd /path/to/anserini"
    echo "     target/appassembler/bin/$EXEC_NAME $*"
    exit 1
  fi
  
  # Use Anserini's tool
  SEARCH_EXEC="$ANSERINI_DIR/target/appassembler/bin/$EXEC_NAME"
else
  # It's an anlessini tool
  SEARCH_EXEC="$PROJECT_DIR/search-lambda-function/target/appassembler/bin/$EXEC_NAME"
fi

if [ ! -f "$SEARCH_EXEC" ]; then
  echo "Error: $EXEC_NAME not found at $SEARCH_EXEC"
  if [[ "$CLASS_NAME" =~ ^io\.anserini\. ]]; then
    echo "Please build Anserini first:"
    echo "  cd $ANSERINI_DIR && mvn clean package appassembler:assemble"
  else
    echo "Please build anlessini first:"
    echo "  mvn clean install"
  fi
  exit 1
fi

# Run with remaining arguments
exec "$SEARCH_EXEC" "$@"

