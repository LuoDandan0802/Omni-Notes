/*
 * Copyright (C) 2013-2026 Federico Iosue (omninotes@iosue.it)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.feio.android.omninotes.db;

import static android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE;
import static it.feio.android.checklistview.interfaces.Constants.UNCHECKED_SYM;
import static it.feio.android.omninotes.utils.Constants.DATABASE_NAME;
import static it.feio.android.omninotes.utils.ConstantsBase.MIME_TYPE_AUDIO;
import static it.feio.android.omninotes.utils.ConstantsBase.MIME_TYPE_FILES;
import static it.feio.android.omninotes.utils.ConstantsBase.MIME_TYPE_IMAGE;
import static it.feio.android.omninotes.utils.ConstantsBase.MIME_TYPE_SKETCH;
import static it.feio.android.omninotes.utils.ConstantsBase.MIME_TYPE_VIDEO;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_ARCHIVED_IN_CATEGORIES;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_PAST_REMINDERS;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_PASSWORD;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SORTING_COLUMN;
import static it.feio.android.omninotes.utils.ConstantsBase.TIMESTAMP_UNIX_EPOCH;
import static it.feio.android.omninotes.utils.Navigation.checkNavigation;
import static java.util.regex.Pattern.MULTILINE;
import static java.util.stream.Collectors.toList;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import com.pixplicity.easyprefs.library.Prefs;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.async.upgrade.UpgradeProcessor;
import it.feio.android.omninotes.exceptions.DatabaseException;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.NotesHelper;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.Stats;
import it.feio.android.omninotes.models.Tag;
import it.feio.android.omninotes.utils.AssetUtils;
import it.feio.android.omninotes.utils.Navigation;
import it.feio.android.omninotes.utils.Security;
import it.feio.android.omninotes.utils.TagsHelper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
*数据库帮助类，管理笔记、附件、分类三张表的创建、升级与全部读写操作。
* @why 继承SQLiteOpenHelper，由Android框架管理数据库的版本变迁和连接生命周期，
*       避免在每个使用数据库的类中重复编写打开/建表逻辑。
 */
public class DbHelper extends SQLiteOpenHelper {

  // Database name（数据库名称）
  // Database version aligned if possible to software version（数据库版本尽可能与软件版本保持一致）
  //数据库版本号，与App版本同步维护；每次升级递增，触发onUpgrade执行对应脚本
  private static final int DATABASE_VERSION = 625;
  // Sql query file directory（Sql查询文件目录）
  // 建表 SQL 脚本所在目录（相对 assets 根）
  private static final String SQL_DIR = "sql";

  //----------notes笔记表：表名与全部列名常量----------
  //列名集中为常量，避免SQL字符串中拼写错误；KEY_ID与KEY_CREATION均指向“creation”
  // Notes table name（笔记表名称）
  public static final String TABLE_NOTES = "notes";
  // Notes table columns（笔记表列）
  // 主键即创建时间戳：便签无需自增 id，直接用毫秒时间戳保证唯一且天然有序
  public static final String KEY_ID = "creation";
  public static final String KEY_CREATION = "creation";
  public static final String KEY_LAST_MODIFICATION = "last_modification";
  public static final String KEY_TITLE = "title";
  public static final String KEY_CONTENT = "content";
  public static final String KEY_ARCHIVED = "archived";
  public static final String KEY_TRASHED = "trashed";
  // 提醒时间列（列名 alarm，与业务含义"提醒"对应）
  public static final String KEY_REMINDER = "alarm";
  public static final String KEY_REMINDER_FIRED = "reminder_fired";
  public static final String KEY_RECURRENCE_RULE = "recurrence_rule";
  public static final String KEY_LATITUDE = "latitude";
  public static final String KEY_LONGITUDE = "longitude";
  public static final String KEY_ADDRESS = "address";
  // 分类外键，关联 categories 表
  public static final String KEY_CATEGORY = "category_id";
  public static final String KEY_LOCKED = "locked";
  public static final String KEY_CHECKLIST = "checklist";

  //----------attachments附件表：一篇笔记可关联多个附件，通过note_id外键关联notes表----------
  // Attachments table name（附件表名）
  public static final String TABLE_ATTACHMENTS = "attachments";
  // Attachments table columns（附件表列）
  public static final String KEY_ATTACHMENT_ID = "attachment_id";
  public static final String KEY_ATTACHMENT_URI = "uri";
  public static final String KEY_ATTACHMENT_NAME = "name";
  public static final String KEY_ATTACHMENT_SIZE = "size";
  public static final String KEY_ATTACHMENT_LENGTH = "length";
  public static final String KEY_ATTACHMENT_MIME_TYPE = "mime_type";
  // 外键：所属便签 id，关联 notes 表主键（creation）
  public static final String KEY_ATTACHMENT_NOTE_ID = "note_id";

  //----------categories分类表；笔记通过category_id关联分类----------
  // Categories table name（分类表名）
  public static final String TABLE_CATEGORY = "categories";
  // Categories table columns（分类表列)
  public static final String KEY_CATEGORY_ID = "category_id";
  public static final String KEY_CATEGORY_NAME = "name";
  public static final String KEY_CATEGORY_DESCRIPTION = "description";
  public static final String KEY_CATEGORY_COLOR = "color";

  // Queries（查询）
  //建库脚本文件名create.sql；升级脚本按upgrade-<版本号>.sql组织，存放于assets/sql目录
  private static final String CREATE_QUERY = "create.sql";
  private static final String UPGRADE_QUERY_PREFIX = "upgrade-";
  private static final String UPGRADE_QUERY_SUFFIX = ".sql";


  private final Context mContext;

  private static DbHelper instance = null;
  private SQLiteDatabase db;

  /**
   * 获取全局唯一的DbHelper实例（懒加载单例）。
   * @why 使用App全局Context，避免持有Activity引用导致内存泄漏。
   */
  public static synchronized DbHelper getInstance() {
    // App 全局 Context，生命周期与进程一致
    return getInstance(OmniNotes.getAppContext());
  }

