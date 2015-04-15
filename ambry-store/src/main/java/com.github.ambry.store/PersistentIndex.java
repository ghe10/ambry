package com.github.ambry.store;

import com.codahale.metrics.Timer;
import com.github.ambry.config.StoreConfig;
import com.github.ambry.utils.Scheduler;
import com.github.ambry.utils.SystemTime;
import com.github.ambry.utils.Utils;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.FilenameFilter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


/**
 * A persistent index implementation that is responsible for adding and modifying index entries,
 * recovering an index from the log and commit and recover index to disk . This class
 * is not thread safe and expects the caller to do appropriate synchronization.
 **/
public class PersistentIndex {

  public static final String Index_File_Name_Suffix = "index";
  public static final String Bloom_File_Name_Suffix = "bloom";
  public static final Short version = 0;

  protected Scheduler scheduler;
  protected ConcurrentSkipListMap<Long, IndexSegment> indexes = new ConcurrentSkipListMap<Long, IndexSegment>();
  protected Journal journal;

  private long maxInMemoryIndexSizeInBytes;
  private int maxInMemoryNumElements;
  private Log log;
  private String dataDir;
  private Logger logger = LoggerFactory.getLogger(getClass());
  private IndexPersistor persistor;
  private StoreKeyFactory factory;
  private StoreConfig config;
  private JournalFactory storeJournalFactory;
  private UUID sessionId;
  private boolean cleanShutdown;
  private long logEndOffsetOnStartup;
  private static final String Clean_Shutdown_Filename = "cleanshutdown";
  private final StoreMetrics metrics;

