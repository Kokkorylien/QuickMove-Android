package com.kokkorylien.quickmove;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.os.storage.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

final class Storage implements TransferEngine.Resolver {
  private final Context context;

  Storage(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public TransferEngine.Node resolve(String ref) throws IOException {
    Uri uri = Uri.parse(ref);
    if ("file".equals(uri.getScheme()))
      return new Disk(new File(Objects.requireNonNull(uri.getPath())));
    if ("content".equals(uri.getScheme())) {
      Uri document =
          DocumentsContract.isDocumentUri(context, uri)
              ? uri
              : DocumentsContract.buildDocumentUriUsingTree(
                  uri, DocumentsContract.getTreeDocumentId(uri));
      return new Document(document, null, null);
    }
    throw new IOException("无法识别的路径");
  }

  List<TransferEngine.Node> roots() {
    LinkedHashMap<String, TransferEngine.Node> roots = new LinkedHashMap<>();
    File internal = Environment.getExternalStorageDirectory();
    roots.put(internal.getAbsolutePath(), new Disk(internal));
    if (Build.VERSION.SDK_INT >= 30) {
      StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
      for (StorageVolume volume : manager.getStorageVolumes()) {
        File file = volume.getDirectory();
        if (file != null
            && (Environment.MEDIA_MOUNTED.equals(volume.getState())
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())))
          roots.put(file.getAbsolutePath(), new Disk(file));
      }
    } else {
      for (File file : context.getExternalFilesDirs(null)) {
        if (file == null) continue;
        String path = file.getAbsolutePath();
        int marker = path.indexOf("/Android/data/");
        if (marker > 0)
          roots.put(path.substring(0, marker), new Disk(new File(path.substring(0, marker))));
      }
    }
    List<TransferEngine.Node> result = new ArrayList<>(roots.values());
    for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
      if (!permission.isReadPermission() || !permission.isWritePermission()) continue;
      try {
        TransferEngine.Node tree = resolve(permission.getUri().toString());
        String identity = tree.identity();
        result.removeIf(
            node -> {
              try {
                return node.identity().equals(identity);
              } catch (IOException e) {
                return false;
              }
            });
        result.add(tree);
      } catch (Exception ignored) {
        /* A removed card will become available when reinserted. */
      }
    }
    return result;
  }

  static String label(TransferEngine.Node node) {
    String path = display(node.ref());
    if (path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) return "内部存储";
    if (path.matches("/storage/[^/]+")) return "外置存储 · " + node.name();
    return node.name();
  }

  static String display(String ref) {
    try {
      Uri uri = Uri.parse(ref);
      if ("file".equals(uri.getScheme())) return uri.getPath();
      String id =
          DocumentsContract.isTreeUri(uri)
              ? (uri.getPath().contains("/document/")
                  ? DocumentsContract.getDocumentId(uri)
                  : DocumentsContract.getTreeDocumentId(uri))
              : DocumentsContract.getDocumentId(uri);
      if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
        int colon = id.indexOf(':');
        if (colon >= 0) {
          String volume = id.substring(0, colon), path = id.substring(colon + 1);
          return (volume.equalsIgnoreCase("primary")
                  ? Environment.getExternalStorageDirectory().getAbsolutePath()
                  : "/storage/" + volume)
              + (path.isEmpty() ? "" : "/" + path);
        }
      }
      return id;
    } catch (Exception e) {
      return ref;
    }
  }

  static final class Disk implements TransferEngine.Node {
    final File file;

    Disk(File file) {
      this.file = file;
    }

    public String ref() {
      return Uri.fromFile(file).toString();
    }

    public String identity() throws IOException {
      return file.getCanonicalPath();
    }

    public String name() {
      return file.getName();
    }

    public boolean directory() {
      return file.isDirectory();
    }

    public long size() {
      return file.length();
    }

    public long modified() {
      return file.lastModified();
    }

    public boolean exists() {
      return file.exists();
    }

    public List<TransferEngine.Node> children() throws IOException {
      File[] files = file.listFiles();
      if (files == null) throw new IOException("不能读取目录，请检查存储权限或重新插入存储卡");
      List<TransferEngine.Node> children = new ArrayList<>(files.length);
      for (File child : files) children.add(new Disk(child));
      return children;
    }

    private File child(String name) throws IOException {
      if (name.isEmpty()
          || name.equals(".")
          || name.equals("..")
          || name.contains("/")
          || name.indexOf('\0') >= 0) throw new IOException("无效的文件名称");
      return new File(file, name);
    }

    public TransferEngine.Node createDirectory(String name) throws IOException {
      File child = child(name);
      if (!child.mkdir()) throw new IOException("无法创建目录，可能只读或空间不足：" + name);
      return new Disk(child);
    }

    public TransferEngine.Node createFile(String name) throws IOException {
      File child = child(name);
      if (!child.createNewFile()) throw new IOException("无法创建文件：" + name);
      return new Disk(child);
    }

    public InputStream read() throws IOException {
      return new FileInputStream(file);
    }

    public OutputStream write() throws IOException {
      return new FileOutputStream(file);
    }

    public TransferEngine.Node rename(String name) throws IOException {
      File target = new Disk(file.getParentFile()).child(name);
      if (target.exists()) throw new IOException("目标出现同名文件，已保留源文件：" + name);
      if (!file.renameTo(target))
        throw new IOException("目标名称不受存储介质支持，或目录不可写；请检查 emoji 处理设置和存储卡状态：" + name);
      return new Disk(target);
    }

    public TransferEngine.Node tryMove(TransferEngine.Node parent) throws IOException {
      return tryMove(parent, name());
    }

    public TransferEngine.Node tryMove(TransferEngine.Node parent, String name) throws IOException {
      if (!(parent instanceof Disk)) return null;
      File target = ((Disk) parent).child(name);
      if (!target.exists() && file.renameTo(target)) return new Disk(target);
      return null;
    }

    public void delete() throws IOException {
      if (!file.delete()) throw new IOException("无法删除源文件或目录仍有内容，已保留源路径");
    }
  }

  final class Document implements TransferEngine.Node {
    final Uri uri;
    private String cachedName, cachedType;

    Document(Uri uri, String name, String type) {
      this.uri = uri;
      cachedName = name;
      cachedType = type;
    }

    private Object value(String column) throws IOException {
      try (Cursor cursor =
          context.getContentResolver().query(uri, new String[] {column}, null, null, null)) {
        if (cursor == null || !cursor.moveToFirst()) throw new IOException("目录不可访问，请重新授权或插入存储卡");
        return cursor.isNull(0)
            ? null
            : (column.equals(DocumentsContract.Document.COLUMN_SIZE)
                    || column.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                ? cursor.getLong(0)
                : cursor.getString(0));
      } catch (SecurityException | IllegalArgumentException e) {
        throw new IOException("目录授权失效，请重新选择路径", e);
      }
    }

    public String ref() {
      return uri.toString();
    }

    public String identity() {
      return "com.android.externalstorage.documents".equals(uri.getAuthority())
          ? display(ref())
          : uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri);
    }

    public String name() {
      try {
        if (cachedName == null)
          cachedName = (String) value(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        return cachedName == null ? "未命名" : cachedName;
      } catch (IOException e) {
        return display(ref());
      }
    }

    public boolean directory() {
      try {
        if (cachedType == null)
          cachedType = (String) value(DocumentsContract.Document.COLUMN_MIME_TYPE);
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(cachedType);
      } catch (IOException e) {
        return false;
      }
    }

    public long size() throws IOException {
      Object result = value(DocumentsContract.Document.COLUMN_SIZE);
      if (result == null) throw new IOException("存储提供程序没有返回文件大小");
      return (Long) result;
    }

    public long modified() throws IOException {
      Object result = value(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
      return result == null ? 0 : (Long) result;
    }

    public boolean exists() throws IOException {
      try {
        value(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        return true;
      } catch (IOException e) {
        return false;
      }
    }

    public List<TransferEngine.Node> children() throws IOException {
      List<TransferEngine.Node> result = new ArrayList<>();
      Uri children =
          DocumentsContract.buildChildDocumentsUriUsingTree(
              uri, DocumentsContract.getDocumentId(uri));
      String[] projection = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
      };
      try (Cursor cursor =
          context.getContentResolver().query(children, projection, null, null, null)) {
        if (cursor == null) throw new IOException("无法读取目录内容");
        while (cursor.moveToNext())
          result.add(
              new Document(
                  DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0)),
                  cursor.getString(1),
                  cursor.getString(2)));
      } catch (SecurityException | IllegalArgumentException e) {
        throw new IOException("目录读取失败，请重新授权", e);
      }
      return result;
    }

    private TransferEngine.Node create(String name, String type) throws IOException {
      try {
        Uri created =
            DocumentsContract.createDocument(context.getContentResolver(), uri, type, name);
        if (created == null) throw new IOException("创建失败，可能空间不足或存储卡只读");
        return new Document(created, null, type);
      } catch (SecurityException e) {
        throw new IOException("没有写入权限，请授权存储卡根目录", e);
      }
    }

    public TransferEngine.Node createDirectory(String name) throws IOException {
      return create(name, DocumentsContract.Document.MIME_TYPE_DIR);
    }

    public TransferEngine.Node createFile(String name) throws IOException {
      return create(name, "application/octet-stream");
    }

    public InputStream read() throws IOException {
      InputStream stream = context.getContentResolver().openInputStream(uri);
      if (stream == null) throw new IOException("无法打开源文件");
      return stream;
    }

    public OutputStream write() throws IOException {
      OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt");
      if (stream == null) throw new IOException("无法写入目标文件");
      return stream;
    }

    public TransferEngine.Node rename(String name) throws IOException {
      Uri renamed = DocumentsContract.renameDocument(context.getContentResolver(), uri, name);
      if (renamed == null) throw new IOException("存储提供程序不能完成文件重命名");
      return new Document(renamed, null, null);
    }

    public void delete() throws IOException {
      // Some document providers delete non-empty folders recursively. Never allow that here.
      if (directory() && !children().isEmpty()) throw new IOException("目录出现新内容，已保留源目录");
      if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri))
        throw new IOException("源路径删除失败");
    }
  }
}
