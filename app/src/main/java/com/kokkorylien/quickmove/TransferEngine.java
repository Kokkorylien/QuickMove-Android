package com.kokkorylien.quickmove;

import java.io.*;
import java.util.*;

/** Sequential streaming copy with optional metadata-only measurement and durable file commits. */
public final class TransferEngine {
  public interface Node {
    String ref();

    String identity() throws IOException;

    String name();

    boolean directory();

    long size() throws IOException;

    long modified() throws IOException;

    boolean exists() throws IOException;

    List<Node> children() throws IOException;

    Node createDirectory(String name) throws IOException;

    Node createFile(String name) throws IOException;

    InputStream read() throws IOException;

    OutputStream write() throws IOException;

    Node rename(String name) throws IOException;

    default Node tryMove(Node parent) throws IOException {
      return null;
    }

    default Node tryMove(Node parent, String name) throws IOException {
      return name().equals(name) ? tryMove(parent) : null;
    }

    void delete() throws IOException;
  }

  public interface Resolver {
    Node resolve(String ref) throws IOException;
  }

  public interface Checkpoints {
    Record get(String source);

    void put(String source, Record record) throws IOException;

    default Map<String, Record> records() {
      return Collections.emptyMap();
    }

    default void renamed(String source, String target, boolean directory) throws IOException {}
  }

  public static final class Record {
    public final String target;
    public final long size, modified;
    public final boolean directory, committed;
    public final int nativeFiles;

    public Record(String target, long size, long modified, boolean directory) {
      this(target, size, modified, directory, true);
    }

    public Record(String target, long size, long modified, boolean directory, boolean committed) {
      this(target, size, modified, directory, committed, -1);
    }

    public Record(
        String target,
        long size,
        long modified,
        boolean directory,
        boolean committed,
        int nativeFiles) {
      this.target = target;
      this.size = size;
      this.modified = modified;
      this.directory = directory;
      this.committed = committed;
      this.nativeFiles = nativeFiles;
    }
  }

  public interface Observer {
    boolean cancelled();

    void progress(String path, long current, long total, long bytes, int files);

    default void overall(long done, long total, int files, int totalFiles, boolean scanning) {}
  }

  public static final class Cancelled extends IOException {
    public Cancelled() {
      super("已暂停");
    }
  }

  public static final class Result {
    public long bytes, completedBytes, totalBytes = -1;
    public int totalFiles = -1;
    public int files, folders, failures;
    public final List<String> errors = new ArrayList<>();
  }

  private final Resolver resolver;
  private final Checkpoints checkpoints;
  private final Observer observer;
  private final boolean move;
  private final FilenamePolicy naming;
  private final boolean measure;
  private final Set<String> credited = new HashSet<>();
  private final Map<String, Measure> measuredDirectories = new HashMap<>();
  private final Map<String, Long> measuredFiles = new HashMap<>();
  private final byte[] buffer = new byte[1024 * 1024];
  private final Result result = new Result();

  public TransferEngine(
      Resolver resolver, Checkpoints checkpoints, Observer observer, boolean move) {
    this(resolver, checkpoints, observer, move, null, false);
  }

  public TransferEngine(
      Resolver resolver,
      Checkpoints checkpoints,
      Observer observer,
      boolean move,
      FilenamePolicy naming,
      boolean measure) {
    this.resolver = resolver;
    this.checkpoints = checkpoints;
    this.observer = observer;
    this.move = move;
    this.naming = naming;
    this.measure = measure;
  }

  public static void validate(List<Node> sources, Node target) throws IOException {
    if (!target.exists() || !target.directory()) throw new IOException("目标目录不存在或不可访问");
    String destination = target.identity();
    Set<String> ids = new HashSet<>();
    for (Node source : sources) {
      String id = source.identity();
      if (!source.exists()) throw new IOException("源路径不存在：" + source.name());
      if (!ids.add(id)) throw new IOException("重复的源路径：" + source.name());
      if (destination.equals(id) || destination.startsWith(id + "/"))
        throw new IOException("不能搬运到源目录自身或其子目录：" + source.name());
      int slash = id.lastIndexOf('/');
      if (slash >= 0 && id.substring(0, slash).equals(destination))
        throw new IOException("目标就是源文件的当前位置：" + source.name());
    }
    for (String id : ids)
      for (String other : ids)
        if (!id.equals(other) && id.startsWith(other + "/"))
          throw new IOException("源路径不能同时包含父目录及其内部文件，请只选择父目录");
  }

