package com.kokkorylien.quickmove;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FilenamePolicyTest {
  static FilenamePolicy policy(String mode, boolean capitalize) throws IOException {
    return new FilenamePolicy(
        Files.newBufferedReader(
            Path.of("app/src/main/assets/emoji-names.tsv"), StandardCharsets.UTF_8),
        mode,
        capitalize);
  }

  static void equals(String expected, String actual) {
    if (!expected.equals(actual))
      throw new AssertionError("expected=" + expected + "; actual=" + actual);
  }

  public static void main(String[] args) throws Exception {
    FilenamePolicy underscore = policy("underscore", false),
        remove = policy("remove", false),
        english = policy("english", false),
        cap = policy("english", true),
        keep = policy("keep", false);
    equals("作者 _ 作品.txt", underscore.apply("作者 🐉 作品.txt"));
    equals("作者  作品.txt", remove.apply("作者 🐉 作品.txt"));
    equals("dragon.txt", english.apply("🐉.txt"));
    equals("Fish.txt", cap.apply("🐟️.txt"));
    equals("_.txt", remove.apply("🐉.txt"));
    equals("_", remove.apply("🐉"));
    equals("_", remove.apply("🐉."));
    equals("_", remove.apply("🐉.."));
    equals("_", underscore.apply("🐉🐟️"));
    equals("dragon_fish", english.apply("🐉🐟️"));
    equals("hello 2026#*中文𠀀.txt", underscore.apply("hello 2026#*中文𠀀.txt"));
    equals("🐉.txt", keep.apply("🐉.txt"));
    equals("_", underscore.apply("👨‍👩‍👧‍👦"));
    equals("_", underscore.apply("🏳️‍🌈"));
    equals("_.jpg", underscore.apply("1️⃣.jpg"));
    equals("_.jpg", underscore.apply("#️⃣.jpg"));
    int count = 0;
    for (String line :
        Files.readAllLines(
            Path.of("app/src/main/assets/emoji-names.tsv"), StandardCharsets.UTF_8)) {
      if (line.startsWith("# ") || line.isEmpty()) continue;
      String[] parts = line.split("\t");
      String emoji = parts[0], name = parts[1];
      equals("_", underscore.apply(emoji));
      equals("_", remove.apply(emoji));
      equals(name, english.apply(emoji));
      count++;
    }
    System.out.println(
        "PASS: emoji filename modes, spaces/extensions, flags/ZWJ/keycaps, "
            + count
            + " Unicode sequences");
  }
}
