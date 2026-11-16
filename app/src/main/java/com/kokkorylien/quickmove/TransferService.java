package com.kokkorylien.quickmove;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferService extends Service {
  static final String PAUSE = "com.kokkorylien.quickmove.PAUSE";
  static final int NOTIFICATION = 21;
  static final String CHANNEL = "file_transfer";
  static volatile Snapshot snapshot = new Snapshot(false, "准备就绪", "", 0, 0, 0, 0);

  static final class Snapshot {
    final boolean running;
    final String title, file;
    final long current, total, bytes;
    final int files;
    final long done, taskTotal;
    final int taskFiles;
    final boolean scanning;

    Snapshot(
        boolean running,
        String title,
        String file,
        long current,
        long total,
        long bytes,
        int files) {
      this(running, title, file, current, total, bytes, files, 0, -1, -1, false);
    }

    Snapshot(
        boolean running,
        String title,
        String file,
        long current,
        long total,
        long bytes,
        int files,
        long done,
        long taskTotal,
        int taskFiles,
        boolean scanning) {
      this.running = running;
      this.title = title;
      this.file = file;
      this.current = current;
      this.total = total;
      this.bytes = bytes;
      this.files = files;
      this.done = done;
      this.taskTotal = taskTotal;
      this.taskFiles = taskFiles;
      this.scanning = scanning;
    }

    int percent() {
      if (!running && "搬运完成".equals(title)) return 100;
      return taskTotal > 0
          ? (int) Math.min(100, (double) done / taskTotal * 100)
          : taskFiles > 0 ? (int) Math.min(100, (double) files / taskFiles * 100) : 0;
    }
  }

  private final AtomicBoolean cancel = new AtomicBoolean();
  private PowerManager.WakeLock wakeLock;
  private volatile boolean working;
  private long lastUpdate;
  private NotificationManager notifications;

  @Override
  public void onCreate() {
    super.onCreate();
    notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel channel =
          new NotificationChannel(CHANNEL, "文件搬运进度", NotificationManager.IMPORTANCE_LOW);
      channel.setSound(null, null);
      channel.setShowBadge(false);
      notifications.createNotificationChannel(channel);
    }
  }

  @SuppressLint("WakelockTimeout") // Held only while the transfer worker runs; always released in
  // finally/onDestroy.
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && PAUSE.equals(intent.getAction())) {
      cancel.set(true);
      if (!working) stopSelf();
      return START_NOT_STICKY;
    }
    if (working) return START_REDELIVER_INTENT;
    Store store = new Store(this);
    Store.Task task = store.active();
    if (task == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    cancel.set(false);
    working = true;
    snapshot = new Snapshot(true, task.move ? "正在剪切搬运" : "正在复制", "正在读取目录…", 0, 0, 0, 0);
    if (Build.VERSION.SDK_INT >= 29)
      startForeground(
          NOTIFICATION,
          notification(snapshot, true),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    else startForeground(NOTIFICATION, notification(snapshot, true));
    PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
    wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":transfer");
    wakeLock.acquire();
    new Thread(() -> execute(store, task), "file-transfer").start();
    return START_REDELIVER_INTENT;
  }

  private void execute(Store store, Store.Task task) {
    String report;
    boolean completed = false;
    try (Store.Journal journal =
        new Store.Journal(store.journalFile(task.id), store.renameFile(task.id))) {
      Storage storage = new Storage(this);
      List<TransferEngine.Node> sources = new ArrayList<>();
      for (String ref : task.sources) sources.add(storage.resolve(ref));
      FilenamePolicy naming =
          new FilenamePolicy(
              new InputStreamReader(getAssets().open("emoji-names.tsv"), StandardCharsets.UTF_8),
              task.emojiMode,
              task.capitalize);
      TransferEngine engine =
          new TransferEngine(
              storage,
              journal,
              new TransferEngine.Observer() {
                long done, taskTotal = -1;
                int taskFiles = -1;
                boolean scanning;

                public void overall(
                    long done, long total, int files, int totalFiles, boolean scanning) {
                  this.done = done;
                  this.taskTotal = total;
                  this.taskFiles = totalFiles;
                  this.scanning = scanning;
                }

                public boolean cancelled() {
                  return cancel.get();
                }

                public void progress(String path, long current, long total, long bytes, int files) {
                  snapshot =
                      new Snapshot(
                          true,
                          scanning ? "正在统计总量" : task.move ? "正在剪切搬运" : "正在复制",
                          path,
                          current,
                          total,
                          bytes,
                          files,
                          done,
                          taskTotal,
                          taskFiles,
                          scanning);
                  long now = SystemClock.elapsedRealtime();
                  if (now - lastUpdate >= 1000) {
                    lastUpdate = now;
                    notifications.notify(NOTIFICATION, notification(snapshot, true));
                  }
                }
              },
              task.move,
              naming,
              task.measure);
      TransferEngine.Result result = engine.run(sources, storage.resolve(task.destination));
      report =
          (result.failures == 0 ? "搬运完成" : "部分完成，有 " + result.failures + " 项失败")
              + "\n本次完成 "
              + result.files
              + " 个文件、"
              + result.folders
              + " 个文件夹，流式传输 "
              + MainActivity.bytes(result.bytes)
              + (result.totalBytes >= 0
                  ? "\n任务总量 "
                      + MainActivity.bytes(result.totalBytes)
                      + " · 已完成 "
                      + MainActivity.bytes(result.completedBytes)
                      + " / "
                      + result.totalFiles
                      + " 个文件"
                  : "\n快速开始模式：未预先统计总量")
              + "\n目标："
              + Storage.display(task.destination);
      if (!result.errors.isEmpty())
        report += "\n\n" + android.text.TextUtils.join("\n", result.errors);
      completed = result.failures == 0;
      if (!completed) report += "\n\n源文件已保留。排除问题后可继续任务；已记录的成功文件会跳过。";
    } catch (TransferEngine.Cancelled e) {
      report = "任务已暂停\n已完成的文件已保留，可点“继续任务”接着搬运。";
    } catch (Exception e) {
      report =
          "任务已停止："
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
              + "\n检查存储权限、可用空间或重新插入存储卡后，可继续任务。";
    } finally {
      releaseWakeLock();
    }
    final String finalReport = report;
    final boolean finalCompleted = completed;
    new Handler(Looper.getMainLooper())
        .post(
            () -> {
              // Serialize final state and stopSelf with onStartCommand to avoid stopping a newly
              // started job.
              if (finalCompleted) store.finish(task, finalReport);
              else store.report(finalReport);
              Snapshot last = snapshot;
              snapshot =
                  new Snapshot(
                      false,
                      finalCompleted ? "搬运完成" : "任务已暂停",
                      finalReport,
                      last.current,
                      last.total,
                      last.bytes,
                      last.files,
                      last.done,
                      last.taskTotal,
                      last.taskFiles,
                      false);
              working = false;
              stopForeground(STOP_FOREGROUND_DETACH);
              notifications.notify(NOTIFICATION, notification(snapshot, false));
              stopSelf();
            });
  }

  private Notification notification(Snapshot state, boolean running) {
    Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
    builder
        .setSmallIcon(R.drawable.ic_transfer)
        .setContentTitle(state.title)
        .setContentIntent(content)
        .setContentText(
            running
                ? state.scanning
                    ? "已发现 " + state.taskFiles + " 个待处理文件 · " + MainActivity.bytes(state.taskTotal)
                    : state.taskTotal >= 0
                        ? state.percent()
                            + "% · "
                            + MainActivity.bytes(state.done)
                            + " / "
                            + MainActivity.bytes(state.taskTotal)
                            + " · "
                            + state.file
                        : state.files
                            + " 个文件 · "
                            + MainActivity.bytes(state.bytes)
                            + " · "
                            + state.file
                : "点此查看搬运结果")
        .setOnlyAlertOnce(true)
        .setOngoing(running)
        .setAutoCancel(!running)
        .setCategory(Notification.CATEGORY_PROGRESS);
    if (running) {
      builder.setProgress(100, state.percent(), state.scanning || state.taskTotal < 0);
      PendingIntent pause =
          PendingIntent.getService(
              this,
              2,
              new Intent(this, TransferService.class).setAction(PAUSE),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      builder.addAction(new Notification.Action.Builder(null, "暂停", pause).build());
      builder.setSubText(
          state.scanning
              ? "统计结束后开始搬运"
              : state.taskTotal >= 0 ? "锁屏后继续 · 整体任务进度" : "锁屏后继续 · 快速开始，未统计总量");
    }
    return builder.build();
  }

  // Android 15+ caps dataSync foreground services. Preserve the journal and pause gracefully.
  @Override
  public void onTimeout(int startId, int fgsType) {
    cancel.set(true);
    new Store(this).report("系统的后台搬运时限已到，进度已保留。打开 APP 后可继续任务。");
    stopForeground(STOP_FOREGROUND_DETACH);
    stopSelf();
  }

  private synchronized void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
  }

  @Override
  public void onDestroy() {
    cancel.set(true);
    releaseWakeLock();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
