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
import it.feio.android.omninotes.commons.models.BaseCategory;

/**
 * 分类模型：表示一个分类（文件夹/标签组），含名称、描述、颜色与笔记数量。
 * @why 继承 commons 模块 BaseCategory 复用基础字段；实现 Parcelable 以便在 Activity/Fragment
 *      之间传递分类对象（如导航抽屉、分类选择器）；toString 返回名称，供 Spinner/Adapter 直接显示。
 */

public class Category extends BaseCategory implements Parcelable {

  /**
   * Parcel 构造（私有）：逐字段还原分类，顺序与 writeToParcel 严格对应。
   */
  private Category(Parcel in) {
    setId(in.readLong());
    setName(in.readString());
    setDescription(in.readString());
    setColor(in.readString());
    setCount(in.readInt());
  }

  /**
   * 无参构造：供框架反射创建空对象后赋值。
   */
  public Category() {
    super();
  }

  public Category(BaseCategory category) {
    super(category.getId(), category.getName(), category.getDescription(), category.getColor());
  }

  /**
   * 由 BaseCategory 转换构造：把 commons 模块的通用分类包装为本模块 Category。
   * @why 跨模块反序列化（JSON、Parcel）得到的是 BaseCategory，需要统一为本模块类型。
   */
  public Category(Long id, String title, String description, String color) {
    super(id, title, description, color);
  }

  /**
   * 完整构造：id、名称、描述、颜色（count 由数据库统计后单独 set）。
   */
  public Category(Long id, String title, String description, String color, int count) {
    super(id, title, description, color, count);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  /**
   * 序列化分类到 Parcel（字段顺序必须与 Parcel 构造严格一致）。
   */
  @Override
  public void writeToParcel(Parcel parcel, int flags) {
    parcel.writeLong(getId());//分类id（主键）
    parcel.writeString(getName());//分类名称
    parcel.writeString(getDescription());//分类描述
    parcel.writeString(getColor());//颜色
    parcel.writeInt(getCount());//该分类下笔记数量（导航显示用）
  }

  /**
   * 返回分类名称：作为 Spinner/Adapter 的显示文本，也用于日志输出。
   */
  @Override
  public String toString() {
    return getName();
  }


  /*
   * Parcelable interface must also have a static field called CREATOR, which is an object implementing the
   * Parcelable.Creator interface. Used to un-marshal or de-serialize object from Parcel.
   */
  public static final Parcelable.Creator<Category> CREATOR = new Parcelable.Creator<>() {

    public Category createFromParcel(Parcel in) {
      return new Category(in);
    }

    public Category[] newArray(int size) {
      return new Category[size];
    }
  };
}
