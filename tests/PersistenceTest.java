package com.kokkorylien.quickmove;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

public final class PersistenceTest {
  public static void main(String[] args) throws Exception {
    Path file = Files.createTempFile("quickmove-journal-", ".jsonl");
    try {
      Store.Task original = new Store.Task();
      original.name = "Download → TF 卡";
      original.move = true;
      original.measure = false;
      original.emojiMode = "english";
      original.capitalize = true;
      original.sources.add("file:///storage/emulated/0/Download");
      original.sources.add("file:///storage/emulated/0/空 格 🐟");
      original.destination = "content://com.android.externalstorage.documents/tree/ABCD-1234%3A";
      Store.Task restored = Store.Task.parse(new JSONObject(original.json().toString()));
      if (!original.id.equals(restored.id)
          || !restored.move
          || !original.sources.equals(restored.sources)
          || !original.destination.equals(restored.destination)
          || !original.name.equals(restored.name))
        throw new AssertionError("task persistence changed paths or mode");
      if (restored.measure || !restored.capitalize || !restored.emojiMode.equals("english"))
        throw new AssertionError("task did not save measurement and naming policy");
      JSONObject old = original.json();
      old.remove("measure");
      old.remove("emojiMode");
      old.remove("capitalize");
      Store.Task migrated = Store.Task.parse(old);
      if (!migrated.measure || !migrated.emojiMode.equals("underscore") || migrated.capitalize)
        throw new AssertionError("old task migration defaults");
      try (Store.Journal journal = new Store.Journal(file.toFile())) {
        journal.put("/from/a", new TransferEngine.Record("/to/a", 123, 456, false));
        journal.put("/from/b", new TransferEngine.Record("/to/.partial", 789, 123, false, false));
        journal.put(
            "/from/directory", new TransferEngine.Record("/to/directory", 0, 0, true, false));
      }
      Path audit = Files.createTempFile("quickmove-rename-", ".jsonl");
      try {
        try (Store.Journal journal = new Store.Journal(file.toFile(), audit.toFile())) {
          journal.renamed("/source/🐉", "/target/_", true);
          journal.put(
              "/native", new TransferEngine.Record("/target/native", 100, 0, true, true, 12));
        }
        try (Store.Journal journal = new Store.Journal(file.toFile(), audit.toFile())) {
          journal.renamed("/source/🐉", "/target/_", true);
          if (journal.get("/native").nativeFiles != 12 || journal.records().size() < 3)
            throw new AssertionError("native metrics did not survive resume");
        }
        long records = Files.readAllLines(audit).stream().filter(s -> s.startsWith("{")).count();
        if (records != 1) throw new AssertionError("rename log was lost or duplicated on resume");
      } finally {
        Files.deleteIfExists(audit);
      }
      Files.writeString(
          file, "{\"source\":\"interrupted", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      try (Store.Journal journal = new Store.Journal(file.toFile())) {
        if (journal.get("/from/a").size != 123
            || !journal.get("/from/a").committed
            || journal.get("/from/b").committed
            || !journal.get("/from/directory").directory)
          throw new AssertionError("journal resume lost completed or partial stage");
        journal.put("/from/b", new TransferEngine.Record("/to/b", 789, 123, false));
      }
      try (Store.Journal journal = new Store.Journal(file.toFile())) {
        if (!journal.get("/from/b").committed
            || !journal.get("/from/b").target.equals("/to/b")
            || journal.get("/from/a") == null)
          throw new AssertionError("journal did not recover after interrupted trailing write");
      }
      System.out.println(
          "PASS: saved-task roundtrip, durable progress reopen, partial-stage recovery, interrupted"
              + " journal recovery");
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
