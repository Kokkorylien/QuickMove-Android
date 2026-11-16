package com.kokkorylien.quickmove;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int BG = Color.rgb(16, 25, 33),
      CARD = Color.rgb(28, 40, 50),
      INK = Color.rgb(236, 244, 246),
      MUTED = Color.rgb(162, 182, 192),
      ACCENT = Color.rgb(107, 227, 194);
  private Store store;
  private Storage storage;
  private final ArrayList<String> sources = new ArrayList<>();
  private String destination = "";
  private boolean move;
  private boolean measure = true, capitalize;
  private String emojiMode = FilenamePolicy.UNDERSCORE;
  private LinearLayout page, sourceList, taskList, favoriteList, activeBox;
  private TextView targetText, statusTitle, statusDetail;
  private ProgressBar progress;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private boolean shownRunning;
  private final Runnable ticker =
      new Runnable() {
        @Override
        public void run() {
          updateProgress();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    storage = new Storage(this);
    destination = store.preferences.getString("draft_destination", "");
    move = store.preferences.getBoolean("draft_move", false);
    measure = store.preferences.getBoolean("draft_measure", true);
    emojiMode = store.preferences.getString("draft_emoji", FilenamePolicy.UNDERSCORE);
    capitalize = store.preferences.getBoolean("draft_capitalize", false);
    try {
      JSONArray array = new JSONArray(store.preferences.getString("draft_sources", "[]"));
      for (int i = 0; i < array.length(); i++) sources.add(array.getString(i));
    } catch (JSONException ignored) {
    }
    buildPage();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (page != null) buildPage();
    handler.removeCallbacks(ticker);
    handler.post(ticker);
  }

  @Override
  protected void onPause() {
    saveDraft();
    handler.removeCallbacks(ticker);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    io.shutdownNow();
    super.onDestroy();
  }

  private void saveDraft() {
    store
        .preferences
        .edit()
        .putString("draft_destination", destination)
        .putBoolean("draft_move", move)
        .putBoolean("draft_measure", measure)
        .putString("draft_emoji", emojiMode)
        .putBoolean("draft_capitalize", capitalize)
        .putString("draft_sources", new JSONArray(sources).toString())
        .apply();
  }

  private int dp(float value) {
    return (int) (getResources().getDisplayMetrics().density * value + 0.5f);
  }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(sp);
    view.setTextColor(color);
    view.setLineSpacing(dp(3), 1);
    return view;
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private LinearLayout row() {
    LinearLayout view = new LinearLayout(this);
    view.setOrientation(LinearLayout.HORIZONTAL);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(radius));
    return shape;
  }

  private void add(LinearLayout parent, View child, int top) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(top);
    parent.addView(child, p);
  }

  private Button button(String title, boolean primary, Runnable action) {
    Button view = new Button(this);
    view.setText(title);
    view.setAllCaps(false);
    view.setTextSize(14);
    view.setTextColor(primary ? BG : ACCENT);
    view.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : Color.rgb(37, 55, 65)));
    view.setOnClickListener(v -> action.run());
    return view;
  }

  private void buttons(LinearLayout parent, Button... buttons) {
    LinearLayout line = row();
    for (Button button : buttons) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
      p.setMargins(0, 0, dp(5), 0);
      line.addView(button, p);
    }
    add(parent, line, 6);
  }

  private LinearLayout card(LinearLayout parent, String caption) {
    LinearLayout card = column();
    card.setPadding(dp(16), dp(14), dp(16), dp(14));
    card.setBackground(shape(CARD, 18));
    TextView label = text(caption, 13, ACCENT);
    label.setTypeface(null, Typeface.BOLD);
    card.addView(label);
    add(parent, card, 14);
    return card;
  }

  private void buildPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(BG);
    page = column();
    page.setPadding(dp(20), dp(18), dp(20), dp(28));
    scroll.addView(page);
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          page.setPadding(
              dp(20),
              dp(18) + insets.getSystemWindowInsetTop(),
              dp(20),
              dp(28) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    setContentView(scroll);
    TextView title = text("文件快搬", 30, INK);
    title.setTypeface(null, Typeface.BOLD);
    add(page, title, 6);
    add(page, text("从多个来源，搬到同一个地方", 14, MUTED), 4);
    if (!hasStoragePermission()
        || (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)) {
      LinearLayout permissions = card(page, "首次使用 · 存储与通知");
      add(permissions, text("访问内部存储和外置卡；允许通知后，可在锁屏界面查看进度、暂停任务。", 13, MUTED), 8);
      buttons(
          permissions,
          button("存储权限", true, this::requestStorage),
          button("通知权限", false, this::requestNotifications));
    }
    LinearLayout status = card(page, "搬运进度");
    statusTitle = text("准备就绪", 18, INK);
    add(status, statusTitle, 8);
    statusDetail = text("选择源文件或文件夹，再指定目标目录。", 13, MUTED);
    add(status, statusDetail, 4);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
    add(status, progress, 10);
    activeBox = column();
    add(status, activeBox, 4);
    renderActive();
    LinearLayout sourceCard = card(page, "01  从哪里搬");
    sourceList = column();
    add(sourceCard, sourceList, 6);
    renderSources();
    buttons(
        sourceCard,
        button("＋ 选择文件 / 文件夹", true, () -> new Explorer(false, null).show()),
        button(
            "清空选择",
            false,
            () -> {
              sources.clear();
              renderSources();
              saveDraft();
            }));
    LinearLayout targetCard = card(page, "02  搬到哪里");
    targetText = text(destination.isEmpty() ? "尚未选择目标目录" : Storage.display(destination), 15, INK);
    targetText.setTextIsSelectable(true);
    add(targetCard, targetText, 10);
    buttons(
        targetCard,
        button("选择目标目录", true, () -> new Explorer(true, destination).show()),
        button(
            "收藏此路径",
            false,
            () -> {
              if (destination.isEmpty()) toast("请先选择目标目录");
              else favorite(destination);
            }));
    LinearLayout actionCard = card(page, "03  怎么搬");
    RadioGroup modes = new RadioGroup(this);
    modes.setOrientation(RadioGroup.HORIZONTAL);
    RadioButton copy = new RadioButton(this), cut = new RadioButton(this);
    copy.setId(View.generateViewId());
    cut.setId(View.generateViewId());
    copy.setText("复制 · 保留源文件");
    cut.setText("剪切 · 移走源文件");
    copy.setTextColor(INK);
    cut.setTextColor(INK);
    copy.setTextSize(13);
    cut.setTextSize(13);
    modes.addView(copy, new RadioGroup.LayoutParams(0, -2, 1));
    modes.addView(cut, new RadioGroup.LayoutParams(0, -2, 1));
    modes.check(move ? cut.getId() : copy.getId());
    modes.setOnCheckedChangeListener(
        (g, id) -> {
          move = id == cut.getId();
          saveDraft();
        });
    add(actionCard, modes, 8);
    CheckBox count = new CheckBox(this);
    count.setText("先统计总量，显示整个任务的大小和进度");
    count.setTextColor(INK);
    count.setTextSize(13);
    count.setChecked(measure);
    count.setOnCheckedChangeListener(
        (v, checked) -> {
          measure = checked;
          saveDraft();
        });
    add(actionCard, count, 8);
    add(actionCard, text("开启：先读取文件清单与大小，再搬运；文件很多时需等待。关闭：立即搬运，不显示未知的总百分比。", 12, MUTED), 3);
    add(actionCard, text("目标文件与文件夹的 emoji 处理", 13, ACCENT), 10);
    String[] namingModes = {
      FilenamePolicy.UNDERSCORE, FilenamePolicy.REMOVE, FilenamePolicy.ENGLISH, FilenamePolicy.KEEP
    };
    Spinner naming = new Spinner(this);
    ArrayAdapter<String> namingAdapter =
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            new String[] {"替换为下划线（默认）", "剔除 emoji", "替换为英文名称", "保留原名（目标介质需支持）"});
    namingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    naming.setAdapter(namingAdapter);
    int namingIndex = Arrays.asList(namingModes).indexOf(emojiMode);
    naming.setSelection(Math.max(0, namingIndex));
    CheckBox caps = new CheckBox(this);
    caps.setText("英文名称首字母大写，如 Dragon / Fish");
    caps.setTextColor(INK);
    caps.setTextSize(13);
    caps.setChecked(capitalize);
    caps.setVisibility(FilenamePolicy.ENGLISH.equals(emojiMode) ? View.VISIBLE : View.GONE);
    naming.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
            emojiMode = namingModes[position];
            caps.setVisibility(FilenamePolicy.ENGLISH.equals(emojiMode) ? View.VISIBLE : View.GONE);
            saveDraft();
          }
        });
    caps.setOnCheckedChangeListener(
        (v, checked) -> {
          capitalize = checked;
          saveDraft();
        });
    add(actionCard, naming, 4);
    add(actionCard, caps, 2);
    add(actionCard, text("🐉 → _ / 剔除 / dragon；只处理目标名称，保留空格和扩展名，实际改名会保存记录。", 12, MUTED), 4);
    add(actionCard, text("同名文件保留两份；同名文件夹合并。跨介质剪切时，每个文件写入成功后才删除源文件。", 12, MUTED), 8);
    buttons(
        actionCard,
        button("开始搬运", true, () -> confirmRun(draft())),
        button("存为一键任务", false, () -> saveTask(draft())));
    LinearLayout favorites = card(page, "收藏路径");
    favoriteList = column();
    add(favorites, favoriteList, 5);
    renderFavorites();
    LinearLayout tasks = card(page, "一键任务");
    taskList = column();
    add(tasks, taskList, 5);
    renderTasks();
    buttons(
        page, button("后台运行设置", false, this::backgroundSettings), button("使用说明", false, this::help));
    add(page, text("本地处理 · 无网络权限 · Android 7.0+", 12, MUTED), 12);
    updateProgress();
  }

  private Store.Task draft() {
    Store.Task task = new Store.Task();
    task.sources.addAll(sources);
    task.destination = destination;
    task.move = move;
    task.measure = measure;
    task.emojiMode = emojiMode;
    task.capitalize = capitalize;
    return task;
  }

  private void renderSources() {
    sourceList.removeAllViews();
    if (sources.isEmpty()) {
      add(sourceList, text("可批量选择文件和文件夹，支持不同存储位置。", 13, MUTED), 4);
      return;
    }
    for (String ref : new ArrayList<>(sources)) {
      LinearLayout line = row();
      TextView path = text(Storage.display(ref), 13, INK);
      path.setPadding(0, dp(10), 0, dp(10));
      line.addView(path, new LinearLayout.LayoutParams(0, -2, 1));
      Button remove =
          button(
              "移除",
              false,
              () -> {
                sources.remove(ref);
                renderSources();
                saveDraft();
              });
      line.addView(remove, new LinearLayout.LayoutParams(dp(66), dp(48)));
      path.setOnClickListener(v -> preview(ref));
      add(sourceList, line, 2);
    }
  }

  private void preview(String ref) {
    io.execute(
        () -> {
          try {
            TransferEngine.Node node = storage.resolve(ref);
            boolean dir = node.directory();
            handler.post(
                () -> {
                  if (dir) new Explorer(false, ref).show();
                  else
                    new AlertDialog.Builder(this)
                        .setTitle(node.name())
                        .setMessage(Storage.display(ref))
                        .setPositiveButton("关闭", null)
                        .show();
                });
          } catch (Exception e) {
            handler.post(() -> error(e));
          }
        });
  }

  private void favorite(String ref) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(shortName(ref));
    new AlertDialog.Builder(this)
        .setTitle("收藏路径")
        .setMessage(Storage.display(ref))
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "收藏",
            (d, w) -> {
              try {
                store.favorite(
                    ref,
                    input.getText().toString().trim().isEmpty()
                        ? shortName(ref)
                        : input.getText().toString().trim());
                renderFavorites();
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private void renderFavorites() {
    favoriteList.removeAllViews();
    Map<String, String> favorites = store.favorites();
    if (favorites.isEmpty())
      add(favoriteList, text("收藏常用目的地，下一次直接点选。浏览目录时也可收藏当前路径。", 13, MUTED), 8);
    for (Map.Entry<String, String> item : favorites.entrySet()) {
      LinearLayout block = column();
      add(block, text("★  " + item.getValue(), 16, INK), 8);
      add(block, text(Storage.display(item.getKey()), 12, MUTED), 3);
      buttons(
          block,
          button(
              "设为目标",
              false,
              () -> {
                destination = item.getKey();
                targetText.setText(Storage.display(destination));
                saveDraft();
                toast("已设为目标目录");
              }),
          button("浏览", false, () -> new Explorer(true, item.getKey()).show()));
      block.setOnLongClickListener(
          v -> {
            new AlertDialog.Builder(this)
                .setTitle("取消收藏？")
                .setMessage(item.getValue())
                .setNegativeButton("保留", null)
                .setPositiveButton(
                    "取消收藏",
                    (d, w) -> {
                      try {
                        store.unfavorite(item.getKey());
                        renderFavorites();
                      } catch (Exception e) {
                        error(e);
                      }
                    })
                .show();
            return true;
          });
      add(favoriteList, block, 2);
    }
  }

  private boolean validTask(Store.Task task) {
    if (task.sources.isEmpty()) {
      toast("请先选择源文件或文件夹");
      return false;
    }
    if (task.destination.isEmpty()) {
      toast("请先选择目标目录");
      return false;
    }
    return true;
  }

  private void saveTask(Store.Task task) {
    if (!validTask(task)) return;
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(
        task.name.isEmpty()
            ? (task.move ? "剪切 " : "复制 ")
                + shortName(task.sources.get(0))
                + " → "
                + shortName(task.destination)
            : task.name);
    new AlertDialog.Builder(this)
        .setTitle("保存为一键任务")
        .setMessage("保存路径和搬运方式。下次执行时读取源路径当时的内容。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              task.name = input.getText().toString().trim();
              if (task.name.isEmpty()) task.name = "文件搬运";
              try {
                store.saveTask(task);
                renderTasks();
                toast("一键任务已保存");
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private void renderTasks() {
    taskList.removeAllViews();
    List<Store.Task> tasks = store.tasks();
    if (tasks.isEmpty())
      add(taskList, text("例如：Download → TF 卡根目录。保存一次，以后两次点按即可重新执行。", 13, MUTED), 8);
    for (Store.Task task : tasks) {
      LinearLayout block = column();
      TextView title = text(task.name, 16, INK);
      title.setTypeface(null, Typeface.BOLD);
      add(block, title, 10);
      add(
          block,
          text(
              (task.move ? "剪切" : "复制")
                  + " · "
                  + task.sources.size()
                  + " 个源路径\n→ "
                  + Storage.display(task.destination),
              12,
              MUTED),
          5);
      buttons(
          block,
          button("执行", true, () -> confirmRun(task)),
          button("查看 / 修改", false, () -> loadTask(task)));
      buttons(block, button("删除任务", false, () -> deleteTask(task)));
      block.setOnLongClickListener(
          v -> {
            deleteTask(task);
            return true;
          });
      add(taskList, block, 3);
    }
  }

  private void deleteTask(Store.Task task) {
    new AlertDialog.Builder(this)
        .setTitle("删除一键任务？")
        .setMessage(task.name + "\n\n只删除保存的配置，不影响文件或正在执行的任务。")
        .setNegativeButton("保留", null)
        .setPositiveButton(
            "删除任务",
            (d, w) -> {
              try {
                store.removeTask(task.id);
                renderTasks();
                toast("一键任务已删除");
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private void loadTask(Store.Task task) {
    StringBuilder info = new StringBuilder(task.move ? "剪切搬运\n\n" : "复制文件\n\n");
    for (String ref : task.sources) info.append(Storage.display(ref)).append('\n');
    info.append("\n目标：").append(Storage.display(task.destination));
    info.append("\n\n")
        .append(task.measure ? "先统计总量" : "快速开始，不统计总量")
        .append("\n")
        .append(FilenamePolicy.label(task.emojiMode, task.capitalize));
    new AlertDialog.Builder(this)
        .setTitle(task.name)
        .setMessage(info)
        .setNegativeButton("关闭", null)
        .setNeutralButton("重命名", (d, w) -> saveTask(task))
        .setPositiveButton(
            "载入编辑",
            (d, w) -> {
              sources.clear();
              sources.addAll(task.sources);
              destination = task.destination;
              move = task.move;
              measure = task.measure;
              emojiMode = task.emojiMode;
              capitalize = task.capitalize;
              saveDraft();
              buildPage();
              toast("已载入，修改后可另存为一键任务");
            })
        .show();
  }

  private void confirmRun(Store.Task original) {
    if (!validTask(original)) return;
    if (TransferService.snapshot.running || store.active() != null) {
      toast("已有运行中或暂停的任务，请先继续或结束它");
      return;
    }
    StringBuilder description = new StringBuilder(original.move ? "剪切以下源路径：\n" : "复制以下源路径：\n");
    for (String ref : original.sources) description.append(Storage.display(ref)).append('\n');
    description
        .append("\n目标目录：\n")
        .append(Storage.display(original.destination))
        .append("\n\n源文件夹本身也会搬入目标目录；同名文件夹合并，同名文件保留两份。");
    description
        .append("\n\n")
        .append(original.measure ? "先统计总量，再开始搬运" : "快速开始（不统计总量）")
        .append("\n")
        .append(FilenamePolicy.label(original.emojiMode, original.capitalize));
    new AlertDialog.Builder(this)
        .setTitle(original.name.isEmpty() ? "确认搬运" : original.name)
        .setMessage(description)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            original.move ? "开始剪切" : "开始复制",
            (d, w) -> {
              Store.Task execution = new Store.Task();
              execution.name = original.name;
              execution.move = original.move;
              execution.measure = original.measure;
              execution.emojiMode = original.emojiMode;
              execution.capitalize = original.capitalize;
              execution.destination = original.destination;
              execution.sources.addAll(original.sources);
              try {
                store.begin(execution);
                launchService();
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private void launchService() {
    try {
      Intent intent = new Intent(this, TransferService.class);
      if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
      else startService(intent);
      handler.postDelayed(
          () -> {
            renderActive();
            updateProgress();
          },
          300);
    } catch (Exception e) {
      store.report("任务启动失败：" + e.getMessage());
      error(e);
      renderActive();
    }
  }

  private void renderActive() {
    if (activeBox == null) return;
    activeBox.removeAllViews();
    boolean running = TransferService.snapshot.running;
    Store.Task active = store.active();
    shownRunning = running;
    if (running)
      buttons(
          activeBox,
          button(
              "暂停搬运",
              false,
              () -> {
                startService(
                    new Intent(this, TransferService.class).setAction(TransferService.PAUSE));
                toast("正在暂停，已完成文件会保留");
              }));
    else if (active != null)
      buttons(
          activeBox,
          button("继续任务", true, this::launchService),
          button(
              "结束此任务",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("结束尚未完成的任务？")
                      .setMessage("源文件和已搬入目标的文件都会保留，只清除续传记录。下次重新执行复制可能产生同名副本。")
                      .setNegativeButton("保留任务", null)
                      .setPositiveButton(
                          "结束任务",
                          (d, w) -> {
                            store.finish(active, "任务已手动结束；源文件和已完成文件均已保留。");
                            renderActive();
                            updateProgress();
                          })
                      .show()));
    String report = store.preferences.getString("report", "");
    if (!report.isEmpty())
      buttons(
          activeBox,
          button(
              "查看上次结果",
              false,
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("搬运结果")
                      .setMessage(report)
                      .setPositiveButton("关闭", null)
                      .show()));
    File renameLog = store.renameFile();
    if (renameLog != null && renameLog.exists() && renameLog.length() > 1)
      buttons(activeBox, button("查看 / 导出改名记录", false, () -> showRenames(renameLog)));
  }

  private void updateProgress() {
    if (statusTitle == null) return;
    TransferService.Snapshot state = TransferService.snapshot;
    if (state.running != shownRunning) renderActive();
    statusTitle.setText(
        state.running
            ? state.title
            : store.active() != null ? "有任务等待继续" : state.taskTotal >= 0 ? state.title : "准备就绪");
    statusDetail.setText(
        state.running
            ? state.file
                + (state.scanning
                    ? "\n统计中：已发现 "
                        + state.taskFiles
                        + " 个文件 · "
                        + bytes(state.taskTotal)
                        + "\n统计结束后才开始搬运"
                    : state.taskTotal >= 0
                        ? "\n总进度 "
                            + state.percent()
                            + "% · "
                            + bytes(state.done)
                            + " / "
                            + bytes(state.taskTotal)
                            + "\n文件 "
                            + state.files
                            + " / "
                            + state.taskFiles
                        : "\n快速开始：未统计总大小和总百分比")
                + "\n已完成 "
                + state.files
                + " 个文件 · 已传输 "
                + bytes(state.bytes)
                + (state.total > 0
                    ? "\n当前文件 " + bytes(state.current) + " / " + bytes(state.total)
                    : "")
            : (store.active() != null ? "进度已保存，检查存储卡和权限后可继续。" : "支持后台与锁屏搬运，进度会显示在通知栏。")
                + (state.taskTotal >= 0
                    ? "\n任务总量 "
                        + bytes(state.taskTotal)
                        + " · 已完成 "
                        + bytes(state.done)
                        + "\n文件 "
                        + state.files
                        + " / "
                        + state.taskFiles
                    : ""));
    progress.setIndeterminate(state.running && (state.scanning || state.taskTotal < 0));
    progress.setProgress(state.scanning || state.taskTotal < 0 ? 0 : state.percent());
  }

  private void showRenames(File file) {
    io.execute(
        () -> {
          try (BufferedReader reader =
              new BufferedReader(
                  new InputStreamReader(
                      new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder preview = new StringBuilder();
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null)
              try {
                JSONObject item = new JSONObject(line);
                count++;
                if (count <= 100)
                  preview
                      .append(item.optBoolean("directory") ? "文件夹：" : "文件：")
                      .append(Storage.display(item.getString("source")))
                      .append("\n→ ")
                      .append(Storage.display(item.getString("target")))
                      .append("\n\n");
              } catch (JSONException ignored) {
              }
            if (count == 0) preview.append("本任务尚无改名操作。");
            if (count > 100) preview.append("仅预览前 100 条；导出包含全部 ").append(count).append(" 条记录。");
            String detail = preview.toString();
            handler.post(
                () -> {
                  TextView content = text(detail, 13, INK);
                  content.setTextIsSelectable(true);
                  content.setPadding(dp(20), dp(8), dp(20), dp(8));
                  ScrollView scroll = new ScrollView(this);
                  scroll.addView(content);
                  new AlertDialog.Builder(this)
                      .setTitle("改名记录")
                      .setView(scroll)
                      .setNegativeButton("关闭", null)
                      .setPositiveButton(
                          "导出全部",
                          (d, w) -> {
                            pendingRenameExport = file;
                            startActivityForResult(
                                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .setType("text/plain")
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .putExtra(Intent.EXTRA_TITLE, "文件快搬-改名记录.txt"),
                                13);
                          })
                      .show();
                });
          } catch (Exception e) {
            handler.post(() -> error(e));
          }
        });
  }

  private File pendingRenameExport;

  private void exportRenames(Uri destinationUri) {
    File file = pendingRenameExport == null ? store.renameFile() : pendingRenameExport;
    io.execute(
        () -> {
          try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(
                          new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8));
              Writer writer =
                  new OutputStreamWriter(
                      Objects.requireNonNull(getContentResolver().openOutputStream(destinationUri)),
                      java.nio.charset.StandardCharsets.UTF_8)) {
            writer.write("文件快搬 · 实际改名记录\n\n");
            String line;
            while ((line = reader.readLine()) != null)
              try {
                JSONObject item = new JSONObject(line);
                writer.write(
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(new Date(item.getLong("time")))
                        + "\n"
                        + (item.optBoolean("directory") ? "文件夹：" : "文件：")
                        + Storage.display(item.getString("source"))
                        + "\n→ "
                        + Storage.display(item.getString("target"))
                        + "\n\n");
              } catch (JSONException ignored) {
              }
            handler.post(() -> toast("改名记录已导出"));
          } catch (Exception e) {
            handler.post(() -> error(e));
          }
        });
  }

  private boolean hasStoragePermission() {
    return Build.VERSION.SDK_INT >= 30
        ? Environment.isExternalStorageManager()
        : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
  }

  private void requestStorage() {
    if (Build.VERSION.SDK_INT >= 30)
      try {
        startActivity(
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
      } catch (ActivityNotFoundException e) {
        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
      }
    else
      requestPermissions(
          new String[] {
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
          },
          10);
  }

  private void requestNotifications() {
    if (Build.VERSION.SDK_INT >= 33)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
    else toast("此系统无需单独申请通知权限");
  }

  private void backgroundSettings() {
    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
    boolean exempt = power.isIgnoringBatteryOptimizations(getPackageName());
    new AlertDialog.Builder(this)
        .setTitle("锁屏后继续搬运")
        .setMessage(
            "搬运时 APP 会启动前台服务并保持 CPU 唤醒，不需要屏幕常亮。\n\n"
                + (exempt ? "已允许忽略系统电池优化。" : "建议允许忽略电池优化，避免深度待机暂停搬运。")
                + "\n"
                + "部分系统还需在应用电池设置中选择“不受限制”、允许后台运行。强行停止 APP、弹出存储卡或部分厂商的清理操作仍会中断任务；重新打开后可继续。\n\n"
                + "Android 15 及以上的系统后台时限到达时会暂停，请回到 APP 继续。")
        .setNegativeButton("知道了", null)
        .setPositiveButton(
            exempt ? "应用后台设置" : "允许锁屏搬运",
            (d, w) -> {
              try {
                startActivity(
                    new Intent(
                        exempt
                            ? Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            : Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
              } catch (ActivityNotFoundException e) {
                startActivity(
                    new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
              }
            })
        .show();
  }

  private void help() {
    new AlertDialog.Builder(this)
        .setTitle("使用文件快搬")
        .setMessage(
            "1. 允许存储访问。Android 7–10 写入外置卡时，请在目录浏览中点“授权存储卡”，选择卡的根目录。\n\n"
                + "2. 选择文件或整个文件夹，可跨目录、跨存储批量选择。点文件夹名称进入，勾选框用于选择整项。\n\n"
                + "3. 选择目标目录。收藏路径后可直接设为目标，也可进入预览目录内容。\n\n"
                + "4. 选择复制或剪切，开始搬运。以 Download 为源、存储卡根目录为目标时，将得到 存储卡/Download。\n\n"
                + "5. 保存为“一键任务”后，点执行并确认即可复用。剪切后若 Download 不存在，需待系统或用户重新创建后执行。\n\n"
                + "暂停或进程中断后可继续。已完成文件会跳过，未完成的文件重新复制。请勿在续传期间修改已搬运的源文件或目标文件。\n\n"
                + "不支持访问系统私有目录及其他 APP 的 Android/data、Android/obb；不提供 root 功能。长按收藏或一键任务可删除配置。")
        .setPositiveButton("知道了", null)
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    buildPage();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 13 && result == RESULT_OK && data != null && data.getData() != null) {
      exportRenames(data.getData());
      return;
    }
    if (request == 12 && result == RESULT_OK && data != null && data.getData() != null) {
      try {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0
            || (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
          throw new IOException("所选目录未授予读写权限，请选择可写的存储卡目录");
        getContentResolver()
            .takePersistableUriPermission(
                data.getData(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        toast("目录授权已保存，请重新打开路径浏览");
      } catch (Exception e) {
        error(e);
      }
    }
  }

  static String bytes(long value) {
    if (value < 1024) return value + " B";
    if (value < 1048576) return String.format(Locale.ROOT, "%.1f KB", value / 1024d);
    if (value < 1073741824) return String.format(Locale.ROOT, "%.1f MB", value / 1048576d);
    return String.format(Locale.ROOT, "%.2f GB", value / 1073741824d);
  }

  private static String shortName(String ref) {
    String path = Storage.display(ref);
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
  }

  private void error(Exception e) {
    new AlertDialog.Builder(this)
        .setTitle("操作未完成")
        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
        .setPositiveButton("知道了", null)
        .show();
  }

  private final class Explorer {
    final boolean target;
    final Dialog dialog = new Dialog(MainActivity.this);
    final LinkedHashSet<String> selected = new LinkedHashSet<>(sources);
    final ArrayList<TransferEngine.Node> stack = new ArrayList<>();
    final ArrayList<TransferEngine.Node> entries = new ArrayList<>();
    LinearLayout body;
    ListView listing;
    TextView path, info;
    EditText search;
    Button choose;
    int generation;
    boolean closed;
    final ArrayList<TransferEngine.Node> visible = new ArrayList<>();
    final BaseAdapter adapter =
        new BaseAdapter() {
          public int getCount() {
            return visible.size();
          }

          public Object getItem(int position) {
            return visible.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public View getView(int position, View reusable, ViewGroup parent) {
            TransferEngine.Node node = visible.get(position);
            boolean directory = node.directory();
            LinearLayout line = reusable instanceof LinearLayout ? (LinearLayout) reusable : row();
            line.removeAllViews();
            line.setPadding(0, dp(3), 0, dp(3));
            if (!target && !stack.isEmpty()) {
              CheckBox check = new CheckBox(MainActivity.this);
              check.setChecked(selected.contains(node.ref()));
              check.setButtonTintList(ColorStateList.valueOf(ACCENT));
              check.setOnCheckedChangeListener(
                  (v, checked) -> {
                    if (checked) selected.add(node.ref());
                    else selected.remove(node.ref());
                    choose.setText("加入所选 (" + selected.size() + ")");
                  });
              line.addView(check, new LinearLayout.LayoutParams(dp(44), dp(52)));
            }
            LinearLayout labels = column();
            TextView name =
                text(
                    (directory ? "▰  " : "▤  ")
                        + (stack.isEmpty() ? Storage.label(node) : node.name()),
                    15,
                    target && !directory ? MUTED : INK);
            add(labels, name, 4);
            add(
                labels,
                text(
                    stack.isEmpty()
                        ? Storage.display(node.ref())
                        : directory ? "文件夹 · 点名称进入" : target ? "文件 · 仅预览" : "文件 · 勾选加入",
                    11,
                    MUTED),
                2);
            line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            labels.setOnClickListener(
                v -> {
                  if (directory) {
                    stack.add(node);
                    load(node);
                  } else if (!target) {
                    if (selected.contains(node.ref())) selected.remove(node.ref());
                    else selected.add(node.ref());
                    choose.setText("加入所选 (" + selected.size() + ")");
                    notifyDataSetChanged();
                  } else
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(node.name())
                        .setMessage(Storage.display(node.ref()))
                        .setPositiveButton("关闭", null)
                        .show();
                });
            labels.setOnLongClickListener(
                v -> {
                  new AlertDialog.Builder(MainActivity.this)
                      .setTitle(node.name())
                      .setMessage(Storage.display(node.ref()))
                      .setPositiveButton("关闭", null)
                      .show();
                  return true;
                });
            return line;
          }
        };
    final String initial;

    Explorer(boolean target, String initial) {
      this.target = target;
      this.initial = initial;
    }

    void show() {
      body = column();
      body.setPadding(dp(16), dp(18), dp(16), dp(12));
      body.setBackgroundColor(BG);
      body.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            body.setPadding(
                dp(16),
                dp(18) + insets.getSystemWindowInsetTop(),
                dp(16),
                dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
          });
      add(body, text(target ? "选择目标目录" : "选择源文件 / 文件夹", 22, INK), 0);
      path = text("存储位置", 13, ACCENT);
      path.setTextIsSelectable(true);
      add(body, path, 8);
      buttons(
          body,
          button("上一级", false, this::up),
          button(
              "存储位置",
              false,
              () -> {
                stack.clear();
                load(null);
              }));
      search = new EditText(MainActivity.this);
      search.setSingleLine(true);
      search.setHint("筛选当前目录");
      search.setTextSize(14);
      search.setTextColor(INK);
      search.setHintTextColor(MUTED);
      add(body, search, 2);
      search.addTextChangedListener(
          new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

            public void onTextChanged(CharSequence s, int start, int before, int count) {
              renderEntries();
            }

            public void afterTextChanged(android.text.Editable e) {}
          });
      info = text("读取中…", 12, MUTED);
      add(body, info, 5);
      if (!target)
        buttons(
            body,
            button("全选当前显示", false, () -> selectVisible(false)),
            button("反选当前显示", false, () -> selectVisible(true)));
      listing = new ListView(MainActivity.this);
      listing.setAdapter(adapter);
      listing.setDividerHeight(dp(1));
      body.addView(listing, new LinearLayout.LayoutParams(-1, 0, 1));
      buttons(
          body,
          button(
              "收藏当前",
              false,
              () -> {
                if (stack.isEmpty()) toast("请先进入一个目录");
                else favorite(stack.get(stack.size() - 1).ref());
              }),
          button("新建目录", false, this::newDirectory),
          button("授权存储卡", false, this::grantTree));
      choose = button(target ? "使用此目录" : "加入所选 (" + selected.size() + ")", true, this::choose);
      buttons(body, choose, button("取消", false, dialog::dismiss));
      dialog.setContentView(body);
      dialog.setOnDismissListener(
          d -> {
            closed = true;
            generation++;
          });
      dialog.setOnKeyListener(
          (d, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK
                && event.getAction() == android.view.KeyEvent.ACTION_UP
                && !stack.isEmpty()) {
              up();
              return true;
            }
            return false;
          });
      dialog.show();
      Window window = dialog.getWindow();
      if (window != null) {
        window.setLayout(-1, -1);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      }
      if (initial != null && !initial.isEmpty()) {
        try {
          TransferEngine.Node node = storage.resolve(initial);
          stack.add(node);
          load(node);
        } catch (Exception e) {
          error(e);
          load(null);
        }
      } else load(null);
    }

    void grantTree() {
      Intent intent =
          new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
              .addFlags(
                  Intent.FLAG_GRANT_READ_URI_PERMISSION
                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                      | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                      | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
      dialog.dismiss();
      try {
        startActivityForResult(intent, 12);
      } catch (ActivityNotFoundException e) {
        error(e);
      }
    }

    void up() {
      if (!stack.isEmpty()) stack.remove(stack.size() - 1);
      load(stack.isEmpty() ? null : stack.get(stack.size() - 1));
    }

    void load(TransferEngine.Node directory) {
      int current = ++generation;
      entries.clear();
      visible.clear();
      adapter.notifyDataSetChanged();
      info.setText("正在读取目录…");
      path.setText(directory == null ? "内部存储 / 外置存储 / 收藏路径" : Storage.display(directory.ref()));
      search.setText("");
      io.execute(
          () -> {
            try {
              List<TransferEngine.Node> nodes =
                  directory == null ? storage.roots() : directory.children();
              if (directory == null)
                for (String ref : store.favorites().keySet()) {
                  try {
                    TransferEngine.Node node = storage.resolve(ref);
                    boolean found = false;
                    for (TransferEngine.Node root : nodes) if (root.ref().equals(ref)) found = true;
                    if (!found) nodes.add(node);
                  } catch (Exception ignored) {
                  }
                }
              nodes.sort(
                  Comparator.comparing((TransferEngine.Node node) -> !node.directory())
                      .thenComparing(TransferEngine.Node::name, String.CASE_INSENSITIVE_ORDER));
              handler.post(
                  () -> {
                    if (closed || current != generation) return;
                    entries.addAll(nodes);
                    renderEntries();
                  });
            } catch (Exception e) {
              handler.post(
                  () -> {
                    if (closed || current != generation) return;
                    info.setText("读取失败：" + e.getMessage() + "\n请先允许存储权限；旧版 Android 的存储卡请使用目录授权。");
                  });
            }
          });
    }

    void renderEntries() {
      if (listing == null) return;
      visible.clear();
      String filter = search.getText().toString().toLowerCase(Locale.ROOT);
      int count = 0;
      for (TransferEngine.Node node : entries) {
        if (!node.name().toLowerCase(Locale.ROOT).contains(filter)) continue;
        count++;
        visible.add(node);
      }
      adapter.notifyDataSetChanged();
      info.setText(
          count == 0
              ? "此目录为空，或没有匹配项"
              : count + " 项" + (target ? " · 可预览内容，底部选用当前目录" : " · 点目录名称浏览，勾选整项"));
    }

    void selectVisible(boolean invert) {
      if (stack.isEmpty()) {
        toast("请先进入一个存储目录");
        return;
      }
      for (TransferEngine.Node node : visible) {
        if (invert && selected.contains(node.ref())) selected.remove(node.ref());
        else selected.add(node.ref());
      }
      choose.setText("加入所选 (" + selected.size() + ")");
      adapter.notifyDataSetChanged();
    }

    void choose() {
      if (target) {
        if (stack.isEmpty()) {
          toast("请先进入目标目录");
          return;
        }
        destination = stack.get(stack.size() - 1).ref();
        targetText.setText(Storage.display(destination));
      } else {
        sources.clear();
        sources.addAll(selected);
        renderSources();
      }
      saveDraft();
      dialog.dismiss();
    }

    void newDirectory() {
      if (stack.isEmpty()) {
        toast("请先进入目录");
        return;
      }
      TransferEngine.Node parent = stack.get(stack.size() - 1);
      EditText input = new EditText(MainActivity.this);
      input.setSingleLine(true);
      input.setHint("文件夹名称");
      new AlertDialog.Builder(MainActivity.this)
          .setTitle("新建文件夹")
          .setView(input)
          .setNegativeButton("取消", null)
          .setPositiveButton(
              "创建",
              (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) {
                  toast("请输入有效的文件夹名称");
                  return;
                }
                io.execute(
                    () -> {
                      try {
                        parent.createDirectory(name);
                        handler.post(
                            () -> {
                              if (!closed) load(parent);
                            });
                      } catch (Exception e) {
                        handler.post(() -> error(e));
                      }
                    });
              })
          .show();
    }
  }
}
