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

package it.feio.android.omninotes.async.notes;

import android.content.Context;
import android.os.AsyncTask;
import de.greenrobot.event.EventBus;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.async.bus.NotesUpdatedEvent;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.utils.ReminderHelper;
import it.feio.android.omninotes.utils.StorageHelper;
import it.feio.android.omninotes.utils.date.DateUtils;
import java.util.List;


/**
 * 异步保存笔记任务：在后台线程执行“新建/更新笔记”及附件清理、系统提醒注册，完成后通知 UI 刷新
 * @why 数据库写入与文件删除耗时可观，放后台线程避免阻塞主线程导致界面卡顿；
 *       AsyncTask 的三个泛型分别是：入参 Note、进度 Void、结果 Note。
 */
public class SaveNoteTask extends AsyncTask<Note, Void, Note> {

  private Context context;//应用全局上下文（用于注册/移除系统提醒）
  private boolean updateLastModification = true;//是否回写 lastModification:归档/回收等状态翻转场景传 false，避免刷新排序

  /**
   * @param updateLastModification 是否更新"最后修改时间"：
   *                              普通编辑保存传 true；归档、移入回收站等纯状态变更传 false，
   *                              否则每次翻转状态都会把笔记顶到列表最前，排序体验混乱
   */

  public SaveNoteTask(boolean updateLastModification) {
    super();
    this.context = OmniNotes.getAppContext();
    this.updateLastModification = updateLastModification;
  }

  /**
   * 后台线程主体：按"清理附件 → 重置提醒标记 → 落库 → 同步系统提醒"的顺序保存便签。
   * @why 步骤顺序有依赖：先清附件避免残留文件；落库拿到真实 id 后再决定注册/移除系统提醒。
   * @return 保存后的 Note（含数据库回填的 creation/lastModification）
   */

  @Override
  protected Note doInBackground(Note... params) {
    Note note = params[0];//取传入的待保存笔记（AsyncTask 只接收一个参数）
    purgeRemovedAttachments(note);// 第一步：先删除本次编辑中"被移除的附件"的实体文件
    boolean reminderMustBeSet = DateUtils.isFuture(note.getAlarm());// 提醒时间在未来才需要注册系统提醒；已过期/无提醒不注册
    if (reminderMustBeSet) {
      note.setReminderFired(false);// 重置"已触发"标记：让闹钟到点后可以再次正常触发并发送通知
    }
    note = DbHelper.getInstance().updateNote(note, updateLastModification);// 核心落库：事务写入笔记+附件，锁定内容自动加密（复用 DbHelper 逻辑）
    if (Boolean.TRUE.equals(note.isTrashed())) {
      ReminderHelper.removeReminder(context, note);// 笔记进回收站：取消系统闹钟，避免已删除内容仍弹提醒
    } else if (reminderMustBeSet) {
      ReminderHelper.addReminder(context, note);// 有未来提醒：注册系统闹钟（精确闹钟权限申请也在 ReminderHelper 内）
    }
    return note;// 返回带真实 id/时间戳的笔记，供主线程通知 UI
  }

  /**
   * 删除本次编辑中被移除的附件实体文件。
   * @why 通过"旧附件列表 − 当前附件列表 = 被删除附件"求差集：只在数据库与文件系统两侧同步清理，
   *      避免删掉用户仍在使用的附件，也避免残留孤儿文件占用存储。
   */

  private void purgeRemovedAttachments(Note note) {
    List<Attachment> deletedAttachments = note.getAttachmentsListOld();// 数据库中的旧附件快照（本次编辑前的状态）
    for (Attachment attachment : note.getAttachmentsList()) {// 遍历当前附件列表，逐个从"待删除"集合中剔除
      if (attachment.getId() != null) {// 无 id 的是新增附件，不在旧快照里，无需处理
        // Workaround to prevent deleting attachments if instance is changed (app restart)
        //（用于规避实例变更（应用重启）时附件被删除的问题）
        if (!deletedAttachments.contains(attachment)) {// 应用重启后 Note 实例被重建，新旧 Attachment 对象虽同 id 但 equals 可能不等
          attachment = getFixedAttachmentInstance(deletedAttachments, attachment);// 用旧列表中的同 id 实例替换，保证能正确 remove
        }
        deletedAttachments.remove(attachment);// 仍在使用的附件移出待删集合
      }
    }
    // Remove from database deleted attachments（从数据库中移除已删除的附件）
    for (Attachment deletedAttachment : deletedAttachments) {// 剩下的才是真正被删除的附件
      StorageHelper.delete(context, deletedAttachment.getUri().getPath());// 删除实体文件（数据库行已由 updateNote 的事务清理）
      LogDelegate.d("Removed attachment " + deletedAttachment.getUri());// 记录删除日志便于排查
    }
  }

  /**
   * 在旧附件列表中按 id 查找与当前附件对应的"旧实例"。
   * @why 重启后对象引用失效，contains/remove 依赖 equals 实现；用同 id 的旧实例替换，
   *      保证差异计算不受对象实例变化影响（防误删的 Workaround）。
   * @return 找到的旧实例；找不到则返回原附件（正常情况应能找到）
   */

  private Attachment getFixedAttachmentInstance(List<Attachment> deletedAttachments,
      Attachment attachment) {
    for (Attachment deletedAttachment : deletedAttachments) {
      if (deletedAttachment.getId().equals(attachment.getId())) {//按主键 id 匹配新旧实例
        return deletedAttachment;//命中：返回旧实例，使后续remove能正确命中
      }
    }
    return attachment;// 兜底：未匹配到返回原对象（不误删）
  }

  /**
   * 主线程回调：保存完成后发布 EventBus 事件，通知列表页/详情页刷新。
   * @why AsyncTask 保证本方法运行在 UI 线程，可直接触发界面刷新；
   *      NotesUpdatedEvent 携带保存后的便签，订阅方据此更新对应条目，避免整表重查。
   */

  @Override
  protected void onPostExecute(Note note) {
    super.onPostExecute(note);
    EventBus.getDefault().post(new NotesUpdatedEvent(List.of(note)));// 发布事件：列表/详情订阅者收到后刷新该笔记
  }

}
