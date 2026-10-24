# besu-blockprof

Profiles a running Besu with async-profiler and writes a self-contained HTML report with one timeline per imported block:
- every thread's samples around each `engine_newPayload` and the `engine_forkchoiceUpdated` that follows it
- phases on the engine thread, the parallel-tx workers and the trie pools
- time inside RocksDB, split into file reads, block cache, decompression and locks
- a "Where the time went" flame graph and method table for any thread, pool and time range

The block windows come from Besu's own log lines: `Imported #… | … ms exec` and `FCU(VALID) | head: …`.

## Build

Needs a JDK 21+ for building only:

```
./build.sh          # writes besu-blockprof.jar
```

Running needs only a Java 21+ runtime with `jdk.jfr`; the JRE Besu runs on is enough.

## Record

`record` passes everything after `--` to `asprof`, forces JFR output, and attaches to the Besu JVM. That is the single process running `org.hyperledger.besu.Besu`, or the one given with `--pid`.

Besu has to write its INFO log to a file, because the block windows come from its `Imported #` and `FCU(VALID)` lines; pass that file with `--log`.

asprof has to run as the user of the Besu JVM, and that JVM writes the JFR, so `--out` must be writable by it:

```
sudo -u <besu user> besu-blockprof record --log <besu log> --out /tmp/besu-blockprof -- -d 300 -i 5ms -e wall --cstack vm
```

The jar and the log file must be readable by that user too, so keep the tool out of home directories it cannot enter.

It writes five files next to each other:

| File | Contents |
|---|---|
| `besu-blockprof-<UTC time>.jfr` | the async-profiler capture |
| `besu-blockprof-<UTC time>.log` | only the `Imported #` / `FCU(` lines for the capture, ±60 s |
| `besu-blockprof-<UTC time>.proc.csv` | per-thread I/O and scheduler counters |
| `besu-blockprof-<UTC time>.properties` | asprof args, pid, host, start and end |
| `besu-blockprof-<UTC time>.html` | the report |

While asprof runs, `record` also reads the kernel's per-thread I/O and scheduler counters (`/proc/<pid>/task/<tid>/io` and `schedstat`) of the engine API thread every millisecond into `<name>.proc.csv`. `read_bytes` only grows when the thread makes the kernel read from the block device, so the report can tell RocksDB file reads served from disk from those served by the page cache, and how long the thread was blocked.

Options:
- `--proc-threads PREFIX,...|none`: which threads to read counters for, by thread name prefix (Linux truncates names to 15 characters). Default `engine-ordered-`, the engine API thread. Add `besu-block-cpu` for the parallel-tx workers.
- `--proc-interval-us N`: how often to read them. Default 1000.
- `--log FILE`: the file Besu writes its INFO log to, repeatable for rotated files. Required.
- `--pad MS`: padding around each newPayload and FCU window. Default 50.
- `--asprof PATH`: default is `asprof` on `PATH`, else the newest `/opt/async-profiler*/bin/asprof` or `~/opt/async-profiler*/bin/asprof`.

async-profiler arguments that matter for the report:
- `-e wall`: every thread, including parked ones. `-e cpu` works too, but only shows time on CPU.
- `-i 5ms` or smaller: a newPayload of 50 ms gets 10 samples per thread at 5 ms.
- `--cstack vm`: keeps native frames, which the RocksDB split needs. Stack walking can crash a JVM that runs Go code through cgo, as Besu does for some precompiles, with the default cstack mode and occasionally with `--cstack vm` too. Keep captures short on nodes that matter.

## Report

Rebuilds the HTML from a JFR, for example after copying the files to a laptop:

```
besu-blockprof report --jfr besu-blockprof-20261024-043832.jfr
besu-blockprof report --jfr x.jfr --log besu.log --log besu.log.1 --out x.html
```

Next to a JFR from `record`, the `.log`, `.proc.csv` and `.properties` files are picked up automatically. Otherwise `--log` is required.

Other options:
- `--event auto|wall|cpu`: which sample event to use when the JFR has both.
- `--interval MS`: the sample interval, if it can't be inferred from the samples.

## License

Apache License 2.0, see [LICENSE](LICENSE).