  private class IndexFilter implements FilenameFilter {
    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(Index_File_Name_Suffix);
    }
  }

  /**
   * Creates a new persistent index
   * @param datadir The directory to use to store the index files
   * @param scheduler The scheduler that runs regular background tasks
   * @param log The log that is represented by this index
   * @param config The store configs for this index
   * @param factory The factory used to create store keys
   * @param recovery The recovery handle to perform recovery on startup
   * @throws StoreException
   */
  public PersistentIndex(String datadir, Scheduler scheduler, Log log, StoreConfig config, StoreKeyFactory factory,
      MessageStoreRecovery recovery, StoreMetrics metrics)
      throws StoreException {
    try {
      this.scheduler = scheduler;
      this.metrics = metrics;
      this.log = log;
      File indexDir = new File(datadir);
      File[] indexFiles = indexDir.listFiles(new IndexFilter());
      this.factory = factory;
      this.config = config;
      persistor = new IndexPersistor();
      storeJournalFactory = Utils.getObj(config.storeJournalFactory);
      /* If a put and a delete of a key happens within the same segment, the segment will have only one entry for it,
      whereas the journal keeps both. In order to account for this, and to ensure that the journal always has all the
      elements held by the latest segment, the journal needs to be able to hold twice the max number of elements a
      segment can hold. */
      journal = storeJournalFactory.getJournal(datadir, 2 * config.storeIndexMaxNumberOfInmemElements,
          config.storeMaxNumberOfEntriesToReturnFromJournal);
      Arrays.sort(indexFiles, new Comparator<File>() {
        @Override
        public int compare(File o1, File o2) {
          if (o1 == null || o2 == null) {
            throw new NullPointerException("arguments to compare two files is null");
          }
          // File name pattern for index is offset_name. We extract the offset from
          // name to compare
          int o1Index = o1.getName().indexOf("_", 0);
          long o1Offset = Long.parseLong(o1.getName().substring(0, o1Index));
          int o2Index = o2.getName().indexOf("_", 0);
          long o2Offset = Long.parseLong(o2.getName().substring(0, o2Index));
          if (o1Offset == o2Offset) {
            return 0;
          } else if (o1Offset < o2Offset) {
            return -1;
          } else {
            return 1;
          }
        }
      });

      for (int i = 0; i < indexFiles.length; i++) {
        boolean map = false;
        // We map all the indexes except the most recent index segment.
        // The recent index segment would go through recovery after they have been
        // read into memory
        if (i < indexFiles.length - 1) {
          map = true;
        }
        IndexSegment info = new IndexSegment(indexFiles[i], map, factory, config, metrics, journal);
        logger
            .info("Index : {} loaded index segment {} with start offset {} and end offset {} ", datadir, indexFiles[i],
                info.getStartOffset(), info.getEndOffset());
        indexes.put(info.getStartOffset(), info);
      }
      this.dataDir = datadir;
      logger.info("Index : " + datadir + " log end offset of index  before recovery " + log.getLogEndOffset());

      // perform recovery if required
      final Timer.Context context = metrics.recoveryTime.time();
      if (indexes.size() > 0) {
        IndexSegment lastSegment = indexes.lastEntry().getValue();
        // recover last segment
        recover(lastSegment, log.sizeInBytes(), recovery);
      } else {
        recover(null, log.sizeInBytes(), recovery);
      }
      context.stop();

      // set the log end offset to the recovered offset from the index after initializing it
      log.setLogEndOffset(getCurrentEndOffset());
      logEndOffsetOnStartup = log.getLogEndOffset();

      this.maxInMemoryIndexSizeInBytes = config.storeIndexMaxMemorySizeBytes;
      this.maxInMemoryNumElements = config.storeIndexMaxNumberOfInmemElements;
      this.sessionId = UUID.randomUUID();
      // delete the shutdown file
      File cleanShutdownFile = new File(datadir, Clean_Shutdown_Filename);
      if (cleanShutdownFile.exists()) {
        cleanShutdown = true;
        cleanShutdownFile.delete();
      }

      // start scheduler thread to persist index in the background
      this.scheduler = scheduler;
      this.scheduler.schedule("index persistor", persistor,
          config.storeDataFlushDelaySeconds + new Random().nextInt(SystemTime.SecsPerMin),
          config.storeDataFlushIntervalSeconds, TimeUnit.SECONDS);
    } catch (StoreException e) {
      throw e;
    } catch (Exception e) {
      throw new StoreException("Unknown error while creating index " + datadir, e,
          StoreErrorCodes.Index_Creation_Failure);
    }
  }

  /**
   * Recovers a segment given the end offset in the log and a recovery handler
   * @param segmentToRecover The segment to recover. If this is null, it creates a new segment
   * @param endOffset The end offset till which recovery needs to happen in the log
   * @param recovery The recovery handler that is used to perform the recovery
   * @throws StoreException
   * @throws IOException
   */
  private void recover(IndexSegment segmentToRecover, long endOffset, MessageStoreRecovery recovery)
      throws StoreException, IOException {
    // fix the start offset in the log for recovery.
    long startOffsetForRecovery = 0;
    if (segmentToRecover != null) {
      startOffsetForRecovery =
          segmentToRecover.getEndOffset() == -1 ? segmentToRecover.getStartOffset() : segmentToRecover.getEndOffset();
    }
    logger.info("Index : {} performing recovery on index with start offset {} and end offset {}", dataDir,
        startOffsetForRecovery, endOffset);
    List<MessageInfo> messagesRecovered = recovery.recover(log, startOffsetForRecovery, endOffset, factory);
    if (messagesRecovered.size() > 0) {
      metrics.nonzeroMessageRecovery.inc(1);
    }
    long runningOffset = startOffsetForRecovery;
    // Iterate through the recovered messages and update the index
    for (MessageInfo info : messagesRecovered) {
      logger.trace("Index : {} recovering key {} offset {} size {}", dataDir, info.getStoreKey(), runningOffset,
          info.getSize());
      if (segmentToRecover == null) {
        // if there was no segment passed in, create a new one
        segmentToRecover = new IndexSegment(dataDir, startOffsetForRecovery, factory, info.getStoreKey().sizeInBytes(),
            IndexValue.Index_Value_Size_In_Bytes, config, metrics);
        indexes.put(startOffsetForRecovery, segmentToRecover);
      }
      IndexValue value = findKey(info.getStoreKey());
      if (value != null) {
        // if the key already exists in the index, update it if it is deleted
        logger.info("Index : {} msg already exist with key {}", dataDir, info.getStoreKey());
        if (value.isFlagSet(IndexValue.Flags.Delete_Index)) {
          // key already has a deleted entry in the index!
          logger.error("Index: {} recovered msg {} is for a key that is already deleted in the index: "
              + "index offset {} Original Offset {}", dataDir, info, value.getOffset(),
              value.getOriginalMessageOffset());
          throw new StoreException("Encountered a duplicate record for key", StoreErrorCodes.Initialization_Error);
        } else if (info.isDeleted()) {
          value.setFlag(IndexValue.Flags.Delete_Index);
          value.setNewOffset(runningOffset);
          value.setNewSize(info.getSize());
        } else {
          throw new StoreException("Illegal message state during recovery. ", StoreErrorCodes.Initialization_Error);
        }
        validateFileSpan(new FileSpan(runningOffset, runningOffset + info.getSize()));
        segmentToRecover.addEntry(new IndexEntry(info.getStoreKey(), value), runningOffset + info.getSize());
        journal.addEntry(runningOffset, info.getStoreKey());
        if (value.getOriginalMessageOffset() != runningOffset && value.getOriginalMessageOffset() >= segmentToRecover
            .getStartOffset()) {
          journal.addEntry(value.getOriginalMessageOffset(), info.getStoreKey());
        }
        logger.info("Index : {} updated message with key {} size {} ttl {} deleted {}", dataDir, info.getStoreKey(),
            value.getSize(), value.getTimeToLiveInMs(), info.isDeleted());
      } else {
        // create a new entry in the index
        IndexValue newValue = new IndexValue(info.getSize(), runningOffset, info.getExpirationTimeInMs());
        validateFileSpan(new FileSpan(runningOffset, runningOffset + info.getSize()));
        segmentToRecover.addEntry(new IndexEntry(info.getStoreKey(), newValue), runningOffset + info.getSize());
        journal.addEntry(runningOffset, info.getStoreKey());
        logger.info("Index : {} adding new message to index with key {} size {} ttl {} deleted {}", dataDir,
            info.getStoreKey(), info.getSize(), info.getExpirationTimeInMs(), info.isDeleted());
      }
      runningOffset += info.getSize();
    }
  }

  /**
   * Adds a new entry to the index
   * @param entry The entry to be added to the index
   * @param fileSpan The file span that this entry represents in the log
   * @throws StoreException
   */
  public void addToIndex(IndexEntry entry, FileSpan fileSpan)
      throws StoreException {
    validateFileSpan(fileSpan);
    if (needToRollOverIndex(entry)) {
      IndexSegment info = new IndexSegment(dataDir, entry.getValue().getOffset(), factory, entry.getKey().sizeInBytes(),
          IndexValue.Index_Value_Size_In_Bytes, config, metrics);
      info.addEntry(entry, fileSpan.getEndOffset());
      indexes.put(info.getStartOffset(), info);
    } else {
      indexes.lastEntry().getValue().addEntry(entry, fileSpan.getEndOffset());
    }
    journal.addEntry(entry.getValue().getOffset(), entry.getKey());
  }

  /**
   * Adds a set of entries to the index
   * @param entries The entries to be added to the index
   * @param fileSpan The file span that the entries represent in the log
   * @throws StoreException
   */
  public void addToIndex(ArrayList<IndexEntry> entries, FileSpan fileSpan)
      throws StoreException {
    validateFileSpan(fileSpan);
    for (IndexEntry entry : entries) {
      long entryStartOffset = entry.getValue().getOffset();
      long entryEndOffset = entryStartOffset + entry.getValue().getSize();
      addToIndex(entry, new FileSpan(entryStartOffset, entryEndOffset));
    }
  }

  /**
   * Checks if the index segment needs to roll over to a new segment
   * @param entry The new entry that needs to be added to the existing active segment
   * @return True, if segment needs to roll over. False, otherwise
   */
  private boolean needToRollOverIndex(IndexEntry entry) {
    if (indexes.size() == 0) {
      logger.info("Creating first segment");
      return true;
    }

    IndexSegment lastSegment = indexes.lastEntry().getValue();

    if (lastSegment.getSizeWritten() >= maxInMemoryIndexSizeInBytes) {
      logger.info("Rolling over because the size written {} >= maxInMemoryIndexSizeInBytes {}",
          lastSegment.getSizeWritten(), maxInMemoryIndexSizeInBytes);
      return true;
    }
    if (lastSegment.getNumberOfItems() >= maxInMemoryNumElements) {
      logger.info("Rolling over because the number of items in the last segment: {} >= maxInMemoryNumElements {}",
          lastSegment.getNumberOfItems(), maxInMemoryNumElements);
      return true;
    }
    if (lastSegment.getKeySize() != entry.getKey().sizeInBytes()) {
      logger.info("Rolling over because the segment keySize: {} != entry's keysize {}", lastSegment.getKeySize(),
          entry.getKey().sizeInBytes());
      return true;
    }
    if (lastSegment.getValueSize() != IndexValue.Index_Value_Size_In_Bytes) {
      logger.info("Rolling over because the segment value size: {} != IndexValue.Index_Value_Size_In_Bytes: {}",
          IndexValue.Index_Value_Size_In_Bytes);
      return true;
    }
    return false;
  }

  /**
   * Checks if all the keys in the entry set have the same key size.
   * @param entries The set of new entries.
   * @throws IllegalArgumentException
   */
  private void validateEntries(ArrayList<IndexEntry> entries) {
    StoreKey firstKey = entries.get(0).getKey();

    for (IndexEntry entry : entries) {
      if (entry.getKey().sizeInBytes() != firstKey.sizeInBytes()) {
        metrics.keySizeMismatchCount.inc(1);
        throw new IllegalArgumentException(
            "Key sizes in the entries list are not the same, key size: " + entry.getKey().sizeInBytes() + " key: "
                + entry.getKey().getLongForm() + " is different from size of first key: " + firstKey.sizeInBytes()
                + " key: " + firstKey.getLongForm());
      }
    }
  }

  /**
   * Finds a key in the index and returns the blob index value associated with it. If not found,
   * returns null
   * @param key  The key to find in the index
   * @return The blob index value associated with the key. Null if the key is not found.
   * @throws StoreException
   */
  public IndexValue findKey(StoreKey key)
      throws StoreException {
    return findKey(key, null);
  }

  /**
   * Finds the value associated with a key if it is present in the index within the passed in filespan.
   * Filespan represents the start offset and end offset in the log.
   * @param key The key to do the exist check against
   * @param fileSpan FileSpan which specifies the range within which search should be made
   * @return The associated IndexValue if one exists within the fileSpan, null otherwise.
   * @throws StoreException
   */
  public IndexValue findKey(StoreKey key, FileSpan fileSpan)
      throws StoreException {
    final Timer.Context context = metrics.findTime.time();
    try {
      ConcurrentNavigableMap<Long, IndexSegment> segmentsMapToSearch = null;
      if (fileSpan == null) {
        logger.trace("Searching for " + key + " in the entire index");
        segmentsMapToSearch = indexes.descendingMap();
      } else {
        logger.trace("Searching for " + key + " in index with filespan ranging from " + fileSpan.getStartOffset() +
            " to " + fileSpan.getEndOffset());
        segmentsMapToSearch = indexes
            .subMap(indexes.floorKey(fileSpan.getStartOffset()), true, indexes.floorKey(fileSpan.getEndOffset()), true)
            .descendingMap();
        metrics.segmentSizeForExists.update(segmentsMapToSearch.size());
      }
      for (Map.Entry<Long, IndexSegment> entry : segmentsMapToSearch.entrySet()) {
        logger.trace("Index : {} searching index with start offset {}", dataDir, entry.getKey());
        IndexValue value = entry.getValue().find(key);
        if (value != null) {
          logger.trace("Index : {} found value offset {} size {} ttl {}", dataDir, value.getOffset(), value.getSize(),
              value.getTimeToLiveInMs());
          return value;
        }
      }
    } finally {
      context.stop();
    }
    return null;
  }

  /**
   * Marks the index entry represented by the key for delete
   * @param id The id of the entry that needs to be deleted
   * @param fileSpan The file range represented by this entry in the log
   * @throws StoreException
   */
  public void markAsDeleted(StoreKey id, FileSpan fileSpan)
      throws StoreException {
    validateFileSpan(fileSpan);
    IndexValue value = findKey(id);
    if (value == null) {
      throw new StoreException("Id " + id + " not present in index " + dataDir, StoreErrorCodes.ID_Not_Found);
    } else if (value.isFlagSet(IndexValue.Flags.Delete_Index)) {
      throw new StoreException("Id " + id + " already deleted in index " + dataDir, StoreErrorCodes.ID_Deleted);
    }
    IndexValue newValue =
        new IndexValue(value.getSize(), value.getOffset(), value.getFlags(), value.getTimeToLiveInMs());
    newValue.setFlag(IndexValue.Flags.Delete_Index);
    newValue.setNewOffset(fileSpan.getStartOffset());
    newValue.setNewSize(fileSpan.getEndOffset() - fileSpan.getStartOffset());
    indexes.lastEntry().getValue().addEntry(new IndexEntry(id, newValue), fileSpan.getEndOffset());
    journal.addEntry(fileSpan.getStartOffset(), id);
  }

  /**
   * Returns the blob read info for a given key that is not deleted or expired ttl
   * @param id The id of the entry whose info is required
   * @return The blob read info that contains the information for the given key
   * @throws StoreException
   */
  public BlobReadOptions getBlobReadInfo(StoreKey id, EnumSet<StoreGetOptions> getOptions)
      throws StoreException {
    IndexValue value = findKey(id);
    if (value == null) {
      throw new StoreException("Id " + id + " not present in index " + dataDir, StoreErrorCodes.ID_Not_Found);
    } else if (value.isFlagSet(IndexValue.Flags.Delete_Index)) {
      throw new StoreException("Id " + id + " has been deleted in index " + dataDir, StoreErrorCodes.ID_Deleted);
    } else if (value.isExpired() && !getOptions.contains(StoreGetOptions.Store_Include_Expired)) {
      throw new StoreException("Id " + id + " has expired ttl in index " + dataDir, StoreErrorCodes.TTL_Expired);
    }
    return new BlobReadOptions(value.getOffset(), value.getSize(), value.getTimeToLiveInMs(), id);
  }

  /**
   * Returns the list of keys that are not found in the index from the given input keys. This also checks
   * keys that are marked for deletion and those that have an expired ttl
   * @param keys The list of keys that needs to be tested against the index
   * @return The list of keys that are not found in the index
   * @throws StoreException
   */
  public Set<StoreKey> findMissingKeys(List<StoreKey> keys)
      throws StoreException {
    Set<StoreKey> missingKeys = new HashSet<StoreKey>();
    for (StoreKey key : keys) {
      if (findKey(key) == null) {
        missingKeys.add(key);
      }
    }
    return missingKeys;
  }

  /**
   * Finds all the entries from the given start token(inclusive). The token defines the start position in the index from
   * where entries needs to be fetched
   * @param token The token that signifies the start position in the index from where entries need to be retrieved
   * @param maxTotalSizeOfEntries The maximum total size of entries that needs to be returned. The api will try to
   *                              return a list of entries whose total size is close to this value.
   * @return The FindInfo state that contains both the list of entries and the new findtoken to start the next iteration
   */
  public FindInfo findEntriesSince(FindToken token, long maxTotalSizeOfEntries)
      throws StoreException {
    try {
      boolean tokenWasReset = false;
      long logEndOffsetBeforeFind = log.getLogEndOffset();
      StoreFindToken storeToken = (StoreFindToken) token;
      // validate token
      if (storeToken.getSessionId() == null || storeToken.getSessionId().compareTo(sessionId) != 0) {
        // the session has changed. check if we had an unclean shutdown on startup
        if (!cleanShutdown) {
          // if we had an unclean shutdown and the token offset is larger than the logEndOffsetOnStartup
          // we reset the token to logEndOffsetOnStartup
          if ((storeToken.getStoreKey() != null && storeToken.getIndexStartOffset() > logEndOffsetOnStartup) || (
              storeToken.getOffset() > logEndOffsetOnStartup)) {
            logger.info("Index : " + dataDir + " resetting offset after not clean shutdown " + logEndOffsetOnStartup
                + " before offset " + storeToken.getOffset());
            storeToken = new StoreFindToken(logEndOffsetOnStartup, sessionId);
            tokenWasReset = true;
          }
        } else if ((storeToken.getStoreKey() != null && storeToken.getIndexStartOffset() > logEndOffsetOnStartup) || (
            storeToken.getOffset() > logEndOffsetOnStartup)) {
          logger.error(
              "Index : " + dataDir + " invalid token. Provided offset is outside the log range after clean shutdown");
          // if the shutdown was clean, the offset should always be lesser or equal to the logEndOffsetOnStartup
          throw new IllegalArgumentException(
              "Invalid token. Provided offset is outside the log range after clean shutdown");
        }
      }
      List<MessageInfo> messageEntries = new ArrayList<MessageInfo>();
      if (storeToken.getStoreKey() == null) {
        boolean inclusive = false;
        long offsetToStart = storeToken.getOffset();
        if (offsetToStart == StoreFindToken.Uninitialized_Offset) {
          offsetToStart = 0;
          inclusive = true;
        } else if (tokenWasReset) {
          offsetToStart = logEndOffsetOnStartup;
          inclusive = true; //the key, if any, at logEndOffsetOnStartup is new.
        }
        logger.trace("Index : " + dataDir + " getting entries since " + offsetToStart);
        // check journal
        List<JournalEntry> entries = journal.getEntriesSince(offsetToStart, inclusive);
        if (entries != null) {
          logger.trace("Index : " + dataDir + " retrieving from journal from offset " +
              offsetToStart + " total entries " + entries.size());
          long offsetEnd = offsetToStart;
          long currentTotalSizeOfEntries = 0;
          long lastEntrySize = 0;
          for (JournalEntry entry : entries) {
            IndexValue value = findKey(entry.getKey());
            messageEntries.add(
                new MessageInfo(entry.getKey(), value.getSize(), value.isFlagSet(IndexValue.Flags.Delete_Index),
                    value.getTimeToLiveInMs()));
            currentTotalSizeOfEntries += value.getSize();
            offsetEnd = entry.getOffset();
            lastEntrySize = value.getSize();
            if (currentTotalSizeOfEntries >= maxTotalSizeOfEntries) {
              break;
            }
          }
          logger.trace("Index : " + dataDir + " new offset from find info " + offsetEnd);
          eliminateDuplicates(messageEntries);
          StoreFindToken storeFindToken = new StoreFindToken(offsetEnd, sessionId);
          if (messageEntries.size() == 0) {
            // if there are no messageEntries, total bytes read is equivalent to the logEndOffsetBeforeFind
            storeFindToken.setBytesRead(logEndOffsetBeforeFind);
          } else {
            // if we have messageEntries, then the total bytes read is sum of endOffset and the size of the last message entry
            storeFindToken.setBytesRead(offsetEnd + lastEntrySize);
          }
          return new FindInfo(messageEntries, storeFindToken);
        } else {
          // Find index segment closest to the token offset.
          // Get entries starting from the first key in this offset.
          Map.Entry<Long, IndexSegment> entry = indexes.floorEntry(offsetToStart);
          StoreFindToken newToken = null;
          if (entry != null && entry.getKey() != indexes.lastKey()) {
            newToken = findEntriesFromSegmentStartOffset(entry.getKey(), null, messageEntries, maxTotalSizeOfEntries);
            messageEntries = updateDeleteStateForMessages(messageEntries);
          } else {
            newToken = storeToken;
          }
          eliminateDuplicates(messageEntries);
          logger.trace("Index : " + dataDir +
              " new offset from find info" +
              " offset : " + (newToken.getOffset() != StoreFindToken.Uninitialized_Offset ? newToken.getOffset()
              : newToken.getIndexStartOffset() + ":" + newToken.getStoreKey()));
          long totalBytesRead = getTotalBytesRead(newToken, messageEntries, logEndOffsetBeforeFind);
          newToken.setBytesRead(totalBytesRead);
          return new FindInfo(messageEntries, newToken);
        }
      } else {
        // Find the index segment corresponding to the token indexStartOffset.
        // Get entries starting from the token Key in this index.
        StoreFindToken newToken =
            findEntriesFromSegmentStartOffset(storeToken.getIndexStartOffset(), storeToken.getStoreKey(),
                messageEntries, maxTotalSizeOfEntries);
        messageEntries = updateDeleteStateForMessages(messageEntries);
        eliminateDuplicates(messageEntries);
        long totalBytesRead = getTotalBytesRead(newToken, messageEntries, logEndOffsetBeforeFind);
        newToken.setBytesRead(totalBytesRead);
        return new FindInfo(messageEntries, newToken);
      }
    } catch (IOException e) {
      throw new StoreException("IOError when finding entries for index " + dataDir, e, StoreErrorCodes.IOError);
    } catch (Exception e) {
      throw new StoreException("Unknown error when finding entries for index " + dataDir, e,
          StoreErrorCodes.Unknown_Error);
    }
  }

  private long getTotalBytesRead(StoreFindToken newToken, List<MessageInfo> messageEntries,
      long logEndOffsetBeforeFind) {
    if (newToken.getOffset() == StoreFindToken.Uninitialized_Offset) {
      if (newToken.getIndexStartOffset() == StoreFindToken.Uninitialized_Offset) {
        return 0;
      } else {
        return newToken.getIndexStartOffset();
      }
    } else {
      if (messageEntries.size() > 0) {
        MessageInfo lastMsgInfo = messageEntries.get(messageEntries.size() - 1);
        return newToken.getOffset() + lastMsgInfo.getSize();
      } else {
        return logEndOffsetBeforeFind;
      }
    }
  }

  /**
   * Finds entries starting from a key from the segment with start offset initialSegmentStartOffset. The key represents
   * the position in the segment starting from where entries needs to be fetched.
   * @param initialSegmentStartOffset The segment start offset of the segment to start reading entries from.
   * @param key The key representing the position (exclusive) in the segment to start reading entries from. If the key
   *            is null, all the keys will be read.
   * @param messageEntries the list to be populated with the MessageInfo for every entry that is read.
   * @param maxTotalSizeOfEntries The maximum total size of entries that needs to be returned.
   * @return A token representing the position in the segment/journal up to which entries have been read and returned.
   */
  private StoreFindToken findEntriesFromSegmentStartOffset(long initialSegmentStartOffset, StoreKey key,
      List<MessageInfo> messageEntries, long maxTotalSizeOfEntries)
      throws IOException, StoreException {

    long segmentStartOffset = initialSegmentStartOffset;
    if (segmentStartOffset == indexes.lastKey()) {
      // We would never have given away a token with a segmentStartOffset of the latest segment.
      throw new IllegalArgumentException("Index : " + dataDir +
          " findEntriesFromOffset segment start offset " + segmentStartOffset + " is of the last segment");
    }

    long newTokenSegmentStartOffset = StoreFindToken.Uninitialized_Offset;
    long newTokenOffsetInJournal = StoreFindToken.Uninitialized_Offset;

    IndexSegment segmentToProcess = indexes.get(segmentStartOffset);
    AtomicLong currentTotalSizeOfEntries = new AtomicLong(0);

    /* First, get keys from the segment corresponding to the passed in segment start offset if the token has a non-null
       key. Otherwise, since all the keys starting from the offset have to be read, skip this and check in the journal
       first. */
    if (key != null) {
      if (segmentToProcess.getEntriesSince(key, maxTotalSizeOfEntries, messageEntries, currentTotalSizeOfEntries)) {
        newTokenSegmentStartOffset = segmentStartOffset;
      }
      logger.trace("Index : " + dataDir + " findEntriesFromOffset segment start offset " + segmentStartOffset +
          " with key " + key + " total entries received " + messageEntries.size());
      segmentStartOffset = indexes.higherKey(segmentStartOffset);
      segmentToProcess = indexes.get(segmentStartOffset);
    }

    while (currentTotalSizeOfEntries.get() < maxTotalSizeOfEntries) {
      // Check in the journal to see if we are already at an offset in the journal, if so get entries from it.
      long journalFirstOffsetBeforeCheck = journal.getFirstOffset();
      long journalLastOffsetBeforeCheck = journal.getLastOffset();
      List<JournalEntry> entries = journal.getEntriesSince(segmentStartOffset, true);
      if (entries != null) {
        logger.trace("Index : " + dataDir + " findEntriesFromOffset journal offset " +
            segmentStartOffset + " total entries received " + entries.size());
        for (JournalEntry entry : entries) {
          newTokenOffsetInJournal = entry.getOffset();
          IndexValue value = findKey(entry.getKey());
          messageEntries.add(
              new MessageInfo(entry.getKey(), value.getSize(), value.isFlagSet(IndexValue.Flags.Delete_Index),
                  value.getTimeToLiveInMs()));
          if (currentTotalSizeOfEntries.addAndGet(value.getSize()) >= maxTotalSizeOfEntries) {
            break;
          }
        }
        break; // we have entered and finished reading from the journal, so we are done.
      }

      if (segmentStartOffset == indexes.lastKey()) {
        /* The start offset is of the latest segment, and was not found in the journal. This means an entry was added
         * to the index (creating a new segment) but not yet to the journal. However, if the journal does not contain
         * the latest segment's start offset, then it *must* have the previous segment's start offset (if it did not,
         * then the previous segment must have been the latest segment at the time it was scanned, which couldn't have
         * happened due to this same argument).
         * */
        throw new IllegalStateException("Index : " + dataDir +
            " findEntriesFromOffset segment start offset " + segmentStartOffset
            + " is of the latest segment and not found in journal with range [" + journalFirstOffsetBeforeCheck + ", "
            + journalLastOffsetBeforeCheck + "]");
      } else {
        // Read and populate from the first key in the segment with this segmentStartOffset
        if (segmentToProcess.getEntriesSince(null, maxTotalSizeOfEntries, messageEntries, currentTotalSizeOfEntries)) {
          newTokenSegmentStartOffset = segmentStartOffset;
        }
        logger.trace("Index : " + dataDir + " findEntriesFromOffset segment start offset " + segmentStartOffset +
            " with all the keys, total entries received " + messageEntries.size());
        segmentStartOffset = indexes.higherKey(segmentStartOffset);
        segmentToProcess = indexes.get(segmentStartOffset);
      }
    }

    if (newTokenOffsetInJournal != StoreFindToken.Uninitialized_Offset) {
      return new StoreFindToken(newTokenOffsetInJournal, sessionId);
    } else if (messageEntries.size() == 0) {
      throw new IllegalStateException(
          "Message entries cannot be null. At least one entry should have been returned, start offset: "
              + initialSegmentStartOffset + ", key: " + key + ", max total size of entries to return: "
              + maxTotalSizeOfEntries);
    } else {
      return new StoreFindToken(messageEntries.get(messageEntries.size() - 1).getStoreKey(), newTokenSegmentStartOffset,
          sessionId);
    }
  }

  /**
   * We can have duplicate entries in the message entries since updates can happen to the same key. For example,
   * insert a key followed by a delete. This would create two entries in the journal or the index. A single findInfo
   * could read both the entries. The findInfo should return as clean information as possible. This method removes
   * the oldest duplicate in the list.
   * @param messageEntries The message entry list where duplicates need to be removed
   */
  private void eliminateDuplicates(List<MessageInfo> messageEntries) {
    Set<StoreKey> setToFindDuplicate = new HashSet<StoreKey>();
    ListIterator<MessageInfo> messageEntriesIterator = messageEntries.listIterator(messageEntries.size());
    while (messageEntriesIterator.hasPrevious()) {
      MessageInfo messageInfo = messageEntriesIterator.previous();
      if (setToFindDuplicate.contains(messageInfo.getStoreKey())) {
        messageEntriesIterator.remove();
      } else {
        setToFindDuplicate.add(messageInfo.getStoreKey());
      }
    }
  }

  /**
   * Updates the messages with their deleted state. This method can be used when
   * the messages have been retrieved from an old index segment and needs to be updated with the deleted state
   * from the new index segment
   * @param currentMessageEntries The message entries that needs to be updated with the delete state
   * @return The new list of message entries with the updated state
   */
  private List<MessageInfo> updateDeleteStateForMessages(List<MessageInfo> currentMessageEntries)
      throws StoreException {
    List<MessageInfo> newMessageEntries = new ArrayList<MessageInfo>(currentMessageEntries.size());
    for (MessageInfo messageInfo : currentMessageEntries) {
      if (!messageInfo.isDeleted()) {
        IndexValue indexValue = findKey(messageInfo.getStoreKey());
        MessageInfo newMessageInfo = new MessageInfo(messageInfo.getStoreKey(), messageInfo.getSize(),
            indexValue.isFlagSet(IndexValue.Flags.Delete_Index), messageInfo.getExpirationTimeInMs());
        newMessageEntries.add(newMessageInfo);
      } else {
        newMessageEntries.add(messageInfo);
      }
    }
    return newMessageEntries;
  }

  /**
   * Closes the index
   * @throws StoreException
   */
  public void close()
      throws StoreException {
    persistor.write();
    File cleanShutdownFile = new File(dataDir, Clean_Shutdown_Filename);
    try {
      cleanShutdownFile.createNewFile();
    } catch (IOException e) {
      logger.error("Index : " + dataDir + " error while creating clean shutdown file ", e);
    }
  }

  /**
   * Returns the current end offset that the index represents in the log
   * @return The end offset in the log that this index currently represents
   */
  protected long getCurrentEndOffset() {
    return indexes.size() == 0 ? 0 : indexes.lastEntry().getValue().getEndOffset();
  }

  /**
   * Ensures that the provided fileendoffset in the fileSpan satisfies constraints
   * @param fileSpan The filespan that needs to be verified
   */
  private void validateFileSpan(FileSpan fileSpan) {
    if (getCurrentEndOffset() > fileSpan.getStartOffset() || fileSpan.getStartOffset() > fileSpan.getEndOffset()) {
      logger.error("File span offsets provided to the index does not meet constraints "
          + "logEndOffset {} inputFileStartOffset {} inputFileEndOffset {}", getCurrentEndOffset(),
          fileSpan.getStartOffset(), fileSpan.getEndOffset());
      throw new IllegalArgumentException("File span offsets provided to the index " + dataDir +
          " does not meet constraints" +
          " logEndOffset " + getCurrentEndOffset() +
          " inputFileStartOffset " + fileSpan.getStartOffset() +
          " inputFileEndOffset " + fileSpan.getEndOffset());
    }
  }

  class IndexPersistor implements Runnable {

    /**
     * Writes all the individual index segments to disk. It flushes the log before starting the
     * index flush. The penultimate index segment is flushed if it is not already flushed and mapped.
     * The last index segment is flushed whenever write is invoked.
     * @throws StoreException
     */
    public void write()
        throws StoreException {
      final Timer.Context context = metrics.indexFlushTime.time();
      try {
        if (indexes.size() > 0) {
          // before iterating the map, get the current file end pointer
          Map.Entry<Long, IndexSegment> lastEntry = indexes.lastEntry();
          IndexSegment currentInfo = lastEntry.getValue();
          long currentIndexEndOffsetBeforeFlush = currentInfo.getEndOffset();
          long logEndOffsetBeforeFlush = log.getLogEndOffset();
          if (logEndOffsetBeforeFlush < currentIndexEndOffsetBeforeFlush) {
            throw new StoreException("LogEndOffset " + logEndOffsetBeforeFlush + " before flush cannot be less than " +
                "currentEndOffSet of index " + currentIndexEndOffsetBeforeFlush, StoreErrorCodes.Illegal_Index_State);
          }

          //  flush the log to ensure everything till the fileEndPointerBeforeFlush is flushed
          log.flush();

          long lastOffset = lastEntry.getKey();
          IndexSegment prevInfo = indexes.size() > 1 ? indexes.lowerEntry(lastOffset).getValue() : null;
          long currentLogEndPointer = log.getLogEndOffset();
          while (prevInfo != null && !prevInfo.isMapped()) {
            if (prevInfo.getEndOffset() > currentLogEndPointer) {
              String message = "The read only index cannot have a file end pointer " + prevInfo.getEndOffset() +
                  " greater than the log end offset " + currentLogEndPointer;
              throw new StoreException(message, StoreErrorCodes.IOError);
            }
            logger.trace("Index : " + dataDir + " writing prev index with end offset " + prevInfo.getEndOffset());
            prevInfo.writeIndexToFile(prevInfo.getEndOffset());
            prevInfo.map(true);
            Map.Entry<Long, IndexSegment> infoEntry = indexes.lowerEntry(prevInfo.getStartOffset());
            prevInfo = infoEntry != null ? infoEntry.getValue() : null;
          }
          currentInfo.writeIndexToFile(currentIndexEndOffsetBeforeFlush);
        }
      } catch (IOException e) {
        throw new StoreException("IO error while writing index to file", e, StoreErrorCodes.IOError);
      } finally {
        context.stop();
      }
    }

    public void run() {
      try {
        write();
      } catch (Exception e) {
        logger.error("Index : " + dataDir + " error while persisting the index to disk ", e);
      }
    }
  }
}

