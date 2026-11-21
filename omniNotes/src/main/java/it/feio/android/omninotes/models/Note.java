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
package it.feio.android.omninotes.models;

import android.os.Parcel;
import android.os.Parcelable;
import com.google.gson.Gson;
import it.feio.android.omninotes.commons.models.BaseAttachment;
import it.feio.android.omninotes.commons.models.BaseCategory;
import it.feio.android.omninotes.commons.models.BaseNote;
import java.util.ArrayList;
import java.util.List;


/**
 * 便签领域模型：承载一条笔记的全部业务字段（内容、状态、提醒、位置、分类、附件等）。
 * @why 继承 commons 模块的 BaseNote 复用基础字段与 JSON 序列化；实现 Parcelable 以便在
 *      Intent/进程间传递完整便签（如分享、闹钟触发时还原 Note）；
 *      models 层是数据库(DbHelper)与界面(Fragment)之间的数据载体。
 */

public class Note extends BaseNote implements Parcelable {

  /*
   * Parcelable interface must also have a static field called CREATOR, which is an object implementing the
   * Parcelable.Creator interface. Used to un-marshal or de-serialize object from Parcel.
   * Parcelable 约定：CREATOR 负责把 Parcel 还原成 Note 对象（反序列化入口）
   */
  public static final Parcelable.Creator<Note> CREATOR = new Parcelable.Creator<>() {

    public Note createFromParcel(Parcel in) {
      return new Note(in);
    }


    public Note[] newArray(int size) {
      return new Note[size];
    }
  };
  // Not saved in DB
  private boolean passwordChecked = false;// 运行时标记：本便签是否已通过密码校验（不持久化，进程重启即复位）
  // 数据库字段：是否置顶到列表顶部（对应 notes.is_pinned，1 置顶 / 0 普通）
  private boolean pinned = false;


  /**
   * 全参构造：一次性设置笔记全部业务字段。
   * @why 从数据库查询结果组装对象时使用；参数顺序必须与 BaseNote 构造函数严格一致，否则字段错位。
   */

  public Note() {
    super();
  }


  public Note(Long creation, Long lastModification, String title, String content, Integer archived,
      Integer trashed, String alarm, String recurrenceRule, Integer reminderFired, String latitude,
      String longitude,
      Category
          category, Integer locked, Integer checklist) {
    super(creation, lastModification, title, content, archived, trashed, alarm, reminderFired,
        recurrenceRule,
        latitude,
        longitude, category, locked, checklist);
  }


  /**
   * 拷贝构造：基于已有笔记创建副本（编辑前快照、撤销操作使用）。
   * @why 深拷贝避免两处编辑共享同一对象导致状态互相污染；passwordChecked 属运行时状态一并复制。
   */

  public Note(Note note) {
    super(note);
    setPasswordChecked(note.isPasswordChecked());
    setPinned(note.isPinned());//置顶状态随副本一起复制，避免编辑快照丢标记
  }


  /**
   * Parcel 构造（私有）：从 Parcel 逐字段还原笔记，与 writeToParcel 的写入顺序严格一一对应。
   * @why 顺序必须完全镜像 writeToParcel：先写先读，类型对应（String/Int/Parcelable/List），
   *      任一处增减字段都要两边同步修改，否则反序列化会字段错位或崩溃。
   */

  private Note(Parcel in) {
    setCreation(in.readString());
    setLastModification(in.readString());
    setTitle(in.readString());
    setContent(in.readString());
    setArchived(in.readInt());
    setTrashed(in.readInt());
    setAlarm(in.readString());
    setReminderFired(in.readInt());
    setRecurrenceRule(in.readString());
    setLatitude(in.readString());
    setLongitude(in.readString());
    setAddress(in.readString());
    super.setCategory(in.readParcelable(Category.class.getClassLoader()));
    setLocked(in.readInt());
    setChecklist(in.readInt());
    setPinned(in.readInt() == 1);//置顶标志：布尔以 1/0 传递
    in.readList(getAttachmentsList(), Attachment.class.getClassLoader());
  }

  /**
   * 获取附件列表（容器为 commons 模块的通用 Attachment 强转为本模块 Attachment）。
   * @why 直接强转是历史 Workaround（修复 issue #199），此前版本会逐个包装转换；
   *      强转依赖两个模块的 Attachment 结构兼容，存在 ClassCastException 风险，报告 3.x 可引用。
   */

  public List<Attachment> getAttachmentsList() {
//		List<Attachment> list = new ArrayList<>();
//		for (it.feio.android.omninotes.commons.models.Attachment attachment : super.getAttachmentsList()) {
//			if (attachment.getClass().equals(Attachment.class)) {
//				list.add((Attachment) attachment);
//			} else {
//				list.add(new Attachment(attachment));
//			}
//		}
//		return list;
    // FIXME This fixes https://github.com/federicoiosue/Omni-Notes/issues/199 but could introduce other issues
    return (List<Attachment>) super.getAttachmentsList();// 未做逐个类型包装，直接强转（潜在 ClassCastException 点）
  }

  public void setAttachmentsList(ArrayList<Attachment> attachmentsList) {
    super.setAttachmentsList(attachmentsList);
  }

  /**
   * 添加附件：把附件加入列表后回写父类字段。
   */
  public void addAttachment(Attachment attachment) {
    List<Attachment> attachmentsList = ((List<Attachment>) super.getAttachmentsList());// 取当前列表（强转同 getAttachmentsList）
    attachmentsList.add(attachment);//追加新附件
    setAttachmentsList(attachmentsList);//回写，保证父类字段指向同一列表
  }