  /**
   *获取单例；传入外部Context时仅在首次创建时使用。
   * @why 首次创建需要Context才能定位数据库文件路径，之后复用已创建的实例。
   */
  public static synchronized DbHelper getInstance(Context context) {
    // 懒加载：首次调用才创建实例
    if (instance == null) {
      instance = new DbHelper(context);
    }
    return instance;
  }

  /**
   *私有构造函数，禁止外部直接new，保证单例唯一性。
   * @why 通过super传入数据库名与版本号，SQLiteOpenHelper据此管理建库与升级回调。
   */
  public static synchronized DbHelper getInstance(boolean forcedNewInstance) {
    if (instance == null || forcedNewInstance) {
      // 复用旧实例的 Context，避免重复持有 Activity 引用
      Context context = (instance == null || instance.mContext == null) ? OmniNotes.getAppContext()
          : instance.mContext;
      instance = new DbHelper(context);
    }
    return instance;
  }


  /**
   * 私有构造，禁止外部 new，保证单例唯一。
   * @why 传入库名与版本号，SQLiteOpenHelper 据此判定是否触发 onCreate/onUpgrade。
   */
  private DbHelper(Context mContext) {
    // 版本号与现有库不匹配时框架会自动调用 onCreate/onUpgrade
    super(mContext, DATABASE_NAME, null, DATABASE_VERSION);
    this.mContext = mContext;
  }


  /**
   * 返回数据库文件名。
   * @why 供备份/恢复模块（BackupHelper）定位磁盘上的实际库文件。
   */
  public String getDatabaseName() {
    return DATABASE_NAME;
  }

  /**
   * 获取数据库连接（默认只读优先）。
   * @why 只读连接避免误触发写锁，提高并发查询可能性。
   */
  public SQLiteDatabase getDatabase() {
    return getDatabase(false);
  }

  /**
   * 获取数据库连接，可强制获取可写链接。
   * @why 写入操作（增删改）必须使用可写连接；IllegalStateException时回退到已缓存连接，保证异常场景可用。
   */
  public SQLiteDatabase getDatabase(boolean forceWritable) {
    try {
      // 写操作取可写连接，读操作取只读连接
      return forceWritable ? getWritableDatabase() : getReadableDatabase();
    } catch (IllegalStateException e) {
      // 库未就绪时回退已缓存连接，避免抛异常导致界面崩溃
      return this.db;
    }
  }

  /**
   * 数据库每次打开时回调；关闭WAL（预写日志）模式。
   * @why WAL下多个连接同时读写会引发database is locked 异常，关闭后以传统回滚日志模式
   * 运行，规避并发冲突。
   */
  @Override
  public void onOpen(SQLiteDatabase db) {
    // 禁用 WAL，规避多线程读写锁冲突
    db.disableWriteAheadLogging();
    super.onOpen(db);
  }

  /**
   * 首次创建数据库时回调，执行assets/sql/create.sql中的建表脚本。
   * @why 将建表SQL独立存放于assets文件而非硬编码在Java代码中：
   *       ①长SQL在Java字符串中需要处理大量转义与拼接，可读性差；
   *       ②建表脚本随版本统一管理，便于后续升级脚本复用相同表结构；
   *       ③修改表结构只需改SQL文件，无需重新编译代码。
   */
  @Override
  public void onCreate(SQLiteDatabase db) {
    try {
      LogDelegate.i("Database creation");
      // 执行 create.sql：创建 notes/attachments/categories 三张表
      execSqlFile(CREATE_QUERY, db);
    } catch (IOException e) {
      // 建表脚本缺失属致命错误，包装为业务异常快速失败
      throw new DatabaseException("Database creation failed: " + e.getMessage(), e);
    }
  }


  /**
   * 数据库版本升级时回调，按版本差依次执行assets/sql下的upgrade-N.sql脚本。
   * @why 用户升级App后旧数据库需增量迁移（如新增列、新建表），
   *       通过旧版本号定位升级起点，逐个执行脚本数据不丢失。
   */
  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    this.db = db; // 暂存连接供升级流程内部使用
    LogDelegate.i("Upgrading database version from " + oldVersion + " to " + newVersion);