/**
 * The StoreFindToken is an implementation of FindToken.
 * It is used to provide a token to the client to resume
 * the find from where it was left previously. The StoreFindToken
 * maintains a offset to track entries within the journal. If the
 * offset gets outside the range of the journal, the storekey and
 * indexstartoffset that refers to the segment of the index is used
 * to perform the search. This is possible because the journal is
 * always equal or larger than the writable segment.
 */
class StoreFindToken implements FindToken {
  private long offset;
  private long indexStartOffset;
  private StoreKey storeKey;
  private UUID sessionId;
  private long bytesRead;

  private static final short version = 0;
  private static final int Version_Size = 2;
  private static final int SessionId_Size = 4;
  private static final int Offset_Size = 8;
  private static final int Start_Offset_Size = 8;

  public static final int Uninitialized_Offset = -1;

  public StoreFindToken() {
    this(Uninitialized_Offset, Uninitialized_Offset, null, null);
  }

  public StoreFindToken(StoreKey key, long indexStartOffset, UUID sessionId) {
    this(Uninitialized_Offset, indexStartOffset, key, sessionId);
  }

  public StoreFindToken(long offset, UUID sessionId) {
    this(offset, Uninitialized_Offset, null, sessionId);
  }

  private StoreFindToken(long offset, long indexStartOffset, StoreKey key, UUID sessionId) {
    this.offset = offset;
    this.indexStartOffset = indexStartOffset;
    this.storeKey = key;
    this.sessionId = sessionId;
    this.bytesRead = Uninitialized_Offset;
  }

