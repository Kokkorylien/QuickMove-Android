package com.kokkorylien.quickmove;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Filesystem integration cases. Run without Android or an emulator. */
public final class TransferEngineTest {
  static Path sandbox;
  static int assertions;

  static final class Checkpoints implements TransferEngine.Checkpoints {
    final Map<String, TransferEngine.Record> records = new HashMap<>();

    public TransferEngine.Record get(String source) {
      return records.get(source);
    }

    public void put(String source, TransferEngine.Record value) {
      records.put(source, value);
    }

    public Map<String, TransferEngine.Record> records() {
      return records;
    }

    final List<String> renamed = new ArrayList<>();

    public void renamed(String source, String target, boolean directory) {
      renamed.add(source + " → " + target);
    }
  }

  static class Disk implements TransferEngine.Node {
    final Path path;

    Disk(Path path) {
      this.path = path;
    }

    public String ref() {
      return path.toString();
    }

    public String identity() {
      return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    public String name() {
      return path.getFileName().toString();
    }

    public boolean directory() {
      return Files.isDirectory(path);
    }

    public boolean exists() {
      return Files.exists(path);
    }

    public long size() throws IOException {
      return Files.size(path);
    }

    public long modified() throws IOException {
      return Files.getLastModifiedTime(path).toMillis();
    }

    public List<TransferEngine.Node> children() throws IOException {
      List<TransferEngine.Node> result = new ArrayList<>();
      try (var stream = Files.list(path)) {
        stream.sorted().forEach(p -> result.add(new Disk(p)));
      }
      return result;
    }

    public TransferEngine.Node createDirectory(String name) throws IOException {
      return new Disk(Files.createDirectory(path.resolve(name)));
    }

    public TransferEngine.Node createFile(String name) throws IOException {
      return new Disk(Files.createFile(path.resolve(name)));
    }

    public InputStream read() throws IOException {
      return Files.newInputStream(path);
    }

    public OutputStream write() throws IOException {
      return Files.newOutputStream(path);
    }

    public TransferEngine.Node rename(String name) throws IOException {
      return new Disk(Files.move(path, path.resolveSibling(name)));
    }

    public void delete() throws IOException {
      Files.delete(path);
    }
  }

  static TransferEngine.Observer idle =
      new TransferEngine.Observer() {
        public boolean cancelled() {
          return false;
        }

        public void progress(String p, long c, long t, long b, int f) {}
      };

  static void check(boolean success, String message) {
    assertions++;
    if (!success) throw new AssertionError(message);
  }

  static String read(Path path) throws IOException {
    return Files.readString(path);
  }

  static Path area(String name) throws IOException {
    Path area = Files.createDirectory(sandbox.resolve(name));
    Files.createDirectories(area.resolve("from"));
    Files.createDirectories(area.resolve("to"));
    return area;
  }

  static TransferEngine engine(boolean move, Checkpoints cp, TransferEngine.Observer observer) {
    return new TransferEngine(ref -> new Disk(Path.of(ref)), cp, observer, move);
  }

  static void rejected(List<TransferEngine.Node> sources, TransferEngine.Node to) throws Exception {
    try {
      TransferEngine.validate(sources, to);
      throw new AssertionError("unsafe target accepted");
    } catch (IOException expected) {
      assertions++;
    }
  }

  public static void main(String[] args) throws Exception {
    sandbox = Files.createTempDirectory("quickmove-tests-");
    try {
      copyTreeAndMerge();
      moveTree();
      collisions();
      unsafePaths();
      writeFailure();
      cancelResume();
      mutatingSource();
      missingCard();
      deleteFailure();
      emptyAndUnicode();
      largeStreaming();
      nativeMove();
      directoryListingCost();
      totalProgress();
      emojiStorage();
      nestedNativeNaming();
      System.out.println("PASS: 16 filesystem scenarios, " + assertions + " assertions");
    } finally {
      try (var paths = Files.walk(sandbox)) {
        paths
            .sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      }
    }
  }

