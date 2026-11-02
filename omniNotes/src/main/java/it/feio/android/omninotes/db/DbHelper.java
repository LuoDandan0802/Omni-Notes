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
    //结果列表容器
    List<Note> noteList = new ArrayList<>();

    //排序列与排序方向，根据导航状态和用户偏好动态确定
    String sortColumn = "";
    String sortOrder = "";

    // Getting sorting criteria from preferences. Reminder screen forces sorting.（从偏好设置中获取排序条件。提醒界面强制进行排序。）
    if (checkNavigation(Navigation.REMINDERS)) {
      sortColumn = KEY_REMINDER;
    } else {
      sortColumn = Prefs.getString(PREF_SORTING_COLUMN, KEY_TITLE);
    }
    //标题/提醒按字母升序，其他列（如创建时间）按降序
    if (order) {
      sortOrder =
          KEY_TITLE.equals(sortColumn) || KEY_REMINDER.equals(sortColumn) ? " ASC " : " DESC ";
    }

    // In case of title sorting criteria it must be handled empty title by concatenating content
    //按标题排序时，用“标题||内容”拼接：标题为空的笔记一内容参与排序，避免空串排到前面
    sortColumn = KEY_TITLE.equals(sortColumn) ? KEY_TITLE + "||" + KEY_CONTENT : sortColumn;

    // In case of reminder sorting criteria the empty reminder notes must be moved on bottom of results
    //（如果按提醒事项排序规则，空白提醒备注必须排在结果列表的底部）
    sortColumn = KEY_REMINDER.equals(sortColumn) ? "IFNULL(" + KEY_REMINDER + ", " +
        "" + TIMESTAMP_UNIX_EPOCH + ")" : sortColumn;

    // Generic query to be specialized with conditions passed as parameter（需要通过传入参数中的条件进行具体化的通用查询语句）
    //主查询：SELECT 18列，LEFT JOIN categories 联表带回分类名称/描述/颜色，再拼接调用方法传入的where条件与排序子句
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

    //执行查询并遍历游标：try-with-resources自动关闭游标，避免资源泄漏
    try (Cursor cursor = getDatabase().rawQuery(query, null)) {

      //游标移到首行后进入循环，逐行构建Note对象
      if (cursor.moveToFirst()) {
        do {
          int i = 0;//列索引计数器：严格按SELECT列顺序递增读取
          Note note = new Note();
          note.setCreation(cursor.getLong(i++));
          note.setLastModification(cursor.getLong(i++));
          note.setTitle(cursor.getString(i++));
          note.setContent(cursor.getString(i++));
          //SQLite 无布尔型：archived/trsdhed存“0”/“1”字符串，此处转换回布尔
          note.setArchived("1".equals(cursor.getString(i++)));
          note.setTrashed("1".equals(cursor.getString(i++)));
          note.setAlarm(cursor.getString(i++));
          note.setReminderFired(cursor.getInt(i++));
          note.setRecurrenceRule(cursor.getString(i++));
          //经纬度可能为空，用isNull判空后置null
          note.setLatitude(cursor.isNull(i) ? null : cursor.getDouble(i));
          i++;
          note.setLongitude(cursor.isNull(i) ? null : cursor.getDouble(i));
          i++;
          note.setAddress(cursor.getString(i++));
          note.setLocked("1".equals(cursor.getString(i++)));
          note.setChecklist("1".equals(cursor.getString(i++)));

          // Eventual decryption of content（内容的最终解密)
          //读到的内容若是锁定便签，则用密码解密还原明文（与updateNote写入时加密对称）
          if (Boolean.TRUE.equals(note.isLocked())) {
            note.setContent(
                Security.decrypt(note.getContent(), Prefs.getString(PREF_PASSWORD, "")));
          }

          // Set category（设置分类）
          //读取分类外键：非0才构造分类对象并读回分类名/描述/颜色
          long categoryId = cursor.getLong(i++);
          if (categoryId != 0) {
            Category category = new Category(categoryId, cursor.getString(i++),
                cursor.getString(i++), cursor.getString(i));
            note.setCategory(category);
          }

          // Add eventual attachments uri（添加最终附件的统一资源标识符）
          //二次查询该笔记的全部附件（按note_id 查 attachments 表）挂到 Note 上
          note.setAttachmentsList(getNoteAttachments(note));

          // Adding note to list（向列表添加备注）
          noteList.add(note);

        } while (cursor.moveToNext());//游标下移，直到遍历完所有行
      }

    }

    LogDelegate.v("Query: Retrieval finished!");//日志：查询完成（verbose级，仅调试可见）
    return noteList;
  }


  /**
   * Archives/restore single note（归档/恢复单个笔记）
   * 只翻转 archived 标记并复用 updateNote 落库，不动其他字段
   * @why 归档是软删除的一种，保留数据便于归档视图恢复，不物理删除
   */
  public void archiveNote(Note note, boolean archive) {
    note.setArchived(archive);//翻转归档标记
    updateNote(note, false);//复用统一保存流程落库（false = 不删附件）
  }


  /**
   * Trashes/restore single note（回收站/恢复单条笔记）
   * @why 与归档相似：软删除，进回收站后可恢复，避免误删
   */
  public void trashNote(Note note, boolean trash) {
    note.setTrashed(trash);//翻转回收站标记
    updateNote(note, false);//复用统一保存流程落库
  }


  /**
   * Deleting single note（删除单个笔记）
   *   重载①：按 Note 对象，默认连附件一起删
   */
  public boolean deleteNote(Note note) {
    return deleteNote(note, false);//默认删除时连同附件记录一起删
  }


  /**
   * Deleting single note, eventually keeping attachments（删除单条笔记，保留附件）
   *   重载②：可指定是否保留附件
   *   @why 分离 Note 对象与 ID 两个入口，调用方按手头参数选择；keepAttachments 供备份恢复等场景
   */
  public boolean deleteNote(Note note, boolean keepAttachments) {
    return deleteNote(note.get_id(), keepAttachments);//取 ID 调转按 ID 删除的核心实现
  }


  /**
   * Deleting single note by its ID（通过ID删除单条笔记）
   * 先删 notes 表记录，再默认级联删 attachments 表记录；keepAttachments=true 时保留附件
   * @why 附件表用 note_id 外键关联，不手动级联删除会留下孤儿附件行，占存储且查询脏数据
   */
  public boolean deleteNote(long noteId, boolean keepAttachments) {
    SQLiteDatabase db = getDatabase(true);//打开可写数据库（true=强制新实例，避免旧连接竞争）
    db.delete(TABLE_NOTES, KEY_ID + " = ?", new String[]{String.valueOf(noteId)});//按主键参数化删除笔记，防SQL注入
    if (!keepAttachments) {
      db.delete(TABLE_ATTACHMENTS, KEY_ATTACHMENT_NOTE_ID + " = ?",
          new String[]{String.valueOf(noteId)});//级联删除该笔记全部附件行，防孤儿数据
    }
    return true;
  }


  /**
   * Empties trash deleting all trashed notes（清空回收站，删除所有已移入回收站的笔记）
   * @why 回收站笔记仍占存储，用户确认清空时彻底释放（连同附件）
   */
  public void emptyTrash() {
    //先取出全部 trashed=1的笔记
    for (Note note : getNotesTrashed()) {
      deleteNote(note);//逐个物理删除（默认连附件）
    }
  }


  /**
   * Gets notes matching pattern with title or content text（获取标题或内容文本匹配指定模式的笔记）
   *
   * @param pattern String to match with（要匹配的字符串）
   * @return Notes list（笔记列表）
   * 先按当前导航拼过滤（回收站/归档/分类/未分类/提醒），再拼关键字匹配；
   * 锁定笔记只匹配标题不匹配内容（内容为密文，无法明文匹配，也避免解密开销）
   * @why 搜素在SQL层完成粗筛，减少 Java 层遍历量；加密笔记不解密搜索是性能与安全的权衡
   */
  public List<Note> getNotesByPattern(String pattern) {
    String escapedPattern = escapeSql(pattern);//先转义用户输入，防注入与通配符误匹配
    int navigation = Navigation.getNavigation();//读取当前导航状态（全部/归档/回收站/分类/未分类/提醒）
    String whereCondition = " WHERE "
        + KEY_TRASHED + (navigation == Navigation.TRASH ? " IS 1" : " IS NOT 1")//回收站导航只搜已删，其他导航排除已删
        + (navigation == Navigation.ARCHIVE ? " AND " + KEY_ARCHIVED + " IS 1" : "")//归档视图下额外限定 archived=1
        + (navigation == Navigation.CATEGORY ? " AND " + KEY_CATEGORY + " = " + Navigation
        .getCategory() : "")//分类视图下限定分类
        + (navigation == Navigation.UNCATEGORIZED ? " AND (" + KEY_CATEGORY + " IS NULL OR "
        + KEY_CATEGORY_ID
        + " == 0) " : "")//未分类视图限定无分类笔记
        + (checkNavigation(Navigation.REMINDERS) ? " AND " + KEY_REMINDER
        + " IS NOT NULL" : "")//提醒视图限定有提醒时间
        + " AND ("
        + " ( " + KEY_LOCKED + " IS NOT 1 AND (" + KEY_TITLE + " LIKE '%" + escapedPattern
        + "%' ESCAPE '\\' " + " OR "// 指定转义字符，与 escapeSql 的 %/_ 转义配套
        +
        KEY_CONTENT + " LIKE '%" + escapedPattern + "%' ESCAPE '\\' ))"//未锁定笔记标题或内容均可命中
        + " OR ( " + KEY_LOCKED + " = 1 AND " + KEY_TITLE + " LIKE '%" + escapedPattern
        + "%' ESCAPE '\\' )"
        + ")";//锁定笔记只按标题匹配
    return getNotes(whereCondition, true);//复用统一查询入口，order=true按默认排序
  }

  /**
   * 转义SQL LIKE 模式中的特殊字符
   * '→"(SQL字符串转义）；%和_ → \% \_（LIKE通配符转义）
   * @why 用户输入直接拼进SQL会引发注入或通配符误匹配，必须先转义再拼接
   */
  static String escapeSql(String pattern) {
    return StringUtils.replace(pattern, "'", "''")//单引号翻倍：SQL字符串字面量转义，防注入
        .replace("%", "\\%")
        .replace("_", "\\_");//通配符转义
  }


  /**
   * Search for notes with reminder（搜索带有提醒的笔记）
   *
   * @param filterPastReminders Excludes past reminders（排除过往提醒）
   * @return Notes list（笔记列表）
   * @why 提醒列表页需区分“待提醒”与“全部提醒”，是否过滤过去时间由调用方决定
   */
  public List<Note> getNotesWithReminder(boolean filterPastReminders) {
    String whereCondition = " WHERE " + KEY_REMINDER
        + (filterPastReminders ? " >= " + Calendar.getInstance().getTimeInMillis() : " IS NOT NULL")//当前时间毫秒值，与库中提醒时间戳同单位比较
        + " AND " + KEY_ARCHIVED + " IS NOT 1"
        + " AND " + KEY_TRASHED + " IS NOT 1";// 只显示在用笔记，排除归档与回收站
    return getNotes(whereCondition, true);
  }


  /**
   * Returns all notes that have a reminder that has not been alredy fired（返回所有包含尚未触发提醒的笔记）
   *
   * @return Notes list（笔记列表）
   * @why 供提醒轮询/接收器判断哪些需要真正发通知；reminder_fired=1 的已通知过，不再重复
   */
  public List<Note> getNotesWithReminderNotFired() {
    String whereCondition = " WHERE " + KEY_REMINDER + " IS NOT NULL"
        + " AND " + KEY_REMINDER_FIRED + " IS NOT 1"//排除已触发过的，避免重复通知
        + " AND " + KEY_ARCHIVED + " IS NOT 1"
        + " AND " + KEY_TRASHED + " IS NOT 1";
    return getNotes(whereCondition, true);
  }


  /**
   * Returns all notes with a reminder, regardless of their navigation state（返回所有带有提醒的笔记，不受其导航状态影响）
   *
   * @return Notes list
   * @why 备份/统计等场景需要完整提醒清单，不走导航过滤
   */
  public List<Note> getAllNotesWithReminder() {
    return getNotes(" WHERE " + KEY_REMINDER + " IS NOT NULL", false);// 无额外过滤 + 不排序，按插入顺序返回
  }


  /**
   * Retrieves locked or unlocked notes（检索已锁定或未锁定的笔记）
   * @param locked true=只取锁定便签，false=只取未锁定便签
   */
  public List<Note> getNotesWithLock(boolean locked) {
    String whereCondition = " WHERE " + KEY_LOCKED + (locked ? " = 1 " : " IS NOT 1 ");//locked=true 匹配 =1，false 匹配 IS NOT 1（含 NULL 未锁定）
    return getNotes(whereCondition, true);
  }


  /**
   * Search for notes with reminder expiring the current day（搜索提醒将于当日到期的笔记）
   *
   * @return Notes list
   * 用 SQLite 日期函数把毫秒时间戳转成当天日期与"今天"比较
   * @why 首页/小组件展示"今日提醒"，在 SQL 层按天过滤，避免全表拉到 Java 再判断
   */
  public List<Note> getTodayReminders() {
    // 毫秒 ÷1000 转秒后取日期，与当前日期比较（同为 UTC 可比）
    String whereCondition =
        " WHERE DATE(" + KEY_REMINDER + "/1000, 'unixepoch') = DATE('now') AND " +
            KEY_TRASHED + " IS NOT 1";// 回收站里的不参与提醒
    return getNotes(whereCondition, false);
  }


  /**
   * Retrieves all attachments related to specific note（检索与指定笔记相关的所有附件）
   * @why getNotes 逐条解析时调用（对应 L630），把附件列表挂到每个 Note 上
   */
  public List<Attachment> getNoteAttachments(Note note) {
    String whereCondition = " WHERE " + KEY_ATTACHMENT_NOTE_ID + " = " + note.get_id();// 按外键 note_id 过滤（长整型拼接安全）
    return getAttachments(whereCondition);// 复用附件查询统一入口
  }


  /**
   * 查询所有清单型笔记（checklist=1）
   * @why 清单筛选视图需快速定位带勾选框的笔记
   */
  public List<Note> getChecklists() {
    String whereCondition = " WHERE " + KEY_CHECKLIST + " = 1";
    return getNotes(whereCondition, false);
  }


  /**
   * 查询所有锁定（加密）笔记
   * @why 锁定笔记在列表中显示为掩码摘要，需单独查询做特殊展示
   */
  public List<Note> getMasked() {
    String whereCondition = " WHERE " + KEY_LOCKED + " = 1";
    return getNotes(whereCondition, false);
  }


  /**
   * Retrieves all notes related to Category it passed as parameter（检索与传入参数中的分类相关的所有笔记）
   *
   * @param categoryId Category integer identifier
   * @return List of notes with requested category（指定分类下的笔记列表）
   * 先读该分类是否启用"隐藏已归档"偏好，再动态拼 where；
   * 分类 ID 非法（NumberFormatException）时兜底返回全部未删便签，不让列表崩溃
   * @why 分类过滤策略可由用户在偏好中自定义，故查询前须按分类 ID 读偏好决定
   */
  public List<Note> getNotesByCategory(Long categoryId) {
    List<Note> notes;
    boolean filterArchived = Prefs
        .getBoolean(PREF_FILTER_ARCHIVED_IN_CATEGORIES + categoryId, false);// 读该分类专属"隐藏已归档"偏好（键含分类 ID）
    try {
      String whereCondition = " WHERE "
          + KEY_CATEGORY_ID + " = " + categoryId// 按分类外键过滤
          + " AND " + KEY_TRASHED + " IS NOT 1"// 回收站便签不显示
          + (filterArchived ? " AND " + KEY_ARCHIVED + " IS NOT 1" : "");// 偏好启用时额外排除已归档
      notes = getNotes(whereCondition, true);
    } catch (NumberFormatException e) {// categoryId 非法时兜底，不抛给 UI
      notes = getAllNotes(true);// 兜底：返回全部未删便签
    }
    return notes;
  }


  /**
   * Retrieves all tags（检索所有标签）
   */
  public List<Tag> getTags() {
    return getTags(null);//null = 统计所有便签的标签
  }


  /**
   * Retrieves all tags of a specified note（获取指定笔记的所有标签）
   * 标签 = 标题/内容中以 # 开头的词；HashMap 聚合计数，最后按标签名忽略大小写排序
   * @param note null 时统计所有未删便签
   * @why Omni-Notes 的标签不是独立实体表，而是 #hashtag 文本约定，读取时实时提取统计
   */
  public List<Tag> getTags(Note note) {
    List<Tag> tags = new ArrayList<>();
    HashMap<String, Integer> tagsMap = new HashMap<>();// 标签名 → 出现次数

    String whereCondition = " WHERE "
        + (note != null ? KEY_ID + " = " + note.get_id() + " AND " : "")
        + "(" + KEY_CONTENT + " LIKE '%#%' OR " + KEY_TITLE + " LIKE '%#%' " + ")"// SQL 层先筛出可能含 # 的便签，减少 Java 遍历量
        + " AND " + KEY_TRASHED + " IS " + (checkNavigation(Navigation.TRASH) ? "1" : " NOT 1")// 回收站视图统计回收站，否则统计非回收站
        + " AND " + KEY_LOCKED + " IS NOT 1";// 锁定便签内容为密文，无法可靠提取标签，直接排除
    List<Note> notesRetrieved = getNotes(whereCondition, true);

    for (Note noteRetrieved : notesRetrieved) {
      Map<String, Integer> tagsRetrieved = TagsHelper.retrieveTags(noteRetrieved);// 从单条便签标题/内容提取 #词及计数
      for (String s : tagsRetrieved.keySet()) {
        int count = tagsMap.get(s) == null ? 0 : tagsMap.get(s);
        tagsMap.put(s, ++count);// 合并计数：首次置 1，重复累加
      }
    }

    for (Entry<String, Integer> entry : tagsMap.entrySet()) {
      Tag tag = new Tag(entry.getKey(), entry.getValue());
      tags.add(tag);
    }

    Collections.sort(tags, (tag1, tag2) -> tag1.getText().compareToIgnoreCase(tag2.getText()));// 按标签文本忽略大小写排序，保证展示顺序稳定
    return tags;
  }


  /**
   * Retrieves all notes related to category it passed as parameter（检索与传入参数中指定分类相关的所有笔记）
   */
  public List<Note> getNotesByTag(String tag) {
    return tag.contains(",")
      ? getNotesByTag(tag.split(","))
      : getNotesByTag(new String[]{tag});// 逗号分隔表示多标签，拆开转数组走重载
  }


  /**
   * Retrieves all notes with specified tags（检索带有指定标签的所有笔记）
   * 按多个标签查询便签（标签间为 AND 关系，须同时包含全部标签）
   * 两步过滤：SQL 粗筛标题/内容含标签文本的便签 → 流式正则"整词匹配"精筛
   * 锁定便签只匹配标题（密文无法匹配）；回收站便签仅在回收站视图纳入结果
   * @why 纯 SQL LIKE 会把 "tagged" 误配成 "tag"，必须二次正则保证标签边界（前后为空白或行首尾）
   */
  public List<Note> getNotesByTag(String[] tags) {
    var whereCondition = new StringBuilder();// 可变字符串逐段拼接 where，避免字符串频繁重建
    whereCondition.append(" WHERE ");
    for (int i = 0; i < tags.length; i++) {
      if (i != 0) {
        whereCondition.append(" AND ");
      }// 多标签用 AND 连接，全部命中才算
      whereCondition.append(String.format("((%s IS 1 AND %s LIKE '%%%s%%') OR (%s is NOT 1 AND (%s LIKE '%%%s%%' OR %s LIKE '%%%s%%')))"
          , KEY_LOCKED, KEY_TITLE, tags[i], KEY_LOCKED, KEY_CONTENT, tags[i], KEY_TITLE, tags[i]));
    }// 每个标签：锁定只查标题，未锁定查标题或内容（% 转义成 %% 由 String.format 处理）
    // Trashed notes must be included in search results only if search if performed from trash
    // （已删除笔记仅在回收站中执行搜索时，才会出现在搜索结果里）
    whereCondition.append(" AND " + KEY_TRASHED + " IS ")
        .append(checkNavigation(Navigation.TRASH) ? "" : "NOT ").append("1");// 回收站过滤与当前导航一致

    return getNotes(whereCondition.toString(), true).stream()
        .map(note -> {// 流式二次精筛：逐条验证标签整词匹配
          boolean matches = Arrays.stream(tags)
              .allMatch(tag -> {// 所有标签都命中才保留
                var p = Pattern.compile(".*(\\s|^)" + tag + "(\\s|$).*", MULTILINE);// 标签前后须为空白或行首尾（整词边界）
                return p.matcher((note.getTitle() + " " + note.getContent())).find();// 标题+内容拼接后整体匹配
              });
          return matches ? note : null;
        })
        .filter(Objects::nonNull)// 剔除未命中的 null
        .collect(toList());// 收集为 List 返回
  }

  /**
   * Retrieves all uncompleted checklists（检索所有未完成的检查清单）
   * 条件：checklist=1 且内容中仍含未勾选符号，且不在回收站
   * @why 全部勾完的清单没有提醒价值，"待办"入口只显示还有未完成项的
   */
  public List<Note> getNotesByUncompleteChecklist() {
    String whereCondition =
        " WHERE " + KEY_CHECKLIST + " = 1 AND " + KEY_CONTENT + " LIKE '%" + UNCHECKED_SYM + "%' AND "
    + KEY_TRASHED + (checkNavigation(Navigation.TRASH) ? " IS 1" : " IS NOT 1");// 内容里还有未勾选项才算未完成
    return getNotes(whereCondition, true);
  }


  /**
   * Retrieves all attachments（检索所有附件）
   */
  public ArrayList<Attachment> getAllAttachments() {
    return getAttachments("");// 空条件 = 无过滤，查全表
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
