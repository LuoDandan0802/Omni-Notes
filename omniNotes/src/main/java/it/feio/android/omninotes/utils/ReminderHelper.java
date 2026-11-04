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

package it.feio.android.omninotes.utils;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_NO_CREATE;
import static android.widget.Toast.LENGTH_LONG;
import static it.feio.android.omninotes.helpers.IntentHelper.immutablePendingIntentFlag;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_NOTE;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.R;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.helpers.date.DateHelper;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.receiver.AlarmReceiver;
import it.feio.android.omninotes.utils.date.DateUtils;
import java.util.Calendar;


/**
 * 提醒/闹钟工具类：封装 AlarmManager + PendingIntent，统一管理笔记提醒的注册、查询、取消与提示。
 * @why 提醒涉及系统闹钟权限（Android 12+ 精确闹钟）、PendingIntent 请求码、通知权限等多处系统交互，
 *      集中在此类可避免 SaveNoteTask、DetailFragment 等调用方重复实现；私有构造禁止实例化，全部静态方法。
 */

public class ReminderHelper {

  private ReminderHelper() {
    // hides public constructor（隐藏公共构造函数）
    // 私有构造：工具类只提供静态方法，禁止外部 new 实例
  }

  /**
   * 为笔记注册提醒（重载1）：从笔记的 alarm 字符串解析触发时间后注册。
   * @why alarm 在 Note 中以字符串存储（与数据库列类型一致），先解析为 long 再走统一注册逻辑；
   *      解析失败（非数字）时由 NumberFormatException 向上抛出，由调用方兜底。
   */

  public static void addReminder(Context context, Note note) {
    if (note.getAlarm() != null) {// 无提醒时间则直接跳过，不注册任何闹钟
      addReminder(context, note, Long.parseLong(note.getAlarm()));// 解析字符串时间为毫秒时间戳
    }
  }

  /**
   * 为笔记注册提醒（重载2，核心实现）：检查精确闹钟权限后设置一次性系统闹钟。
   * @why 笔记提醒要求"到点准时弹通知"，必须用 setExact 精确闹钟而非省电模式下的宽松闹钟；
   *      Android 12+ 对精确闹钟有单独权限，无权限时跳系统设置页引导用户授权（不静默降级）。
   */