  static void copyTreeAndMerge() throws Exception {
    Path p = area("copy");
    Files.createDirectories(p.resolve("from/Download/sub"));
    Files.writeString(p.resolve("from/Download/sub/a.txt"), "hello");
    Files.createDirectories(p.resolve("to/Download"));
    Files.writeString(p.resolve("to/Download/existing.txt"), "keep");
    var result =
        engine(false, new Checkpoints(), idle)
            .run(List.of(new Disk(p.resolve("from/Download"))), new Disk(p.resolve("to")));
    check(result.failures == 0 && result.files == 1, "copy result");
    check(read(p.resolve("to/Download/sub/a.txt")).equals("hello"), "nested copy");
    check(Files.exists(p.resolve("from/Download/sub/a.txt")), "copy retains source");
    check(read(p.resolve("to/Download/existing.txt")).equals("keep"), "merge retains existing");
  }

  static void moveTree() throws Exception {
    Path p = area("move");
    Files.createDirectories(p.resolve("from/Download/empty"));
    Files.writeString(p.resolve("from/Download/a"), "moved");
    var result =
        engine(true, new Checkpoints(), idle)
            .run(List.of(new Disk(p.resolve("from/Download"))), new Disk(p.resolve("to")));
    check(
        result.failures == 0 && !Files.exists(p.resolve("from/Download")),
        "move removes source directory");
    check(read(p.resolve("to/Download/a")).equals("moved"), "move content");
    check(Files.isDirectory(p.resolve("to/Download/empty")), "move empty directory");
  }

  static void collisions() throws Exception {
    Path p = area("collisions");
    Files.writeString(p.resolve("from/a.txt"), "new");
    Files.writeString(p.resolve("to/a.txt"), "old");
    Files.writeString(p.resolve("to/a (2).txt"), "older");
    engine(false, new Checkpoints(), idle)
        .run(List.of(new Disk(p.resolve("from/a.txt"))), new Disk(p.resolve("to")));
    check(read(p.resolve("to/a.txt")).equals("old"), "no overwrite");
    check(read(p.resolve("to/a (3).txt")).equals("new"), "numbered collision");
    Files.createDirectories(p.resolve("from/folder"));
    Files.writeString(p.resolve("to/folder"), "file");
    engine(false, new Checkpoints(), idle)
        .run(List.of(new Disk(p.resolve("from/folder"))), new Disk(p.resolve("to")));
    check(Files.isDirectory(p.resolve("to/folder (2)")), "file-directory collision");
  }

  static void unsafePaths() throws Exception {
    Path p = area("unsafe");
    Files.createDirectories(p.resolve("from/sub"));
    Files.writeString(p.resolve("from/sub/a"), "x");
    rejected(List.of(new Disk(p.resolve("from"))), new Disk(p.resolve("from")));
    rejected(List.of(new Disk(p.resolve("from"))), new Disk(p.resolve("from/sub")));
    rejected(List.of(new Disk(p.resolve("from/sub/a"))), new Disk(p.resolve("from/sub")));
    rejected(
        List.of(new Disk(p.resolve("from")), new Disk(p.resolve("from/sub/a"))),
        new Disk(p.resolve("to")));
  }

  static void writeFailure() throws Exception {
    Path p = area("failure");
    Files.writeString(p.resolve("from/a"), "must survive");
    Disk failing =
        new Disk(p.resolve("to")) {
          public TransferEngine.Node createFile(String name) throws IOException {
            Path partial = Files.createFile(path.resolve(name));
            return new Disk(partial) {
              public OutputStream write() {
                return new OutputStream() {
                  public void write(int b) throws IOException {
                    throw new IOException("disk full");
                  }

                  public void write(byte[] b, int o, int l) throws IOException {
                    throw new IOException("disk full");
                  }
                };
              }
            };
          }
        };
    var result =
        engine(true, new Checkpoints(), idle).run(List.of(new Disk(p.resolve("from/a"))), failing);
    check(result.failures == 1, "write failure reported");
    check(read(p.resolve("from/a")).equals("must survive"), "failed move retains source");
    try (var list = Files.list(p.resolve("to"))) {
      check(list.count() == 0, "partial cleaned");
    }
  }