  /**
   * 移除附件：从列表删除后回写父类字段。
   * @why 仅维护对象内列表，实体文件与数据库的清理由 SaveNoteTask.purgeRemovedAttachments 负责。
   */
  public void removeAttachment(Attachment attachment) {
    List<Attachment> attachmentsList = ((List<Attachment>) super.getAttachmentsList());//取当前列表
    attachmentsList.remove(attachment);//按对象移除
    setAttachmentsList(attachmentsList);//回写父类
  }

  /**
   * 获取编辑前的附件快照（旧列表）。
   * @why SaveNoteTask 用它做差集（旧列表 − 当前列表）判断哪些附件被移除，是附件清理的依据。
   */

  public List<Attachment> getAttachmentsListOld() {
    return (List<Attachment>) super.getAttachmentsListOld();
  }

  public void setAttachmentsListOld(ArrayList<Attachment> attachmentsListOld) {
    super.setAttachmentsListOld(attachmentsListOld);
  }

  /**
   * 是否已通过密码校验（运行时状态，不落库）。
   * @why 列表/详情刷新后需要恢复"已解锁"状态，避免每次操作都重复弹密码框。
   */

  public boolean isPasswordChecked() {
    return passwordChecked;
  }

  public void setPasswordChecked(boolean passwordChecked) {
    this.passwordChecked = passwordChecked;
  }

  /**
   * 是否置顶到笔记列表顶部。
   * @why 置顶只影响列表排序，不改变笔记归属（不像 archived/trashed 那样切换视图），
   *      因此单独建字段而不是复用现有标记；该值由 DbHelper 落库到 notes.is_pinned。
   */
  public boolean isPinned() {
    return pinned;
  }

  public void setPinned(boolean pinned) {
    this.pinned = pinned;
  }

  /**
   * 获取分类，并把 commons 模块的 BaseCategory 兜底转换为本模块 Category。
   * @why BaseNote 的 category 字段声明为 BaseCategory，直接强转可能失败；
   *      捕获 ClassCastException 后新建 Category 包装，保证上层拿到的始终是 Category 类型。
   */
  @Override
  public Category getCategory() {
    try {
      return (Category) super.getCategory();// 正常路径：父类存的已是本模块 Category
    } catch (ClassCastException e) {
      return new Category(super.getCategory());// 兜底：BaseCategory 包装为本模块 Category（id/name/description/color 复制）
    }
  }

  /**
   * 设置分类（做类型归一：BaseCategory 自动包装为 Category）。
   * @why 保证对象内 category 字段始终是本模块 Category，避免 getCategory 反复走异常兜底路径。
   */
  public void setCategory(Category category) {
    if (category != null && category.getClass().equals(BaseCategory.class)) {// 仅当实际类型是 BaseCategory 本身才需要包装
      setCategory(new Category(category));// 包装后递归设置（新对象已是 Category，不再进入本分支）
    }
    super.setCategory(category);//最终写入父类字段
  }

  /**
   * 从 JSON 反序列化笔记，并把附件重建为本模块 Attachment 类型。
   * @why 父类反序列化出的是 commons 模块的 BaseAttachment，不包装成 Attachment 会导致后续强转失败。
   */
  @Override
  public void buildFromJson(String jsonNote) {
    super.buildFromJson(jsonNote);
    // 父类 buildFromNote 只搬运 commons 里声明的字段，pinned 是本模块新增的，需单独解析回填：
    // 备份文件由 Gson 序列化 Note 本身生成，其中已含 pinned；旧备份无此字段时 Gson 取默认 false
    Note parsed = new Gson().fromJson(jsonNote, Note.class);
    if (parsed != null) {
      setPinned(parsed.isPinned());
    }
    List<Attachment> attachments = new ArrayList<>();//新建本模块附件列表
    for (BaseAttachment attachment : getAttachmentsList()) {// 遍历父类反序列化出的通用附件
      attachments.add(new Attachment(attachment));// 逐个包装为本模块 Attachment
    }
    setAttachmentsList(attachments);// 回写，替换父类中的通用附件列表
  }

  @Override
  public int describeContents() {
    return 0;
  }// Parcelable 约定：无特殊文件描述符时固定返回 0

  /**
   * 序列化笔记到 Parcel（写入顺序必须与 Parcel 构造的读取顺序严格一致）。
   * @why 布尔字段用 int（1/0）存储、时间戳转 String 存储，都是为跨进程传递保持类型稳定；
   *      分类与附件列表直接写 Parcelable/List，依赖其自身序列化实现。
   */
  @Override
  public void writeToParcel(Parcel parcel, int flags) {
    parcel.writeString(String.valueOf(getCreation()));//创建时间：转字符串写入
    parcel.writeString(String.valueOf(getLastModification()));//最后修改时间
    parcel.writeString(getTitle());//标题
    parcel.writeString(getContent());//正文
    parcel.writeInt(isArchived() ? 1 : 0);//归档标志：布尔转int
    parcel.writeInt(isTrashed() ? 1 : 0);//回收站标志
    parcel.writeString(getAlarm());//提醒时间（毫秒时间戳字符串）
    parcel.writeInt(isReminderFired() ? 1 : 0);//提醒是否已触发
    parcel.writeString(getRecurrenceRule());//重复规则（RRULE）
    parcel.writeString(String.valueOf(getLatitude()));//纬度
    parcel.writeString(String.valueOf(getLongitude()));//经度
    parcel.writeString(getAddress());//地址描述
    parcel.writeParcelable(getCategory(), 0);//分类：Parcelable 嵌套写入
    parcel.writeInt(isLocked() ? 1 : 0);// 锁定标志（加密笔记）
    parcel.writeInt(isChecklist() ? 1 : 0);//清单标志
    parcel.writeInt(isPinned() ? 1 : 0);//置顶标志
    parcel.writeList(getAttachmentsList());//附件列表（List 嵌套写入）
  }

}
