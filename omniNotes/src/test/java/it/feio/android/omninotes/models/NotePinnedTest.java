package it.feio.android.omninotes.models;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.os.Parcel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 置顶字段在两条持久化路径上的回归测试：Parcelable 跨进程传递、编辑快照的拷贝构造。
 * @why 项目开启了 unitTests.returnDefaultValues，真实 Parcel 在 JVM 下只会返回默认值，
 *      因此用一个按调用顺序记账的假 Parcel 来验证「写入与读取严格对称」这一最易出错处：
 *      若日后在 writeToParcel 或 Parcel 构造里单边增减字段，本用例会立即失败。
 */
public class NotePinnedTest {

  private List<Object> buffer;
  private int[] position;

  /**
   * 构造一个按调用顺序记账的假 Parcel：写入进队，读取出队。
   * @why writeToParcel 与 Note(Parcel) 的字段顺序必须一一对应，
   *      用队列可以把这个顺序约束变成可断言的测试事实。
   */
  private Parcel fakeParcel() {
    buffer = new ArrayList<>();
    position = new int[]{0};
    Parcel parcel = mock(Parcel.class);

    doAnswer(invocation -> {
      buffer.add(invocation.getArgument(0));
      return null;
    }).when(parcel).writeString(any());
    doAnswer(invocation -> {
      buffer.add(invocation.getArgument(0));
      return null;
    }).when(parcel).writeInt(anyInt());
    doAnswer(invocation -> {
      buffer.add(invocation.getArgument(0));
      return null;
    }).when(parcel).writeParcelable(any(), anyInt());
    doAnswer(invocation -> {
      buffer.add(invocation.getArgument(0));
      return null;
    }).when(parcel).writeList(any());

    doAnswer(invocation -> buffer.get(position[0]++)).when(parcel).readString();
    doAnswer(invocation -> buffer.get(position[0]++)).when(parcel).readInt();
    doAnswer(invocation -> buffer.get(position[0]++)).when(parcel).readParcelable(any());
    doAnswer(invocation -> null).when(parcel).readList(any(), any());

    return parcel;
  }

  @Test
  public void parcelRoundTripKeepsPinned() {
    Note note = new Note();
    note.setTitle("置顶笔记");
    note.setPinned(true);

    Parcel parcel = fakeParcel();
    note.writeToParcel(parcel, 0);
    Note restored = Note.CREATOR.createFromParcel(parcel);

    assertTrue("置顶状态应在 Parcel 往返后保留", restored.isPinned());
  }

  @Test
  public void parcelRoundTripKeepsUnpinned() {
    Note note = new Note();
    note.setTitle("普通笔记");

    Parcel parcel = fakeParcel();
    note.writeToParcel(parcel, 0);
    Note restored = Note.CREATOR.createFromParcel(parcel);

    assertFalse("未置顶的笔记往返后不应变成置顶", restored.isPinned());
  }

  @Test
  public void copyConstructorKeepsPinned() {
    Note original = new Note();
    original.setTitle("编辑前快照");
    original.setPinned(true);

    Note copy = new Note(original);

    assertTrue("拷贝构造应复制置顶状态，避免编辑快照丢标记", copy.isPinned());
  }
}
