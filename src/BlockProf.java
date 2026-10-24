import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/** Records an async-profiler capture of a running Besu and turns it into a per-block HTML report. */
public final class BlockProf {

  static final String USAGE =
      """
      usage:
        blockprof record [--pid N] [--log FILE]... [--out DIR] [--pad MS] [--asprof PATH] -- <asprof args>
        blockprof report --jfr FILE [--log FILE]... [--out FILE] [--pad MS] [--event auto|wall|cpu] [--interval MS]

      record  runs asprof with the given arguments against Besu, writing JFR, then writes the report.
              The pid defaults to the one process running org.hyperledger.besu.Besu, the log to
              /var/log/besu/besu.log. asprof must run as the user of the Besu JVM, and that user
              writes the files, so pick an --out it can write to.
              example: sudo -u execution blockprof record --out /tmp/blockprof -- -d 300 -i 5ms -e wall --cstack vm

      report  builds the HTML from a JFR and the besu log lines that cover it. Next to a JFR from
              record, the .log and .properties files it wrote are picked up automatically.
      """;

  static final class UsageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  public static void main(String[] args) {
    try {
      if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) {
        System.out.print(USAGE);
        return;
      }
      List<String> rest = List.of(args).subList(1, args.length);
      switch (args[0]) {
        case "record" -> record(rest);
        case "report" -> report(rest);
        default -> throw new UsageException("unknown command " + args[0]);
      }
    } catch (UsageException e) {
      System.err.println("blockprof: " + e.getMessage());
      System.err.print(USAGE);
      System.exit(2);
    } catch (Exception e) {
      System.err.println("blockprof: " + e.getMessage());
      System.exit(1);
    }
  }

  // ---------- record ----------

  // asprof actions other than the default collect would not leave a finished JFR behind
  static final Set<String> OTHER_ACTIONS =
      Set.of("start", "resume", "stop", "dump", "check", "status", "meminfo", "list", "version");

  static void record(List<String> args) throws Exception {
    String pid = null;
    List<Path> logs = new ArrayList<>();
    Path outDir = Path.of(".");
    long pad = 50;
    String asprof = null;
    List<String> ap = null;
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      if (a.equals("--")) {
        ap = new ArrayList<>(args.subList(i + 1, args.size()));
        break;
      }
      switch (a) {
        case "--pid" -> pid = value(args, ++i, a);
        case "--log" -> logs.add(Path.of(value(args, ++i, a)));
        case "--out" -> outDir = Path.of(value(args, ++i, a));
        case "--pad" -> pad = Long.parseLong(value(args, ++i, a));
        case "--asprof" -> asprof = value(args, ++i, a);
        default -> throw new UsageException("unknown option " + a + " (asprof arguments go after --)");
      }
    }
    if (ap == null || ap.isEmpty()) {
      throw new UsageException(
          "no asprof arguments; put them after --, e.g. -- -d 300 -i 5ms -e wall --cstack vm");
    }
    for (String t : ap) {
      if (OTHER_ACTIONS.contains(t)) {
        throw new UsageException("asprof action " + t + " is not supported; record runs a timed collect (-d N)");
      }
    }
    int o = ap.indexOf("-o");
    if (o >= 0 && (o + 1 >= ap.size() || !ap.get(o + 1).equals("jfr"))) {
      throw new UsageException("the report needs JFR output; drop -o or use -o jfr");
    }
    // a trailing number that is not an option's value is asprof's pid
    String last = ap.get(ap.size() - 1);
    if (last.matches("\\d+") && (ap.size() == 1 || !ap.get(ap.size() - 2).startsWith("-"))) {
      if (pid != null && !pid.equals(last)) throw new UsageException("pid given twice: " + pid + " and " + last);
      pid = last;
      ap.remove(ap.size() - 1);
    }
    if (!ap.contains("-d")) {
      System.err.println("blockprof: no -d given, asprof collects for its default 60 s");
    }

    if (logs.isEmpty()) {
      Path dflt = Path.of("/var/log/besu/besu.log");
      if (!Files.isReadable(dflt)) throw new UsageException("no --log given and " + dflt + " is not readable");
      logs.add(dflt);
    }
    for (Path l : logs) if (!Files.isReadable(l)) throw new IllegalStateException("cannot read log " + l);
    if (pid == null) pid = findBesuPid();
    String asprofPath = findAsprof(asprof);

    // the agent inside the target JVM writes the file, so its path must not depend on our cwd
    Path jfr;
    int f = ap.indexOf("-f");
    if (f >= 0) {
      if (f + 1 >= ap.size() || !ap.get(f + 1).endsWith(".jfr")) {
        throw new UsageException("-f must name a .jfr file");
      }
      jfr = Path.of(ap.get(f + 1)).toAbsolutePath();
      ap.set(f + 1, jfr.toString());
    } else {
      Files.createDirectories(outDir);
      String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
      jfr = outDir.resolve("blockprof-" + stamp + ".jfr").toAbsolutePath();
      ap.add("-f");
      ap.add(jfr.toString());
    }
    Files.createDirectories(jfr.getParent());
    String base = stripJfr(jfr);
    String userArgs = String.join(" ", ap.subList(0, f >= 0 ? ap.size() : ap.size() - 2));

    List<String> cmd = new ArrayList<>();
    cmd.add(asprofPath);
    cmd.addAll(ap);
    cmd.add(pid);
    System.err.println("blockprof: " + String.join(" ", cmd));
    Instant start = Instant.now();
    int rc = new ProcessBuilder(cmd).inheritIO().start().waitFor();
    Instant end = Instant.now();
    boolean alive = Files.exists(Path.of("/proc", pid));
    if (!alive) {
      System.err.println("blockprof: warning: pid " + pid + " is gone, the JVM died during the capture");
    }
    if (rc != 0) throw new IllegalStateException("asprof exited with " + rc);
    if (!Files.exists(jfr) || Files.size(jfr) == 0) throw new IllegalStateException("asprof wrote no JFR at " + jfr);

    // keep only the log lines the report needs, so the capture can be re-reported elsewhere
    Path slice = Path.of(base + ".log");
    long from = start.toEpochMilli() - 60_000, to = end.toEpochMilli() + 60_000;
    int kept = 0;
    try (BufferedWriter w = Files.newBufferedWriter(slice, StandardCharsets.UTF_8)) {
      for (Path l : logs) {
        try (Stream<String> lines = Files.lines(l, StandardCharsets.UTF_8)) {
          for (String line : (Iterable<String>) lines::iterator) {
            if (!line.contains("Imported #") && !line.contains("FCU(")) continue;
            String clean = Converter.stripAnsi(line);
            long t = Converter.lineTime(clean);
            if (t >= from && t <= to) {
              w.write(clean);
              w.newLine();
              kept++;
            }
          }
        }
      }
    }
    if (kept == 0) {
      throw new IllegalStateException(
          "no Imported/FCU lines in " + logs + " between " + Instant.ofEpochMilli(from) + " and "
              + Instant.ofEpochMilli(to) + "; is that the log of this Besu?");
    }

    Properties p = new Properties();
    p.setProperty("asprofArgs", userArgs);
    p.setProperty("pid", pid);
    p.setProperty("host", hostName());
    p.setProperty("start", start.toString());
    p.setProperty("end", end.toString());
    try (Writer w = Files.newBufferedWriter(Path.of(base + ".properties"), StandardCharsets.UTF_8)) {
      p.store(w, "blockprof record");
    }

    writeReport(jfr, List.of(slice), Path.of(base + ".html"), pad, "auto", null, p);
  }

  // ---------- report ----------

  static void report(List<String> args) throws Exception {
    Path jfr = null, out = null;
    List<Path> logs = new ArrayList<>();
    long pad = 50;
    String event = "auto";
    Double interval = null;
    for (int i = 0; i < args.size(); i++) {
      String a = args.get(i);
      switch (a) {
        case "--jfr" -> jfr = Path.of(value(args, ++i, a));
        case "--log" -> logs.add(Path.of(value(args, ++i, a)));
        case "--out" -> out = Path.of(value(args, ++i, a));
        case "--pad" -> pad = Long.parseLong(value(args, ++i, a));
        case "--event" -> event = value(args, ++i, a);
        case "--interval" -> interval = Double.parseDouble(value(args, ++i, a));
        default -> throw new UsageException("unknown option " + a);
      }
    }
    if (jfr == null) throw new UsageException("--jfr is required");
    if (!Files.isReadable(jfr)) throw new IllegalStateException("cannot read " + jfr);
    String base = stripJfr(jfr.toAbsolutePath());
    if (logs.isEmpty()) {
      Path slice = Path.of(base + ".log");
      if (!Files.isReadable(slice)) {
        throw new UsageException("--log is required (no " + slice.getFileName() + " next to the JFR)");
      }
      logs.add(slice);
    }
    Properties p = new Properties();
    Path props = Path.of(base + ".properties");
    if (Files.isReadable(props)) {
      try (Reader r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
        p.load(r);
      }
    }
    writeReport(jfr, logs, out != null ? out : Path.of(base + ".html"), pad, event, interval, p);
  }

  static void writeReport(
      Path jfr, List<Path> logs, Path out, long pad, String event, Double interval, Properties p)
      throws IOException {
    Converter.Result r =
        Converter.convert(
            new Converter.Options(
                jfr, logs, pad, event, interval, p.getProperty("asprofArgs"), p.getProperty("pid"),
                p.getProperty("host")));
    Files.writeString(out, Converter.html(r.json()), StandardCharsets.UTF_8);
    System.err.printf(
        "blockprof: %d blocks (of %d in the log), %d samples, %s every %s ms%n  report %s%n",
        r.blocks(), r.logBlocks(), r.samples(),
        r.event().equals(Converter.WALL) ? "wall clock" : "CPU", Converter.fmtMs(r.intervalMs()),
        out.toAbsolutePath());
  }

  // ---------- helpers ----------

  static String value(List<String> args, int i, String option) {
    if (i >= args.size()) throw new UsageException(option + " needs a value");
    return args.get(i);
  }

  static String stripJfr(Path jfr) {
    String s = jfr.toString();
    return s.endsWith(".jfr") ? s.substring(0, s.length() - 4) : s;
  }

  static String findBesuPid() throws IOException {
    List<String> found = new ArrayList<>();
    String self = String.valueOf(ProcessHandle.current().pid());
    try (DirectoryStream<Path> procs =
        Files.newDirectoryStream(Path.of("/proc"), d -> d.getFileName().toString().matches("\\d+"))) {
      for (Path d : procs) {
        try {
          String cmd = new String(Files.readAllBytes(d.resolve("cmdline")), StandardCharsets.UTF_8);
          String pid = d.getFileName().toString();
          if (cmd.contains("org.hyperledger.besu.Besu") && !pid.equals(self)) found.add(pid);
        } catch (IOException gone) {
          // the process exited while we looked
        }
      }
    }
    if (found.isEmpty()) {
      throw new IllegalStateException("no running Besu (no process with org.hyperledger.besu.Besu); pass --pid");
    }
    if (found.size() > 1) throw new IllegalStateException("several Besu processes " + found + "; pass --pid");
    return found.get(0);
  }

  static String findAsprof(String given) throws IOException {
    if (given != null) {
      if (!Files.isExecutable(Path.of(given))) throw new IllegalStateException(given + " is not executable");
      return given;
    }
    for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
      Path p = Path.of(dir, "asprof");
      if (!dir.isEmpty() && Files.isExecutable(p)) return p.toString();
    }
    List<Path> roots = new ArrayList<>(List.of(Path.of("/opt")));
    String home = System.getProperty("user.home");
    if (home != null) roots.add(Path.of(home, "opt"));
    String best = null;
    for (Path root : roots) {
      if (!Files.isDirectory(root)) continue;
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "async-profiler*")) {
        for (Path d : ds) {
          Path p = d.resolve("bin/asprof");
          if (Files.isExecutable(p) && (best == null || p.toString().compareTo(best) > 0)) best = p.toString();
        }
      }
    }
    if (best == null) throw new IllegalStateException("asprof not found on PATH or in /opt; pass --asprof PATH");
    return best;
  }

  static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException e) {
      try {
        return Files.readString(Path.of("/etc/hostname")).trim();
      } catch (IOException e2) {
        return "unknown host";
      }
    }
  }

  private BlockProf() {}
}