  public static void addReminder(Context context, Note note, long reminder) {
    if (DateUtils.isFuture(reminder)) {// 仅提醒时间在未来才注册；已过期/过去的时间不注册（避免保存后立即触发）
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) and above（Android 12（API 31）及以上版本）
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        if (!alarmManager.canScheduleExactAlarms()) {// 检查精确闹钟权限（Android 12+ 新增，默认关闭）
          Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);// 跳转系统"闹钟和提醒"授权页
          context.startActivity(intent);
          return;// 本次不注册，等用户在设置页授权后重试
        }
      }

      Intent intent = new Intent(context, AlarmReceiver.class);// 闹钟触发后交给 AlarmReceiver 处理（发通知）
      intent.putExtra(INTENT_NOTE, ParcelableUtil.marshall(note));// 把笔记序列化塞进 Intent：闹钟到点后接收方能还原完整 Note
      PendingIntent sender = PendingIntent.getBroadcast(
          context,
          getRequestCode(note),// 请求码=笔记创建时间：同一笔记重复保存时覆盖旧闹钟（详见 getRequestCode）
          intent,
          immutablePendingIntentFlag(PendingIntent.FLAG_CANCEL_CURRENT)// 已存在则先取消旧的再建新的；FLAG_IMMUTABLE 适配 Android 12+ 可变性要求
      );

      AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
      am.setExact(AlarmManager.RTC_WAKEUP, reminder, sender);// RTC_WAKEUP：按墙钟时间到点唤醒设备并投递 PendingIntent
    }
  }


  /**
   * Checks if exists any reminder for given note（检查指定笔记是否存在任意提醒）
   * @why 用于界面显示"已设置提醒"状态；FLAG_NO_CREATE 表示"仅查询不创建"，
   *      查不到返回 null，即不存在该提醒。
   * @return true=存在对应闹钟；false=未设置
   */
  public static boolean checkReminder(Context context, Note note) {
    return
        PendingIntent.getBroadcast(context, getRequestCode(note), new Intent(context, AlarmReceiver
            .class), immutablePendingIntentFlag(FLAG_NO_CREATE)) != null;// 只查不建：能取到 PendingIntent 说明闹钟还在
  }

  /**
   * 生成笔记对应的 PendingIntent 请求码。
   * @why 请求码 = 笔记创建时间（截断为 int）：同一笔记每次保存都得到相同请求码，
   *      从而"重复注册=覆盖旧闹钟"、"取消=精确命中"，不同笔记之间又互不冲突。
   * @return 创建时间截断后的 int；创建时间为空时用当前时间兜底
   */
  static int getRequestCode(Note note) {
    long longCode = note.getCreation() != null ? note.getCreation()// 优先用笔记创建时间（稳定不变）
        : Calendar.getInstance().getTimeInMillis() / 1000L;// 兜底：当前时间秒级（理论上不会走到，防御空指针）
    return (int) longCode;// 截断为 int：PendingIntent 请求码是 int 类型，低位截断足以区分
  }

  /**
   * 取消指定笔记的提醒闹钟（笔记删除、移入回收站、关闭提醒时调用）。
   * @why 已注册的闹钟不会自动消失，若不显式 cancel，删除的笔记到点仍会弹出通知；
   *      AlarmManager 与 PendingIntent 两侧都 cancel，确保系统与本地引用彻底清除。
   */

  public static void removeReminder(Context context, Note note) {
    if (!TextUtils.isEmpty(note.getAlarm())) {// 没有提醒时间的笔记无需取消（防御性判断）
      AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
      Intent intent = new Intent(context, AlarmReceiver.class);
      PendingIntent p = PendingIntent.getBroadcast(context, getRequestCode(note), intent,
          immutablePendingIntentFlag(0));// 请求码一致 → 能精确匹配到当初注册的那个闹钟
      am.cancel(p);// 取消 AlarmManager 侧的闹钟调度
      p.cancel();// 同时释放 PendingIntent 引用，避免内存泄漏
    }
  }

  /**
   * Removes all pending reminders and clears notifications already shown
   * （移除所有待处理提醒，并清除已展示的通知）
   * @why 用户关闭"全局提醒"或退出登录/清除数据时调用：先逐个取消数据库中有提醒的笔记，
   *       再取消通知栏里已弹出的提醒通知，保证提醒完全停用。
   */
  public static void removeAllReminders(Context context) {
    for (Note note : DbHelper.getInstance().getAllNotesWithReminder()) {
      removeReminder(context, note);// 逐个复用单条取消逻辑
    }
    new NotificationsHelper(context).cancelAll();// 清空通知栏中已显示的提醒通知
  }

  /**
   * 保存笔记后显示"提醒已设置"的 Toast 提示，并检查通知权限。
   * @why 保存操作在后台线程完成，Toast 必须切回主线程（Handler + mainLooper）才能弹；
   *      顺带检查通知权限，提醒用户若关闭通知则闹钟到了也不会弹提醒。
   */

  public static void showReminderMessage(String reminderString) {
    if (reminderString != null) {
      var context = OmniNotes.getAppContext();// 应用级上下文（非 Activity，避免持有 Activity 引用导致泄漏）
      long reminder = Long.parseLong(reminderString);// 解析提醒时间戳
      if (reminder > Calendar.getInstance().getTimeInMillis()) {// 仅在提醒时间在未来时提示（过去的提醒无意义）
        new Handler(OmniNotes.getAppContext().getMainLooper()).post(() ->// 切到主线程执行 UI 操作
            Toast.makeText(context,
                context.getString(R.string.alarm_set_on) + " " + DateHelper
                    .getDateTimeShort(context, reminder), LENGTH_LONG).show());// 显示"提醒已设置在 具体时间"

        if (!new NotificationsHelper(context).checkNotificationsEnabled(context)) {// 顺带检查通知是否被系统关闭
          Toast.makeText(context, context.getString(R.string.denied_notifications_permission), LENGTH_LONG).show();// 提示用户开启通知，否则闹钟到点不弹
        }
      }
    }
  }

}
