package com.anyoz.faceattendance;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Destructive operations are exercised exclusively in a temporary, invented database. */
@RunWith(AndroidJUnit4.class)
public final class HistoryStoreTest {
  private static float[] vector(int index) {
    float[] v = new float[512];
    v[index] = 1;
    return v;
  }
  private static String person(AttendanceStore store, String name, String group, int index) {
    float[] v = vector(index);
    return store.enroll(null, name, group, Arrays.asList(v, v, v, v, v, v));
  }
  private static int count(AttendanceStore store, String table, String session) {
    try (Cursor c = store.getReadableDatabase().rawQuery(
             "SELECT count(*) FROM " + table + (session == null ? "" : " WHERE session_id=?"),
             session == null ? null : new String[] {session})) {
      assertTrue(c.moveToFirst());
      return c.getInt(0);
    }
  }
  @Test
  public void completedHistoryDeletionIsAtomicAndKeepsCurrentPeopleAndFaces() throws Exception {
    Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String filename = "history-test-" + AttendanceStore.id() + ".db";
    AttendanceStore store = new AttendanceStore(target, filename);
    try {
      store.addGroup("Вторая");
      String a = person(store, "Участник A", "Основная", 0);
      String b = person(store, "Участник B", "Вторая", 1);
      String first = store.start("Первая", Arrays.asList("Основная", "Вторая"));
      store.mark(first, a, true, false);
      store.closeSession(first);
      String second = store.start("Вторая", Arrays.asList("Основная", "Вторая"));
      store.mark(second, b, true, false);
      store.closeSession(second);
      String current = store.start("Текущая", Arrays.asList("Основная", "Вторая"));
      store.mark(current, a, true, false);
      try {
        store.deleteSession(current);
        fail("Must not delete an open check");
      } catch (IllegalArgumentException expected) {
      }
      assertEquals(3, store.sessions().size());
      assertEquals(current, store.active().id);
      assertEquals(1, store.active().present);
      assertEquals(2, store.entries(current).size());
      store.deleteSession(first);
      assertEquals(2, store.sessions().size());
      assertTrue(store.entries(first).isEmpty());
      assertEquals(0, count(store, "audit", first));
      assertEquals(0, count(store, "session_groups", first));
      try {
        store.deleteSession(first);
        fail("Missing check must not change other data");
      } catch (IllegalArgumentException expected) {
      }
      // Force failure after child rows were removed, to verify a real transaction rollback.
      store.getWritableDatabase().execSQL(
          "CREATE TRIGGER reject_history_delete BEFORE DELETE ON sessions BEGIN SELECT "
          + "RAISE(ABORT,'test failure'); END");
      try {
        store.clearHistory();
        fail("Database trigger should reject deletion");
      } catch (SQLiteException expected) {
      }
      assertEquals(2, store.sessions().size());
      assertEquals(2, store.entries(second).size());
      assertEquals(1, count(store, "audit", second));
      assertEquals(2, count(store, "session_groups", second));
      assertEquals(current, store.active().id);
      assertEquals(1, store.active().present);
      store.getWritableDatabase().execSQL("DROP TRIGGER reject_history_delete");
      assertEquals(1, store.clearHistory());
      assertEquals(0, store.clearHistory());
      assertEquals(1, store.sessions().size());
      assertEquals(current, store.active().id);
      assertEquals(1, store.active().present);
      assertEquals(2, store.people().size());
      assertEquals(2, store.groups().size());
      assertEquals(2, store.profiles().size());
      assertEquals(12, count(store, "templates", null));
      JSONObject backup = new JSONObject(store.backup());
      assertEquals(1, backup.getJSONArray("sessions").length());
      assertEquals(2, backup.getJSONArray("attendance").length());
      assertEquals(2, backup.getJSONArray("session_groups").length());
      assertEquals(1, backup.getJSONArray("audit").length());
      try (Cursor integrity =
               store.getReadableDatabase().rawQuery("PRAGMA foreign_key_check", null)) {
        assertFalse("No dangling history references", integrity.moveToFirst());
      }
      assertTrue(store.mark(current, b, true, false));
      assertEquals(2, store.active().present);
      store.closeSession(current);
      store.deleteSession(current);
      assertTrue(store.sessions().isEmpty());
      assertEquals(0, count(store, "audit", null));
      assertEquals(2, store.people().size());
      assertEquals(12, count(store, "templates", null));
    } finally {
      store.close();
      target.deleteDatabase(filename);
    }
  }
}
