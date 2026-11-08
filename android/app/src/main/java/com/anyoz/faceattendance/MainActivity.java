package com.anyoz.faceattendance;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.Size;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends ComponentActivity {
  private static final int BG = Ui.BG, INK = Ui.INK, MUTED = Ui.MUTED, GREEN = Ui.GREEN;
  private AttendanceApp app;
  private Ui ui;
  private LinearLayout page, shell;
  private FrameLayout content;
  private ScrollView scroll;
  private String screen = "home", peopleFilter;
  private ProcessCameraProvider cameraProvider;
  private PreviewView preview;
  private FaceOverlay overlay;
  private TextView cameraResult, cameraDetail, cameraCount;
  private ProgressBar captureProgress;
  private volatile boolean cameraActive = false;
  private volatile int generation = 0;
  private final Object cameraGate = new Object();
  private boolean cameraScreen = false, resumeCamera = false, front = true;
  private String enrollName, enrollGroup, recaptureId, sessionId;
  private int cameraTotal;
  private final Recognition.Verifier verifier = new Recognition.Verifier();
  private final List<float[]> enrollSamples = new ArrayList<>();
  private final Set<String> marked = new HashSet<>();
  private long lastAnalysis, lastTimestamp;
  private String pendingExport;
  volatile long processedFrames;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    app = (AttendanceApp) getApplication();
    ui = new Ui(this);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (!screen.equals("home"))
          navigateBack();
        else {
          setEnabled(false);
          getOnBackPressedDispatcher().onBackPressed();
          setEnabled(true);
        }
      }
    });
    showHome();
  }
  private int dp(float n) {
    return ui.dp(n);
  }
  private String quantity(int n, String one, String few, String many) {
    int h = n % 100, d = n % 10;
    return n + " " + (h >= 11 && h <= 14 ? many : d == 1 ? one : d >= 2 && d <= 4 ? few : many);
  }
  private GradientDrawable shape(int color, int radius) {
    return ui.shape(color, radius);
  }
  private TextView text(String value, int size, int color) {
    return ui.text(value, size, color);
  }
  private void gap(int height) {
    ui.gap(page, height);
  }
  private void title(String value) {
    page.addView(ui.bold(value, 24, INK));
    gap(8);
  }
  private void note(String value) {
    page.addView(text(value, 14, MUTED));
    gap(12);
  }
  private void section(String value) {
    gap(24);
    page.addView(ui.bold(value, 16, INK));
    gap(14);
  }
  private Runnable safe(Runnable action) {
    return () -> {
      try {
        action.run();
      } catch (Exception e) {
        error(e);
      }
    };
  }
  private Button button(String value, Runnable action) {
    return button(value, null, Ui.ACCENT, Ui.SURFACE, action);
  }
  private Button button(String value, String icon, int fill, int ink, Runnable action) {
    Button b = ui.button(value, icon, fill, ink, safe(action));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(10);
    page.addView(b, lp);
    return b;
  }
  private void link(String value, Runnable action) {
    button(value, null, Ui.SOFT, Ui.ACCENT, action);
  }
  private void base(String name) {
    stopCamera();
    View focused = getCurrentFocus();
    if (focused != null)
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    screen = name;
    boolean dark = name.equals("camera");
    shell = ui.column();
    shell.setBackgroundColor(dark ? Ui.DARK_BG : Ui.SURFACE);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(dark ? Ui.DARK_BG : Ui.SURFACE);
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightStatusBars(!dark);
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightNavigationBars(!dark);
    ViewCompat.setOnApplyWindowInsetsListener(shell, (view, insets) -> {
      androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()),
                                    ime = insets.getInsets(WindowInsetsCompat.Type.ime());
      view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
      return insets;
    });
    setContentView(shell);
    ViewCompat.requestApplyInsets(shell);
    LinearLayout bar = ui.row();
    bar.setPadding(dp(20), dp(8), dp(20), dp(12));
    boolean child = name.equals("report") || name.equals("settings") || dark;
    if (child) {
      ImageButton back = ui.iconButton("back", "Назад", dark ? Ui.DARK_SURFACE : Ui.SURFACE,
          dark ? Ui.DARK_TEXT : INK, this::navigateBack);
      back.setId(R.id.navigate_back);
      bar.addView(back);
      TextView label = ui.bold(dark   ? "Камера"
              : name.equals("report") ? "Результаты"
                                      : "Настройки",
          18, dark ? Ui.DARK_TEXT : INK);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.leftMargin = dp(12);
      bar.addView(label, lp);
    } else {
      String heading = name.equals("people") ? "Люди"
          : name.equals("groups")            ? "Группы"
          : name.equals("history")           ? "История"
                                             : "Rollmark";
      bar.addView(ui.bold(heading, 21, INK), new LinearLayout.LayoutParams(0, -2, 1));
      ImageButton settings =
          ui.iconButton("settings", "Настройки", Color.TRANSPARENT, MUTED, this::showSettings);
      settings.setId(R.id.open_settings);
      bar.addView(settings);
    }
    shell.addView(bar);
    content = new FrameLayout(this);
    shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    page = ui.column();
    page.setPadding(dp(20), dp(12), dp(20), dp(12));
    scroll.addView(page);
    content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    if (!dark && !child)
      addNavigation(name);
  }
  private void addNavigation(String selected) {
    LinearLayout nav = ui.row();
    nav.setBackgroundColor(Ui.SURFACE);
    nav.setPadding(dp(8), dp(8), dp(8), dp(8));
    String[] labels = {"Журнал", "Люди", "Группы", "История"},
             keys = {"home", "people", "groups", "history"},
             icons = {"home", "people", "groups", "history"};
    Runnable[] actions = {
        this::showHome, () -> showPeople(null), this::showGroups, this::showHistory};
    for (int i = 0; i < 4; i++) {
      boolean active = selected.equals(keys[i]);
      LinearLayout item = ui.column();
      item.setGravity(Gravity.CENTER);
      item.setPadding(0, dp(4), 0, dp(4));
      item.setBackground(ui.ripple(Ui.SURFACE, 18));
      FrameLayout pill = new FrameLayout(this);
      pill.setBackground(null);
      ImageView icon = new ImageView(this);
      icon.setImageDrawable(ui.icon(icons[i], active ? Ui.ACCENT : MUTED));
      pill.addView(icon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
      item.addView(pill, new LinearLayout.LayoutParams(dp(60), dp(32)));
      ui.gap(item, 5);
      TextView caption = ui.bold(labels[i], 12, active ? Ui.ACCENT : MUTED);
      caption.setGravity(Gravity.CENTER);
      item.addView(caption, new LinearLayout.LayoutParams(-1, -2));
      item.setId(new int[] {R.id.nav_home, R.id.nav_people, R.id.nav_groups, R.id.nav_history}[i]);
      item.setContentDescription(labels[i]);
      item.setSelected(active);
      final Runnable action = actions[i];
      item.setOnClickListener(v -> action.run());
      nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    View rule = new View(this);
    rule.setBackgroundColor(Ui.LINE);
    shell.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
    shell.addView(nav);
  }
  private void error(Exception e) {
    Log.w("FaceAttendance", "User operation", e);
    String message = e instanceof android.database.sqlite.SQLiteException
        ? "Не удалось сохранить данные. Проверьте имя и выбранную группу."
        : e.getMessage() == null ? "Повторите действие"
                                 : e.getMessage();
    new AlertDialog.Builder(this)
        .setTitle("Не получилось")
        .setMessage(message)
        .setPositiveButton("Понятно", null)
        .show();
  }
  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
  private void confirm(String title, String description, Runnable action) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(description)
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Продолжить", (d, w) -> safe(action).run())
        .show();
  }
  private EditText field(String hint, String value) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(16);
    e.setSingleLine(true);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setPadding(dp(16), dp(14), dp(16), dp(14));
    e.setBackground(shape(BG, 8));
    e.setMinHeight(dp(48));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(12);
    e.setLayoutParams(lp);
    return e;
  }
  private Spinner spinner(List<String> values, String selected) {
    Spinner s = new Spinner(this);
    ArrayAdapter<String> adapter = new ArrayAdapter<String>(
        this, android.R.layout.simple_spinner_dropdown_item, values) {
      @Override
      public View getView(int pos, View view, ViewGroup parent) {
        TextView t = (TextView) super.getView(pos, view, parent);
        t.setTextColor(INK);
        t.setTextSize(16);
        t.setPadding(dp(16), dp(14), dp(12), dp(14));
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ui.icon("down", MUTED), null);
        return t;
      }
    };
    s.setAdapter(adapter);
    s.setBackground(shape(BG, 8));
    s.setMinimumHeight(dp(48));
    if (selected != null)
      s.setSelection(Math.max(0, values.indexOf(selected)));
    return s;
  }
  private LinearLayout form() {
    LinearLayout l = ui.column();
    l.setPadding(dp(24), dp(12), dp(24), dp(12));
    return l;
  }
  private void label(LinearLayout parent, String title) {
    parent.addView(ui.bold(title, 12, MUTED));
    ui.gap(parent, 8);
  }
  private ScrollView formScroll(LinearLayout form) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.addView(form);
    return scroll;
  }
  private String date(long time) {
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(new Date(time));
  }
  private void inline(Ui.Item item) {
    Ui.ItemView view = ui.new ItemView();
    view.setPadding(0, 0, 0, 0);
    view.bind(item);
    page.addView(view);
  }
  private void empty(String icon, String heading, String detail) {
    gap(20);
    page.addView(ui.bold(heading, 18, INK));
    gap(8);
    note(detail);
  }
  private void listActions(String count, String label, int id, Runnable action) {
    LinearLayout row = ui.row();
    row.addView(text(count, 14, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    Button add = ui.button(label, "plus", Color.TRANSPARENT, Ui.ACCENT, safe(action));
    add.setId(id);
    add.setContentDescription(label.equals("Добавить") ? "Добавить человека" : "Создать группу");
    row.addView(add, new LinearLayout.LayoutParams(-2, dp(48)));
    page.addView(row);
    gap(12);
  }
  private void firstStep(String number, String heading, String detail) {
    LinearLayout row = ui.row();
    row.setGravity(Gravity.TOP);
    TextView index = text(number, 16, MUTED);
    row.addView(index, new LinearLayout.LayoutParams(dp(28), -2));
    LinearLayout words = ui.column();
    words.addView(ui.bold(heading, 15, INK));
    ui.gap(words, 4);
    words.addView(text(detail, 13, MUTED));
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    page.addView(row);
    gap(18);
  }

  private void showHome() {
    base("home");
    page.addView(
        text(new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(new Date()), 14, MUTED));
    gap(24);
    AttendanceStore.Session active = app.store.active();
    int count = app.store.people().size();
    if (active != null) {
      page.addView(ui.bold(active.name, 24, INK));
      gap(6);
      note(String.join(", ", active.groups));
      LinearLayout tally = ui.row();
      tally.addView(ui.bold(String.valueOf(active.present), 40, INK));
      TextView rest = text("из " + active.total + " отмечены", 16, MUTED);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = dp(10);
      tally.addView(rest, lp);
      page.addView(tally);
      gap(18);
      Button camera = button(
          "Открыть камеру", "scan", Ui.ACCENT, Ui.SURFACE, () -> openCamera(null, null, null));
      camera.setId(R.id.home_action);
      button("Посмотреть список", null, Color.TRANSPARENT, Ui.ACCENT, () -> showReport(active));
    } else {
      title(count == 0 ? "Добавьте участников" : "Новая проверка");
      note(count == 0 ? "Укажите имя и группу. Лицо сохранится автоматически, когда человек "
                  + "посмотрит в камеру."
                      : "Выберите группы, которые нужно отметить сегодня.");
      Button primary =
          button(count == 0 ? "Добавить человека" : "Начать проверку", count == 0 ? "plus" : "scan",
              Ui.ACCENT, Ui.SURFACE, count == 0 ? () -> enrollmentDialog(null) : this::startDialog);
      primary.setId(R.id.home_action);
    }
    List<AttendanceStore.Session> completed = new ArrayList<>();
    for (AttendanceStore.Session session : app.store.sessions())
      if (session.closed > 0)
        completed.add(session);
    if (!completed.isEmpty()) {
      section("Последние проверки");
      for (int i = 0; i < Math.min(4, completed.size()); i++) inline(sessionItem(completed.get(i)));
    } else if (count == 0) {
      section("Для первой проверки");
      firstStep("1", "Добавьте людей", "Имя, группа и несколько секунд перед камерой.");
      firstStep("2", "Начните проверку", "Можно выбрать сразу несколько групп.");
      firstStep("3", "Отмечайте посещение", "Участники подходят к телефону по очереди.");
    } else {
      gap(20);
      note("Результаты сохранятся в истории. В списке можно поставить или снять отметку вручную.");
    }
  }

  private final class Rows extends BaseAdapter {
    List<Ui.Item> values = new ArrayList<>();
    void replace(List<Ui.Item> rows) {
      values = rows;
      notifyDataSetChanged();
    }
    public int getCount() {
      return values.size();
    }
    public Object getItem(int i) {
      return values.get(i);
    }
    public long getItemId(int i) {
      return i;
    }
    public View getView(int i, View view, ViewGroup parent) {
      Ui.ItemView row = view instanceof Ui.ItemView ? (Ui.ItemView) view : ui.new ItemView();
      row.bind(values.get(i));
      return row;
    }
  }
  private Rows list() {
    ((ViewGroup) page.getParent()).removeView(page);
    content.removeAllViews();
    ListView list = new ListView(this);
    list.setDivider(null);
    list.setVerticalScrollBarEnabled(false);
    list.setCacheColorHint(Color.TRANSPARENT);
    list.setClipToPadding(false);
    list.setPadding(0, 0, 0, dp(12));
    list.addHeaderView(page, null, false);
    Rows rows = new Rows();
    list.setAdapter(rows);
    content.addView(list, new FrameLayout.LayoutParams(-1, -1));
    return rows;
  }
  private EditText search(String hint) {
    EditText e = field(hint, "");
    e.setCompoundDrawablesRelativeWithIntrinsicBounds(ui.icon("search", MUTED), null, null, null);
    e.setCompoundDrawablePadding(dp(12));
    e.setBackground(shape(BG, 10));
    e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
    page.addView(e);
    return e;
  }
  private void watch(EditText edit, Runnable action) {
    edit.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        action.run();
      }
      public void afterTextChanged(Editable s) {}
    });
  }
  private void chips(LinearLayout parent, List<String> names, String selected,
      java.util.function.Consumer<String> action) {
    HorizontalScrollView scroller = new HorizontalScrollView(this);
    scroller.setHorizontalScrollBarEnabled(false);
    LinearLayout row = ui.row();
    for (String name : names) {
      boolean chosen = name.equals(selected);
      TextView label = ui.bold(name, 14, chosen ? Ui.ACCENT : MUTED);
      label.setGravity(Gravity.CENTER);
      label.setPadding(dp(14), 0, dp(14), 0);
      FrameLayout tab = new FrameLayout(this);
      tab.setBackground(ui.ripple(Ui.SURFACE, 4));
      tab.addView(label, new FrameLayout.LayoutParams(-1, -1));
      int width = Math.round(label.getPaint().measureText(name)) + dp(28);
      if (chosen) {
        View underline = new View(this);
        underline.setBackgroundColor(Ui.ACCENT);
        tab.addView(underline, new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM));
      }
      tab.setSelected(chosen);
      tab.setOnClickListener(v -> action.accept(name));
      row.addView(tab, new LinearLayout.LayoutParams(width, dp(48)));
    }
    scroller.addView(row);
    parent.addView(scroller);
    ui.gap(parent, 10);
  }

  private List<Ui.Item> noResults(List<Ui.Item> items) {
    if (items.isEmpty())
      items.add(
          new Ui.Item("Ничего не найдено", "Попробуйте другое имя или фильтр", "search", null));
    return items;
  }
  private void showPeople() {
    showPeople(peopleFilter);
  }
  private void showPeople(String filter) {
    peopleFilter = filter;
    base("people");
    List<AttendanceStore.Person> people = app.store.people();
    listActions(quantity(people.size(), "человек", "человека", "человек"), "Добавить",
        R.id.add_person, () -> enrollmentDialog(null));
    if (people.isEmpty()) {
      empty("people", "Список пока пуст",
          "Добавьте участников. После регистрации лицо можно переснять или перевести человека в "
              + "другую группу.");
      return;
    }
    EditText query = search("Имя или группа");
    query.setId(R.id.people_search);
    List<String> names = new ArrayList<>();
    names.add("Все группы");
    names.addAll(app.store.groups());
    Spinner filterView = spinner(names, peopleFilter == null ? "Все группы" : peopleFilter);
    filterView.setId(R.id.people_group_filter);
    page.addView(filterView);
    gap(4);
    Rows rows = list();
    Runnable update = () -> {
      String q = query.getText().toString().trim().toLowerCase(Locale.ROOT);
      List<Ui.Item> items = new ArrayList<>();
      for (AttendanceStore.Person person : people) {
        if ((peopleFilter != null && !person.group.equals(peopleFilter))
            || !(person.name + " " + person.group).toLowerCase(Locale.ROOT).contains(q))
          continue;
        Ui.Item item = new Ui.Item(person.name, peopleFilter == null ? person.group : "", null,
            () -> personActions(person));
        if (person.samples < 6)
          item.badge("Переснять", Ui.AMBER, Color.TRANSPARENT);
        items.add(item);
      }
      rows.replace(noResults(items));
    };
    filterView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        peopleFilter = position == 0 ? null : names.get(position);
        update.run();
      }
      public void onNothingSelected(AdapterView<?> parent) {}
    });
    watch(query, update);
    update.run();
  }

  private void personActions(AttendanceStore.Person p) {
    new AlertDialog.Builder(this)
        .setTitle(p.name)
        .setItems(new String[] {"Переснять лицо", "Перевести в другую группу", "В архив"},
            (d, item) -> {
              if (item == 0)
                enrollmentDialog(p);
              else if (item == 1) {
                List<String> groups = app.store.groups();
                new AlertDialog.Builder(this)
                    .setTitle("Новая группа")
                    .setItems(groups.toArray(new String[0]), (a, pos) -> safe(() -> {
                      app.store.move(p.id, groups.get(pos));
                      app.worker.execute(app::reloadGallery);
                      showPeople();
                    }).run())
                    .show();
              } else
                confirm("Архивировать?", "История посещаемости сохранится.", () -> {
                  app.store.archive(p.id);
                  app.worker.execute(app::reloadGallery);
                  showPeople();
                });
            })
        .show();
  }
  private void enrollmentDialog(AttendanceStore.Person existing) {
    LinearLayout form = form();
    label(form, "Имя и фамилия");
    EditText name = field("Например, Алексей Иванов", existing == null ? "" : existing.name);
    form.addView(name);
    label(form, "Группа");
    Spinner group = spinner(app.store.groups(), existing == null ? peopleFilter : existing.group);
    form.addView(group);
    ui.gap(form, 16);
    form.addView(
        text("Посмотрите в камеру. Съёмка и сохранение лица начнутся автоматически.", 13, MUTED));
    if (existing != null) {
      name.setEnabled(false);
      group.setEnabled(false);
    }
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle(existing == null ? "Новый человек" : "Обновить лицо")
                             .setView(formScroll(form))
                             .setNegativeButton("Отмена", null)
                             .setPositiveButton("Открыть камеру", null)
                             .create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(b -> {
      try {
        String n = AttendanceStore.clean(name.getText().toString(), 100),
               g = group.getSelectedItem().toString();
        dialog.dismiss();
        openCamera(n, g, existing == null ? null : existing.id);
      } catch (Exception e) {
        name.setError(e.getMessage());
      }
    }));
    dialog.show();
  }
  private void showGroups() {
    base("groups");
    listActions(quantity(app.store.groups().size(), "группа", "группы", "групп"), "Создать",
        R.id.add_group, this::groupDialog);
    List<AttendanceStore.Person> people = app.store.people();
    Rows rows = list();
    List<Ui.Item> items = new ArrayList<>();
    for (String group : app.store.groups()) {
      int count = 0;
      for (AttendanceStore.Person p : people)
        if (p.group.equals(group))
          count++;
      items.add(new Ui.Item(group, quantity(count, "человек", "человека", "человек"), "groups",
          () -> showPeople(group)));
    }
    rows.replace(items);
  }
  private void groupDialog() {
    LinearLayout form = form();
    EditText input = field("Название группы", "");
    form.addView(input);
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle("Новая группа")
                             .setView(form)
                             .setNegativeButton("Отмена", null)
                             .setPositiveButton("Создать", null)
                             .create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(b -> {
      try {
        app.store.addGroup(input.getText().toString());
        dialog.dismiss();
        showGroups();
      } catch (Exception e) {
        input.setError(e.getMessage());
      }
    }));
    dialog.show();
  }
  private void startDialog() {
    if (app.store.people().isEmpty()) {
      enrollmentDialog(null);
      return;
    }
    List<String> groups = app.store.groups();
    List<AttendanceStore.Person> people = app.store.people();
    LinearLayout form = form();
    label(form, "Название");
    EditText name = field("Название проверки",
        "Проверка " + DateFormat.getDateInstance(DateFormat.SHORT).format(new Date()));
    form.addView(name);
    label(form, "Группы");
    List<CheckBox> boxes = new ArrayList<>();
    for (String group : groups) {
      int count = 0;
      for (AttendanceStore.Person person : people)
        if (person.group.equals(group))
          count++;
      CheckBox c = new CheckBox(this);
      c.setText(group + "  ·  " + count);
      c.setTag(group);
      c.setTextSize(15);
      c.setTextColor(count > 0 ? INK : MUTED);
      c.setMinHeight(dp(52));
      c.setEnabled(count > 0);
      c.setChecked(count > 0 && groups.size() == 1);
      boxes.add(c);
      form.addView(c);
    }
    TextView hint = text("Можно выбрать сразу несколько групп.", 13, MUTED);
    ui.gap(form, 12);
    form.addView(hint);
    AlertDialog dialog = new AlertDialog.Builder(this)
                             .setTitle("Новая проверка")
                             .setView(formScroll(form))
                             .setNegativeButton("Отмена", null)
                             .setPositiveButton("Начать", null)
                             .create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(b -> {
      try {
        List<String> selected = new ArrayList<>();
        for (CheckBox c : boxes)
          if (c.isChecked())
            selected.add((String) c.getTag());
        if (selected.isEmpty()) {
          hint.setText("Выберите хотя бы одну непустую группу.");
          hint.setTextColor(Ui.AMBER);
          return;
        }
        app.store.start(name.getText().toString(), selected);
        app.worker.execute(app::reloadGallery);
        dialog.dismiss();
        openCamera(null, null, null);
      } catch (Exception e) {
        error(e);
      }
    }));
    dialog.show();
  }
  private Ui.Item sessionItem(AttendanceStore.Session session) {
    return new Ui
        .Item(session.name, date(session.opened) + (session.closed == 0 ? "  Идёт сейчас" : ""),
            "history", () -> showReport(session))
        .badge(session.present + " / " + session.total, session.closed == 0 ? GREEN : MUTED,
            Color.TRANSPARENT);
  }

  private void showHistory() {
    base("history");
    List<AttendanceStore.Session> sessions = app.store.sessions();
    note(quantity(sessions.size(), "проверка", "проверки", "проверок"));
    if (sessions.isEmpty()) {
      empty("history", "Пока ни одной проверки",
          "Начните проверку в журнале. Здесь останутся списки присутствующих и отсутствующих.");
      button("Открыть журнал", this::showHome);
      return;
    }
    EditText query = search("Найти проверку");
    query.setId(R.id.history_search);
    Rows rows = list();
    Runnable update = () -> {
      List<Ui.Item> items = new ArrayList<>();
      String q = query.getText().toString().trim().toLowerCase(Locale.ROOT);
      for (AttendanceStore.Session session : sessions)
        if ((session.name + " " + String.join(" ", session.groups))
                .toLowerCase(Locale.ROOT)
                .contains(q))
          items.add(sessionItem(session));
      rows.replace(noResults(items));
    };
    watch(query, update);
    update.run();
  }
  private void showReport(AttendanceStore.Session selected) {
    AttendanceStore.Session found = selected;
    for (AttendanceStore.Session candidate : app.store.sessions())
      if (candidate.id.equals(selected.id))
        found = candidate;
    final AttendanceStore.Session session = found;
    base("report");
    title(session.name);
    note(String.join(" + ", session.groups) + "\n" + date(session.opened));
    List<AttendanceStore.Entry> entries = app.store.entries(session.id);
    LinearLayout summary = ui.row();
    summary.addView(ui.bold(session.present + " / " + session.total, 30, INK));
    TextView caption = text("присутствуют", 14, MUTED);
    LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-2, -2);
    captionLp.leftMargin = dp(12);
    summary.addView(caption, captionLp);
    page.addView(summary);
    gap(18);
    if (session.closed == 0)
      button("Открыть камеру", "camera", Ui.ACCENT, Ui.SURFACE, () -> openCamera(null, null, null));
    else
      note("Проверка завершена");
    LinearLayout bar = (LinearLayout) shell.getChildAt(0);
    ImageButton more =
        ui.iconButton("more", "Действия с проверкой", Color.TRANSPARENT, INK, () -> {});
    more.setId(R.id.report_more);
    more.setOnClickListener(view -> {
      PopupMenu menu = new PopupMenu(this, more);
      menu.getMenu().add(0, 1, 0, "Сохранить CSV");
      if (session.closed == 0)
        menu.getMenu().add(0, 2, 1, "Завершить проверку");
      menu.setOnMenuItemClickListener(item -> {
        safe(() -> {
          if (item.getItemId() == 1)
            export(app.store.csv(session), "text/csv", "Rollmark-attendance.csv", 601);
          else
            confirm("Завершить проверку?",
                "Участники без отметки будут записаны как отсутствующие.", () -> {
                  app.store.closeSession(session.id);
                  showReport(session);
                });
        }).run();
        return true;
      });
      menu.show();
    });
    bar.addView(more);
    if (session.groups.size() > 1) {
      for (String group : session.groups) {
        int total = 0, present = 0;
        for (AttendanceStore.Entry entry : entries)
          if (entry.group.equals(group)) {
            total++;
            if (entry.present > 0)
              present++;
          }
        page.addView(text(group + "  ·  " + present + " / " + total, 13, MUTED));
        gap(6);
      }
      gap(10);
    }
    EditText query = search("Найти участника");
    query.setId(R.id.report_search);
    LinearLayout filters = ui.column();
    page.addView(filters);
    Rows rows = list();
    final String[] status = {"Все"};
    final Runnable[] update = {null}, rebuild = {null};
    update[0] = () -> {
      String q = query.getText().toString().trim().toLowerCase(Locale.ROOT);
      List<Ui.Item> items = new ArrayList<>();
      for (AttendanceStore.Entry entry : entries) {
        if (!status[0].equals("Все") && (status[0].equals("Присутствуют") != (entry.present > 0)))
          continue;
        if (!(entry.name + " " + entry.group).toLowerCase(Locale.ROOT).contains(q))
          continue;
        String badge = entry.present > 0 ? "Есть" : session.closed == 0 ? "Нет отметки" : "Нет";
        String detail = entry.group
            + (entry.blocked ? " · вручную снята отметка"
                    : entry.present > 0 && "manual".equals(entry.method) ? " · вручную"
                                                                         : "");
        items.add(new Ui
                .Item(entry.name, detail, null,
                    session.closed == 0
                    ? ()
                          -> confirm(entry.present > 0 ? "Снять отметку?" : "Отметить вручную?",
                              entry.present > 0
                                  ? "Камера не вернёт отметку автоматически в этой проверке."
                                  : entry.name,
                              () -> {
                                app.store.mark(session.id, entry.id, entry.present == 0, true);
                                showReport(session);
                              })
                    : null)
                .badge(badge, entry.present > 0 ? GREEN : MUTED, Color.TRANSPARENT));
      }
      rows.replace(noResults(items));
    };
    rebuild[0] = () -> {
      filters.removeAllViews();
      chips(filters,
          Arrays.asList("Все", "Присутствуют", session.closed == 0 ? "Без отметки" : "Отсутствуют"),
          status[0], value -> {
            status[0] = value;
            rebuild[0].run();
            update[0].run();
          });
    };
    rebuild[0].run();
    watch(query, update[0]);
    update[0].run();
  }
  private void showSettings() {
    base("settings");
    LinearLayout about = ui.row();
    about.addView(ui.logo(42));
    LinearLayout words = ui.column();
    words.addView(ui.bold("Rollmark", 21, INK));
    ui.gap(words, 4);
    words.addView(text("Версия " + BuildConfig.VERSION_NAME, 13, MUTED));
    LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(0, -2, 1);
    aboutLp.leftMargin = dp(12);
    about.addView(words, aboutLp);
    page.addView(about);
    section("Данные");
    java.io.File incoming = new java.io.File(getFilesDir(), "incoming-backup.json");
    if (incoming.exists())
      inline(new Ui.Item("Перенести данные с ноутбука", "Заменить базу подготовленной копией",
          "restore",
          ()
              -> confirm("Перенести данные с ноутбука?",
                  "Текущая база будет заменена. Сначала сохраните копию, если вы уже добавили "
                      + "людей на телефоне.",
                  () -> app.worker.execute(() -> {
                    try (InputStream in = new FileInputStream(incoming)) {
                      app.store.restore(readText(in, 32 * 1024 * 1024));
                      app.reloadGallery();
                      runOnUiThread(() -> {
                        toast("Люди и история перенесены");
                        showHome();
                      });
                    } catch (Exception e) {
                      runOnUiThread(() -> error(e));
                    }
                  }))));
    inline(new Ui.Item("Сохранить резервную копию", "Люди, группы и история посещений", "download",
        () -> app.worker.execute(() -> {
          try {
            String backup = app.store.backup();
            runOnUiThread(() -> export(backup, "application/json", "Rollmark-backup.json", 602));
          } catch (Exception e) {
            runOnUiThread(() -> error(e));
          }
        })));
    inline(new Ui.Item(
        "Восстановить из копии", "Выбрать файл с сохранёнными данными", "restore", safe(() -> {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.setType("application/json");
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(intent, 603);
        })));
    gap(16);
    note("Люди и журнал хранятся на телефоне. Сохраните копию перед удалением приложения.");
    section("О приложении");
    inline(new Ui.Item("Распознавание лица", "ArcFace ResNet50", "scan",
        ()
            -> new AlertDialog.Builder(this)
                .setTitle("Распознавание лица")
                .setMessage(
                    (app.engine == null ? app.engineError == null ? "Модель загружается."
                                                                  : "Не удалось загрузить модель."
                                        : app.engine.backend)
                    + ("\n\nДля распознавания хранятся числовые шаблоны лица. Снимки с камеры не "
                        + "сохраняются. Проверка живости отключена."))
                .setPositiveButton("Понятно", null)
                .show()));
    inline(new Ui.Item("Модели и лицензии", "Используемые компоненты", "file",
        ()
            -> new AlertDialog.Builder(this)
                .setTitle("Модели и зависимости")
                .setMessage(readAssetNotice())
                .setPositiveButton("Понятно", null)
                .show()));
  }
  private String readAssetNotice() {
    try (InputStream in = getAssets().open("model_notice.txt")) {
      return readText(in, 100000);
    } catch (Exception e) {
      return "ArcFace / YuNet / ONNX Runtime";
    }
  }
  private void export(String text, String type, String name, int request) {
    pendingExport = text;
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType(type);
    intent.putExtra(Intent.EXTRA_TITLE, name);
    startActivityForResult(intent, request);
  }
  private static String readText(InputStream in, int limit) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (out.size() + n > limit)
        throw new IllegalArgumentException("Файл слишком большой");
      out.write(buffer, 0, n);
    }
    return out.toString("UTF-8");
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null)
      return;
    if (request == 601 || request == 602) {
      if (pendingExport == null) {
        toast("Повторите экспорт");
        return;
      }
      try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
        out.write(pendingExport.getBytes(StandardCharsets.UTF_8));
        pendingExport = null;
        toast("Файл сохранён");
      } catch (Exception e) {
        error(e);
      }
    }
    if (request == 603)
      confirm("Заменить данные приложения?",
          "Люди и история будут заменены выбранной копией. Сначала сохраните текущую копию, если "
              + "она нужна.",
          () -> app.worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
              app.store.restore(readText(in, 32 * 1024 * 1024));
              app.reloadGallery();
              runOnUiThread(() -> {
                toast("Данные восстановлены");
                showHome();
              });
            } catch (Exception e) {
              runOnUiThread(() -> error(e));
            }
          }));
  }

  private void openCamera(String name, String group, String existing) {
    base("camera");
    cameraScreen = true;
    enrollName = name;
    enrollGroup = group;
    recaptureId = existing;
    AttendanceStore.Session active = app.store.active();
    sessionId = active == null ? null : active.id;
    cameraTotal = active == null ? 0 : active.total;
    if (name == null && sessionId == null) {
      showHome();
      toast("Сначала начните проверку");
      return;
    }
    ((ViewGroup) page.getParent()).removeView(page);
    content.removeAllViews();
    content.addView(page, new FrameLayout.LayoutParams(-1, -1));
    page.setPadding(dp(20), 0, dp(20), dp(16));
    page.addView(ui.bold(name == null ? "Отметка посещаемости" : name, 23, Ui.DARK_TEXT));
    gap(8);
    page.addView(text(
        name == null ? "Подойдите к камере по очереди" : group + " · в кадре только один человек",
        14, Ui.DARK_MUTED));
    gap(16);
    FrameLayout stage = new FrameLayout(this);
    stage.setBackground(shape(Ui.DARK_PREVIEW, 24));
    stage.setClipToOutline(true);
    LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, 0, 1);
    stageLp.bottomMargin = dp(16);
    page.addView(stage, stageLp);
    preview = new PreviewView(this);
    preview.setId(R.id.camera_preview);
    preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
    stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    overlay = new FaceOverlay();
    stage.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    cameraCount = ui.pill(name == null ? active.present + " / " + active.total + " отмечены"
                                       : "Снимки сохраняются автоматически",
        Ui.DARK_TEXT, 0xdd303943);
    cameraCount.setId(R.id.camera_count);
    FrameLayout.LayoutParams badgeLp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    badgeLp.topMargin = dp(14);
    stage.addView(cameraCount, badgeLp);
    LinearLayout status = ui.column();
    status.setPadding(dp(18), dp(16), dp(18), dp(16));
    status.setBackground(shape(Ui.DARK_SURFACE, 20));
    cameraResult = ui.bold("Подготовка камеры…", 19, Ui.DARK_TEXT);
    status.addView(cameraResult);
    ui.gap(status, 8);
    cameraDetail =
        text(name == null ? "Держите лицо ближе к центру" : "Кадры сохранятся автоматически", 13,
            Ui.DARK_MUTED);
    status.addView(cameraDetail);
    captureProgress = null;
    if (name != null) {
      ui.gap(status, 14);
      captureProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      captureProgress.setId(R.id.registration_progress);
      captureProgress.setMax(6);
      captureProgress.setProgressTintList(
          android.content.res.ColorStateList.valueOf(Ui.DARK_ACCENT));
      captureProgress.setProgressBackgroundTintList(
          android.content.res.ColorStateList.valueOf(Ui.DARK_TRACK));
      captureProgress.setContentDescription("Снято 0 из 6 кадров");
      status.addView(captureProgress, new LinearLayout.LayoutParams(-1, dp(6)));
    }
    page.addView(status);
    gap(14);
    LinearLayout actions = ui.row();
    actions.addView(ui.button("Сменить", "flip", Ui.DARK_SURFACE, Ui.DARK_TEXT, () -> {
      stopCameraInternal();
      front = !front;
      startCamera();
    }), new LinearLayout.LayoutParams(0, -2, 1));
    View spacer = new View(this);
    actions.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
    actions.addView(ui.button(name == null ? "Список" : "Отмена", name == null ? "people" : "close",
                        Ui.DARK_ACCENT, Ui.DARK_BG,
                        () -> {
                          if (name == null && active != null)
                            showReport(active);
                          else
                            showPeople();
                        }),
        new LinearLayout.LayoutParams(0, -2, 1));
    page.addView(actions);
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
      startCamera();
    else
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 41);
  }
  private void navigateBack() {
    if (screen.equals("camera")) {
      if (enrollName != null)
        showPeople();
      else {
        AttendanceStore.Session active = app.store.active();
        if (active != null)
          showReport(active);
        else
          showHome();
      }
    } else if (screen.equals("report"))
      showHistory();
    else
      showHome();
  }
  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 41) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && cameraScreen)
        startCamera();
      else if (cameraResult != null)
        cameraResult.setText(
            "Разрешите камеру в настройках приложения и откройте этот экран снова.");
    }
  }
  private void startCamera() {
    if (!cameraScreen || cameraActive)
      return;
    final int version;
    synchronized (cameraGate) {
      cameraActive = true;
      version = ++generation;
    }
    verifier.reset();
    enrollSamples.clear();
    if (captureProgress != null)
      captureProgress.setProgress(0);
    marked.clear();
    lastAnalysis = 0;
    lastTimestamp = 0;
    if (sessionId != null)
      for (AttendanceStore.Entry e : app.store.entries(sessionId))
        if (e.present > 0)
          marked.add(e.id);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
    future.addListener(() -> {
      if (!cameraActive || generation != version)
        return;
      try {
        cameraProvider = future.get();
        cameraProvider.unbindAll();
        Preview p = new Preview.Builder()
                        .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                        .build();
        p.setSurfaceProvider(preview.getSurfaceProvider());
        ImageAnalysis analysis =
            new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(app.worker, image -> analyze(image, version));
        CameraSelector selector =
            front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        if (!cameraProvider.hasCamera(selector)) {
          front = false;
          selector = CameraSelector.DEFAULT_BACK_CAMERA;
        }
        cameraProvider.bindToLifecycle(this, selector, p, analysis);
      } catch (Exception e) {
        cameraActive = false;
        cameraResult.setText("Не удалось включить камеру: " + e.getMessage());
        Log.e("FaceAttendance", "Camera failed", e);
      }
    }, ContextCompat.getMainExecutor(this));
  }
  private void analyze(ImageProxy image, int version) {
    Bitmap bitmap = null;
    try {
      if (!cameraActive || generation != version)
        return;
      long now = SystemClock.elapsedRealtime();
      if (now - lastAnalysis < 220)
        return;
      lastAnalysis = now;
      long timestamp = image.getImageInfo().getTimestamp() / 1000000;
      if (timestamp <= lastTimestamp)
        return;
      lastTimestamp = timestamp;
      if (app.engine == null) {
        updateCamera(version,
            app.engineError == null ? "Загрузка модели…" : "Не удалось загрузить модель",
            app.engineError == null ? "Первый запуск может занять несколько секунд"
                                    : "Закройте и снова откройте приложение",
            null, 0, 0, false);
        return;
      }
      Bitmap raw = image.toBitmap();
      int rotation = image.getImageInfo().getRotationDegrees();
      if (rotation == 0)
        bitmap = raw;
      else {
        Matrix rotate = new Matrix();
        rotate.postRotate(rotation);
        bitmap = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), rotate, true);
        if (raw != bitmap)
          raw.recycle();
      }
      int w = bitmap.getWidth(), h = bitmap.getHeight();
      FaceEngine.Frame frame;
      try {
        frame = app.engine.analyze(bitmap, enrollName != null);
        processedFrames++;
      } catch (FaceEngine.QualityException e) {
        processedFrames++;
        verifier.reset();
        enrollSamples.clear();
        updateCamera(version, e.getMessage(), "Посмотрите прямо, держите телефон неподвижно", e.box,
            w, h, false);
        return;
      }
      if (!cameraActive || generation != version)
        return;
      synchronized (cameraGate) {
        if (!cameraActive || generation != version)
          return;
        if (enrollName != null) {
          for (float[] previous : enrollSamples)
            if (Recognition.dot(previous, frame.embedding) < .55f) {
              enrollSamples.clear();
              updateCamera(version, "Начинаем съёмку заново", "Лицо или ракурс сильно изменились",
                  frame.box, w, h, false);
              return;
            }
          enrollSamples.add(frame.embedding);
          Recognition.Match duplicate = app.gallery.duplicate(enrollSamples, recaptureId);
          if (duplicate != null) {
            enrollSamples.clear();
            updateCamera(version, "Лицо уже есть: " + duplicate.person.name,
                "Выберите существующего человека и переснимите его лицо", frame.box, w, h, false);
            return;
          }
          if (enrollSamples.size() >= 6) {
            String savedName = enrollName;
            app.store.enroll(recaptureId, enrollName, enrollGroup, enrollSamples);
            app.reloadGallery();
            cameraActive = false;
            runOnUiThread(() -> {
              if (generation == version) {
                stopCamera();
                toast(savedName + " — регистрация завершена");
                showPeople();
              }
            });
            return;
          }
          updateCamera(version, "Сохраняем лицо · " + enrollSamples.size() + " из 6",
              frame.millis + " мс · держитесь неподвижно", frame.box, w, h, false);
          return;
        }
        Recognition.Match match = app.gallery.identify(frame.embedding);
        if (match == null) {
          verifier.reset();
          updateCamera(version, "Лицо не узнано",
              frame.millis + " мс · посмотрите прямо или обновите регистрацию", frame.box, w, h,
              false);
          return;
        }
        if (!verifier.observe(frame.embedding, match, app.gallery, timestamp)) {
          updateCamera(version, "Проверяем лицо…",
              frame.millis + " мс · подтверждений: " + verifier.count(), frame.box, w, h, false);
          return;
        }
        if (marked.contains(match.person.id)) {
          updateCamera(version, match.person.name + " — уже отмечен",
              frame.millis + " мс · " + match.person.group, frame.box, w, h, true);
          return;
        }
        app.store.mark(sessionId, match.person.id, true, false);
        marked.add(match.person.id);
        updateCamera(version, match.person.name + " — отмечен",
            frame.millis + " мс · " + match.person.group, frame.box, w, h, true);
      }
    } catch (Exception e) {
      verifier.reset();
      updateCamera(version, e.getMessage() == null ? "Ошибка обработки кадра" : e.getMessage(),
          "Повторите попытку", null, 0, 0, false);
      Log.w("FaceAttendance", "Frame", e);
    } finally {
      if (bitmap != null)
        bitmap.recycle();
      image.close();
    }
  }
  private void updateCamera(int version, String message, String detail, RectF box, int width,
      int height, boolean success) {
    final int captured = enrollSamples.size(), present = marked.size();
    runOnUiThread(() -> {
      if (!cameraActive || generation != version || cameraResult == null)
        return;
      cameraResult.setText(message);
      cameraResult.setTextColor(success ? Ui.DARK_SUCCESS : Ui.DARK_TEXT);
      cameraDetail.setText(detail.replaceFirst("^\\d+ мс · ", ""));
      if (captureProgress != null) {
        captureProgress.setProgress(captured, true);
        captureProgress.setContentDescription("Снято " + captured + " из 6 кадров");
      } else if (cameraCount != null) {
        cameraCount.setText(present + " / " + cameraTotal + " отмечены");
      }
      overlay.box = box == null ? null : new RectF(box);
      overlay.imageWidth = width;
      overlay.imageHeight = height;
      overlay.success = success;
      overlay.invalidate();
    });
  }
  private void stopCameraInternal() {
    synchronized (cameraGate) {
      cameraActive = false;
      generation++;
    }
    if (cameraProvider != null)
      cameraProvider.unbindAll();
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }
  private void stopCamera() {
    resumeCamera = false;
    cameraScreen = false;
    stopCameraInternal();
  }
  @Override
  protected void onStop() {
    boolean was = cameraScreen && cameraActive;
    stopCameraInternal();
    resumeCamera = was;
    super.onStop();
  }
  @Override
  protected void onResume() {
    super.onResume();
    if (resumeCamera && cameraScreen) {
      resumeCamera = false;
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        startCamera();
    }
  }
  private final class FaceOverlay extends View {
    RectF box;
    int imageWidth, imageHeight;
    boolean success;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF guide = new RectF(), drawnBox = new RectF();
    FaceOverlay() {
      super(MainActivity.this);
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(dp(2));
      p.setColor(Ui.DARK_ACCENT);
      if (box == null || imageWidth == 0) {
        p.setAlpha(140);
        guide.set(getWidth() * .22f, getHeight() * .16f, getWidth() * .78f, getHeight() * .84f);
        canvas.drawRoundRect(guide, dp(55), dp(55), p);
        return;
      }
      float scale = Math.min((float) getWidth() / imageWidth, (float) getHeight() / imageHeight),
            left = (getWidth() - imageWidth * scale) / 2,
            top = (getHeight() - imageHeight * scale) / 2;
      RectF r = drawnBox;
      r.set(box);
      if (front) {
        float x = imageWidth - r.right;
        r.right = imageWidth - r.left;
        r.left = x;
      }
      r.set(left + r.left * scale, top + r.top * scale, left + r.right * scale,
          top + r.bottom * scale);
      p.setAlpha(255);
      p.setColor(success ? Ui.DARK_SUCCESS : Ui.DARK_ACCENT);
      canvas.drawRoundRect(r, dp(18), dp(18), p);
      p.setStyle(Paint.Style.FILL);
      p.setTextSize(dp(12));
      canvas.drawText(
          success ? "Готово" : "Выбрано лицо", r.left, Math.max(dp(18), r.top - dp(8)), p);
    }
  }
}
