package it.feio.android.omninotes.db;

import static org.junit.Assert.assertEquals;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 笔记列表置顶排序的仪器测试。
 * @why 排序写在 getNotes 的 ORDER BY 里，依赖真实 SQLite 才能验证语义；
 *      Android 的 SQLiteDatabase 在纯 JVM 单元测试下不可用，"
 *      因此这里用内存数据库执行与生产代码同一个 buildNotesOrderBy 生成的 SQL，
 *      既不污染 App 的真实笔记库，又能拿到真实 Android SQLite 的排序结果。
 */
@RunWith(AndroidJUnit4.class)
public class PinnedOrderInstrumentedTest {

  private SQLiteDatabase database;

  @Before
  public void setUp() {
    database = SQLiteDatabase.create(null);
    database.execSQL(
        "CREATE TABLE notes (title TEXT, content TEXT, is_pinned INTEGER DEFAULT 0)");
  }

  @After
  public void tearDown() {
    if (database != null) {
      database.close();
    }
  }

  /** 插入一条笔记，content 留空以便标题序直接参与比较。 */
  private void insertNote(String title, int pinned) {
    database.execSQL("INSERT INTO notes (title, content, is_pinned) VALUES (?, '', ?)",
        new Object[]{title, pinned});
  }

  /** 按生产代码同一段 ORDER BY 查出标题顺序。 */
  private List<String> queryTitlesInOrder() {
    String sql = "SELECT title FROM notes"
        + DbHelper.buildNotesOrderBy(DbHelper.KEY_TITLE + "||" + DbHelper.KEY_CONTENT, " ASC ");
    List<String> titles = new ArrayList<>();
    try (Cursor cursor = database.rawQuery(sql, null)) {
      while (cursor.moveToNext()) {
        titles.add(cursor.getString(0));
      }
    }
    return titles;
  }

  @Test
  public void pinnedNoteGoesToTop() {
    insertNote("apple", 0);
    insertNote("mango", 1);
    insertNote("zebra", 0);

    assertEquals("置顶笔记应排在列表首位", Arrays.asList("mango", "apple", "zebra"),
        queryTitlesInOrder());
  }

  @Test
  public void pinnedNotesKeepTitleOrderAmongThemselves() {
    insertNote("apple", 0);
    insertNote("mango", 1);
    insertNote("zebra", 1);

    assertEquals("多条置顶笔记内部仍按标题升序", Arrays.asList("mango", "zebra", "apple"),
        queryTitlesInOrder());
  }

  @Test
  public void noPinnedNoteFallsBackToPlainTitleOrder() {
    insertNote("zebra", 0);
    insertNote("apple", 0);
    insertNote("mango", 0);

    assertEquals("全部未置顶时排序应退化为纯标题升序", Arrays.asList("apple", "mango", "zebra"),
        queryTitlesInOrder());
  }
}
