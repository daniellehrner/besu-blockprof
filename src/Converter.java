/*
 * Copyright besu-blockprof contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Cuts async-profiler samples into per-block windows taken from the besu log: newPayload runs from
 * the "Imported" line minus its exec time to the line itself, extended to the following FCU line,
 * all padded. Every sample gets a phase (which part of block processing) and an activity (running,
 * RocksDB read or write, parked), and RocksDB samples get the kind of work found in their native
 * frames.
 */
final class Converter {

  static final String WALL = "profiler.WallClockSample";
  static final String CPU = "jdk.ExecutionSample";

  /** What the report needs besides the JFR and the logs; null fields are unknown. */
  record Options(
      Path jfr,
      List<Path> logs,
      Path proc,
      long padMs,
      String event,
      Double intervalMs,
      String asprofArgs,
      String pid,
      String host) {}

  record Result(String json, int blocks, int logBlocks, long samples, String event, double intervalMs) {}

  // deepest match wins, so list the generic markers too: a more specific one deeper in the stack
  // overrides them
  static final String[][] PHASES = {
    // newPayload, engine thread
    {"np.other", "EngineNewPayloadV1.syncResponse"},
    {"np.decode", "readRequestParameters"},
    {"np.decode", "com.fasterxml.jackson"},
    {"np.header", "EngineNewPayloadV1.createBlockHeader"},
    {"np.header", "setBlockHeaderFields"},
    {"np.header", "BlockHeader.getHash"},
    {"np.header", "BodyValidation.transactionsRoot"},
    {"np.senders", "asyncPrecomputeSenders"},
    {"np.validate", "MergeCoordinator.rememberBlock"},
    {"np.headerValidation", "BlockHeaderValidator.validateHeader"},
    {"np.worldState", "getWorldState"},
    {"np.worldState", "getMutable"},
    {"blk.other", "AbstractBlockProcessor.processBlock"},
    {"blk.startParallel", "startParallelExecution"},
    {"blk.applyParallel", "getProcessingResult"},
    {"blk.txSequential", "processTransactionSequentially"},
    {"blk.receipts", "TransactionReceiptFactory"},
    {"blk.receipts", "ReceiptFactory.create"},
    {"blk.system", "WithdrawalsProcessor"},
    {"blk.system", "RequestProcessor"},
    {"blk.system", "SystemCallProcessor"},
    {"blk.system", "rewardCoinbase"},
    {"blk.accumulatorCommit", "BonsaiWorldStateUpdateAccumulator.commit"},
    {"persist.other", "PathBasedWorldState.persist"},
    {"persist.stateRoot", "StateRootCommitter"},
    {"persist.stateRoot", "StateRootComputations"},
    {"persist.stateRoot", "calculateRootHash"},
    {"persist.stateRoot", "getRootHash"},
    {"persist.trieLog", "TrieLogManager"},
    {"persist.trieLog", "TrieLogFactory"},
    {"persist.dbCommit", "commitComposedOnly"},
    {"persist.dbCommit", "commitAndPublishCache"},
    {"persist.dbCommit", "commitTrieLogOnly"},
    {"np.bodyValidation", "MainnetBlockBodyValidator.validateBody"},
    {"np.bodyValidation", "validatePostExecution"},
    {"np.storeBlock", "DefaultBlockchain.storeBlock"},
    {"np.storeBlock", "DefaultBlockchain.appendBlock"},
    {"np.respond", "respondWithValid"},
    // forkchoiceUpdated, engine thread
    {"fcu.other", "EngineForkchoiceUpdatedV1.syncResponse"},
    {"fcu.rollWorldState", "moveWorldStateTo"},
    {"fcu.rollForward", "PathBasedWorldStateUpdateAccumulator.rollForward"},
    {"fcu.rollForward", "PathBasedWorldStateUpdateAccumulator.rollBack"},
    {"fcu.chainHead", "DefaultBlockchain.forwardToBlock"},
    {"fcu.chainHead", "DefaultBlockchain.rewindToBlock"},
    // other threads
    {"w.parallelTx", "OptimisticConcurrentTransactionProcessor.runTransaction"},
    {"w.parallelTx", "BalConcurrentTransactionProcessor"},
    {"w.prefetch", "BonsaiCachedMerkleTrieLoader"},
    {"w.senders", "Transaction.getSender"},
    {"w.senders", "secp256k1"},
    {"w.trieParallel", "ParallelStoredMerklePatriciaTrie"},
    {"w.rpc", "io.vertx.core.http.impl"},
    {"w.rpc", "io.netty.channel.nio"},
    {"w.rpc", "io.netty.channel.epoll"},
  };