  public Result run(List<Node> sources, Node destination) throws IOException {
    for (Map.Entry<String, Record> entry : checkpoints.records().entrySet()) {
      Record record = entry.getValue();
      if (record.committed && (!record.directory || record.nativeFiles >= 0)) {
        credited.add(entry.getKey());
        result.completedBytes += record.size;
        result.files += record.directory ? record.nativeFiles : 1;
      }
    }
    // Missing sources are allowed only when a previous move committed them.
    List<Node> present = new ArrayList<>();
    for (Node source : sources) {
      if (source.exists()) present.add(source);
      else {
        Record record = checkpoints.get(source.identity());
        if (record == null || !record.committed)
          throw new IOException("源路径不存在或存储介质已断开：" + source.name());
        Node done = resolver.resolve(record.target);
        if (!done.exists()) throw new IOException("目标路径不可访问，请重新插入存储介质");
      }
    }
    validate(present, destination);
    if (measure) {
      Measure all = new Measure();
      for (Node source : present) all.add(scan(source, new HashSet<>()));
      result.totalBytes = result.completedBytes + all.bytes;
      result.totalFiles = result.files + all.files;
      emit("统计完成，准备搬运", 0, 0);
    }
    Map<String, Node> index = index(destination);
    for (Node source : present) {
      checkCancel();
      try {
        transfer(source, destination, index, new HashSet<>());
      } catch (Cancelled e) {
        throw e;
      } catch (IOException e) {
        failure(source, e);
      }
    }
    return result;
  }

  private boolean transfer(Node source, Node parent, Map<String, Node> index, Set<String> ancestors)
      throws IOException {
    checkCancel();
    String key = source.identity();
    String requested = naming == null ? source.name() : naming.apply(source.name());
    Measure subtree = measuredDirectories.get(key);
    boolean nativeDirectory =
        naming == null || naming.preservesNames() || (subtree != null && !subtree.descendantRename);
    if (move
        && checkpoints.get(key) == null
        && !index.containsKey(requested)
        && (!source.directory() || nativeDirectory)) {
      long size = source.directory() ? (subtree == null ? 0 : subtree.bytes) : source.size();
      long modified = source.directory() ? 0 : source.modified();
      Node relocated = source.tryMove(parent, requested);
      if (relocated != null) {
        int count = relocated.directory() ? (subtree == null ? -1 : subtree.files) : 1;
        checkpoints.put(
            key,
            new Record(
                relocated.ref(),
                relocated.directory() ? size : relocated.size(),
                modified,
                relocated.directory(),
                true,
                relocated.directory() ? count : -1));
        index.put(relocated.name(), relocated);
        recordRename(source, relocated);
        credit(key, size, Math.max(0, count));
        if (relocated.directory()) result.folders++;
        emit(relocated.name(), 0, 0);
        return true;
      }
    }
    if (source.directory()) {
      if (!ancestors.add(key)) throw new IOException("检测到目录循环");
      try {
        Record previous = checkpoints.get(key);
        Node dest;
        if (previous != null && previous.directory) {
          dest = resolver.resolve(previous.target);
          if (!dest.exists() || !dest.directory()) throw new IOException("中断前的目标目录已被移除，请新建任务");
        } else {
          dest = index.get(requested);
          if (dest == null || !dest.directory()) {
            String name = unique(requested, true, index);
            dest = parent.createDirectory(name);
            index.put(dest.name(), dest);
          }
          checkpoints.put(key, new Record(dest.ref(), 0, 0, true, false));
        }
        recordRename(source, dest);
        result.folders++;
        emit(source.name(), 0, 0);
        Map<String, Node> childIndex = index(dest);
        boolean success = true;
        for (Node child : source.children()) {
          checkCancel();
          try {
            success &= transfer(child, dest, childIndex, ancestors);
          } catch (Cancelled e) {
            throw e;
          } catch (IOException e) {
            failure(child, e);
            success = false;
          }
        }
        if (success) {
          checkpoints.put(key, new Record(dest.ref(), 0, 0, true));
          if (move) source.delete(); // deletion must fail if new children appeared
        }
        return success;
      } finally {
        ancestors.remove(key);
      }
    }
    long expected = source.size(), modified = source.modified();
    if (measure && !credited.contains(key)) {
      Long counted = measuredFiles.put(key, expected);
      if (counted == null) {
        result.totalBytes += expected;
        result.totalFiles++;
      } else result.totalBytes += expected - counted;
    }
    Record previous = checkpoints.get(key);
    if (previous != null && !previous.directory && previous.committed) {
      Node done = resolver.resolve(previous.target);
      if (previous.size != expected || previous.modified != modified)
        throw new IOException("暂停后源文件已改变，请新建任务：" + source.name());
      if (!done.exists() || done.size() != expected)
        throw new IOException("暂停后的目标文件缺失或大小变化，请新建任务：" + source.name());
      if (move) source.delete();
      credit(key, expected, 1);
      emit(source.name(), 0, 0);
      return true;
    }
    String finalName = unique(requested, false, index);
    if (previous != null && !previous.committed) {
      Node stale = resolver.resolve(previous.target);
      if (stale.exists()) stale.delete();
    }
    String tempName = ".quickmove-" + UUID.randomUUID() + ".part";
    Node partial = parent.createFile(tempName);
    boolean committed = false;
    long copied = 0;
    try {
      checkpoints.put(key, new Record(partial.ref(), expected, modified, false, false));
      try (InputStream in = source.read();
          OutputStream out = partial.write()) {
        int count;
        while ((count = in.read(buffer)) != -1) {
          checkCancel();
          out.write(buffer, 0, count);
          copied += count;
          result.bytes += count;
          emit(source.name(), copied, expected);
        }
        out.flush();
      }
      checkCancel();
      if (copied != expected
          || partial.size() != expected
          || source.size() != expected
          || source.modified() != modified) throw new IOException("文件写入不完整或源文件正在变化，已保留源文件");
      // The storage adapter refuses replacement; one directory listing is shared by siblings.
      Node done = partial.rename(finalName);
      committed = true;
      index.put(done.name(), done);
      checkpoints.put(key, new Record(done.ref(), expected, modified, false));
      recordRename(source, done);
      if (move) source.delete();
      credit(key, expected, 1);
      emit(source.name(), 0, 0);
      return true;
    } finally {
      if (!committed) {
        try {
          partial.delete();
        } catch (IOException cleanup) {
          failure(partial, new IOException("临时文件清理失败：" + cleanup.getMessage()));
        }
      }
    }
  }