  public void setBytesRead(long bytesRead) {
    this.bytesRead = bytesRead;
  }

  public static StoreFindToken fromBytes(DataInputStream stream, StoreKeyFactory factory)
      throws IOException {
    // read version
    short version = stream.readShort();
    // read sessionId
    String sessionId = Utils.readIntString(stream);
    UUID sessionIdUUID = null;
    if (sessionId != null) {
      sessionIdUUID = UUID.fromString(sessionId);
    }
    // read offset
    long offset = stream.readLong();
    // read index start offset
    long indexStartOffset = stream.readLong();
    // read store key if needed
    if (indexStartOffset != Uninitialized_Offset) {
      return new StoreFindToken(factory.getStoreKey(stream), indexStartOffset, sessionIdUUID);
    } else {
      return new StoreFindToken(offset, sessionIdUUID);
    }
  }

  public long getOffset() {
    return offset;
  }

  public StoreKey getStoreKey() {
    return storeKey;
  }

  public long getIndexStartOffset() {
    return indexStartOffset;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public void setOffset(long offset) {
    this.offset = offset;
    this.storeKey = null;
    this.indexStartOffset = Uninitialized_Offset;
  }

  @Override
  public long getBytesRead() {
    if (this.bytesRead == Uninitialized_Offset) {
      throw new IllegalStateException("Bytes read not initialized");
    }
    return this.bytesRead;
  }

  @Override
  public byte[] toBytes() {
    int size = Version_Size + SessionId_Size + (sessionId == null ? 0 : sessionId.toString().getBytes().length) +
        Offset_Size + Start_Offset_Size + (storeKey == null ? 0 : storeKey.sizeInBytes());
    byte[] buf = new byte[size];
    ByteBuffer bufWrap = ByteBuffer.wrap(buf);
    // add version
    bufWrap.putShort(version);
    // add sessionId
    bufWrap.putInt(sessionId == null ? 0 : sessionId.toString().length());
    if (sessionId != null) {
      bufWrap.put(sessionId.toString().getBytes());
    }
    // add offset
    bufWrap.putLong(offset);
    // add index start offset
    bufWrap.putLong(indexStartOffset);
    // add storekey
    if (storeKey != null) {
      bufWrap.put(storeKey.toBytes());
    }
    return buf;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("version: ").append(version);
    if (sessionId != null) {
      sb.append(" sessionId ").append(sessionId);
    }
    if (storeKey != null) {
      sb.append(" indexStartOffset ").append(indexStartOffset).append(" storeKey ").append(storeKey);
    } else {
      sb.append(" offset ").append(offset);
    }
    sb.append(" bytesRead ").append(bytesRead);
    return sb.toString();
  }
}
