package com.kokkorylien.quickmove;

import java.io.*;
import java.util.*;

/**
 * Offline longest-sequence matching using Unicode data, independent of Android's Unicode version.
 */
final class FilenamePolicy {
  static final String UNDERSCORE = "underscore",
      REMOVE = "remove",
      ENGLISH = "english",
      KEEP = "keep";

  private static final class Branch {
    final Map<Integer, Branch> children = new HashMap<>();
    String english;
  }

  private final Branch root = new Branch();
  private final String mode;
  private final boolean capitalize;

  FilenamePolicy(Reader data, String mode, boolean capitalize) throws IOException {
    this.mode = mode;
    this.capitalize = capitalize;
    try (BufferedReader reader = new BufferedReader(data)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty() || line.startsWith("# ")) continue;
        int tab = line.indexOf('\t');
        if (tab < 0) throw new IOException("emoji 名称表损坏");
        Branch branch = root;
        for (int offset = 0; offset < tab; ) {
          int cp = line.codePointAt(offset);
          branch = branch.children.computeIfAbsent(cp, ignored -> new Branch());
          offset += Character.charCount(cp);
        }
        branch.english = line.substring(tab + 1);
      }
    }
  }

  String apply(String name) {
    if (KEEP.equals(mode)) return name;
    StringBuilder out = new StringBuilder();
    boolean adjacentEmoji = false;
    for (int offset = 0; offset < name.length(); ) {
      Branch branch = root;
      String english = null;
      int end = offset, cursor = offset;
      while (cursor < name.length()) {
        int cp = name.codePointAt(cursor);
        if (cp == 0xFE0E || cp == 0xFE0F) {
          cursor += Character.charCount(cp);
          if (end > offset) end = cursor;
          continue;
        }
        branch = branch.children.get(cp);
        if (branch == null) break;
        cursor += Character.charCount(cp);
        if (branch.english != null) {
          end = cursor;
          english = branch.english;
        }
      }
      int cp = name.codePointAt(offset);
      // Keep future supplementary pictographs safe even before the names table is updated.
      if (end == offset && cp >= 0x1F000 && cp <= 0x1FAFF) {
        end = offset + Character.charCount(cp);
      }
      if (end > offset) {
        while (end < name.length()) {
          int tail = name.codePointAt(end);
          if (tail != 0x200D
              && tail != 0xFE0E
              && tail != 0xFE0F
              && !(tail >= 0xE0020 && tail <= 0xE007F)) break;
          end += Character.charCount(tail);
        }
        if (ENGLISH.equals(mode)) {
          if (adjacentEmoji) out.append('_');
          String word = english == null || english.isEmpty() ? "_" : english;
          if (capitalize) word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
          out.append(word);
        } else if (!REMOVE.equals(mode) && !adjacentEmoji) out.append('_');
        adjacentEmoji = true;
        offset = end;
      } else {
        out.appendCodePoint(cp);
        offset += Character.charCount(cp);
        adjacentEmoji = false;
      }
    }
    String result = out.toString();
    if (result.equals(name)) return name;
    if (result.isEmpty() || result.equals(".") || result.equals("..")) return "_";
    if (result.lastIndexOf('.') == 0) return "_" + result;
    return result;
  }

  boolean preservesNames() {
    return KEEP.equals(mode);
  }

  static String label(String mode, boolean capitalize) {
    if (REMOVE.equals(mode)) return "剔除 emoji";
    if (ENGLISH.equals(mode)) return "英文名称" + (capitalize ? "（首字母大写）" : "（小写）");
    if (KEEP.equals(mode)) return "保留原名";
    return "emoji → 下划线";
  }
}
