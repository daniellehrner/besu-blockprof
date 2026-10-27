# besu-blockprof

Profiles a running Besu with async-profiler and writes a self-contained HTML report with one timeline per imported block:
- every thread's samples around each `engine_newPayload` and the `engine_forkchoiceUpdated` that follows it
- phases on the engine thread, the parallel-tx workers and the trie pools
- time inside RocksDB, split into file reads, block cache, decompression and locks
- a "Where the time went" flame graph and method table for any thread, pool and time range

The block windows come from Besu's own log lines: `Imported #… | … ms exec` and `FCU(VALID) | head: …`.

## Download

Each [release](https://github.com/daniellehrner/besu-blockprof/releases) has `besu-blockprof`, a single executable: a short shell script with the jar appended. It runs the jar with the Java that runs Besu, else with `java` on `PATH` (21+ with `jdk.jfr`).

```
wget https://github.com/daniellehrner/besu-blockprof/releases/latest/download/besu-blockprof
chmod +x besu-blockprof
sudo ./besu-blockprof record
```

Recording also needs [async-profiler](https://github.com/async-profiler/async-profiler/releases) on the machine running Besu, in a directory the Besu user can read: the JVM loads its library from there.

The release also has `besu-blockprof.jar` for `java -jar besu-blockprof.jar`, which takes the same commands and options.

## Build

Needs a JDK 21+ for building only:

```
./build.sh          # writes besu-blockprof and besu-blockprof.jar
```

## Record

`record` runs asprof against the Besu JVM, which is the single process running `org.hyperledger.besu.Besu` or the one given with `--pid`, and writes the report to `besu-blockprof-<UTC time>.html` in the current directory.

Run it with sudo or as the user of the Besu JVM. As root, asprof attaches with the JVM's user and group, and the JVM writes the JFR into a scratch directory in its own `/tmp`, which is removed afterwards. Under sudo, the report belongs to the user who ran sudo.

Besu has to write its INFO log to a file, because the block windows come from its `Imported #` and `FCU(VALID)` lines. `--log` defaults to `/var/log/besu`.

Everything after `--` goes to asprof and replaces the defaults `-d 120 -i 5ms -e wall --cstack vm` one by one, so this profiles for five minutes instead of two:

```
sudo besu-blockprof record -- -d 300
```

With `--archive`, `record` also keeps the files the report was built from next to it, under the report's name:

| File | Contents |
|---|---|
| `<name>.jfr` | the async-profiler capture |
| `<name>.log` | only the `Imported #` / `FCU(` lines for the capture, ±60 s |
| `<name>.proc.csv` | per-thread I/O and scheduler counters |
| `<name>.properties` | asprof args, pid, host, start and end |

While asprof runs, `record` also reads the kernel's per-thread I/O and scheduler counters (`/proc/<pid>/task/<tid>/io` and `schedstat`) of the engine API thread every millisecond into `<name>.proc.csv`. `read_bytes` only grows when the thread makes the kernel read from the block device, so the report can tell RocksDB file reads served from disk from those served by the page cache, and how long the thread was blocked.

Options:
- `--proc-threads PREFIX,...|none`: which threads to read counters for, by thread name prefix (Linux truncates names to 15 characters). Default `engine-ordered-`, the engine API thread. Add `besu-block-cpu` for the parallel-tx workers.
- `--proc-interval-us N`: how often to read them. Default 1000.
- `--out FILE`: the report. Default `besu-blockprof-<UTC time>.html` in the current directory, or in `FILE` if it is a directory.
- `--archive`: keep the capture files next to the report, see above.
- `--log PATH`: the file or directory Besu writes its INFO log to, repeatable. In a directory, the `*.log` and `*.log.N` files written during the capture. Default `/var/log/besu`.
- `--pad MS`: padding around each newPayload and FCU window. Default 50.
- `--asprof PATH`: default is `asprof` on `PATH`, else the newest `/opt/async-profiler*/bin/asprof` or `~/opt/async-profiler*/bin/asprof`.

Ctrl-C stops the capture without a report.

async-profiler arguments that matter for the report:
- `-d 120`: how many seconds to profile.
- `-e wall`: every thread, including parked ones. `-e cpu` works too, but only shows time on CPU.
- `-i 5ms` or smaller: a newPayload of 50 ms gets 10 samples per thread at 5 ms.
- `--cstack vm`: keeps native frames, which the RocksDB split needs. Stack walking can crash a JVM that runs Go code through cgo, as Besu does for some precompiles, with the default cstack mode and occasionally with `--cstack vm` too. Keep captures short on nodes that matter.

## Report

Rebuilds the HTML from a JFR, for example after copying the files of `record --archive` to a laptop:

```
besu-blockprof report --jfr besu-blockprof-20261024-043832.jfr
besu-blockprof report --jfr x.jfr --log besu.log --log besu.log.1 --out x.html
```

Next to a JFR from `record --archive`, the `.log`, `.proc.csv` and `.properties` files are picked up automatically. Otherwise `--log` is required.

Other options:
- `--event auto|wall|cpu`: which sample event to use when the JFR has both.
- `--interval MS`: the sample interval, if it can't be inferred from the samples.

## License

Apache License 2.0, see [LICENSE](LICENSE).
