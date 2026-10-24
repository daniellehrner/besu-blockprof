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
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Samples the kernel's per-thread I/O and scheduler counters of selected Besu threads while the
 * profiler runs. read_bytes only grows when the thread makes the kernel read from the block device,
 * so together with on-CPU time it separates disk reads from page-cache hits.
 *
 * <p>One CSV row per thread whenever a counter moved, plus a heartbeat every 100 ms:
 * epoch_ns,tid,comm,rchar,syscr,read_bytes,run_ns,wait_ns.
 */
final class ProcSampler implements Runnable {

  static final int COMM_LEN = 15;
  static final long HEARTBEAT_NS = 100_000_000L;

  private final String pid;
  private final List<String> prefixes;
  private final long intervalNanos;
  private final Path out;
  private volatile boolean stop;
  private volatile String error;
  private long rows;

  ProcSampler(String pid, List<String> prefixes, long intervalNanos, Path out) {
    this.pid = pid;
    this.prefixes =
        prefixes.stream().map(p -> p.length() > COMM_LEN ? p.substring(0, COMM_LEN) : p).toList();
    this.intervalNanos = intervalNanos;
    this.out = out;
  }

  /** The matching threads now, or a reason why their counters cannot be read. */
  List<String[]> probe() throws IOException {
    List<String[]> tids = scan();
    if (tids.isEmpty()) {
      throw new IOException("no thread of pid " + pid + " has a name starting with " + prefixes);
    }
    if (read(tids.get(0)[0]) == null) {
      throw new IOException(
          "cannot read /proc/" + pid + "/task/" + tids.get(0)[0]
              + "/io; run as the user of the Besu JVM");
    }
    return tids;
  }

  void stop() {
    stop = true;
  }

  String error() {
    return error;
  }

  long rows() {
    return rows;
  }

  @Override
  public void run() {
    try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
      w.write("# epoch_ns,tid,comm,rchar,syscr,read_bytes,run_ns,wait_ns\n");
      Map<String, long[]> last = new HashMap<>();
      Map<String, Long> lastWritten = new HashMap<>();
      List<String[]> tids = scan();
      long nextScan = System.nanoTime() + 1_000_000_000L;
      while (!stop) {
        long started = System.nanoTime();
        if (started >= nextScan) {
          tids = scan();
          nextScan = started + 1_000_000_000L;
        }
        long epochNs = epochNanos(Instant.now());
        for (String[] t : tids) {
          long[] v = read(t[0]);
          if (v == null) continue;
          long[] prev = last.get(t[0]);
          Long written = lastWritten.get(t[0]);
          if (prev == null || !Arrays.equals(prev, v) || epochNs - written >= HEARTBEAT_NS) {
            w.write(epochNs + "," + t[0] + "," + t[1] + "," + v[0] + "," + v[1] + "," + v[2] + ","
                + v[3] + "," + v[4] + "\n");
            last.put(t[0], v);
            lastWritten.put(t[0], epochNs);
            rows++;
          }
        }
        long sleep = intervalNanos - (System.nanoTime() - started);
        if (sleep > 0) LockSupport.parkNanos(sleep);
      }
    } catch (IOException e) {
      error = e.getMessage();
    }
  }

  private List<String[]> scan() throws IOException {
    List<String[]> found = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/proc", pid, "task"))) {
      for (Path d : ds) {
        try {
          String comm = Files.readString(d.resolve("comm")).trim();
          for (String p : prefixes) {
            if (comm.startsWith(p)) {
              found.add(new String[] {d.getFileName().toString(), comm.replace(',', '_')});
              break;
            }
          }
        } catch (IOException gone) {
          // the thread exited while we looked
        }
      }
    }
    return found;
  }

  /** rchar, syscr, read_bytes, run_ns, wait_ns; null when the thread is gone or unreadable. */
  private long[] read(String tid) {
    Path task = Path.of("/proc", pid, "task", tid);
    try {
      long[] v = new long[5];
      for (String line : Files.readString(task.resolve("io")).split("\n")) {
        int colon = line.indexOf(':');
        if (colon < 0) continue;
        String key = line.substring(0, colon);
        long value = Long.parseLong(line.substring(colon + 1).trim());
        switch (key) {
          case "rchar" -> v[0] = value;
          case "syscr" -> v[1] = value;
          case "read_bytes" -> v[2] = value;
          default -> {}
        }
      }
      String[] sched = Files.readString(task.resolve("schedstat")).trim().split(" ");
      v[3] = Long.parseLong(sched[0]);
      v[4] = Long.parseLong(sched[1]);
      return v;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  static long epochNanos(Instant t) {
    return t.getEpochSecond() * 1_000_000_000L + t.getNano();
  }
}