  static void cancelResume() throws Exception {
    Path p = area("resume");
    Files.createDirectories(p.resolve("from/folder"));
    Files.writeString(p.resolve("from/folder/a"), "one");
    Files.writeString(p.resolve("from/folder/b"), "two");
    Checkpoints cp = new Checkpoints();
    var observer =
        new TransferEngine.Observer() {
          boolean stop;

          public boolean cancelled() {
            return stop;
          }

          public void progress(String path, long c, long t, long bytes, int files) {
            if (files >= 1) stop = true;
          }
        };
    try {
      engine(false, cp, observer)
          .run(List.of(new Disk(p.resolve("from/folder"))), new Disk(p.resolve("to")));
      throw new AssertionError("did not cancel");
    } catch (TransferEngine.Cancelled expected) {
      assertions++;
    }
    var result =
        engine(false, cp, idle)
            .run(List.of(new Disk(p.resolve("from/folder"))), new Disk(p.resolve("to")));
    check(result.failures == 0, "resume succeeds");
    try (var list = Files.list(p.resolve("to/folder"))) {
      check(list.count() == 2, "resume avoids duplicate completed file");
    }
    check(read(p.resolve("to/folder/b")).equals("two"), "remaining file copied");
  }

  static void mutatingSource() throws Exception {
    Path p = area("mutating");
    Files.writeString(p.resolve("from/a"), "original");
    var observer =
        new TransferEngine.Observer() {
          boolean mutated;

          public boolean cancelled() {
            return false;
          }

          public void progress(String n, long c, long t, long b, int f) {
            if (!mutated && c > 0) {
              mutated = true;
              try {
                Files.writeString(p.resolve("from/a"), "changed to another size");
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
          }
        };
    var result =
        engine(true, new Checkpoints(), observer)
            .run(List.of(new Disk(p.resolve("from/a"))), new Disk(p.resolve("to")));
    check(result.failures == 1 && Files.exists(p.resolve("from/a")), "changing source retained");
  }

  static void missingCard() throws Exception {
    Path p = area("missing");
    Checkpoints cp = new Checkpoints();
    Disk absent = new Disk(p.resolve("from/gone"));
    cp.put(
        absent.identity(),
        new TransferEngine.Record(p.resolve("to/partial").toString(), 10, 0, false, false));
    try {
      engine(true, cp, idle).run(List.of(absent), new Disk(p.resolve("to")));
      throw new AssertionError("missing incomplete source accepted");
    } catch (IOException expected) {
      assertions++;
    }
  }

  static void deleteFailure() throws Exception {
    Path p = area("delete");
    Files.writeString(p.resolve("from/a"), "safe");
    Disk protectedSource =
        new Disk(p.resolve("from/a")) {
          public void delete() throws IOException {
            throw new IOException("read only source");
          }
        };
    var result =
        engine(true, new Checkpoints(), idle)
            .run(List.of(protectedSource), new Disk(p.resolve("to")));
    check(result.failures == 1, "deletion failure reported");
    check(
        read(p.resolve("from/a")).equals("safe") && read(p.resolve("to/a")).equals("safe"),
        "delete failure retains both");
  }

  static void emptyAndUnicode() throws Exception {
    Path p = area("names");
    Files.write(p.resolve("from/空 格 🐟️.txt"), new byte[0]);
    var result =
        engine(false, new Checkpoints(), idle)
            .run(List.of(new Disk(p.resolve("from/空 格 🐟️.txt"))), new Disk(p.resolve("to")));
    check(
        result.files == 1 && Files.size(p.resolve("to/空 格 🐟️.txt")) == 0,
        "zero byte and Unicode filename");
  }

  static void totalProgress() throws Exception {
    Path p = area("overall");
    Files.createDirectories(p.resolve("from/folder"));
    Files.writeString(p.resolve("from/folder/a"), "123");
    Files.writeString(p.resolve("from/folder/b"), "1234567");
    Checkpoints cp = new Checkpoints();
    class Observe implements TransferEngine.Observer {
      long done, total = -1;
      int count, all;
      boolean scanned, stop;

      public boolean cancelled() {
        return stop;
      }

      public void overall(long d, long t, int f, int tf, boolean scanning) {
        done = d;
        total = t;
        count = f;
        all = tf;
        scanned |= scanning;
      }

      public void progress(String name, long c, long t, long bytes, int files) {
        if (files >= 1) stop = true;
      }
    }
    Observe first = new Observe();
    try {
      new TransferEngine(ref -> new Disk(Path.of(ref)), cp, first, true, null, true)
          .run(List.of(new Disk(p.resolve("from/folder"))), new Disk(p.resolve("to")));
      throw new AssertionError("expected pause");
    } catch (TransferEngine.Cancelled expected) {
    }
    check(
        first.scanned && first.total == 10 && first.done == 3 && first.all == 2,
        "whole task measured and first commit counted");
    Observe resumed =
        new Observe() {
          public void progress(String name, long c, long t, long bytes, int files) {}
        };
    var result =
        new TransferEngine(ref -> new Disk(Path.of(ref)), cp, resumed, true, null, true)
            .run(List.of(new Disk(p.resolve("from/folder"))), new Disk(p.resolve("to")));
    check(
        result.totalBytes == 10 && result.completedBytes == 10 && result.files == 2,
        "resume includes already moved bytes in numerator and denominator");
    check(
        resumed.done == 10 && resumed.total == 10 && resumed.count == 2,
        "overall ends at actual complete total");
    Path q = area("quick");
    Files.writeString(q.resolve("from/a"), "fast");
    Observe quick =
        new Observe() {
          public void progress(String n, long c, long t, long b, int f) {}
        };
    new TransferEngine(ref -> new Disk(Path.of(ref)), new Checkpoints(), quick, false, null, false)
        .run(List.of(new Disk(q.resolve("from/a"))), new Disk(q.resolve("to")));
    check(
        !quick.scanned && quick.total == -1,
        "quick mode performs no measurement and never invents total");
  }

  static final class Restricted extends Disk {
    Restricted(Path path) {
      super(path);
    }

    static void accept(String name) throws IOException {
      if (name.codePoints().anyMatch(cp -> cp > 0xFFFF))
        throw new IOException("TF does not support emoji names");
    }

    public TransferEngine.Node createDirectory(String name) throws IOException {
      accept(name);
      return new Restricted(Files.createDirectory(path.resolve(name)));
    }

    public TransferEngine.Node createFile(String name) throws IOException {
      accept(name);
      return new Restricted(Files.createFile(path.resolve(name)));
    }

    public TransferEngine.Node rename(String name) throws IOException {
      accept(name);
      return new Restricted(Files.move(path, path.resolveSibling(name)));
    }
  }

  static void emojiStorage() throws Exception {
    Path p = area("emoji");
    Files.createDirectories(p.resolve("from/🐉 folder/🐟"));
    Files.writeString(p.resolve("from/🐉 folder/🐟/🐉 title.txt"), "dragon");
    Files.writeString(p.resolve("from/🐉 folder/🐟/🐟 title.txt"), "fish");
    Checkpoints cp = new Checkpoints();
    var result =
        new TransferEngine(
                ref -> new Restricted(Path.of(ref)),
                cp,
                idle,
                true,
                FilenamePolicyTest.policy("underscore", false),
                true)
            .run(List.of(new Disk(p.resolve("from/🐉 folder"))), new Restricted(p.resolve("to")));
    check(
        result.failures == 0 && !Files.exists(p.resolve("from/🐉 folder")),
        "nested emoji directory and file move on restricted media");
    check(
        read(p.resolve("to/_ folder/_/_ title.txt")).equals("dragon")
            && read(p.resolve("to/_ folder/_/_ title (2).txt")).equals("fish"),
        "emoji collision retains both source contents");
    check(cp.renamed.size() == 4, "actual file and directory renames recorded");
    Path q = area("emojiEnglish");
    Files.createDirectories(q.resolve("from/🐉"));
    Files.writeString(q.resolve("from/🐉/🐟️.txt"), "fish");
    result =
        new TransferEngine(
                ref -> new Restricted(Path.of(ref)),
                new Checkpoints(),
                idle,
                false,
                FilenamePolicyTest.policy("english", true),
                true)
            .run(List.of(new Disk(q.resolve("from/🐉"))), new Restricted(q.resolve("to")));
    check(
        result.failures == 0 && read(q.resolve("to/Dragon/Fish.txt")).equals("fish"),
        "English names and capitalization reach actual target");
  }

  static void nestedNativeNaming() throws Exception {
    Path p = area("nestedNative");
    Files.createDirectories(p.resolve("from/normal"));
    Files.writeString(p.resolve("from/normal/🐉.txt"), "safe");
    Disk source =
        new Disk(p.resolve("from/normal")) {
          public TransferEngine.Node tryMove(TransferEngine.Node parent) throws IOException {
            throw new AssertionError("directory fast move bypassed nested filename policy");
          }
        };
    var result =
        new TransferEngine(
                ref -> new Restricted(Path.of(ref)),
                new Checkpoints(),
                idle,
                true,
                FilenamePolicyTest.policy("underscore", false),
                true)
            .run(List.of(source), new Restricted(p.resolve("to")));
    check(
        result.failures == 0 && read(p.resolve("to/normal/_.txt")).equals("safe"),
        "native directory optimization respects descendant renaming");
  }

  static void nativeMove() throws Exception {
    Path p = area("native");
    Files.createDirectories(p.resolve("from/Download"));
    Files.writeString(p.resolve("from/Download/a"), "renamed");
    Checkpoints cp = new Checkpoints();
    Disk source =
        new Disk(p.resolve("from/Download")) {
          public TransferEngine.Node tryMove(TransferEngine.Node parent) throws IOException {
            return new Disk(Files.move(path, ((Disk) parent).path.resolve(name())));
          }
        };
    var result = engine(true, cp, idle).run(List.of(source), new Disk(p.resolve("to")));
    check(result.bytes == 0 && result.folders == 1, "native move bypasses streaming and prewalk");
    check(
        !Files.exists(p.resolve("from/Download"))
            && read(p.resolve("to/Download/a")).equals("renamed"),
        "native move preserves content");
    result = engine(true, cp, idle).run(List.of(source), new Disk(p.resolve("to")));
    check(result.failures == 0, "resume accepts committed native move");
  }

  static void directoryListingCost() throws Exception {
    Path p = area("listing");
    List<TransferEngine.Node> sources = new ArrayList<>();
    for (int i = 0; i < 80; i++) {
      Path file = p.resolve("from/f" + i);
      Files.writeString(file, "data");
      sources.add(new Disk(file));
    }
    int[] listings = {0};
    Disk target =
        new Disk(p.resolve("to")) {
          public List<TransferEngine.Node> children() throws IOException {
            listings[0]++;
            return super.children();
          }
        };
    var result = engine(false, new Checkpoints(), idle).run(sources, target);
    check(result.files == 80 && result.failures == 0, "bulk file copy");
    check(listings[0] == 1, "one shared destination listing for all siblings");
  }

  static void largeStreaming() throws Exception {
    Path p = area("large");
    byte[] data = new byte[5 * 1024 * 1024 + 13];
    new Random(7).nextBytes(data);
    Files.write(p.resolve("from/large.bin"), data);
    var result =
        engine(false, new Checkpoints(), idle)
            .run(List.of(new Disk(p.resolve("from/large.bin"))), new Disk(p.resolve("to")));
    check(result.bytes == data.length, "streamed byte count");
    check(
        Arrays.equals(data, Files.readAllBytes(p.resolve("to/large.bin"))),
        "multi-buffer copy integrity");
  }
}