  static final Set<String> DB_READ =
      Set.of(
          "get", "multiGetAsList", "multiGet", "getForUpdate", "seek", "seekForPrev", "next",
          "keyMayExist", "getDirect");
  static final Set<String> WAIT_LEAF =
      Set.of(
          "jdk.internal.misc.Unsafe.park", "java.lang.Object.wait0", "java.lang.Thread.sleep0",
          "java.lang.Thread.sleepNanos0", "java.lang.Thread.yield0", "sun.nio.ch.EPoll.wait",
          "sun.nio.ch.Net.poll", "java.lang.Object.wait");

  // inside a RocksDB call, the deepest native frame matching one of these names what the call was
  // doing; libc is often unsymbolised, so the frame that called into it decides
  static final String[] IO_KINDS = {
    "none", "file read (pread)", "decompress", "block cache", "index / filter / block search",
    "lock wait", "other RocksDB CPU", "WAL write", "memtable", "conflict check", "fsync",
    "no native frames", "JVM safepoint wait (GC)", "block cache mutex"
  };
  static final int IO_NO_NATIVE = 11;
  static final String[][] IO_RULES = {
    {"12", "SafepointSynchronize"}, {"12", "SafepointMechanism"},
    {"1", "RandomAccessFileReader::Read"}, {"1", "RandomAccessFile::Read"},
    {"1", "RandomAccessFile::MultiRead"}, {"1", "PosixRandomAccessFile"}, {"1", "pread"},
    {"1", "ReadAsync"}, {"1", "io_uring"},
    {"10", "fsync"}, {"10", "fdatasync"}, {"10", "SyncWAL"}, {"10", "::Sync("},
    {"2", "LZ4"}, {"2", "Uncompress"}, {"2", "Decompress"}, {"2", "ZSTD"}, {"2", "Snappy"},
    {"5", "futex"}, {"5", "Mutex"}, {"5", "pthread_mutex"}, {"5", "SpinMutex"}, {"5", "::Lock("},
    {"5", "CondVar"},
    {"7", "log::Writer"}, {"7", "WriteGroupToWAL"}, {"7", "WriteToWAL"}, {"7", "WritableFileWriter"},
    {"8", "MemTable"}, {"8", "InsertInto"}, {"8", "InlineSkipList"},
    {"9", "CheckKeysForConflicts"}, {"9", "TransactionUtil::CheckKey"},
    {"3", "LRUCache"}, {"3", "ClockCache"}, {"3", "CacheShard"}, {"3", "ShardedCache"},
    {"3", "LRUHandle"}, {"3", "ClockHandle"}, {"3", "GetEntryFromCache"}, {"3", "Cache::Lookup"},
    {"3", "Cache::Release"},
    {"4", "FilterBlockReader"}, {"4", "Bloom"}, {"4", "MayMatch"}, {"4", "IndexBlockIter"},
    {"4", "DataBlockIter"}, {"4", "BlockIter"}, {"4", "BinarySearch"}, {"4", "Comparator"},
  };

  record LogBlock(
      long num, String hash, int tx, double parallelPct, long gas, double gasPct, double execMs,
      double mgas, long logT, long fcuT, String raw) {}