    try {
      // 核心：按版本区间逐一执行升级脚本，完成增量迁移
      UpgradeProcessor.process(oldVersion, newVersion);

      //遍历assets/sql目录，寻找所有升级脚本文件
      for (String sqlFile : AssetUtils.list(SQL_DIR, mContext.getAssets())) {
        if (sqlFile.startsWith(UPGRADE_QUERY_PREFIX)) {
          int fileVersion = Integer.parseInt(sqlFile.substring(UPGRADE_QUERY_PREFIX.length(),
              sqlFile.length() - UPGRADE_QUERY_SUFFIX.length()));
          //仅执行区间内的脚本：fileVersion大于旧版本且不大于新版本，保证多次升级时每个脚本
          //按版本顺序只执行一次，增量迁移不重不漏
          if (fileVersion > oldVersion && fileVersion <= newVersion) {
            execSqlFile(sqlFile, db);
          }
        }
      }
      LogDelegate.i("Database upgrade successful");

    } catch (IOException | InvocationTargetException | IllegalAccessException e) {
      //升级失败属致命错误：直接抛运行时异常终止应用，避免带病运行造成数据损坏
      throw new RuntimeException("Database upgrade failed", e);
    }
  }

  /**
   * 更新（或新建）一条笔记：写入标题、内容、提醒、分类等全部字段，并同步附件。
   * @why ①笔记内容若被锁定（locked)，入库前先用Security.encrypt加密，防止明文落盘；
   *      ②笔记与附件是多条写操作，必须用事务包住保证原子性（全部成功或全部回滚）；
   *      ③CONFLICT_REPLACE冲突策略使该方法同时承担“新增”与“覆盖更新”两种职责。
   */
  public Note updateNote(Note note, boolean updateLastModification) {
    //写操作必须使用可写连接
    db = getDatabase(true);

    //锁定笔记内容加密后落盘：未锁定则存原文
    String content = Boolean.TRUE.equals(note.isLocked())
        ? Security.encrypt(note.getContent(), Prefs.getString(PREF_PASSWORD, ""))
        : note.getContent();

    // To ensure note and attachments insertions are atomic and boost performances transaction are used（为保证批注和附件的插入具备原子性并提升性能，这里使用了事务。）
    db.beginTransaction();

    ContentValues values = new ContentValues();
    values.put(KEY_TITLE, note.getTitle());
    values.put(KEY_CONTENT, content);
    //新建时creation缺省为当时毫秒时间（主键复用时间戳保证唯一）
    values.put(KEY_CREATION,
        note.getCreation() != null ? note.getCreation() : Calendar.getInstance().getTimeInMillis());
    //未强制更新时保留原修改时间，避免误触改签刷新排序
    long lastModification = note.getLastModification() != null && !updateLastModification
        ? note.getLastModification()
        : Calendar.getInstance().getTimeInMillis();
    values.put(KEY_LAST_MODIFICATION, lastModification);
    values.put(KEY_ARCHIVED, note.isArchived());
    values.put(KEY_TRASHED, note.isTrashed());
    values.put(KEY_REMINDER, note.getAlarm());
    values.put(KEY_REMINDER_FIRED, note.isReminderFired());
    values.put(KEY_RECURRENCE_RULE, note.getRecurrenceRule());
    values.put(KEY_LATITUDE, note.getLatitude());
    values.put(KEY_LONGITUDE, note.getLongitude());
    values.put(KEY_ADDRESS, note.getAddress());
    //分类只存id，保持表间弱关联
    values.put(KEY_CATEGORY, note.getCategory() != null ? note.getCategory().getId() : null);
    values.put(KEY_LOCKED, note.isLocked() != null && note.isLocked());
    values.put(KEY_CHECKLIST, note.isChecklist() != null && note.isChecklist());

    //REPLACE策略：主键冲突即覆盖整行，实现“保存即新增或更新”
    db.insertWithOnConflict(TABLE_NOTES, KEY_ID, values, CONFLICT_REPLACE);
    LogDelegate.d("Updated note titled '" + note.getTitle() + "'");

    // Updating attachments（更新附件）
    //逐条写入当前附件：仍在旧列表中的附件视为被删除，事务内一并删除
    List<Attachment> deletedAttachments = note.getAttachmentsListOld();
    for (Attachment attachment : note.getAttachmentsList()) {
      updateAttachment(note.get_id() != null ? note.get_id() : values.getAsLong(KEY_CREATION),
          attachment, db);
      //已存在的附件从“待删除”集合中移除
      deletedAttachments.remove(attachment);
    }
    // Remove from database deleted attachments（从数据库中移除已删除的附件）
    for (Attachment attachmentDeleted : deletedAttachments) {
      db.delete(TABLE_ATTACHMENTS, KEY_ATTACHMENT_ID + " = ?",
          new String[]{String.valueOf(attachmentDeleted.getId())});
    }

    //提交事务：期间任何异常都会回滚
    db.setTransactionSuccessful();
    db.endTransaction();

    // Fill the note with correct data before returning it（在归还单据前填写正确信息）
    //回填真实写入的creation/lastModification，供调用方（如EventBus通知）使用
    note.setCreation(
        note.getCreation() != null ? note.getCreation() : values.getAsLong(KEY_CREATION));
    note.setLastModification(values.getAsLong(KEY_LAST_MODIFICATION));

    return note;
  }


  /**
   * 解析asscets/sal下的SQL脚本文件并逐条执行。
   * @why 建表/升级脚本统一以文件形式管理，此处负责把文件内容解析为单条SQL指令依次执行。
   */
  private void execSqlFile(String sqlFile, SQLiteDatabase db) throws SQLException, IOException {
    LogDelegate.i("  exec sql file: {}" + sqlFile);
    //SqlParser按分号把脚本文件拆成单条指令（忽略注释与空白）
    for (String sqlInstruction : SqlParser
        .parseSqlFile(SQL_DIR + "/" + sqlFile, mContext.getAssets())) {
      LogDelegate.v("    sql: {}" + sqlInstruction);
      try {
        db.execSQL(sqlInstruction);
      } catch (Exception e) {
        //单条语句失败仅记日志不中断：部分老版本升级脚本可能包含不适用语句，容忍继续执行
        LogDelegate.e("Error executing command: " + sqlInstruction, e);
      }
    }
  }


  /**
   * New attachment insertion（插入新附件，无noteId时用-1占位，由重载方法回写）。
   */
  public Attachment updateAttachment(Attachment attachment) {
    //noteId为空时取-1，避免SQL空值异常
    var noteId = attachment.getNoteId() != null ? attachment.getNoteId() : -1;
    return updateAttachment(noteId, attachment, getDatabase(true));
  }


  /**
   * Attachments update（附件更新：写入一条附件记录，含所属笔记id、URI、类型、大小等）。
   * @why 在调用方事务内执行，保证与笔记主体写入一致；attachment_id缺省用当前毫秒时间戳。
   */
  public Attachment updateAttachment(long noteId, Attachment attachment, SQLiteDatabase db) {
    var valuesAttachments = new ContentValues();
    //无id的新附件用当前时间戳作主键
    valuesAttachments.put(KEY_ATTACHMENT_ID,
        attachment.getId() != null ? attachment.getId() : Calendar.getInstance().getTimeInMillis());
    valuesAttachments.put(KEY_ATTACHMENT_NOTE_ID, noteId);
    valuesAttachments.put(KEY_ATTACHMENT_URI, attachment.getUri().toString());
    valuesAttachments.put(KEY_ATTACHMENT_MIME_TYPE, attachment.getMime_type());
    valuesAttachments.put(KEY_ATTACHMENT_NAME, attachment.getName());
    valuesAttachments.put(KEY_ATTACHMENT_SIZE, attachment.getSize());
    valuesAttachments.put(KEY_ATTACHMENT_LENGTH, attachment.getLength());
    //同一附件重复保存时覆盖旧纪录
    db.insertWithOnConflict(TABLE_ATTACHMENTS, KEY_ATTACHMENT_ID, valuesAttachments, CONFLICT_REPLACE);
    return attachment;
  }


  /**
   * Getting single note（获取单个笔记）
   * 按主键（creation）查询单条便签；查不到返回null。
   * @why 列表点击进入详情时按id精确加载，避免全表查询。
   */
  public Note getNote(long id) {
    //复用通用查询方法，拼接主键条件
    List<Note> notes = getNotes(" WHERE " + KEY_ID + " = " + id, true);
    return notes.isEmpty() ? null : notes.get(0);
  }


  /**
   * Getting All notes（获取所有笔记）
   *
   * @param checkNavigation Tells if navigation status (notes, archived) must be kept in
   *                        consideration or if all notes have to be retrieved（用于指定是否需要考虑导航状态（笔记、已归档），还是检索全部笔记）
   * @return Notes list（笔记列表）
   */
  public List<Note> getAllNotes(Boolean checkNavigation) {
    //通用查询入口；由调用方传入where条件与排序方向，方法内拼接SQL并解析游标
    String whereCondition = "";
    // 按侧边栏导航状态分发到不同查询：决定当前显示"活动/归档/回收站/分类"哪个集合
    if (Boolean.TRUE.equals(checkNavigation)) {
      int navigation = Navigation.getNavigation();
      switch (navigation) {
        // 主列表：仅查未归档且未删除的便签
        case Navigation.NOTES:
          return getNotesActive();
        // 归档列表：只查 archived=1 且未删除
        case Navigation.ARCHIVE:
          return getNotesArchived();
        // 提醒列表：按提醒时间过滤，可选隐藏已过期的提醒
        case Navigation.REMINDERS:
          return getNotesWithReminder(Prefs.getBoolean(PREF_FILTER_PAST_REMINDERS, false));
        // 回收站：只查已删除
        case Navigation.TRASH:
          return getNotesTrashed();
        // 未分类：category_id 为空或 0 的笔记
        case Navigation.UNCATEGORIZED:
          return getNotesUncategorized();
        // 按当前选中的分类查询
        case Navigation.CATEGORY:
          return getNotesByCategory(Navigation.getCategory());
        default:
          // 其他情况回退到全量查询（按修改时间排序）
          return getNotes(whereCondition, true);
      }
    } else {
      // 不关心导航状态时直接全量查询
      return getNotes(whereCondition, true);
    }

  }


  /**
   * 查询"活动"笔记：未归档（archived != 1）且未删除（trashed != 1）。
   * @why 主列表页默认展示该集合，用 SQL 条件而非代码过滤，减少内存开销。
   */
  public List<Note> getNotesActive() {
    String whereCondition =
        " WHERE " + KEY_ARCHIVED + " IS NOT 1 AND " + KEY_TRASHED + " IS NOT 1 ";
    return getNotes(whereCondition, true);
  }


  /**
   * 查询"已归档"笔记：archived=1 且未删除。
   */
  public List<Note> getNotesArchived() {
    String whereCondition = " WHERE " + KEY_ARCHIVED + " = 1 AND " + KEY_TRASHED + " IS NOT 1 ";
    return getNotes(whereCondition, true);
  }


  /**
   * 查询"回收站"笔记：trashed=1（归档与否不影响回收站展示）。
   */
  public List<Note> getNotesTrashed() {
    String whereCondition = " WHERE " + KEY_TRASHED + " = 1 ";
    return getNotes(whereCondition, true);
  }


  /**
   * 查询"未分类"笔记：category_id 为空或为 0，且未删除。
   * @why 未分类是业务上的特殊集合，需用 OR 条件同时兼容空值与 0 两种写法。
   */
  public List<Note> getNotesUncategorized() {
    String whereCondition = " WHERE "
        + "(" + KEY_CATEGORY_ID + " IS NULL OR " + KEY_CATEGORY_ID + " == 0) "
        + "AND " + KEY_TRASHED + " IS NOT 1";
    return getNotes(whereCondition, true);
  }


  /**
   * 查询"带地理位置"的笔记：经度非空且非 0（地图视图使用）。
   */
  public List<Note> getNotesWithLocation() {
    String whereCondition = " WHERE " + KEY_LONGITUDE + " IS NOT NULL "
        + "AND " + KEY_LONGITUDE + " != 0 ";
    return getNotes(whereCondition, true);
  }


  /**
   * Common method for notes retrieval. It accepts a query to perform and returns matching records.
   * （笔记检索的通用方法。该方法接收一条查询指令并返回匹配的记录。）
   * 通用笔记查询核心方法：按传入的 where 条件与排序方向执行查询并解析游标。
   * @param whereCondition 附加 SQL 条件（WHERE 子句），由各便捷方法拼好传入
   * @param order          是否按最后修改时间排序
   * @return 匹配的便签列表
   */
  public List<Note> getNotes(String whereCondition, boolean order) {
    List<Note> noteList = new ArrayList<>();

    String sortColumn = "";
    String sortOrder = "";

    // Getting sorting criteria from preferences. Reminder screen forces sorting.
    if (checkNavigation(Navigation.REMINDERS)) {
      sortColumn = KEY_REMINDER;
    } else {
      sortColumn = Prefs.getString(PREF_SORTING_COLUMN, KEY_TITLE);
    }
    if (order) {
      sortOrder =
          KEY_TITLE.equals(sortColumn) || KEY_REMINDER.equals(sortColumn) ? " ASC " : " DESC ";
    }

    // In case of title sorting criteria it must be handled empty title by concatenating content
    sortColumn = KEY_TITLE.equals(sortColumn) ? KEY_TITLE + "||" + KEY_CONTENT : sortColumn;

    // In case of reminder sorting criteria the empty reminder notes must be moved on bottom of results
    sortColumn = KEY_REMINDER.equals(sortColumn) ? "IFNULL(" + KEY_REMINDER + ", " +
        "" + TIMESTAMP_UNIX_EPOCH + ")" : sortColumn;

    // Generic query to be specialized with conditions passed as parameter
    String query = "SELECT "
        + KEY_CREATION + ","
        + KEY_LAST_MODIFICATION + ","
        + KEY_TITLE + ","
        + KEY_CONTENT + ","
        + KEY_ARCHIVED + ","
        + KEY_TRASHED + ","
        + KEY_REMINDER + ","
        + KEY_REMINDER_FIRED + ","
        + KEY_RECURRENCE_RULE + ","
        + KEY_LATITUDE + ","
        + KEY_LONGITUDE + ","
        + KEY_ADDRESS + ","
        + KEY_LOCKED + ","
        + KEY_CHECKLIST + ","
        + KEY_CATEGORY + ","
        + KEY_CATEGORY_NAME + ","
        + KEY_CATEGORY_DESCRIPTION + ","
        + KEY_CATEGORY_COLOR
        + " FROM " + TABLE_NOTES
        + " LEFT JOIN " + TABLE_CATEGORY + " USING( " + KEY_CATEGORY + ") "
        + whereCondition
        + (order ? " ORDER BY " + sortColumn + " COLLATE NOCASE " + sortOrder : "");

    LogDelegate.v("Query: " + query);

    try (Cursor cursor = getDatabase().rawQuery(query, null)) {

      if (cursor.moveToFirst()) {
        do {
          int i = 0;
          Note note = new Note();
          note.setCreation(cursor.getLong(i++));
          note.setLastModification(cursor.getLong(i++));
          note.setTitle(cursor.getString(i++));
          note.setContent(cursor.getString(i++));
          note.setArchived("1".equals(cursor.getString(i++)));
          note.setTrashed("1".equals(cursor.getString(i++)));
          note.setAlarm(cursor.getString(i++));
          note.setReminderFired(cursor.getInt(i++));
          note.setRecurrenceRule(cursor.getString(i++));
          note.setLatitude(cursor.isNull(i) ? null : cursor.getDouble(i));
          i++;
          note.setLongitude(cursor.isNull(i) ? null : cursor.getDouble(i));
          i++;
          note.setAddress(cursor.getString(i++));
          note.setLocked("1".equals(cursor.getString(i++)));
          note.setChecklist("1".equals(cursor.getString(i++)));

          // Eventual decryption of content
          if (Boolean.TRUE.equals(note.isLocked())) {
            note.setContent(
                Security.decrypt(note.getContent(), Prefs.getString(PREF_PASSWORD, "")));
          }

          // Set category
          long categoryId = cursor.getLong(i++);
          if (categoryId != 0) {
            Category category = new Category(categoryId, cursor.getString(i++),
                cursor.getString(i++), cursor.getString(i));
            note.setCategory(category);
          }

          // Add eventual attachments uri
          note.setAttachmentsList(getNoteAttachments(note));

          // Adding note to list
          noteList.add(note);

        } while (cursor.moveToNext());
      }

    }

    LogDelegate.v("Query: Retrieval finished!");
    return noteList;
  }


  /**
   * Archives/restore single note
   */
  public void archiveNote(Note note, boolean archive) {
    note.setArchived(archive);
    updateNote(note, false);
  }


  /**
   * Trashes/restore single note
   */
  public void trashNote(Note note, boolean trash) {
    note.setTrashed(trash);
    updateNote(note, false);
  }


  /**
   * Deleting single note
   */
  public boolean deleteNote(Note note) {
    return deleteNote(note, false);
  }


  /**
   * Deleting single note, eventually keeping attachments
   */
  public boolean deleteNote(Note note, boolean keepAttachments) {
    return deleteNote(note.get_id(), keepAttachments);
  }


  /**
   * Deleting single note by its ID
   */
  public boolean deleteNote(long noteId, boolean keepAttachments) {
    SQLiteDatabase db = getDatabase(true);
    db.delete(TABLE_NOTES, KEY_ID + " = ?", new String[]{String.valueOf(noteId)});
    if (!keepAttachments) {
      db.delete(TABLE_ATTACHMENTS, KEY_ATTACHMENT_NOTE_ID + " = ?",
          new String[]{String.valueOf(noteId)});
    }
    return true;
  }


  /**
   * Empties trash deleting all trashed notes
   */
  public void emptyTrash() {
    for (Note note : getNotesTrashed()) {
      deleteNote(note);
    }
  }


  /**
   * Gets notes matching pattern with title or content text
   *
   * @param pattern String to match with
   * @return Notes list
   */
  public List<Note> getNotesByPattern(String pattern) {
    String escapedPattern = escapeSql(pattern);
    int navigation = Navigation.getNavigation();
    String whereCondition = " WHERE "
        + KEY_TRASHED + (navigation == Navigation.TRASH ? " IS 1" : " IS NOT 1")
        + (navigation == Navigation.ARCHIVE ? " AND " + KEY_ARCHIVED + " IS 1" : "")
        + (navigation == Navigation.CATEGORY ? " AND " + KEY_CATEGORY + " = " + Navigation
        .getCategory() : "")
        + (navigation == Navigation.UNCATEGORIZED ? " AND (" + KEY_CATEGORY + " IS NULL OR "
        + KEY_CATEGORY_ID
        + " == 0) " : "")
        + (checkNavigation(Navigation.REMINDERS) ? " AND " + KEY_REMINDER
        + " IS NOT NULL" : "")
        + " AND ("
        + " ( " + KEY_LOCKED + " IS NOT 1 AND (" + KEY_TITLE + " LIKE '%" + escapedPattern
        + "%' ESCAPE '\\' " + " OR "
        +
        KEY_CONTENT + " LIKE '%" + escapedPattern + "%' ESCAPE '\\' ))"
        + " OR ( " + KEY_LOCKED + " = 1 AND " + KEY_TITLE + " LIKE '%" + escapedPattern
        + "%' ESCAPE '\\' )"
        + ")";
    return getNotes(whereCondition, true);
  }

  static String escapeSql(String pattern) {
    return StringUtils.replace(pattern, "'", "''")
        .replace("%", "\\%")
        .replace("_", "\\_");
  }


  /**
   * Search for notes with reminder
   *
   * @param filterPastReminders Excludes past reminders
   * @return Notes list
   */
  public List<Note> getNotesWithReminder(boolean filterPastReminders) {
    String whereCondition = " WHERE " + KEY_REMINDER
        + (filterPastReminders ? " >= " + Calendar.getInstance().getTimeInMillis() : " IS NOT NULL")
        + " AND " + KEY_ARCHIVED + " IS NOT 1"
        + " AND " + KEY_TRASHED + " IS NOT 1";
    return getNotes(whereCondition, true);
  }


  /**
   * Returns all notes that have a reminder that has not been alredy fired
   *
   * @return Notes list
   */
  public List<Note> getNotesWithReminderNotFired() {
    String whereCondition = " WHERE " + KEY_REMINDER + " IS NOT NULL"
        + " AND " + KEY_REMINDER_FIRED + " IS NOT 1"
        + " AND " + KEY_ARCHIVED + " IS NOT 1"
        + " AND " + KEY_TRASHED + " IS NOT 1";
    return getNotes(whereCondition, true);
  }


  /**
   * Returns all notes with a reminder, regardless of their navigation state
   *
   * @return Notes list
   */
  public List<Note> getAllNotesWithReminder() {
    return getNotes(" WHERE " + KEY_REMINDER + " IS NOT NULL", false);
  }


  /**
   * Retrieves locked or unlocked notes
   */
  public List<Note> getNotesWithLock(boolean locked) {
    String whereCondition = " WHERE " + KEY_LOCKED + (locked ? " = 1 " : " IS NOT 1 ");
    return getNotes(whereCondition, true);
  }


  /**
   * Search for notes with reminder expiring the current day
   *
   * @return Notes list
   */
  public List<Note> getTodayReminders() {
    String whereCondition =
        " WHERE DATE(" + KEY_REMINDER + "/1000, 'unixepoch') = DATE('now') AND " +
            KEY_TRASHED + " IS NOT 1";
    return getNotes(whereCondition, false);
  }


  /**
   * Retrieves all attachments related to specific note
   */
  public List<Attachment> getNoteAttachments(Note note) {
    String whereCondition = " WHERE " + KEY_ATTACHMENT_NOTE_ID + " = " + note.get_id();
    return getAttachments(whereCondition);
  }


  public List<Note> getChecklists() {
    String whereCondition = " WHERE " + KEY_CHECKLIST + " = 1";
    return getNotes(whereCondition, false);
  }


  public List<Note> getMasked() {
    String whereCondition = " WHERE " + KEY_LOCKED + " = 1";
    return getNotes(whereCondition, false);
  }


  /**
   * Retrieves all notes related to Category it passed as parameter
   *
   * @param categoryId Category integer identifier
   * @return List of notes with requested category
   */
  public List<Note> getNotesByCategory(Long categoryId) {
    List<Note> notes;
    boolean filterArchived = Prefs
        .getBoolean(PREF_FILTER_ARCHIVED_IN_CATEGORIES + categoryId, false);
    try {
      String whereCondition = " WHERE "
          + KEY_CATEGORY_ID + " = " + categoryId
          + " AND " + KEY_TRASHED + " IS NOT 1"
          + (filterArchived ? " AND " + KEY_ARCHIVED + " IS NOT 1" : "");
      notes = getNotes(whereCondition, true);
    } catch (NumberFormatException e) {
      notes = getAllNotes(true);
    }
    return notes;
  }


  /**
   * Retrieves all tags
   */
  public List<Tag> getTags() {
    return getTags(null);
  }


  /**
   * Retrieves all tags of a specified note
   */
  public List<Tag> getTags(Note note) {
    List<Tag> tags = new ArrayList<>();
    HashMap<String, Integer> tagsMap = new HashMap<>();

    String whereCondition = " WHERE "
        + (note != null ? KEY_ID + " = " + note.get_id() + " AND " : "")
        + "(" + KEY_CONTENT + " LIKE '%#%' OR " + KEY_TITLE + " LIKE '%#%' " + ")"
        + " AND " + KEY_TRASHED + " IS " + (checkNavigation(Navigation.TRASH) ? "1" : " NOT 1")
        + " AND " + KEY_LOCKED + " IS NOT 1";
    List<Note> notesRetrieved = getNotes(whereCondition, true);

    for (Note noteRetrieved : notesRetrieved) {
      Map<String, Integer> tagsRetrieved = TagsHelper.retrieveTags(noteRetrieved);
      for (String s : tagsRetrieved.keySet()) {
        int count = tagsMap.get(s) == null ? 0 : tagsMap.get(s);
        tagsMap.put(s, ++count);
      }
    }

    for (Entry<String, Integer> entry : tagsMap.entrySet()) {
      Tag tag = new Tag(entry.getKey(), entry.getValue());
      tags.add(tag);
    }

    Collections.sort(tags, (tag1, tag2) -> tag1.getText().compareToIgnoreCase(tag2.getText()));
    return tags;
  }


  /**
   * Retrieves all notes related to category it passed as parameter
   */
  public List<Note> getNotesByTag(String tag) {
    return tag.contains(",")
      ? getNotesByTag(tag.split(","))
      : getNotesByTag(new String[]{tag});
  }


  /**
   * Retrieves all notes with specified tags
   */
  public List<Note> getNotesByTag(String[] tags) {
    var whereCondition = new StringBuilder();
    whereCondition.append(" WHERE ");
    for (int i = 0; i < tags.length; i++) {
      if (i != 0) {
        whereCondition.append(" AND ");
      }
      whereCondition.append(String.format("((%s IS 1 AND %s LIKE '%%%s%%') OR (%s is NOT 1 AND (%s LIKE '%%%s%%' OR %s LIKE '%%%s%%')))"
          , KEY_LOCKED, KEY_TITLE, tags[i], KEY_LOCKED, KEY_CONTENT, tags[i], KEY_TITLE, tags[i]));
    }
    // Trashed notes must be included in search results only if search if performed from trash
    whereCondition.append(" AND " + KEY_TRASHED + " IS ")
        .append(checkNavigation(Navigation.TRASH) ? "" : "NOT ").append("1");

    return getNotes(whereCondition.toString(), true).stream()
        .map(note -> {
          boolean matches = Arrays.stream(tags)
              .allMatch(tag -> {
                var p = Pattern.compile(".*(\\s|^)" + tag + "(\\s|$).*", MULTILINE);
                return p.matcher((note.getTitle() + " " + note.getContent())).find();
              });
          return matches ? note : null;
        })
        .filter(Objects::nonNull)
        .collect(toList());
  }

  /**
   * Retrieves all uncompleted checklists
   */
  public List<Note> getNotesByUncompleteChecklist() {
    String whereCondition =
        " WHERE " + KEY_CHECKLIST + " = 1 AND " + KEY_CONTENT + " LIKE '%" + UNCHECKED_SYM + "%' AND "
    + KEY_TRASHED + (checkNavigation(Navigation.TRASH) ? " IS 1" : " IS NOT 1");
    return getNotes(whereCondition, true);
  }


  /**
   * Retrieves all attachments
   */
  public ArrayList<Attachment> getAllAttachments() {
    return getAttachments("");
  }


  /**
   * Retrieves attachments using a condition passed as parameter
   *
   * @return List of attachments
   */
  public ArrayList<Attachment> getAttachments(String whereCondition) {
    ArrayList<Attachment> attachmentsList = new ArrayList<>();
    String sql = "SELECT "
        + KEY_ATTACHMENT_ID + ","
        + KEY_ATTACHMENT_URI + ","
        + KEY_ATTACHMENT_NAME + ","
        + KEY_ATTACHMENT_SIZE + ","
        + KEY_ATTACHMENT_LENGTH + ","
        + KEY_ATTACHMENT_MIME_TYPE + ","
        + KEY_ATTACHMENT_NOTE_ID
        + " FROM " + TABLE_ATTACHMENTS
        + whereCondition;

    Cursor cursor = null;

    try {
      cursor = getDatabase().rawQuery(sql, null);

      // Looping through all rows and adding to list
      if (cursor.moveToFirst()) {
        do {
          var attachment = new Attachment(cursor.getLong(0),
              Uri.parse(cursor.getString(1)), cursor.getString(2), cursor.getInt(3),
              cursor.getInt(4), cursor.getString(5));
          attachment.setNoteId(cursor.getLong(6));
          attachmentsList.add(attachment);
        } while (cursor.moveToNext());
      }

    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
    return attachmentsList;
  }


  /**
   * Retrieves categories list from database
   *
   * @return List of categories
   */
  public ArrayList<Category> getCategories() {
    ArrayList<Category> categoriesList = new ArrayList<>();
    String sql = "SELECT "
        + KEY_CATEGORY_ID + ","
        + KEY_CATEGORY_NAME + ","
        + KEY_CATEGORY_DESCRIPTION + ","
        + KEY_CATEGORY_COLOR + ","
        + " COUNT(" + KEY_ID + ") count"
        + " FROM " + TABLE_CATEGORY
        + " LEFT JOIN ("
        + " SELECT " + KEY_ID + ", " + KEY_CATEGORY
        + " FROM " + TABLE_NOTES
        + " WHERE " + KEY_TRASHED + " IS NOT 1"
        + ") USING( " + KEY_CATEGORY + ") "
        + " GROUP BY "
        + KEY_CATEGORY_ID + ","
        + KEY_CATEGORY_NAME + ","
        + KEY_CATEGORY_DESCRIPTION + ","
        + KEY_CATEGORY_COLOR
        + " ORDER BY IFNULL(NULLIF(" + KEY_CATEGORY_NAME + ", ''),'zzzzzzzz') ";

    Cursor cursor = null;
    try {
      cursor = getDatabase().rawQuery(sql, null);
      // Looping through all rows and adding to list
      if (cursor.moveToFirst()) {
        do {
          categoriesList.add(new Category(cursor.getLong(0),
              cursor.getString(1), cursor.getString(2), cursor
              .getString(3), cursor.getInt(4)));
        } while (cursor.moveToNext());
      }

    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
    return categoriesList;
  }


  /**
   * Updates or insert a new a category
   *
   * @param category Category to be updated or inserted
   * @return Rows affected or new inserted category ID
   */
  public Category updateCategory(Category category) {
    ContentValues values = new ContentValues();
    values.put(KEY_CATEGORY_ID, category.getId() != null ? category.getId() : Calendar.getInstance()
        .getTimeInMillis());
    values.put(KEY_CATEGORY_NAME, category.getName());
    values.put(KEY_CATEGORY_DESCRIPTION, category.getDescription());
    values.put(KEY_CATEGORY_COLOR, category.getColor());
    getDatabase(true).insertWithOnConflict(TABLE_CATEGORY, KEY_CATEGORY_ID, values, CONFLICT_REPLACE);
    return category;
  }


  /**
   * Deletion of  a category
   *
   * @param category Category to be deleted
   * @return Number 1 if category's record has been deleted, 0 otherwise
   */
  public long deleteCategory(Category category) {
    long deleted;

    SQLiteDatabase db = getDatabase(true);
    // Un-categorize notes associated with this category
    ContentValues values = new ContentValues();
    values.putNull(KEY_CATEGORY);

    // Updating row
    db.update(TABLE_NOTES, values, KEY_CATEGORY + " = ?",
        new String[]{String.valueOf(category.getId())});

    // Delete category
    deleted = db.delete(TABLE_CATEGORY, KEY_CATEGORY_ID + " = ?",
        new String[]{String.valueOf(category.getId())});
    return deleted;
  }


  /**
   * Get note Category
   */
  public Category getCategory(Long id) {
    Category category = null;
    String sql = "SELECT "
        + KEY_CATEGORY_ID + ","
        + KEY_CATEGORY_NAME + ","
        + KEY_CATEGORY_DESCRIPTION + ","
        + KEY_CATEGORY_COLOR
        + " FROM " + TABLE_CATEGORY
        + " WHERE " + KEY_CATEGORY_ID + " = " + id;

    try (Cursor cursor = getDatabase().rawQuery(sql, null)) {

      if (cursor.moveToFirst()) {
        category = new Category(cursor.getLong(0), cursor.getString(1),
            cursor.getString(2), cursor.getString(3));
      }

    }
    return category;
  }


  public int getCategorizedCount(Category category) {
    int count = 0;
    String sql = "SELECT COUNT(*)"
        + " FROM " + TABLE_NOTES
        + " WHERE " + KEY_CATEGORY + " = " + category.getId();

    try (Cursor cursor = getDatabase().rawQuery(sql, null)) {
      if (cursor.moveToFirst()) {
        count = cursor.getInt(0);
      }
    }
    return count;
  }


  /**
   * Retrieves statistics data based on app usage
   */
  public Stats getStats() {
    Stats mStats = new Stats();

    // Categories
    mStats.setCategories(getCategories().size());

    // Everything about notes and their text stats
    int notesActive = 0;
    int notesArchived = 0;
    int notesTrashed = 0;
    int reminders = 0;
    int remindersFuture = 0;
    int checklists = 0;
    int notesMasked = 0;
    int tags = 0;
    int locations = 0;
    int totalWords = 0;
    int totalChars = 0;
    int maxWords = 0;
    int maxChars = 0;
    int avgWords;
    int avgChars;
    int words;
    int chars;
    List<Note> notes = getAllNotes(false);
    for (Note note : notes) {
      if (Boolean.TRUE.equals(note.isTrashed())) {
        notesTrashed++;
      } else if (note.isArchived()) {
        notesArchived++;
      } else {
        notesActive++;
      }
      if (note.getAlarm() != null && Long.parseLong(note.getAlarm()) > 0) {
        if (Long.parseLong(note.getAlarm()) > Calendar.getInstance().getTimeInMillis()) {
          remindersFuture++;
        } else {
          reminders++;
        }
      }
      if (Boolean.TRUE.equals(note.isChecklist())) {
        checklists++;
      }
      if (Boolean.TRUE.equals(note.isLocked())) {
        notesMasked++;
      }
      tags += TagsHelper.retrieveTags(note).size();
      if (note.getLongitude() != null && note.getLongitude() != 0) {
        locations++;
      }
      words = NotesHelper.getWords(note);
      chars = NotesHelper.getChars(note);
      if (words > maxWords) {
        maxWords = words;
      }
      if (chars > maxChars) {
        maxChars = chars;
      }
      totalWords += words;
      totalChars += chars;
    }
    mStats.setNotesActive(notesActive);
    mStats.setNotesArchived(notesArchived);
    mStats.setNotesTrashed(notesTrashed);
    mStats.setReminders(reminders);
    mStats.setRemindersFutures(remindersFuture);
    mStats.setNotesChecklist(checklists);
    mStats.setNotesMasked(notesMasked);
    mStats.setTags(tags);
    mStats.setLocation(locations);
    avgWords = totalWords / (!notes.isEmpty() ? notes.size() : 1);
    avgChars = totalChars / (!notes.isEmpty() ? notes.size() : 1);

    mStats.setWords(totalWords);
    mStats.setWordsMax(maxWords);
    mStats.setWordsAvg(avgWords);
    mStats.setChars(totalChars);
    mStats.setCharsMax(maxChars);
    mStats.setCharsAvg(avgChars);

    // Everything about attachments
    int images = 0;
    int videos = 0;
    int audioRecordings = 0;
    int sketches = 0;
    int files = 0;

    List<Attachment> attachments = getAllAttachments();
    for (Attachment attachment : attachments) {
      if (MIME_TYPE_IMAGE.equals(attachment.getMime_type())) {
        images++;
      } else if (MIME_TYPE_VIDEO.equals(attachment.getMime_type())) {
        videos++;
      } else if (MIME_TYPE_AUDIO.equals(attachment.getMime_type())) {
        audioRecordings++;
      } else if (MIME_TYPE_SKETCH.equals(attachment.getMime_type())) {
        sketches++;
      } else if (MIME_TYPE_FILES.equals(attachment.getMime_type())) {
        files++;
      }
    }
    mStats.setAttachments(attachments.size());
    mStats.setImages(images);
    mStats.setVideos(videos);
    mStats.setAudioRecordings(audioRecordings);
    mStats.setSketches(sketches);
    mStats.setFiles(files);

    return mStats;
  }


  public void setReminderFired(long noteId, boolean fired) {
    ContentValues values = new ContentValues();
    values.put(KEY_REMINDER_FIRED, fired);
    getDatabase(true)
        .update(TABLE_NOTES, values, KEY_ID + " = ?", new String[]{String.valueOf(noteId)});
  }


}
