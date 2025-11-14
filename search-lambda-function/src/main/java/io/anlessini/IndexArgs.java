package io.anlessini;

/**
 * Constants for Lucene index field names.
 * These match the standard field names used by Anserini for Lucene indexes.
 * 
 * Note: IndexCollection.Args is for command-line arguments, not field name constants.
 * These field names ("id", "contents", "raw") are standard across Anserini versions.
 */
public class IndexArgs {
  public static final String ID = "id";
  public static final String CONTENTS = "contents";
  public static final String RAW = "raw";
}