  static Result convert(Options o) throws IOException {
    List<LogBlock> logBlocks = parseLogs(o.logs());
    if (logBlocks.isEmpty()) {
      throw new IllegalStateException(
          "no \"Imported #\" lines in " + o.logs() + "; is this a besu log at INFO level?");
    }

    // pass 1: which sample event to use, the capture span, the interval and the Besu version
    Map<String, Integer> sampleEvents = new TreeMap<>();
    String classPath = null;
    try (RecordingFile f = new RecordingFile(o.jfr())) {
      while (f.hasMoreEvents()) {
        RecordedEvent e = f.readEvent();
        String type = e.getEventType().getName();
        if (type.equals("jdk.InitialSystemProperty") && "java.class.path".equals(e.getString("key"))) {
          classPath = e.getString("value");
        }
        if (e.getStackTrace() != null && (type.equals(WALL) || type.equals(CPU))) {
          sampleEvents.merge(type, 1, Integer::sum);
        }
      }
    }
    String event = chooseEvent(o.event(), sampleEvents);
    long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
    Map<Long, Long> lastByThread = new HashMap<>();
    Map<Long, Integer> gapHistogram = new HashMap<>(); // gap in 0.1 ms units
    try (RecordingFile f = new RecordingFile(o.jfr())) {
      while (f.hasMoreEvents()) {
        RecordedEvent e = f.readEvent();
        if (!e.getEventType().getName().equals(event) || e.getStackTrace() == null) continue;
        long tNanos = nanos(e.getStartTime());
        first = Math.min(first, tNanos / 1_000_000);
        last = Math.max(last, tNanos / 1_000_000);
        RecordedThread th = threadOf(e);
        if (th == null || weightOf(e) != 1) continue;
        Long prev = lastByThread.put(th.getOSThreadId(), tNanos);
        if (prev != null && tNanos > prev) {
          gapHistogram.merge(Math.round((tNanos - prev) / 100_000.0), 1, Integer::sum);
        }
      }
    }
    double intervalMs = o.intervalMs() != null ? o.intervalMs() : inferInterval(gapHistogram);

    // only blocks whose whole window is inside the capture
    long pad = o.padMs();
    final long cFirst = first, cLast = last;
    List<LogBlock> inside = new ArrayList<>();
    for (LogBlock b : logBlocks) {
      long s = b.logT() - Math.round(b.execMs()) - pad - 200;
      long e = (b.fcuT() > 0 ? b.fcuT() : b.logT()) + pad;
      if (s >= cFirst && e <= cLast) inside.add(b);
    }
    if (inside.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "no block falls inside the capture (%s .. %s); the log has %d blocks from %s to %s."
                  + " Check that --log covers the capture and that the clocks agree.",
              Instant.ofEpochMilli(cFirst), Instant.ofEpochMilli(cLast), logBlocks.size(),
              Instant.ofEpochMilli(logBlocks.get(0).logT()),
              Instant.ofEpochMilli(logBlocks.get(logBlocks.size() - 1).logT())));
    }

    // windows; the start gets 200 ms of extra slack so the decode before rememberBlock is found,
    // the page trims it to the first engine sample of that newPayload minus pad
    long[][] win = new long[inside.size()][2];
    for (int i = 0; i < inside.size(); i++) {
      LogBlock b = inside.get(i);
      win[i][0] = b.logT() - Math.round(b.execMs()) - pad - 200;
      win[i][1] = (b.fcuT() > 0 ? b.fcuT() : b.logT()) + pad;
    }

    Map<String, Integer> frameIds = new HashMap<>();
    List<String> frames = new ArrayList<>();
    Map<String, Integer> stackIds = new HashMap<>();
    List<int[]> stacks = new ArrayList<>();
    List<String> phaseNames = new ArrayList<>();
    Map<String, Integer> phaseIds = new HashMap<>();
    // per block -> thread -> samples [t(0.1 ms), stack, phase, activity, state, weight, io kind]
    List<Map<String, List<long[]>>> perBlock = new ArrayList<>();
    for (int i = 0; i < inside.size(); i++) perBlock.add(new TreeMap<>());
    Map<String, Integer> stateIds = new LinkedHashMap<>();
    long sampleCount = 0;
    boolean hasNative = false;

    // pass 2: the samples inside the windows
    try (RecordingFile f = new RecordingFile(o.jfr())) {
      while (f.hasMoreEvents()) {
        RecordedEvent e = f.readEvent();
        if (!e.getEventType().getName().equals(event)) continue;
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) continue;
        long tNanos = nanos(e.getStartTime());
        int bi = findWindow(win, tNanos / 1_000_000);
        if (bi < 0) continue;
        RecordedThread th = threadOf(e);
        String tn = th == null ? "?" : (th.getJavaName() != null ? th.getJavaName() : th.getOSName());
        long tid = th == null ? 0 : th.getOSThreadId();
        List<RecordedFrame> fr = st.getFrames(); // leaf first
        String[] names = new String[fr.size()];
        for (int k = 0; k < fr.size(); k++) {
          RecordedMethod m = fr.get(k).getMethod();
          names[k] = m == null ? "?" : frameName(m);
        }
        String phase = phase(names, tn);
        String state = stateOf(e);
        int activity = activity(names, state);
        int io = activity == 1 || activity == 2 ? ioKind(names) : 0;
        if (io != 0 && io != IO_NO_NATIVE) hasNative = true;
        int stateId = stateIds.computeIfAbsent(state, k -> stateIds.size());
        long weight = weightOf(e);
        int phaseId =
            phaseIds.computeIfAbsent(
                phase,
                k -> {
                  phaseNames.add(k);
                  return phaseNames.size() - 1;
                });
        int stackId = stackId(names, frameIds, frames, stackIds, stacks);
        long rel = Math.round((tNanos - win[bi][0] * 1_000_000L) / 100_000.0);
        perBlock
            .get(bi)
            .computeIfAbsent(tn + "#" + tid, k -> new ArrayList<>())
            .add(new long[] {rel, stackId, phaseId, activity, stateId, weight, io});
        sampleCount++;
      }
    }

    // per block -> tid -> rows [t(0.1 ms), d rchar, d syscr, d read_bytes, d run us, d wait us]
    List<Map<String, List<long[]>>> procPerBlock = new ArrayList<>();
    for (int i = 0; i < inside.size(); i++) procPerBlock.add(new TreeMap<>());
    Map<String, String> procComms = new TreeMap<>();
    double procGapMs = o.proc() != null ? readProc(o.proc(), win, procPerBlock, procComms) : 0;
    boolean hasProc = !procComms.isEmpty();

    StringBuilder out = new StringBuilder(1 << 20);
    out.append("{\"pad\":").append(pad);
    out.append(",\"meta\":")
        .append(
            meta(
                o, event, intervalMs, classPath, cFirst, cLast, hasNative, inside.size(), procComms,
                procGapMs));
    out.append(",\"states\":").append(jsonStrings(stateIds.keySet()));
    out.append(",\"phases\":").append(jsonStrings(phaseNames));
    out.append(",\"ioKinds\":").append(jsonStrings(Arrays.asList(IO_KINDS)));
    out.append(",\"frames\":").append(jsonStrings(frames));
    out.append(",\"stacks\":[");
    for (int i = 0; i < stacks.size(); i++) {
      if (i > 0) out.append(',');
      appendArray(out, stacks.get(i));
    }
    out.append("],\"blocks\":[");
    for (int i = 0; i < inside.size(); i++) {
      LogBlock b = inside.get(i);
      if (i > 0) out.append(',');
      out.append(
          String.format(
              Locale.ROOT,
              "{\"num\":%d,\"hash\":%s,\"tx\":%d,\"parallelPct\":%.1f,\"gas\":%d,\"gasPct\":%.1f,"
                  + "\"execMs\":%.1f,\"mgas\":%.2f,\"t0\":%d,\"logT\":%d,\"fcuT\":%d,\"raw\":%s,"
                  + "\"proc\":%s,\"threads\":[",
              b.num(), jsonString(b.hash()), b.tx(), b.parallelPct(), b.gas(), b.gasPct(),
              b.execMs(), b.mgas(), win[i][0], b.logT() - win[i][0],
              b.fcuT() > 0 ? b.fcuT() - win[i][0] : -1, jsonString(b.raw()),
              hasProc ? procJson(procPerBlock.get(i)) : "null"));
      boolean firstThread = true;
      for (var en : perBlock.get(i).entrySet()) {
        if (!firstThread) out.append(',');
        firstThread = false;
        out.append("{\"name\":").append(jsonString(en.getKey())).append(",\"s\":[");
        List<long[]> ss = en.getValue();
        ss.sort(Comparator.comparingLong(x -> x[0]));
        for (int k = 0; k < ss.size(); k++) {
          if (k > 0) out.append(',');
          appendArray(out, ss.get(k));
        }
        out.append("]}");
      }
      out.append("]}");
    }
    out.append("]}");
    return new Result(
        out.toString(), inside.size(), logBlocks.size(), sampleCount, event, intervalMs);
  }

  /**
   * Cuts the per-thread counter rows into the block windows, each window starting with the last row
   * before it as the baseline. Returns the typical gap between rows of a busy thread in ms.
   */
  static double readProc(
      Path proc, long[][] win, List<Map<String, List<long[]>>> perBlock, Map<String, String> comms)
      throws IOException {
    Map<String, long[]> lastRow = new HashMap<>();
    Map<Long, Integer> gaps = new HashMap<>(); // in 0.1 ms
    for (String line : Files.readAllLines(proc, StandardCharsets.UTF_8)) {
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] f = line.split(",");
      if (f.length < 8) continue;
      long[] cur = {
        Long.parseLong(f[0]), Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[5]),
        Long.parseLong(f[6]), Long.parseLong(f[7])
      };
      String tid = f[1];
      long[] prev = lastRow.put(tid, cur);
      if (prev != null && cur[0] - prev[0] < 50_000_000L) {
        gaps.merge(Math.round((cur[0] - prev[0]) / 100_000.0), 1, Integer::sum);
      }
      int bi = findWindow(win, cur[0] / 1_000_000);
      if (bi < 0) continue;
      comms.put(tid, f[2]);
      List<long[]> rows = perBlock.get(bi).computeIfAbsent(tid, k -> new ArrayList<>());
      long origin = win[bi][0] * 1_000_000L;
      if (rows.isEmpty() && prev != null) {
        rows.add(new long[] {Math.round((prev[0] - origin) / 100_000.0), 0, 0, 0, 0, 0});
      }
      long[] base = rows.size() > 0 && prev != null ? prev : cur;
      rows.add(
          new long[] {
            Math.round((cur[0] - origin) / 100_000.0), cur[1] - base[1], cur[2] - base[2],
            cur[3] - base[3], (cur[4] - base[4]) / 1000, (cur[5] - base[5]) / 1000
          });
    }
    return gaps.isEmpty() ? 0 : inferInterval(gaps);
  }

  static String procJson(Map<String, List<long[]>> byTid) {
    StringBuilder sb = new StringBuilder("{");
    for (var e : byTid.entrySet()) {
      if (sb.length() > 1) sb.append(',');
      sb.append(jsonString(e.getKey())).append(":[");
      List<long[]> rows = e.getValue();
      for (int i = 0; i < rows.size(); i++) {
        if (i > 0) sb.append(',');
        appendArray(sb, rows.get(i));
      }
      sb.append(']');
    }
    return sb.append('}').toString();
  }

  /** Fills the page template, which marks the data spot with a JS comment. */
  static String html(String json) throws IOException {
    String template;
    try (InputStream in = Converter.class.getResourceAsStream("/report.html")) {
      if (in == null) throw new IllegalStateException("report.html missing from the jar");
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int at = template.indexOf("/*DATA*/");
    if (at < 0) throw new IllegalStateException("report.html has no /*DATA*/ marker");
    return template.substring(0, at) + json.replace("</", "<\\/") + template.substring(at + 8);
  }

  static String chooseEvent(String wanted, Map<String, Integer> found) {
    String w = wanted == null ? "auto" : wanted;
    String event =
        switch (w) {
          case "wall" -> WALL;
          case "cpu" -> CPU;
          case "auto" -> found.containsKey(WALL) ? WALL : CPU;
          default -> throw new IllegalArgumentException("--event must be auto, wall or cpu: " + w);
        };
    if (!found.containsKey(event)) {
      throw new IllegalStateException(
          "the JFR has no " + event + " samples (found " + found + "); record with -e wall or -e cpu");
    }
    return event;
  }

  static double inferInterval(Map<Long, Integer> gaps) {
    long best = 0;
    int count = 0;
    for (var e : gaps.entrySet()) {
      if (e.getKey() > 0 && e.getValue() > count) {
        best = e.getKey();
        count = e.getValue();
      }
    }
    if (best == 0) {
      throw new IllegalStateException("cannot infer the sample interval; pass --interval MS");
    }
    return best / 10.0;
  }

  static String meta(
      Options o, String event, double intervalMs, String classPath, long first, long last,
      boolean hasNative, int blocks, Map<String, String> procComms, double procGapMs) {
    boolean wall = event.equals(WALL);
    String build = besuVersion(classPath);
    DateTimeFormatter ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    String capture = ts.format(Instant.ofEpochMilli(first)) + " – " + ts.format(Instant.ofEpochMilli(last))
        .substring(11);
    String iv = fmtMs(intervalMs);
    String profiler =
        o.asprofArgs() != null
            ? "asprof " + o.asprofArgs()
            : "async-profiler " + (wall ? "wall clock" : "CPU") + ", " + iv + " ms";
    List<String> notes = new ArrayList<>();
    notes.add(
        "<b>Windows.</b> newPayload runs from the <code>Imported</code> log line minus its exec"
            + " time to the log line. FCU runs from its first engine-thread sample to its"
            + " <code>FCU(VALID)</code> line. Both are padded by " + o.padMs() + " ms, and the idle"
            + " CL turnaround between them is cut out of the axis. 0 ms is where"
            + " <code>rememberBlock</code> starts; decoding the request comes before it.");
    notes.add(
        "<b>Slices.</b> Each slice is one " + iv + " ms " + (wall ? "wall-clock" : "CPU")
            + " sample of one thread. Its colour is the phase named by the deepest known Besu"
            + " frame on the stack. The ink bar under a slice means the sample was inside a RocksDB"
            + " JNI call: reads are <code>RocksDB.get</code>, writes are <code>Transaction.put</code>"
            + " and <code>commit</code>."
            + (wall
                ? ""
                : " CPU samples only cover time on a CPU: a thread that is parked or blocked on"
                    + " the disk leaves a gap."));
    if (hasNative) {
      notes.add(
          "<b>Inside RocksDB.</b> The samples keep RocksDB's native frames, and the deepest"
              + " recognised one names what the call was doing: reading a file"
              + " (<code>RandomAccessFileReader::Read</code>, i.e. <code>pread</code>),"
              + " decompressing a block, looking up the block cache, searching an index, filter or"
              + " data block, waiting on a lock, or on the write side the WAL, the memtable and the"
              + " conflict check. Where libc is unsymbolised, the frame that called into it"
              + " decides. A file read is a <code>pread</code>: the profile cannot tell a"
              + " page-cache copy from a wait on the disk, because both are the same syscall"
              + (wall ? " and async-profiler's thread state does not separate them." : "."));
    } else {
      notes.add(
          "<b>Inside RocksDB.</b> These samples have no native frames, so time inside RocksDB"
              + " cannot be split into file reads, block cache, decompression and locks. Record"
              + " with <code>--cstack vm</code> to get that split."
              + (wall
                  ? " async-profiler's thread state does not help either: it marks a sample as"
                      + " sleeping only when the signal lands inside a syscall that is still"
                      + " running, and a <code>pread</code> waiting on the disk cannot be"
                      + " interrupted, so it shows as running."
                  : ""));
    }
    if (!procComms.isEmpty()) {
      notes.add(
          "<b>Disk or page cache.</b> While async-profiler ran, besu-blockprof read the kernel's"
              + " per-thread counters of the threads named "
              + new java.util.TreeSet<>(procComms.values()).stream()
                  // Linux cuts thread names to 15 characters
                  .map(n -> escapeHtml(n) + (n.length() >= 15 ? "…" : ""))
                  .collect(java.util.stream.Collectors.joining(", "))
              + " about every " + fmtMs(procGapMs) + " ms: <code>read_bytes</code> (bytes the thread"
              + " made the kernel read from the block device, so page-cache misses including"
              + " readahead), read syscalls, time on a CPU and time waiting for one. Wall time minus"
              + " both is time the thread was blocked. A sample in RocksDB's file read counts as"
              + " <b>disk</b> when the thread caused block-device reads or was blocked for at least"
              + " half of the sample's " + iv + " ms, otherwise as <b>page cache</b>. The two rows"
              + " under the engine lane show blocked time and disk reads as they happened.");
    }
    notes.add(
        "<b>Engine-thread txs.</b> “Tx executed on engine thread” covers two cases the profile"
            + " cannot tell apart: the worker's result conflicted, or the worker had not finished"
            + " that tx when the engine thread reached it. In the second case the engine thread"
            + " cancels the future and runs the tx itself, while the worker keeps running it to the"
            + " end because <code>CompletableFuture.cancel</code> does not interrupt. The log's"
            + " “% parallel” is the share applied from the workers.");
    notes.add(
        "<b>Pools.</b> Pools with many threads are drawn as one lane. Bar height is busy threads"
            + " per sample, and the ink part at the bottom is threads inside RocksDB. Click the"
            + " group name to see one lane per thread with stacks.");
    notes.add(
        "<b>Where the time went.</b> The flame graph, the RocksDB split and the method table cover"
            + " one request at a time (newPayload or FCU) for the threads and time range you pick:"
            + " by default the engine thread, parked samples left out. Samples are assigned to a"
            + " request by time, split halfway between the end of newPayload and the start of FCU,"
            + " and parked samples only count inside a phase. With them included, the engine"
            + " thread adds up to the newPayload and FCU times on the buttons and in the block table."
            + " The flame graph draws every call level, such as each"
            + " nested <code>UpdateTrackingAccount.getStorageValue</code>, the way async-profiler"
            + " does. Tick “Fold nested and pass-through calls” under Options for a shallower graph:"
            + " N nested calls of the same method then show as one frame marked “×N”, and a run of"
            + " calls that only pass through (one callee holding all the time) shares one bar"
            + " labelled “first › n frames › last”. Unsymbolised library frames"
            + " count on the function that called into them. Tick “Count native time on the"
            + " calling Java method” under Options to cut every sample back to its deepest Java"
            + " frame. The ? button lists the hotkeys.");
    notes.add(
        "<b>Stack not walkable.</b> AsyncGetCallTrace could not walk these stacks"
            + " (<code>unknown_Java</code>), which happens in stubs and during deoptimisation. The"
            + " thread was running, but the sample has no phase.");
    notes.add(
        "<b>Source.</b> "
            + (o.asprofArgs() != null
                ? "<code>" + escapeHtml("asprof " + o.asprofArgs()) + "</code>"
                : "async-profiler, arguments unknown (no <code>.properties</code> next to the JFR)")
            + (o.pid() != null ? " on pid " + escapeHtml(o.pid()) : "")
            + (o.host() != null ? " on " + escapeHtml(o.host()) : "")
            + ", Besu " + escapeHtml(build) + ", " + blocks + " blocks cut with the matching"
            + " <code>Imported #</code> and <code>FCU(VALID)</code> log lines. JFR: <code>"
            + escapeHtml(o.jfr().getFileName().toString()) + "</code>.");

    String label =
        (o.host() != null ? o.host() + " · " : "")
            + "async-profiler "
            + (wall ? "wall clock" : "CPU");
    return "{\"intervalMs\":" + fmtJson(intervalMs)
        + ",\"event\":" + jsonString(wall ? "wall" : "cpu")
        + ",\"label\":" + jsonString(label)
        + ",\"build\":" + jsonString(build)
        + ",\"capture\":" + jsonString(capture)
        + ",\"profiler\":" + jsonString(profiler)
        + ",\"hasNative\":" + hasNative
        + ",\"hasProc\":" + !procComms.isEmpty()
        + ",\"notes\":" + jsonStrings(notes) + "}";
  }

  static final Pattern BESU_JAR = Pattern.compile("besu-[a-z-]+?-(\\d[^/:]*?)\\.jar");

  static String besuVersion(String classPath) {
    if (classPath == null) return "unknown";
    Matcher m = BESU_JAR.matcher(classPath);
    return m.find() ? m.group(1) : "unknown";
  }

  static long nanos(Instant t) {
    return t.getEpochSecond() * 1_000_000_000L + t.getNano();
  }

  static String fmtMs(double ms) {
    return ms == Math.rint(ms) ? String.valueOf((long) ms) : String.format(Locale.ROOT, "%.1f", ms);
  }

  static String fmtJson(double v) {
    return String.format(Locale.ROOT, "%.3f", v);
  }

  static void appendArray(StringBuilder sb, long[] a) {
    sb.append('[');
    for (int i = 0; i < a.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(a[i]);
    }
    sb.append(']');
  }

  static void appendArray(StringBuilder sb, int[] a) {
    sb.append('[');
    for (int i = 0; i < a.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(a[i]);
    }
    sb.append(']');
  }

  static int findWindow(long[][] win, long t) {
    for (int i = 0; i < win.length; i++) if (t >= win[i][0] && t <= win[i][1]) return i;
    return -1;
  }

  static RecordedThread threadOf(RecordedEvent e) {
    if (e.hasField("sampledThread")) return e.getThread("sampledThread");
    return e.getThread();
  }

  static String stateOf(RecordedEvent e) {
    if (e.hasField("state")) {
      Object v = e.getValue("state");
      return v == null ? "-" : v.toString();
    }
    return "-";
  }

  static long weightOf(RecordedEvent e) {
    if (e.hasField("samples")) {
      Object v = e.getValue("samples");
      if (v instanceof Number n) return n.longValue();
    }
    return 1;
  }

  static String phase(String[] names, String thread) {
    // walk from the root towards the leaf, keep the last (deepest) marker hit; a worker marker
    // (w.*) only names the phase when no request-level marker is on the stack, so the engine
    // thread waiting on the parallel trie stays in its state root phase
    String best = null, bestWorker = null;
    for (int k = names.length - 1; k >= 0; k--) {
      for (String[] p : PHASES)
        if (names[k].contains(p[1])) {
          if (p[0].startsWith("w.")) bestWorker = p[0];
          else best = p[0];
          break;
        }
    }
    if (best == null) best = bestWorker;
    if (best == null && names.length == 1 && names[0].contains("unknown_Java")) return "unknown";
    if (best != null && best.startsWith("persist.") || "blk.other".equals(best)) {
      // persist and block processing run under newPayload and again under FCU's world state roll
      for (String n : names)
        if (n.contains("EngineForkchoiceUpdatedV1.syncResponse")) {
          return best.startsWith("persist.") ? "fcu." + best.substring(8) : "fcu.other";
        }
    }
    if (best == null) {
      if (thread.startsWith("GC Thread") || thread.startsWith("G1 ")) return "gc";
      if (thread.contains("CompilerThread")) return "jit";
      return "other";
    }
    return best;
  }

  /** 0 running, 1 RocksDB read, 2 RocksDB write, 3 parked or idle, 4 in a syscall otherwise. */
  static int activity(String[] names, String state) {
    for (String n : names) {
      if (n.startsWith("org.rocksdb.")) {
        String m = n.substring(n.lastIndexOf('.') + 1);
        return DB_READ.contains(m) ? 1 : 2;
      }
    }
    for (int k = 0; k < Math.min(names.length, 4); k++) if (WAIT_LEAF.contains(names[k])) return 3;
    if (state.contains("SLEEPING")) return names.length <= 1 ? 3 : 4;
    return 0;
  }

  /** Java frames keep Class.method; native frames drop the library prefix and the C++ arguments. */
  static String frameName(RecordedMethod m) {
    String type = m.getType() == null ? "" : m.getType().getName();
    String name = m.getName().replaceAll("librocksdbjni\\d+", "librocksdbjni");
    boolean nativeLib = type.isEmpty() || type.contains(".so") || type.startsWith("/");
    if (!nativeLib) return type + "." + name;
    if (name.endsWith(" const")) name = name.substring(0, name.length() - 6);
    if (name.endsWith(")")) {
      int depth = 0;
      for (int i = name.length() - 1; i >= 0; i--) {
        char c = name.charAt(i);
        if (c == ')') depth++;
        else if (c == '(' && --depth == 0) {
          if (i > 0) name = name.substring(0, i);
          break;
        }
      }
    }
    return name;
  }

  static int ioKind(String[] names) {
    boolean anyNative = false;
    for (int k = 0; k < names.length; k++) {
      String n = names[k];
      if (n.startsWith("org.rocksdb.")) break; // reached the JNI entry, nothing below matched
      if (n.contains("::") || n.contains("rocksdb") || n.contains(".so") || n.startsWith("Java_")) {
        anyNative = true;
      }
      for (String[] r : IO_RULES)
        if (n.contains(r[1])) {
          int kind = Integer.parseInt(r[0]);
          if (kind == 5)
            for (int j = k + 1; j < Math.min(names.length, k + 5); j++)
              if (names[j].contains("CacheShard")) return 13;
          return kind;
        }
    }
    return anyNative ? 6 : IO_NO_NATIVE;
  }

  static final Set<String> BORING_ROOT =
      Set.of("java.lang.Thread.run", "java.lang.Thread.runWith", "java.lang.VirtualThread.run");

  static int stackId(
      String[] names, Map<String, Integer> frameIds, List<String> frames,
      Map<String, Integer> stackIds, List<int[]> stacks) {
    // root first, without the thread-start boilerplate, consecutive repeats folded as one frame
    List<Integer> ids = new ArrayList<>();
    String prev = null;
    int rep = 0;
    for (int k = names.length - 1; k >= 0; k--) {
      String n = names[k];
      if (ids.isEmpty() && BORING_ROOT.contains(n)) continue;
      if (n.equals(prev)) {
        rep++;
        continue;
      }
      if (rep > 0) ids.set(ids.size() - 1, frameId(prev + " ×" + (rep + 1), frameIds, frames));
      rep = 0;
      prev = n;
      ids.add(frameId(n, frameIds, frames));
    }
    if (rep > 0) ids.set(ids.size() - 1, frameId(prev + " ×" + (rep + 1), frameIds, frames));
    int[] arr = ids.stream().mapToInt(Integer::intValue).toArray();
    String key = Arrays.toString(arr);
    Integer id = stackIds.get(key);
    if (id == null) {
      id = stacks.size();
      stacks.add(arr);
      stackIds.put(key, id);
    }
    return id;
  }

  static int frameId(String n, Map<String, Integer> frameIds, List<String> frames) {
    String s = n.replace("org.hyperledger.besu.", "besu.");
    return frameIds.computeIfAbsent(
        s,
        k -> {
          frames.add(k);
          return frames.size() - 1;
        });
  }

  // ---------- besu log ----------

  static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
  static final Pattern TIME = Pattern.compile("^(\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})([+-]\\d{4})");
  static final Pattern IMPORTED =
      Pattern.compile(
          "Imported #([\\d,]+)\\s+\\(([^)]+)\\)\\|\\s*(\\d+) tx(?: \\(\\s*([\\d.]+)% parallel\\))?.*?"
              + "\\|\\s*([\\d,]+) \\(\\s*([\\d.]+)%\\) gas used\\|\\s*([\\d.]+)(ms|s) exec\\|"
              + "\\s*([\\d.]+) Mgas/s");
  static final Pattern FCU = Pattern.compile("FCU\\(VALID\\) \\| head: (\\S+)");
  static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  /** Epoch millis of a besu log line, or -1 when it does not start with a timestamp. */
  static long lineTime(String line) {
    Matcher m = TIME.matcher(line);
    if (!m.find()) return -1;
    String off = m.group(2);
    ZoneOffset zone = ZoneOffset.of(off.substring(0, 3) + ":" + off.substring(3));
    return LocalDateTime.parse(m.group(1), TS).toInstant(zone).toEpochMilli();
  }

  static String stripAnsi(String line) {
    return ANSI.matcher(line).replaceAll("");
  }

  static List<LogBlock> parseLogs(List<Path> logs) throws IOException {
    List<String> lines = new ArrayList<>();
    for (Path p : logs) {
      for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
        if (l.contains("Imported #") || l.contains("FCU(")) lines.add(stripAnsi(l));
      }
    }
    Map<String, LogBlock> byKey = new LinkedHashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      String l = lines.get(i);
      Matcher m = IMPORTED.matcher(l);
      long t = lineTime(l);
      if (t < 0 || !m.find()) continue;
      double exec = Double.parseDouble(m.group(7)) * (m.group(8).equals("s") ? 1000 : 1);
      String hash = m.group(2);
      long fcuT = -1;
      for (int j = i + 1; j < Math.min(lines.size(), i + 40); j++) {
        String l2 = lines.get(j);
        Matcher f = FCU.matcher(l2);
        if (f.find() && f.group(1).equals(hash)) {
          fcuT = lineTime(l2);
          break;
        }
        if (l2.contains("Imported #")) break;
      }
      long num = Long.parseLong(m.group(1).replace(",", ""));
      byKey.putIfAbsent(
          num + "/" + hash,
          new LogBlock(
              num, hash, Integer.parseInt(m.group(3)),
              m.group(4) == null ? -1 : Double.parseDouble(m.group(4)),
              Long.parseLong(m.group(5).replace(",", "")), Double.parseDouble(m.group(6)), exec,
              Double.parseDouble(m.group(9)), t, fcuT, l.substring(l.indexOf("Imported"))));
    }
    List<LogBlock> out = new ArrayList<>(byKey.values());
    out.sort(Comparator.comparingLong(LogBlock::logT));
    return out;
  }

  // ---------- json ----------

  static String jsonStrings(Collection<String> c) {
    StringBuilder sb = new StringBuilder("[");
    for (String s : c) {
      if (sb.length() > 1) sb.append(',');
      sb.append(jsonString(s));
    }
    return sb.append(']').toString();
  }

  static String jsonString(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
      else if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
      else sb.append(ch);
    }
    return sb.append('"').toString();
  }

  static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private Converter() {}
}
