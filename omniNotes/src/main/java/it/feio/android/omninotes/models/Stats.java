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

/**
 * 统计聚合模型：承载统计页（StatsActivity）展示的全部计数与篇幅指标。
 * @why 继承 StatsSingleNote 复用单条笔记的字数/字符统计字段，扩展出全局统计字段；
 *      由 DbHelper.getStats() 一次性查询组装，避免界面层多次查库。
 */
public class Stats extends StatsSingleNote {

  // ===== 笔记状态计数 =====
  private int notesActive;//活跃笔记数
  private int notesArchived;//归档笔记数
  private int notesTrashed;//回收站笔记数
  // ===== 提醒计数 =====
  private int reminders;//已设置提醒的笔记数
  private int remindersFutures;// 未来待触发的提醒数
  // ===== 特殊类型计数 =====
  private int notesChecklist;//清单型笔记数
  private int notesMasked;//锁定（加密）笔记数
  private int categories;//分类总数

  private int location;//带定位信息的笔记数

  // ===== 篇幅指标 =====
  private int wordsMax;// 单条最大词数
  private int wordsAvg;//平均词数
  private int charsMax;//单条最大字符数
  private int charsAvg;//平均字符数
  private long usageTime;//累计使用时长（毫秒）


  /**
   * 笔记总数 = 活跃 + 归档 + 回收站。
   * @why 三个状态互斥覆盖全部笔记，总数由明细现算而非单独存储，避免数据不一致。
   */
  // 标准 getter/setter：字段读写，无业务逻辑（供 Jackson/界面绑定使用）
  public int getNotesTotalNumber() {
    return notesActive + notesArchived + notesTrashed;
  }


  public int getNotesActive() {
    return notesActive;
  }


  public void setNotesActive(int notesActive) {
    this.notesActive = notesActive;
  }


  public int getNotesArchived() {
    return notesArchived;
  }


  public void setNotesArchived(int notesArchived) {
    this.notesArchived = notesArchived;
  }


  public int getNotesTrashed() {
    return notesTrashed;
  }


  public void setNotesTrashed(int notesTrashed) {
    this.notesTrashed = notesTrashed;
  }


  public int getReminders() {
    return reminders;
  }


  public void setReminders(int reminders) {
    this.reminders = reminders;
  }


  public int getRemindersFutures() {
    return remindersFutures;
  }


  public void setRemindersFutures(int remindersFutures) {
    this.remindersFutures = remindersFutures;
  }


  public int getNotesChecklist() {
    return notesChecklist;
  }


  public void setNotesChecklist(int notesChecklist) {
    this.notesChecklist = notesChecklist;
  }


  public int getNotesMasked() {
    return notesMasked;
  }


  public void setNotesMasked(int notesMasked) {
    this.notesMasked = notesMasked;
  }


  public int getCategories() {
    return categories;
  }


  public void setCategories(int categories) {
    this.categories = categories;
  }


  // 以下 getTags/getWords/getChars 均为父类 StatsSingleNote 字段的访问器重写，
  // 保持统计字段集中在 Stats 中统一读写
  @Override
  public int getTags() {
    return tags;
  }


  @Override
  public void setTags(int tags) {
    this.tags = tags;
  }


  @Override
  public int getWords() {
    return words;
  }


  @Override
  public void setWords(int words) {
    this.words = words;
  }


  @Override
  public int getChars() {
    return chars;
  }


  @Override
  public void setChars(int chars) {
    this.chars = chars;
  }


  public int getWordsMax() {
    return wordsMax;
  }


  public void setWordsMax(int wordsMax) {
    this.wordsMax = wordsMax;
  }


  public int getWordsAvg() {
    return wordsAvg;
  }


  public void setWordsAvg(int wordsAvg) {
    this.wordsAvg = wordsAvg;
  }


  public int getCharsMax() {
    return charsMax;
  }


  public void setCharsMax(int charsMax) {
    this.charsMax = charsMax;
  }


  public int getCharsAvg() {
    return charsAvg;
  }


  public void setCharsAvg(int charsAvg) {
    this.charsAvg = charsAvg;
  }


  public int getLocation() {
    return location;
  }


  public void setLocation(int location) {
    this.location = location;
  }


  public long getUsageTime() {
    return usageTime;
  }


  public void setUsageTime(long usageTime) {
    this.usageTime = usageTime;
  }
}
