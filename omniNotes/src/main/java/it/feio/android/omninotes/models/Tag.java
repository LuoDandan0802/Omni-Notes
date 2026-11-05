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
import it.feio.android.omninotes.commons.models.BaseTag;


/**
 * 标签模型：表示笔记正文中以 # 标记的标签，含文本与出现次数。
 * @why 继承 commons 模块 BaseTag 复用基础字段；实现 Parcelable 以便跨组件传递；
 *      toString 返回标签文本，供 Chips/Adapter 直接显示。
 */
public class Tag extends BaseTag implements Parcelable {

  /**
   * Parcel 构造（私有）：还原标签文本与计数，顺序与 writeToParcel 一致。
   */
  private Tag(Parcel in) {
    setText(in.readString());
    setCount(in.readInt());
  }


  /**
   * 无参构造：供框架反射创建空对象。
   */
  public Tag() {
    super();
  }


  public Tag(String text, Integer count) {
    super(text, count);
  }



  @Override
  public int describeContents() {
    return 0;
  }


  /**
   * 序列化标签到 Parcel（字段顺序与 Parcel 构造严格一致）。
   */
  @Override
  public void writeToParcel(Parcel parcel, int flags) {
    parcel.writeString(getText());// 标签文本
    parcel.writeInt(getCount());//出现次数
  }


  /**
   * 返回标签文本：Adapter/Chips 的显示值，也用于日志。
   */
  @Override
  public String toString() {
    return getText();
  }


  /*
   * Parcelable interface must also have a static field called CREATOR, which is an object implementing the
   * Parcelable.Creator interface. Used to un-marshal or de-serialize object from Parcel.
   */
  public static final Parcelable.Creator<Tag> CREATOR = new Parcelable.Creator<Tag>() {

    public Tag createFromParcel(Parcel in) {
      return new Tag(in);
    }


    public Tag[] newArray(int size) {
      return new Tag[size];
    }
  };
}
