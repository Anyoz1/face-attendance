package com.anyoz.faceattendance;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Exercises the redesign with invented names and an isolated database. Never photographs people.
 */
@RunWith(AndroidJUnit4.class)
public final class DesignTest {
  private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
  private final AttendanceApp app = (AttendanceApp) target.getApplicationContext();
  private static float[] vector(int i) {
    float[] values = new float[512];
    values[i] = 1;
    return values;
  }
  private static View root(MainActivity a) {
    return a.getWindow().getDecorView();
  }
  private static View text(View view, String value) {
    if (view instanceof TextView && value.contentEquals(((TextView) view).getText()))
      return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = text(group.getChildAt(i), value);
        if (found != null)
          return found;
      }
    }
    return null;
  }
  private static ListView list(View view) {
    if (view instanceof ListView)
      return (ListView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        ListView found = list(group.getChildAt(i));
        if (found != null)
          return found;
      }
    }
    return null;
  }
  private static void click(View v) {
    assertNotNull(v);
    while (!v.isClickable() && v.getParent() instanceof View) v = (View) v.getParent();
    assertTrue(v.performClick());
  }
  private static int rowCount(MainActivity a) {
    ListView l = list(root(a));
    assertNotNull(l);
    return l.getAdapter().getCount() - l.getHeaderViewsCount();
  }
  private static void assertNavigationAligned(MainActivity a) {
    for (int id : new int[] {R.id.nav_home, R.id.nav_people, R.id.nav_groups, R.id.nav_history}) {
      LinearLayout item = a.findViewById(id);
      View icon = item.getChildAt(0);
      TextView caption = (TextView) item.getChildAt(item.getChildCount() - 1);
      float iconCenter = icon.getLeft() + icon.getWidth() / 2f;
      float textCenter = caption.getLeft() + caption.getTotalPaddingLeft()
          + (caption.getLayout().getLineLeft(0) + caption.getLayout().getLineRight(0)) / 2;
      assertEquals("Icon and caption must share a center", iconCenter, textCenter, 2f);
    }
  }
  private void screenshot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thread.sleep(250);
    android.view.accessibility.AccessibilityNodeInfo foreground = null;
    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    do {
      foreground =
          InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (foreground != null && target.getPackageName().contentEquals(foreground.getPackageName()))
        break;
      Thread.sleep(100);
    } while (android.os.SystemClock.elapsedRealtime() < deadline);
    assertNotNull("App must be visible before a screenshot", foreground);
    assertEquals("Never capture another app", target.getPackageName(),
        foreground.getPackageName().toString());
    Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(image);
    File dir = new File(target.getFilesDir(), "design-preview");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }
  private android.view.accessibility.AccessibilityNodeInfo node(String label) throws Exception {
    long until = android.os.SystemClock.elapsedRealtime() + 4000;
    do {
      android.view.accessibility.AccessibilityNodeInfo active =
          InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (active != null && target.getPackageName().contentEquals(active.getPackageName())) {
        for (android.view.accessibility.AccessibilityNodeInfo node :
            active.findAccessibilityNodeInfosByText(label))
          if (node.getText() != null && label.contentEquals(node.getText()))
            return node;
      }
      Thread.sleep(50);
    } while (android.os.SystemClock.elapsedRealtime() < until);
    throw new AssertionError("Missing visible control: " + label);
  }
  private void tap(String label) throws Exception {
    android.view.accessibility.AccessibilityNodeInfo control = node(label);
    while (control != null && !control.isClickable()) control = control.getParent();
    assertNotNull(control);
    assertTrue(
        control.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }
  @Test
  public void navigationSearchGroupsAndReport() throws Exception {
    String filename = "design-" + AttendanceStore.id() + ".db";
    AttendanceStore isolated = new AttendanceStore(target, filename), original = app.store;
    Recognition.Gallery originalGallery = app.gallery;
    try {
      app.store = isolated;
      app.gallery = new Recognition.Gallery(Collections.emptyList());
      try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
        scenario.onActivity(a -> {
          assertEquals("Добавить человека",
              ((Button) a.findViewById(R.id.home_action)).getText().toString());
        });
        screenshot("01-empty");
        isolated.addGroup("Команда Б");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
          float[] v = vector(i);
          ids.add(isolated.enroll(null, String.format(Locale.ROOT, "Участник %02d", i + 1),
              i < 35 ? "Основная" : "Команда Б", Arrays.asList(v, v, v, v, v, v)));
        }
        String previous = isolated.start("Утренняя проверка", Arrays.asList("Основная"));
        isolated.mark(previous, ids.get(0), true, false);
        isolated.closeSession(previous);
        String active =
            isolated.start("Занятие · две группы", Arrays.asList("Основная", "Команда Б"));
        for (int i = 0; i < 24; i++) isolated.mark(active, ids.get(i), true, false);
        app.reloadGallery();
        scenario.recreate();
        screenshot("02-home");
        scenario.onActivity(DesignTest::assertNavigationAligned);
        scenario.onActivity(a -> {
          click(a.findViewById(R.id.home_action));
          // Hide the live preview before taking any camera-screen screenshot.
          // Only layout, instructions and a detection outline can be captured.
          a.findViewById(R.id.camera_preview).setAlpha(0f);
        });
        screenshot("10-camera-layout");
        scenario.recreate();
        scenario.onActivity(a -> click(a.findViewById(R.id.nav_people)));
        screenshot("03-people");
        scenario.onActivity(a -> {
          assertEquals(70, rowCount(a));
          EditText query = a.findViewById(R.id.people_search);
          query.setText("Участник 70");
          assertEquals(1, rowCount(a));
          query.setText("");
          assertEquals(70, rowCount(a));
          Spinner group = a.findViewById(R.id.people_group_filter);
          for (int i = 0; i < group.getCount(); i++)
            if ("Команда Б".equals(group.getItemAtPosition(i)))
              group.setSelection(i);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        screenshot("04-group-filter");
        scenario.onActivity(a -> assertEquals(35, rowCount(a)));
        scenario.onActivity(a -> click(a.findViewById(R.id.nav_groups)));
        screenshot("05-groups");
        scenario.onActivity(a -> {
          click(text(root(a), "Основная"));
          assertEquals(35, rowCount(a));
          click(a.findViewById(R.id.nav_history));
        });
        screenshot("06-history");
        scenario.onActivity(a -> {
          assertEquals(2, rowCount(a));
          ((EditText) a.findViewById(R.id.history_search)).setText("Утренняя");
          assertEquals(1, rowCount(a));
          click(a.findViewById(R.id.nav_home));
          click(text(root(a), "Посмотреть список"));
        });
        screenshot("07-report");
        scenario.onActivity(a -> {
          assertEquals(70, rowCount(a));
          click(text(root(a), "Присутствуют"));
          assertEquals(24, rowCount(a));
        });
        screenshot("11-present");
        scenario.onActivity(a -> {
          click(text(root(a), "Без отметки"));
          assertEquals(46, rowCount(a));
          ((EditText) a.findViewById(R.id.report_search)).setText("Участник 70");
          assertEquals(1, rowCount(a));
          click(a.findViewById(R.id.navigate_back));
          assertNotNull(a.findViewById(R.id.nav_history));
          click(a.findViewById(R.id.open_settings));
        });
        screenshot("08-settings");
        scenario.onActivity(a -> {
          click(a.findViewById(R.id.navigate_back));
          click(a.findViewById(R.id.nav_people));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a -> click(a.findViewById(R.id.add_person)));
        screenshot("09-enrollment-form");
        assertEquals(70, isolated.people().size());
        assertEquals(24, isolated.active().present);
        assertEquals(2, isolated.sessions().size());
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(
            android.view.KeyEvent.KEYCODE_BACK);
        scenario.onActivity(a -> {
          click(a.findViewById(R.id.nav_home));
          click(text(root(a), "Посмотреть список"));
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(a -> click(a.findViewById(R.id.report_more)));
        node("Сохранить CSV");
        tap("Завершить проверку");
        tap("Отмена");
        assertNotNull(isolated.active());
        scenario.onActivity(a -> click(a.findViewById(R.id.report_more)));
        tap("Завершить проверку");
        tap("Продолжить");
        assertNull(isolated.active());
        assertEquals(2, isolated.sessions().size());
        screenshot("12-ended");
      }
    } finally {
      app.store = original;
      app.gallery = originalGallery;
      isolated.close();
      target.deleteDatabase(filename);
    }
  }
}