  private void recordRename(Node source, Node target) throws IOException {
    if (!source.name().equals(target.name()))
      checkpoints.renamed(source.ref(), target.ref(), target.directory());
  }

  private void credit(String key, long size, int files) {
    if (credited.add(key)) {
      result.completedBytes += size;
      result.files += files;
    }
  }

  private void emit(String path, long current, long total) {
    observer.overall(
        result.completedBytes + current, result.totalBytes, result.files, result.totalFiles, false);
    observer.progress(path, current, total, result.bytes, result.files);
  }

  private static final class Measure {
    long bytes;
    int files;
    boolean descendantRename;

    void add(Measure child) {
      bytes += child.bytes;
      files += child.files;
      descendantRename |= child.descendantRename;
    }
  }

  private long discoveredBytes;
  private int discoveredFiles;

  private Measure scan(Node source, Set<String> ancestors) throws IOException {
    checkCancel();
    String id = source.identity();
    Measure summary = new Measure();
    if (source.directory()) {
      if (!ancestors.add(id)) throw new IOException("检测到目录循环：" + source.name());
      try {
        for (Node child : source.children()) {
          Measure part = scan(child, ancestors);
          summary.add(part);
          summary.descendantRename |=
              naming != null && !naming.apply(child.name()).equals(child.name());
        }
        measuredDirectories.put(id, summary);
      } finally {
        ancestors.remove(id);
      }
    } else if (!credited.contains(id)) {
      summary.bytes = source.size();
      summary.files = 1;
      measuredFiles.put(id, summary.bytes);
      discoveredBytes += summary.bytes;
      discoveredFiles++;
    }
    observer.overall(
        result.completedBytes,
        result.completedBytes + discoveredBytes,
        result.files,
        result.files + discoveredFiles,
        true);
    observer.progress(source.name(), 0, 0, result.bytes, result.files);
    return summary;
  }

  private void checkCancel() throws Cancelled {
    if (observer.cancelled()) throw new Cancelled();
  }

  private void failure(Node source, IOException e) {
    result.failures++;
    if (result.errors.size() < 100) result.errors.add(source.name() + "：" + e.getMessage());
  }

  private static Map<String, Node> index(Node directory) throws IOException {
    Map<String, Node> children = new HashMap<>();
    for (Node node : directory.children()) children.put(node.name(), node);
    return children;
  }

  static String unique(String requested, boolean directory, Map<String, Node> existing) {
    if (!existing.containsKey(requested)) return requested;
    int dot = directory ? -1 : requested.lastIndexOf('.');
    if (dot <= 0) dot = requested.length();
    String stem = requested.substring(0, dot), ext = requested.substring(dot);
    for (int i = 2; ; i++) {
      String name = stem + " (" + i + ")" + ext;
      if (!existing.containsKey(name)) return name;
    }
  }
}
