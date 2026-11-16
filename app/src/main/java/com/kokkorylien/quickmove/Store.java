package com.kokkorylien.quickmove;

import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class Store {
  final Context context;
  final android.content.SharedPreferences preferences;

  Store(Context context) {
    this.context = context.getApplicationContext();
    preferences = context.getSharedPreferences("quickmove", Context.MODE_PRIVATE);
  }

  static final class Task {
    String id = UUID.randomUUID().toString();
    String name = "", destination = "";
    boolean move;
    boolean measure = true, capitalize;
    String emojiMode = FilenamePolicy.UNDERSCORE;
    final ArrayList<String> sources = new ArrayList<>();

    JSONObject json() throws JSONException {
      return new JSONObject()
          .put("id", id)
          .put("name", name)
          .put("destination", destination)
          .put("move", move)
          .put("measure", measure)
          .put("emojiMode", emojiMode)
          .put("capitalize", capitalize)
          .put("sources", new JSONArray(sources));
    }

    static Task parse(JSONObject json) throws JSONException {
      Task task = new Task();
      task.id = json.getString("id");
      task.name = json.optString("name");
      task.destination = json.getString("destination");
      task.move = json.getBoolean("move");
      task.measure = json.optBoolean("measure", true);
      task.emojiMode = json.optString("emojiMode", FilenamePolicy.UNDERSCORE);
      task.capitalize = json.optBoolean("capitalize", false);
      JSONArray sources = json.getJSONArray("sources");
      for (int i = 0; i < sources.length(); i++) task.sources.add(sources.getString(i));
      return task;
    }
  }

  ArrayList<Task> tasks() {
    ArrayList<Task> result = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(preferences.getString("tasks", "[]"));
      for (int i = 0; i < array.length(); i++) result.add(Task.parse(array.getJSONObject(i)));
    } catch (JSONException ignored) {
    }
    return result;
  }

  void saveTask(Task task) throws JSONException {
    ArrayList<Task> tasks = tasks();
    tasks.removeIf(saved -> saved.id.equals(task.id));
    tasks.add(task);
    saveTasks(tasks);
  }

  void removeTask(String id) throws JSONException {
    ArrayList<Task> tasks = tasks();
    tasks.removeIf(task -> task.id.equals(id));
    saveTasks(tasks);
  }

  private void saveTasks(List<Task> tasks) throws JSONException {
    JSONArray array = new JSONArray();
    for (Task task : tasks) array.put(task.json());
    preferences.edit().putString("tasks", array.toString()).apply();
  }

  LinkedHashMap<String, String> favorites() {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    try {
      JSONArray array = new JSONArray(preferences.getString("favorites", "[]"));
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.getJSONObject(i);
        result.put(item.getString("ref"), item.getString("name"));
      }
    } catch (JSONException ignored) {
    }
    return result;
  }

  void favorite(String ref, String name) throws JSONException {
    LinkedHashMap<String, String> favorites = favorites();
    favorites.put(ref, name);
    saveFavorites(favorites);
  }

  void unfavorite(String ref) throws JSONException {
    LinkedHashMap<String, String> favorites = favorites();
    favorites.remove(ref);
    saveFavorites(favorites);
  }

  private void saveFavorites(Map<String, String> favorites) throws JSONException {
    JSONArray array = new JSONArray();
    for (Map.Entry<String, String> entry : favorites.entrySet())
      array.put(new JSONObject().put("ref", entry.getKey()).put("name", entry.getValue()));
    preferences.edit().putString("favorites", array.toString()).apply();
  }

  private AtomicFile activeFile() {
    return new AtomicFile(new File(context.getFilesDir(), "active.json"));
  }

  Task active() {
    try (InputStream in = activeFile().openRead()) {
      return Task.parse(new JSONObject(readText(in)));
    } catch (Exception e) {
      return null;
    }
  }

  void begin(Task task) throws Exception {
    AtomicFile active = activeFile();
    FileOutputStream out = active.startWrite();
    try {
      out.write(task.json().toString().getBytes(StandardCharsets.UTF_8));
      active.finishWrite(out);
    } catch (Exception e) {
      active.failWrite(out);
      throw e;
    }
  }

  File renameFile(String id) {
    return new File(context.getFilesDir(), "renames-" + id + ".jsonl");
  }

  File renameFile() {
    Task task = active();
    String id = task == null ? preferences.getString("last_task_id", "") : task.id;
    return id.isEmpty() ? null : renameFile(id);
  }

  File journalFile(String id) {
    return new File(context.getFilesDir(), "transfer-" + id + ".jsonl");
  }

  void finish(Task task, String report) {
    preferences.edit().putString("report", report).putString("last_task_id", task.id).apply();
    activeFile().delete();
    journalFile(task.id).delete();
  }

  void report(String text) {
    preferences.edit().putString("report", text).apply();
  }

  static String readText(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    return out.toString("UTF-8");
  }

  static final class Journal implements TransferEngine.Checkpoints, Closeable {
    private final Map<String, TransferEngine.Record> entries = new HashMap<>();
    private final Writer writer;
    private final Writer renameWriter;
    private final Set<String> renames = new HashSet<>();

    Journal(File file) throws IOException {
      this(file, null);
    }

    Journal(File file, File renameFile) throws IOException {
      if (file.exists())
        try (BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            try {
              JSONObject json = new JSONObject(line);
              entries.put(
                  json.getString("source"),
                  new TransferEngine.Record(
                      json.getString("target"),
                      json.getLong("size"),
                      json.getLong("modified"),
                      json.getBoolean("directory"),
                      json.optBoolean("committed", true),
                      json.optInt("nativeFiles", -1)));
            } catch (JSONException ignored) {
              /* A process interruption may leave only the final line incomplete. */
            }
          }
        }
      writer =
          new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
      writer.write('\n'); // separate any incomplete trailing record before resuming
      if (renameFile != null) {
        if (renameFile.exists())
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(new FileInputStream(renameFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null)
              try {
                JSONObject item = new JSONObject(line);
                renames.add(item.getString("source") + "\n" + item.getString("target"));
              } catch (JSONException ignored) {
              }
          }
        renameWriter =
            new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(renameFile, true), StandardCharsets.UTF_8));
        renameWriter.write('\n');
      } else renameWriter = null;
    }

    public TransferEngine.Record get(String source) {
      return entries.get(source);
    }

    public Map<String, TransferEngine.Record> records() {
      return Collections.unmodifiableMap(entries);
    }

    public void renamed(String source, String target, boolean directory) throws IOException {
      String key = source + "\n" + target;
      if (renameWriter == null || renames.contains(key)) return;
      try {
        renameWriter.write(
            new JSONObject()
                .put("source", source)
                .put("target", target)
                .put("directory", directory)
                .put("time", System.currentTimeMillis())
                .toString());
        renameWriter.write('\n');
        renameWriter.flush();
        renames.add(key);
      } catch (JSONException e) {
        throw new IOException("无法保存改名记录", e);
      }
    }

    public void put(String source, TransferEngine.Record record) throws IOException {
      try {
        JSONObject json =
            new JSONObject()
                .put("source", source)
                .put("target", record.target)
                .put("size", record.size)
                .put("modified", record.modified)
                .put("directory", record.directory)
                .put("committed", record.committed);
        json.put("nativeFiles", record.nativeFiles);
        writer.write(json.toString());
        writer.write('\n');
        writer.flush();
        entries.put(source, record);
      } catch (JSONException e) {
        throw new IOException("无法保存任务进度", e);
      }
    }

    public void close() throws IOException {
      try {
        writer.close();
      } finally {
        if (renameWriter != null) renameWriter.close();
      }
    }
  }
}
