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
package it.feio.android.omninotes;

///导入部分，把需要的东西搬出来。import static ...：静态导入；androidx.recyclerview...：引入了列表视图、分割线、滑动控制等核心 UI 组件；de.greenrobot.event.EventBus：引入了事件总线
import static android.content.DialogInterface.BUTTON_POSITIVE;
import static android.text.Html.fromHtml;
import static android.text.TextUtils.isEmpty;
import static androidx.core.view.ViewCompat.animate;
import static it.feio.android.omninotes.helpers.BuildHelper.isDebugBuild;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_FAB_TAKE_PHOTO;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_MERGE;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_POSTPONE;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_SEARCH_UNCOMPLETE_CHECKLISTS;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_SHORTCUT_WIDGET;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_WIDGET_SHOW_LIST;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_CATEGORY;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_NOTE;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_WIDGET;
import static it.feio.android.omninotes.utils.ConstantsBase.MENU_SORT_GROUP_ID;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_ENABLE_SWIPE;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_EXPANDED_VIEW;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FAB_EXPANSION_BEHAVIOR;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_ARCHIVED_IN_CATEGORIES;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_FILTER_PAST_REMINDERS;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_NAVIGATION;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SORTING_COLUMN;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_WIDGET_PREFIX;
import static it.feio.android.omninotes.utils.Navigation.checkNavigation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MenuItem.OnActionExpandListener;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import androidx.core.util.Pair;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pixplicity.easyprefs.library.Prefs;
import de.greenrobot.event.EventBus;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import it.feio.android.omninotes.async.bus.CategoriesUpdatedEvent;
import it.feio.android.omninotes.async.bus.NavigationUpdatedNavDrawerClosedEvent;
import it.feio.android.omninotes.async.bus.NotesLoadedEvent;
import it.feio.android.omninotes.async.bus.NotesMergeEvent;
import it.feio.android.omninotes.async.bus.NotesUpdatedEvent;
import it.feio.android.omninotes.async.bus.PasswordRemovedEvent;
import it.feio.android.omninotes.async.notes.NoteLoaderTask;
import it.feio.android.omninotes.async.notes.NoteProcessorArchive;
import it.feio.android.omninotes.async.notes.NoteProcessorCategorize;
import it.feio.android.omninotes.async.notes.NoteProcessorDelete;
import it.feio.android.omninotes.async.notes.NoteProcessorTrash;
import it.feio.android.omninotes.databinding.FragmentListBinding;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.NotesHelper;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.ONStyle;
import it.feio.android.omninotes.models.PasswordValidator;
import it.feio.android.omninotes.models.Tag;
import it.feio.android.omninotes.models.UndoBarController;
import it.feio.android.omninotes.models.adapters.CategoryRecyclerViewAdapter;
import it.feio.android.omninotes.models.adapters.NoteAdapter;
import it.feio.android.omninotes.models.listeners.OnViewTouchedListener;
import it.feio.android.omninotes.models.listeners.RecyclerViewItemClickSupport;
import it.feio.android.omninotes.models.views.Fab;
import it.feio.android.omninotes.utils.AnimationsHelper;
import it.feio.android.omninotes.utils.IntentChecker;
import it.feio.android.omninotes.utils.KeyboardUtils;
import it.feio.android.omninotes.utils.Navigation;
import it.feio.android.omninotes.utils.PasswordHelper;
import it.feio.android.omninotes.utils.ReminderHelper;
import it.feio.android.omninotes.utils.TagsHelper;
import it.feio.android.omninotes.utils.TextHelper;
import it.feio.android.pixlui.links.UrlCompleter;
import it.feio.android.simplegallery.util.BitmapUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;


public class ListFragment extends BaseFragment implements OnViewTouchedListener,
    UndoBarController.UndoListener {
  ///  这是常量与请求码，方便身份区分和防止串线
  private static final int REQUEST_CODE_CATEGORY = 1;
  private static final int REQUEST_CODE_CATEGORY_NOTES = 2;
  private static final int REQUEST_CODE_ADD_ALARMS = 3;
  public static final String LIST_VIEW_POSITION = "listViewPosition";
  public static final String LIST_VIEW_POSITION_OFFSET = "listViewPositionOffset";
 ///核心 UI 与状态对象
  private FragmentListBinding binding;

  private final List<Note> selectedNotes = new ArrayList<>();
  private SearchView searchView;
  private MenuItem searchMenuItem;
  private Menu menu;
  private AnimationDrawable jinglesAnimation;

    /// 列表滑动与 ActionMode（多选模式），listViewPosition表示列表滚动位置，当点进某一便签退出时自动回滚到此便签；actionMode指上下文操作栏，长按便签时出现顶部标题栏（删除。归档）
  private int listViewPosition;
  private int listViewPositionOffset = 16;
  private boolean sendToArchive;
  private ListFragment mFragment;
  private ActionMode actionMode;
  private boolean keepActionMode = false;

  // Undo archive/trash
  /// 撤销（Undo）机制的数据结构
  private boolean undoTrash = false;
  private boolean undoArchive = false;
  private boolean undoCategorize = false;
  private Category undoCategorizeCategory = null;
  private final SortedMap<Integer, Note> undoNotesMap = new TreeMap<>();
  // Used to remember removed categories from notes
  private final Map<Note, Category> undoCategoryMap = new HashMap<>();
  // Used to remember archived state from notes
  private final Map<Note, Boolean> undoArchivedMap = new HashMap<>();

  // Search variables
  ///搜索状态记忆
  private String searchQuery;
  private String searchQueryInstant;
  private String searchTags;
  private boolean searchUncompleteChecklists;
  private boolean goBackOnToggleSearchLabel = false;
  private boolean searchLabelActive = false;
    ///适配器与控制器
  private NoteAdapter listAdapter;  // 把 Note 数据变成屏幕上能看见的卡片
  private UndoBarController ubc;  // 控制底部“撤销”条的弹出和隐藏
  private Fab fab;   // 右下角那个可以展开的悬浮按钮（新建便签、拍照等）
  private MainActivity mainActivity;   // 宿主 Activity 的引用

    /**
     * @description Fragment 生命周期初始化回调，用于设置基础状态、菜单权限及事件总线注册。
     * @why
     * 1. 保存当前实例引用 (mFragment) 供内部调用；
     * 2. 开启菜单支持以便渲染列表页的排序/搜索按钮；
     * 3. 开启状态保留 (setRetainInstance)，防止横竖屏切换导致列表数据重新加载；
     * 4. 注册 EventBus 并设置高优先级，监听便签加载、分类更新等全局事件。
     */
  @Override
  public void onCreate(Bundle savedInstanceState) { // 当 Fragment 被创建时，系统会最先调用这个方法
    super.onCreate(savedInstanceState);
    mFragment = this;
    setHasOptionsMenu(true);
    setRetainInstance(true);
    EventBus.getDefault().register(this, 1);
  }

    /**
     * @description Fragment 生命周期销毁回调，负责资源清理与事件总线注销。
     * @why 必须与 onCreate 中的注册操作严格配对，调用 unregister 注销 EventBus，否则该 Fragment 实例会被 EventBus 持续持有，导致无法被 GC 回收（内存泄漏），甚至在页面销毁后触发空指针异常。
     */
  @Override
  public void onDestroy() {  // 生命周期的终点
    super.onDestroy();
    EventBus.getDefault().unregister(this); //从 EventBus 观察者池中移除当前对象，与 onCreate 中的 register 严格成对出现，释放引用
  }


  @Override
  /**
   * @description 实例化并配置列表页的视图层级，包括状态恢复、RecyclerView 驱动绑定及视觉特效。
   * @why
   * 状态恢复：从 savedInstanceState 中恢复滚动位置与搜索条件，确保横竖屏切换后状态无缝衔接；
   * 核心配置：初始化 ViewBinding 获取根视图，为列表挂载排版经理（LayoutManager）、分割线（ItemDecoration）与增删动画（ItemAnimator）；
   * 体验优化：开启固定尺寸提升渲染性能，并绑定 EmptyView 以在无数据时展示占位图。
   * 把写好的 XML 布局文件“变成”屏幕上真实存在的控件，并且为底层的列表（RecyclerView）配置好所有必备的“零件”
   */
  public View onCreateView(LayoutInflater inflater, ViewGroup container,
      Bundle savedInstanceState) {
    if (savedInstanceState != null) {
      if (savedInstanceState.containsKey(LIST_VIEW_POSITION)) { //检查 Bundle 对象中是否包含键为 LIST_VIEW_POSITION（值为 "listViewPosition"）的数据，防止空指针异常
        listViewPosition = savedInstanceState.getInt(LIST_VIEW_POSITION); //恢复列表滚到了第几行
        listViewPositionOffset = savedInstanceState.getInt(LIST_VIEW_POSITION_OFFSET);  // istViewPositionOffset = savedInstanceState.getInt(LIST_VIEW_POSITION_OFFSET);
        searchQuery = savedInstanceState.getString("searchQuery"); // 恢复当前的搜索过滤词
        searchTags = savedInstanceState.getString("searchTags"); // 从 Bundle 中读取字符串数据，恢复当前的搜索标签
      }
      keepActionMode = false; // 防止页面错乱和内存泄漏。只要屏幕一转，不管之前是不是多选状态，统统给我退出多选，恢复成普通的列表
    }
    binding = FragmentListBinding.inflate(inflater, container, false); // 参数 false 表示暂不将该视图添加到父容器 container 中，交由 Fragment 生命周期管理。把 fragment_list.xml 这个图纸实例化
    View view = binding.getRoot(); // 拿到根视图，相当于拿到所有的内容

    binding.list.setHasFixedSize(true); // 标记 RecyclerView 的尺寸为固定大小，所以每次添加内容时都不用再重新量宽高了
    LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getContext());
    binding.list.setLayoutManager(linearLayoutManager); // 将线性排版管理器绑定到 RecyclerView

    DividerItemDecoration dividerItemDecoration = new DividerItemDecoration( // DividerItemDecoration 是 Android 提供的一个专门负责画分割线的“刷子”，你需要去哪面墙上面画画，划线的方向是横着还是竖着
        binding.list.getContext(),
        linearLayoutManager.getOrientation());
    dividerItemDecoration
        .setDrawable(getResources().getDrawable(R.drawable.fragment_list_item_divider)); //设置线条样式，调用 setDrawable(Drawable) 方法为装饰器设置用于绘制的图形对象
    binding.list.addItemDecoration(dividerItemDecoration); //将 ItemDecoration 实例注册到 RecyclerView 中

    RecyclerView.ItemAnimator itemAnimator = new DefaultItemAnimator(); // RecyclerView 内置了动画系统，指定执行者，负责管理以后列表卡片的出现、消失、移动时的特效
    itemAnimator.setAddDuration(1000); // 设置 Item 被添加到列表时的动画持续时间为 1000 毫秒
    itemAnimator.setRemoveDuration(1000); // 设置 Item 从列表中移除时的动画持续时间为 1000 毫秒
    binding.list.setItemAnimator(itemAnimator); // 将配置好的 ItemAnimator（执行者） 绑定到 RecyclerView 实例上，以后的列表的增删就归这名执行者指挥了

    // Replace listview with Mr. Jingles if it is empty
    binding.list.setEmptyView(binding.emptyList); // 如果列表为空，，就显示emptyView（通常为图片或者文字）

    return view;
  }

    /**
     * @description 宿主 Activity 就绪后的回调，负责获取宿主引用、恢复临时导航状态并启动核心业务初始化。
     * @why 利用 Activity 完全建好的黄金节点，确保组件交互安全，并防止屏幕旋转导致从 Widget 进入的上下文丢失，最后通过 init() 正式拉起列表加载。
     */
  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    mainActivity = (MainActivity) getActivity(); //通过 getActivity() 获取与当前 Fragment 关联的宿主 Activity
    if (mainActivity!= null && savedInstanceState != null) {
      mainActivity.navigationTmp = savedInstanceState.getString("navigationTmp");//从 Bundle 中取出临时导航标记，重新塞回给 MainActivity，防止 Widget 跳转状态丢失
    }
    init(); // 启动
  }


  private void init() {
    initEasterEgg();
    initListView();
    ubc = new UndoBarController(binding.undobar.getRoot(), this);

    initNotesList(mainActivity.getIntent());
    initFab();
    initTitle();
  }


  private void initFab() {
    fab = new Fab(binding.fab.getRoot(), binding.list,
        Prefs.getBoolean(PREF_FAB_EXPANSION_BEHAVIOR, false));
    fab.setOnFabItemClickedListener(id -> {
      View v = mainActivity.findViewById(id);
      switch (id) {
        case R.id.fab_camera:
          Intent i = mainActivity.getIntent();
          i.setAction(ACTION_FAB_TAKE_PHOTO);
          mainActivity.setIntent(i);
          editNote(new Note(), v);
          break;
        case R.id.fab_checklist:
          Note note = new Note();
          note.setChecklist(true);
          editNote(note, v);
          break;
        default:
          editNote(new Note(), v);
      }
    });
  }


  boolean closeFab() {
    if (fab != null && fab.isExpanded()) {
      fab.performToggle();
      return true;
    }
    return false;
  }


  /**
   * Activity title initialization based on navigation
   */
  private void initTitle() {
    String[] navigationList = getResources().getStringArray(R.array.navigation_list);
    String[] navigationListCodes = getResources().getStringArray(R.array.navigation_list_codes);
    String navigation = mainActivity.navigationTmp != null
        ? mainActivity.navigationTmp
        : Prefs.getString (PREF_NAVIGATION, navigationListCodes[0]);
    int index = Arrays.asList(navigationListCodes).indexOf(navigation);
    String title;
    // If is a traditional navigation item
    if (index >= 0 && index < navigationListCodes.length) {
      title = navigationList[index];
    } else {
      Category category = DbHelper.getInstance().getCategory(Long.parseLong(navigation));
      title = category != null ? category.getName() : "";
    }
    title = title == null ? getString(R.string.title_activity_list) : title;
    mainActivity.setActionBarTitle(title);
  }


  /**
   * Starts a little animation on Mr.Jingles!
   */
  private void initEasterEgg() {
    binding.emptyList.setOnClickListener(v -> {
      if (jinglesAnimation == null) {
        jinglesAnimation = (AnimationDrawable) binding.emptyList.getCompoundDrawables()[1];
        binding.emptyList.post(() -> {
          if (jinglesAnimation != null) {
            jinglesAnimation.start();
          }
        });
      } else {
        stopJingles();
      }
    });
  }


  private void stopJingles() {
    if (jinglesAnimation != null) {
      jinglesAnimation.stop();
      jinglesAnimation = null;
      binding.emptyList
          .setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.jingles_animation, 0, 0);

    }
  }


  @Override
  public void onPause() {
    super.onPause();
    searchQueryInstant = searchQuery;
    stopJingles();
    Crouton.cancelAllCroutons();
    closeFab();
    if (!keepActionMode) {
      commitPending();
      if (getActionMode() != null) {
        getActionMode().finish();
      }
    }
  }


  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    refreshListScrollPosition();
    outState.putInt("listViewPosition", listViewPosition);
    outState.putInt(LIST_VIEW_POSITION_OFFSET, listViewPositionOffset);
    outState.putString("searchQuery", searchQuery);
    outState.putString("searchTags", searchTags);
  }


  private void refreshListScrollPosition() {
    if(binding != null) {
      listViewPosition = ((LinearLayoutManager) binding.list.getLayoutManager())
          .findFirstVisibleItemPosition();
      View v = binding.list.getChildAt(0);
      listViewPositionOffset =
          (v == null) ? (int) getResources().getDimension(R.dimen.vertical_margin) : v.getTop();
    }
  }


  @Override
  public void onResume() {
    super.onResume();
    if (mainActivity.prefsChanged) {
      mainActivity.prefsChanged = false;
      init();
    } else if (Intent.ACTION_SEARCH.equals(mainActivity.getIntent().getAction())) {
      initNotesList(mainActivity.getIntent());
    }
  }


  private final class ModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      // Inflate the menu for the CAB
      MenuInflater inflater = mode.getMenuInflater();
      inflater.inflate(R.menu.menu_list, menu);
      actionMode = mode;
      fab.setAllowed(isFabAllowed());
      fab.hideFab();
      return true;
    }


    @Override
    public void onDestroyActionMode(ActionMode mode) {
      // Here you can make any necessary updates to the activity when
      // the CAB is removed. By default, selected items are
      // deselected/unchecked.
      for (int i = 0; i < listAdapter.getSelectedItems().size(); i++) {
        int key = listAdapter.getSelectedItems().keyAt(i);
      }

      selectedNotes.clear();
      listAdapter.clearSelectedItems();
      listAdapter.notifyDataSetChanged();

      fab.setAllowed(isFabAllowed(true));
      if (undoNotesMap.size() == 0) {
        fab.showFab();
      }

      actionMode = null;
      LogDelegate.d("Closed multiselection contextual menu");
    }


    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
      prepareActionModeMenu();
      return true;
    }


    @Override
    public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
      Integer[] protectedActions = {R.id.menu_select_all, R.id.menu_merge};
      if (!Arrays.asList(protectedActions).contains(item.getItemId())) {
        mainActivity.requestPassword(mainActivity, getSelectedNotes(),
            passwordConfirmed -> {
              if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
                performAction(item, mode);
              }
            });
      } else {
        performAction(item, mode);
      }
      return true;
    }
  }


  public void finishActionMode() {
    if (getActionMode() != null) {
      getActionMode().finish();
    }
  }


  /**
   * Manage check/uncheck of notes in list during multiple selection phase
   */
  private void toggleListViewItem(View view, int position) {
    Note note = listAdapter.getItem(position);
    LinearLayout cardLayout = view.findViewById(R.id.card_layout);
    if (!getSelectedNotes().contains(note)) {
      getSelectedNotes().add(note);
      listAdapter.addSelectedItem(position);
      cardLayout.setBackgroundColor(getResources().getColor(R.color.list_bg_selected));
    } else {
      getSelectedNotes().remove(note);
      listAdapter.removeSelectedItem(position);
      listAdapter.restoreDrawable(note, cardLayout);
    }
    prepareActionModeMenu();

    if (getSelectedNotes().isEmpty()) {
      finishActionMode();
    }

  }


  /**
   * Notes list initialization. Data, actions and callback are defined here.
   */
  private void initListView() {
    // Note long click to start CAB mode
    RecyclerViewItemClickSupport.addTo(binding.list)
        // Note single click listener managed by the activity itself
        .setOnItemClickListener((recyclerView, position, view) -> {
          if (getActionMode() == null) {
            editNote(listAdapter.getItem(position), view);
            return;
          }
          // If in CAB mode
          toggleListViewItem(view, position);
          setCabTitle();
        }).setOnItemLongClickListener((recyclerView, position, view) -> {
      if (getActionMode() != null) {
        return false;
      }
      // Start the CAB using the ActionMode.Callback defined above
      mainActivity.startSupportActionMode(new ModeCallback());
      toggleListViewItem(view, position);
      setCabTitle();
      return true;
    });

    binding.listRoot.setOnViewTouchedListener(this);
  }


  /**
   * Retrieves from the single listview note item the element to be zoomed when opening a note
   */
  private ImageView getZoomListItemView(View view, Note note) {
    View targetView = null;
    if (!note.getAttachmentsList().isEmpty()) {
      targetView = view.findViewById(R.id.attachmentThumbnail);
    }
    if (targetView == null && note.getCategory() != null) {
      targetView = view.findViewById(R.id.category_marker);
    }
    if (targetView == null) {
      targetView = new ImageView(mainActivity);
      targetView.setBackgroundColor(Color.WHITE);
    }
    targetView.setDrawingCacheEnabled(true);
    targetView.buildDrawingCache();
    Bitmap bmp = targetView.getDrawingCache();
    binding.expandedImage.setBackgroundColor(BitmapUtils.getDominantColor(bmp));

    return binding.expandedImage;
  }


  /**
   * Listener that fires note opening once the zooming animation is finished
   */
  private AnimatorListenerAdapter buildAnimatorListenerAdapter(final Note note) {
    return new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        editNote2(note);
      }
    };
  }


  @Override
  public void onViewTouchOccurred(MotionEvent ev) {
    LogDelegate.v("Notes list: onViewTouchOccurred " + ev.getAction());
    commitPending();
  }


  @Override
  public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
    inflater.inflate(R.menu.menu_list, menu);
    super.onCreateOptionsMenu(menu, inflater);
    this.menu = menu;
    initSearchView(menu);
  }


  private void initSortingSubmenu() {
    final String[] arrayDb = getResources().getStringArray(R.array.sortable_columns);
    final String[] arrayDialog = getResources()
        .getStringArray(R.array.sortable_columns_human_readable);
    int selected = Arrays.asList(arrayDb).indexOf(Prefs.getString(PREF_SORTING_COLUMN, arrayDb[0]));

    SubMenu sortMenu = this.menu.findItem(R.id.menu_sort).getSubMenu();
    for (int i = 0; i < arrayDialog.length; i++) {
      if (sortMenu.findItem(i) == null) {
        sortMenu.add(MENU_SORT_GROUP_ID, i, i, arrayDialog[i]);
      }
      if (i == selected) {
        sortMenu.getItem(i).setChecked(true);
      }
    }
    sortMenu.setGroupCheckable(MENU_SORT_GROUP_ID, true, true);
  }


  @Override
  public void onPrepareOptionsMenu(Menu menu) {
    setActionItemsVisibility(menu, false);
  }


  private void prepareActionModeMenu() {
    Menu menu = getActionMode().getMenu();
    int navigation = Navigation.getNavigation();
    boolean showArchive =
        navigation == Navigation.NOTES || navigation == Navigation.REMINDERS || navigation ==
            Navigation.UNCATEGORIZED || navigation == Navigation.CATEGORY;
    boolean showUnarchive =
        navigation == Navigation.ARCHIVE || navigation == Navigation.UNCATEGORIZED ||
            navigation == Navigation.CATEGORY;

    if (navigation == Navigation.TRASH) {
      menu.findItem(R.id.menu_untrash).setVisible(true);
      menu.findItem(R.id.menu_delete).setVisible(true);
    } else {
      if (getSelectedCount() == 1) {
        menu.findItem(R.id.menu_share).setVisible(true);
        menu.findItem(R.id.menu_merge).setVisible(false);
        menu.findItem(R.id.menu_archive)
            .setVisible(showArchive && !getSelectedNotes().get(0).isArchived
                ());
        menu.findItem(R.id.menu_unarchive)
            .setVisible(showUnarchive && getSelectedNotes().get(0).isArchived
                ());
      } else {
        menu.findItem(R.id.menu_share).setVisible(false);
        menu.findItem(R.id.menu_merge).setVisible(true);
        menu.findItem(R.id.menu_archive).setVisible(showArchive);
        menu.findItem(R.id.menu_unarchive).setVisible(showUnarchive);

      }
      menu.findItem(R.id.menu_add_reminder).setVisible(true);
      menu.findItem(R.id.menu_category).setVisible(true);
      menu.findItem(R.id.menu_uncomplete_checklists).setVisible(false);
      menu.findItem(R.id.menu_tags).setVisible(true);
      menu.findItem(R.id.menu_trash).setVisible(true);
    }
    menu.findItem(R.id.menu_select_all).setVisible(true);

    setCabTitle();
  }


  private int getSelectedCount() {
    return getSelectedNotes().size();
  }


  private void setCabTitle() {
    if (getActionMode() != null) {
      int title = getSelectedCount();
      getActionMode().setTitle(String.valueOf(title));
    }
  }


  /**
   * SearchView initialization. It's a little complex because it's not using SearchManager but is
   * implementing on its own.
   */
  @SuppressLint("NewApi")
  private void initSearchView(final Menu menu) {

    // Prevents some mysterious NullPointer on app fast-switching
    if (mainActivity == null) {
      return;
    }

    // Save item as class attribute to make it collapse on drawer opening
    searchMenuItem = menu.findItem(R.id.menu_search);

    Bundle args = getArguments();
    if (args != null && args.getBoolean("setSearchFocus")) {
      searchMenuItem.expandActionView();
      KeyboardUtils.hideKeyboard(this.getView());
    }

    // Associate searchable configuration with the SearchView
    SearchManager searchManager = (SearchManager) mainActivity
        .getSystemService(Context.SEARCH_SERVICE);
    searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
    searchView.setSearchableInfo(searchManager.getSearchableInfo(mainActivity.getComponentName()));
    searchView.setImeOptions(EditorInfo.IME_ACTION_SEARCH);

    // Expands the widget hiding other actionbar icons
    searchView.setOnQueryTextFocusChangeListener(
        (v, hasFocus) -> setActionItemsVisibility(menu, hasFocus));

    searchMenuItem.setOnActionExpandListener(new OnActionExpandListener() {

          boolean searchPerformed = false;


          @Override
          public boolean onMenuItemActionCollapse(MenuItem item) {
            // Reinitialize notes list to all notes when search is collapsed
            searchQuery = null;
            if (binding.searchLayout.getVisibility() == View.VISIBLE) {
              toggleSearchLabel(false);
            }
            mainActivity.getIntent().setAction(Intent.ACTION_MAIN);
            initNotesList(mainActivity.getIntent());
            mainActivity.supportInvalidateOptionsMenu();
            return true;
          }


          @Override
          public boolean onMenuItemActionExpand(MenuItem item) {
            commitPending();
            searchView.setOnQueryTextListener(new OnQueryTextListener() {
              @Override
              public boolean onQueryTextSubmit(String arg0) {

                return Prefs.getBoolean("settings_instant_search", false);
              }


              @Override
              public boolean onQueryTextChange(String pattern) {
                if (Prefs.getBoolean("settings_instant_search", false)
                    && searchPerformed && mFragment.isAdded()) {
                  searchTags = null;
                  searchQuery = pattern;
                  NoteLoaderTask.getInstance().execute("getNotesByPattern", pattern);
                  return true;
                } else {
                  searchPerformed = true;
                  return false;
                }
              }
            });
            return true;
          }
        });
  }


  private void setActionItemsVisibility(Menu menu, boolean searchViewHasFocus) {
    boolean drawerOpen = mainActivity.getDrawerLayout() != null
        && mainActivity.getDrawerLayout().isDrawerOpen(GravityCompat.START);
    boolean expandedView = Prefs.getBoolean(PREF_EXPANDED_VIEW, true);

    int navigation = Navigation.getNavigation();
    boolean navigationReminders = navigation == Navigation.REMINDERS;
    boolean navigationTrash = navigation == Navigation.TRASH;
    boolean navigationCategory = navigation == Navigation.CATEGORY;

    boolean filterPastReminders = Prefs.getBoolean(PREF_FILTER_PAST_REMINDERS, true);
    boolean filterArchivedInCategory = navigationCategory && Prefs
        .getBoolean(PREF_FILTER_ARCHIVED_IN_CATEGORIES + Navigation.getCategory(), false);

    if (isFabAllowed()) {
      fab.setAllowed(true);
      fab.showFab();
    } else {
      fab.setAllowed(false);
      fab.hideFab();
    }
    menu.findItem(R.id.menu_search).setVisible(!drawerOpen);
    menu.findItem(R.id.menu_filter)
        .setVisible(!drawerOpen && !filterPastReminders && navigationReminders &&
            !searchViewHasFocus);
    menu.findItem(R.id.menu_filter_remove)
        .setVisible(!drawerOpen && filterPastReminders && navigationReminders
            && !searchViewHasFocus);
    menu.findItem(R.id.menu_filter_category).setVisible(!drawerOpen && !filterArchivedInCategory &&
        navigationCategory && !searchViewHasFocus);
    menu.findItem(R.id.menu_filter_category_remove)
        .setVisible(!drawerOpen && filterArchivedInCategory && navigationCategory && !searchViewHasFocus);
    menu.findItem(R.id.menu_sort)
        .setVisible(!drawerOpen && !navigationReminders && !searchViewHasFocus);
    menu.findItem(R.id.menu_expanded_view)
        .setVisible(!drawerOpen && !expandedView && !searchViewHasFocus);
    menu.findItem(R.id.menu_contracted_view)
        .setVisible(!drawerOpen && expandedView && !searchViewHasFocus);
    menu.findItem(R.id.menu_empty_trash).setVisible(!drawerOpen && navigationTrash);
    menu.findItem(R.id.menu_uncomplete_checklists).setVisible(searchViewHasFocus);
    menu.findItem(R.id.menu_tags).setVisible(searchViewHasFocus);
  }


  @Override
  public boolean onOptionsItemSelected(final MenuItem item) {
    Integer[] protectedActions = {R.id.menu_empty_trash};
    if (Arrays.asList(protectedActions).contains(item.getItemId())) {
      mainActivity.requestPassword(mainActivity, getSelectedNotes(), passwordConfirmed -> {
        if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
          performAction(item, null);
        }
      });
    } else {
      performAction(item, null);
    }
    return super.onOptionsItemSelected(item);
  }


  /**
   * Performs one of the ActionBar button's actions after checked notes protection
   */
    /**
     * @description 列表页菜单动作的路由分发中心，根据当前模式分发普通操作或批量操作。
     * @why
     * 1. 统一收口防抖校验，防止用户高频连点引发重复弹窗或数据并发冲突；
     * 2. 通过 actionMode 状态实现双重路由：null 走单条/全局过滤导航，非 null 走多选批量处理；
     * 3. 末尾统一拦截排序子菜单点击，避免 switch 分支过度臃肿，保持代码整洁。
     */
  public void performAction(MenuItem item, ActionMode actionMode) {
    if (isOptionsItemFastClick()) { // 就是防止用户疯狂点击。该方法内部通过记录上一次点击的时间戳，与当前系统时间对比，若间隔小于设定的阈值（1秒），则判定为无效的快速点击，直接 return 终止方法执行，从而保证 UI 交互的幂等性与安全性
      return;
    }

    if (actionMode == null) {
      switch (item.getItemId()) {
        case android.R.id.home: // android.R.id.home 是 Android 系统自带的“左上角主页/返回”按钮的专属 ID
         // 取反，如果左上角是打开的，再次点击就需要关闭，如果是关闭的，下次点击就需要打开
          if (mainActivity.getDrawerLayout().isDrawerOpen(GravityCompat.START)) {
            mainActivity.getDrawerLayout().closeDrawer(GravityCompat.START);
          } else {
            mainActivity.getDrawerLayout().openDrawer(GravityCompat.START);
          }
          break;
          /// 提醒过滤开关（成对出现）
        case R.id.menu_filter:
          filterReminders(true);
          break;
        case R.id.menu_filter_remove:
          filterReminders(false);
          break;
          /// 分类归档过滤开关（成对出现）
        case R.id.menu_filter_category:
          filterCategoryArchived(true);
          break;
        case R.id.menu_filter_category_remove:
          filterCategoryArchived(false);
          break;
          /// 未完成清单过滤
        case R.id.menu_uncomplete_checklists:
          item.setVisible(false);
          filterByUncompleteChecklists();
          break;
          /// 标签过滤与排序
        case R.id.menu_tags:
          filterByTags();
          break;
        case R.id.menu_sort:
          initSortingSubmenu();
          break;
          /// 视图切换（展开/收缩）
        case R.id.menu_expanded_view:
          switchNotesView();
          break;
        case R.id.menu_contracted_view:
          switchNotesView();
          break;
          /// 清空回收站
        case R.id.menu_empty_trash:
          emptyTrash();
          break;
        case R.id.menu_search:
          // Nothing to do, it's all managed by SearchView component
          break;
        default:
          LogDelegate.e("Wrong element choosen: " + item.getItemId());
      }
    } else {
      switch (item.getItemId()) {
          /// 批量修改分类与标签
        case R.id.menu_category:
          categorizeNotes();
          break;
        case R.id.menu_tags:
          tagNotes();
          break;
          /// 分享与合并
        case R.id.menu_share:
          share();
          break;
        case R.id.menu_merge:
          merge();
          break;
          /// 批量归档与回收站操作（成对出现）
        case R.id.menu_archive:
          archiveNotes(true);
          break;
        case R.id.menu_unarchive:
          archiveNotes(false);
          break;
        case R.id.menu_trash:
          trashNotes(true);
          break;
        case R.id.menu_untrash:
          trashNotes(false);
          break;
          ///彻底删除
        case R.id.menu_delete:
          deleteNotes();
          break;
          ///全选与批量设置提醒
        case R.id.menu_select_all:
          selectAllNotes();
          break;
        case R.id.menu_add_reminder:
          addReminders();
          break;
//                case R.ID.menu_synchronize:
//                    synchronizeSelectedNotes();
//                    break;
        default:
          LogDelegate.e("Wrong element choosen: " + item.getItemId());
      }
    }

    checkSortActionPerformed(item); //统一检查并处理排序子菜单的点击事件。
  }


  private void addReminders() {
    Intent intent = new Intent(OmniNotes.getAppContext(), SnoozeActivity.class);
    intent.setAction(ACTION_POSTPONE);
    intent.putExtra(INTENT_NOTE, selectedNotes.toArray(new Note[selectedNotes.size()]));
    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    startActivityForResult(intent, REQUEST_CODE_ADD_ALARMS);
  }


  private void switchNotesView() {
    boolean expandedView = Prefs.getBoolean(PREF_EXPANDED_VIEW, true);
    Prefs.edit().putBoolean(PREF_EXPANDED_VIEW, !expandedView).apply();
    searchQueryInstant = searchQuery;
    // Change list view
    initNotesList(mainActivity.getIntent());
    // Called to switch menu voices
    mainActivity.supportInvalidateOptionsMenu();
  }


  void editNote(final Note note, final View view) {
    if (note.isLocked() && !Prefs.getBoolean("settings_password_access", false)) {
      PasswordHelper.requestPassword(mainActivity, passwordConfirmed -> {
        if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
          note.setPasswordChecked(true);
          AnimationsHelper.zoomListItem(mainActivity, view, getZoomListItemView(view, note),
              binding.listRoot, buildAnimatorListenerAdapter(note));
        }
      });
    } else {
      AnimationsHelper.zoomListItem(mainActivity, view, getZoomListItemView(view, note),
          binding.listRoot, buildAnimatorListenerAdapter(note));
    }
  }


  void editNote2(Note note) {
    if (note.get_id() == null) {
      LogDelegate.d("Adding new note");
      // if navigation is a category it will be set into note
      try {
        if (checkNavigation(Navigation.CATEGORY) || !isEmpty(mainActivity.navigationTmp)) {
          String categoryId = ObjectUtils.defaultIfNull(mainActivity.navigationTmp,
              Navigation.getCategory().toString());
          note.setCategory(DbHelper.getInstance().getCategory(Long.parseLong(categoryId)));
        }
      } catch (NumberFormatException e) {
        LogDelegate.v("Maybe was not a category!");
      }
    } else {
      LogDelegate.d("Editing note with ID: " + note.get_id());
    }

    // Current list scrolling position is saved to be restored later
    refreshListScrollPosition();

    // Fragments replacing
    mainActivity.switchToDetail(note);
  }


  @Override
  public// Used to show a Crouton dialog after saved (or tried to) a note
  void onActivityResult(int requestCode, final int resultCode, Intent intent) {
    super.onActivityResult(requestCode, resultCode, intent);
    switch (requestCode) {
      case REQUEST_CODE_CATEGORY:
        // Dialog retarded to give time to activity's views of being completely initialized
        // The dialog style is choosen depending on result code
        switch (resultCode) {
          case Activity.RESULT_OK:
            mainActivity.showMessage(R.string.category_saved, ONStyle.CONFIRM);
            EventBus.getDefault().post(new CategoriesUpdatedEvent());
            break;
          case Activity.RESULT_FIRST_USER:
            mainActivity.showMessage(R.string.category_deleted, ONStyle.ALERT);
            break;
          default:
            break;
        }

        break;

      case REQUEST_CODE_CATEGORY_NOTES:
        if (intent != null) {
          Category tag = intent.getParcelableExtra(INTENT_CATEGORY);
          categorizeNotesExecute(tag);
        }
        break;

      case REQUEST_CODE_ADD_ALARMS:
        selectedNotes.clear();
        finishActionMode();
        break;

      default:
        break;
    }
  }

  private void checkSortActionPerformed(MenuItem item) {
    if (item.getGroupId() == MENU_SORT_GROUP_ID) {
      final String[] arrayDb = getResources().getStringArray(R.array.sortable_columns);
      Prefs.edit().putString(PREF_SORTING_COLUMN, arrayDb[item.getOrder()]).apply();
      initNotesList(mainActivity.getIntent());
      // Resets list scrolling position
      listViewPositionOffset = 16;
      listViewPosition = 0;
      restoreListScrollPosition();
      toggleSearchLabel(false);
      // Updates app widgets
      mainActivity.updateWidgets();
    }
  }

  /**
   * Empties trash deleting all the notes
   */
  private void emptyTrash() {
    new MaterialDialog.Builder(mainActivity)
        .content(R.string.empty_trash_confirmation)
        .positiveText(R.string.ok)
        .onPositive((dialog, which) -> {
          boolean mustDeleteLockedNotes = false;
          for (int i = 0; i < listAdapter.getItemCount(); i++) {
            selectedNotes.add(listAdapter.getItem(i));
            mustDeleteLockedNotes = mustDeleteLockedNotes || listAdapter.getItem(i).isLocked();
          }
          if (mustDeleteLockedNotes) {
            mainActivity.requestPassword(mainActivity, getSelectedNotes(),
                passwordConfirmed -> {
                  if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
                    deleteNotesExecute();
                  }
                });
          } else {
            deleteNotesExecute();
          }
        }).build().show();
  }


  /**
   * Notes list adapter initialization and association to view
   *
   * @FIXME: This method is a divine disgrace and MUST be refactored. I'm ashamed by myself.
   */
  /**
   * @description 列表页数据加载的核心调度枢纽。
   * @why
   * 负责解析复杂的 Intent 意图与用户交互状态，统一调度数据加载。
   * 1. 状态恢复：交接并清空瞬间搜索缓存，处理外部 DeepLink 标签唤醒。
   * 2. 搜索路由：根据关键字、标签、未完成清单等不同维度，利用反射机制异步调用底层查询。
   * 3. 分类过滤：解析桌面小部件绑定的 SQL 规则或侧边栏选中的分类，精准加载对应数据。
   * 4. 异步兜底：默认加载全部便签。全程通过 NoteLoaderTask 配合线程池，确保 UI 零阻塞。
   */
  void initNotesList(Intent intent) {
    LogDelegate.d("initNotesList intent: " + intent.getAction()); // 在底部控制台打印一句话，接了 intent.getAction()，用于在控制台追踪当前 Intent 的 Action 字符串

    binding.progressWheel.setAlpha(1); // 防止用户以为界面卡死了，通过 ViewBinding 获取到进度条控件，调用 setAlpha(1) 将透明度设为完全不透明（可见状态）
    binding.list.setAlpha(0); // 将 RecyclerView（binding.list）的透明度设置为 0。这是一种防闪烁处理。

    // Search for a tag
    // A workaround to simplify it's to simulate normal search
    if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getCategories() != null
        && intent.getCategories().contains(Intent.CATEGORY_BROWSABLE)) { // 为了过滤掉那些无效的启动请求。只有当用户是从“能点击的网页链接”跳过来时，才走这段逻辑。
      searchTags = intent.getDataString().replace(UrlCompleter.HASHTAG_SCHEME, ""); // 通过 getDataString() 获取完整的 URI 字符串。
      goBackOnToggleSearchLabel = true; // 这是一个状态标记，供后续用户点击取消搜索或物理返回键时，代码根据此标记判断是直接 finish() 结束 Activity（返回外部来源），还是仅清除搜索状态并回到主列表。
    }

    if (ACTION_SHORTCUT_WIDGET.equals(intent.getAction())) { // 检查一下，用户是不是点击了桌面上的“快捷方式小部件”（比如直接新建便签的快捷图标）进来的
      return; // 如果是，直接退出
    }

    // Searching
    searchQuery = searchQueryInstant; // 从临时缓存中提取搜索词，恢复当前的搜索状态
    searchQueryInstant = null;  // 阅后即焚：交接完后立刻清空临时变量。
    //判断当前是否处于任何一种搜索/过滤状态。
    if (searchTags != null || searchQuery != null || searchUncompleteChecklists
        || IntentChecker
        .checkAction(intent, Intent.ACTION_SEARCH, ACTION_SEARCH_UNCOMPLETE_CHECKLISTS)) {

      // Using tags
      // 判断是否单纯基于标签进行搜索
      if (searchTags != null && intent.getStringExtra(SearchManager.QUERY) == null) {
        searchQuery = searchTags; // // 将标签名赋给统一查询变量，方便后续顶部提示条显示“搜索：xxx”
        NoteLoaderTask.getInstance()
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "getNotesByTag",
                searchQuery); // 分线程在数据库中查getNotesByTag，NoteLoaderTask 拿到这个字符串后，会在后台偷偷找到 DbHelper 类，看看里面有没有叫 getNotesByTag 的方法，有的话就动态执行它，把结果查出来
      } else if (searchUncompleteChecklists || ACTION_SEARCH_UNCOMPLETE_CHECKLISTS.equals(
          intent.getAction())) { // // 检查一下用户是不是点了菜单里的“筛选未完成清单”或者是系统发来了一个专门的 Intent 要求看未完成清单
        searchQuery = getContext().getResources().getString(R.string.uncompleted_checklists);//通过 Resources 获取本地化字符串，赋值给全局变量 searchQuery
        searchUncompleteChecklists = true;
        NoteLoaderTask.getInstance()
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "getNotesByUncompleteChecklist");//getInstance()：保证单例，防止并发查询冲突。
      } else {                                                                                         //executeOnExecutor(..., "getNotesByUncompleteChecklist")：将任务扔进线程池。这里的字符串依然是反射调用的方法名，NoteLoaderTask 内部会利用反射机制去 DbHelper 里寻找对应的方法并执行。
        // Get the intent, verify the action and get the query
        if (intent.getStringExtra(SearchManager.QUERY) != null) {  //  校验 Intent 中是否携带了系统标准的搜索关键字（SearchManager.QUERY）
          searchQuery = intent.getStringExtra(SearchManager.QUERY); // 将搜索关键字存入全局状态变量 searchQuery，以便后续异步任务（
          searchTags = null;
        }
        NoteLoaderTask.getInstance()
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "getNotesByPattern",
                searchQuery); // 所以调用 "getNotesByPattern"（模式匹配），底层 DbHelper 会将关键字拼接成 SQL 的 `LIKE '%关键字%'` 语句。
      }

      toggleSearchLabel(true); // 立刻在屏幕顶部渲染出“搜索：XXX”的提示条，并激活取消按钮，参数 true 代表“激活并显示搜索标签”，若为 false 则代表收起隐藏。

    } else {
      // Check if is launched from a widget with categories
      // 条件1：是从桌面小部件（Widget）点进来的，且带了小部件的 ID。
      // 条件2：MainActivity 的 navigationTmp 不为空（说明用户在侧边栏选定了特定分类）。
      if ((ACTION_WIDGET_SHOW_LIST.equals(intent.getAction()) && intent.hasExtra(INTENT_WIDGET))
          || !isEmpty(mainActivity.navigationTmp)) {
        // 使用三元运算符判断是否携带了 Widget ID。如果带了就取出来，没带（比如从侧边栏进来的）就赋值为 null。
        String widgetId =
            intent.hasExtra(INTENT_WIDGET) ? intent.getExtras().get(INTENT_WIDGET).toString()
                : null;
        if (widgetId != null) {
          String sqlCondition = Prefs.getString(PREF_WIDGET_PREFIX + widgetId, ""); // 拿到这串配置字符串。这串字符串通常是一句 SQL 语句
          String categoryId = TextHelper.checkIntentCategory(sqlCondition); // 调用自定义的 TextHelper.checkIntentCategory() 方法，利用正则表达式解析 sqlCondition 字符串
          mainActivity.navigationTmp = !isEmpty(categoryId) ? categoryId : null;
        }
        intent.removeExtra(INTENT_WIDGET);
        if (mainActivity.navigationTmp != null) {
          Long categoryId = Long.parseLong(mainActivity.navigationTmp);
          NoteLoaderTask.getInstance().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
              "getNotesByCategory", categoryId); // 用三元运算符（? :）进行状态赋值。如果 categoryId 有效，则赋给主界面的 navigationTmp 暂存；否则置为 null
        } else { // 如果前面所有的特殊情况（搜索、标签、小部件、分类）都没有命中，那就默认去数据库里把所有便签都查出来，展示给用户看
          NoteLoaderTask.getInstance()
              .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "getAllNotes", true);
        }

      } else {
        NoteLoaderTask.getInstance()
            .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "getAllNotes", true);
      }
    }
  }


  public void toggleSearchLabel(boolean activate) {
    if (activate) {
      binding.searchQuery.setText(fromHtml(getString(R.string.search) + ":<b> " + searchQuery + "</b>"));
      binding.searchLayout.setVisibility(View.VISIBLE);
      binding.searchCancel.setOnClickListener(v -> toggleSearchLabel(false));
      searchLabelActive = true;
    } else {
      if (searchLabelActive) {
        searchLabelActive = false;
        AnimationsHelper.expandOrCollapse(binding.searchLayout, false);
        searchTags = null;
        searchQuery = null;
        searchUncompleteChecklists = false;
        if (!goBackOnToggleSearchLabel) {
          mainActivity.getIntent().setAction(Intent.ACTION_MAIN);
          if (searchView != null) {
            searchMenuItem.collapseActionView();
          }
          initNotesList(mainActivity.getIntent());
        } else {
          mainActivity.onBackPressed();
        }
        goBackOnToggleSearchLabel = false;
        if (Intent.ACTION_VIEW.equals(mainActivity.getIntent().getAction())) {
          mainActivity.getIntent().setAction(null);
        }
      }
    }
  }


  public void onEvent(NavigationUpdatedNavDrawerClosedEvent navigationUpdatedNavDrawerClosedEvent) {
    listViewPosition = 0;
    listViewPositionOffset = 16;
    initNotesList(mainActivity.getIntent());
    setActionItemsVisibility(menu, false);
  }


  public void onEvent(CategoriesUpdatedEvent categoriesUpdatedEvent) {
    initNotesList(mainActivity.getIntent());
  }


  public void onEvent(NotesLoadedEvent notesLoadedEvent) {
    listAdapter = new NoteAdapter(mainActivity, Prefs.getBoolean(PREF_EXPANDED_VIEW, true),
        notesLoadedEvent.getNotes());

    initSwipeGesture();

    binding.list.setAdapter(listAdapter);

    // Restores listview position when turning back to list or when navigating reminders
    if (!notesLoadedEvent.getNotes().isEmpty()) {
      if (checkNavigation(Navigation.REMINDERS)) {
        listViewPosition = listAdapter.getClosestNotePosition();
      }
      restoreListScrollPosition();
    }

    animateListView();

    closeFab();
  }

  public void onEvent(NotesUpdatedEvent notesUpdatedEvent) {
    notesUpdatedEvent.getNotes().stream().forEach(updatedNote ->
        IntStream.range(0, listAdapter.getNotes().size() - 1)
            .filter(i -> listAdapter.getItem(i).get_id().equals(updatedNote.get_id()))
            .forEach(i -> {
              listAdapter.getNotes().set(i, updatedNote);
              listAdapter.notifyItemChanged(i);
            }));
  }
  /**
   * @description 初始化 RecyclerView 的左右滑动交互手势（ItemTouchHelper）。
   * @why
   * 1. 规则定义：通过 SimpleCallback 设定仅允许左右滑动，禁用上下拖拽（防排序错乱）。
   * 2. 业务派发：在 onSwiped 中捕获滑动索引，退出多选模式后交由 swipeNote 处理归档/删除逻辑。
   * 3. 动态挂载：仅在非“未分类”视图且用户开启滑动偏好时，才绑定手势；否则传入 null 禁用。
   */
  private void initSwipeGesture() {
    // ：实例化 ItemTouchHelper.SimpleCallback。dragDirs 设为 0 禁用长按拖拽；swipeDirs 传入按位或运算的 LEFT | RIGHT，允许双向滑动。
    ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0,
        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

      @Override
      //重写 onMove 方法并返回 false。由于我们只需要滑动功能（Swipe），不需要拖拽功能（Drag），所以直接拦截拖拽事件，防止列表项错位。
      public boolean onMove(@NonNull RecyclerView recyclerView,
          @NonNull RecyclerView.ViewHolder viewHolder,
          @NonNull RecyclerView.ViewHolder target) {
        return false;
      }

      @Override
      /// 在 onSwiped 回调中，通过 getAdapterPosition() 获取数据索引。先调用 finishActionMode() 清理多选状态，再调用自定义的 swipeNote() 方法，将滑动位置传入，由业务层决定是执行归档还是移入回收站
      public void onSwiped(RecyclerView.ViewHolder viewHolder, int swipeDir) {
        int swipedPosition = viewHolder.getAdapterPosition();
        finishActionMode();
        swipeNote(swipedPosition);
      }
    };
    ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
    // 动态绑定：只有不在“未分类”下，且用户开启了滑动开关，才让滑动手势生效
    if (Navigation.getNavigation() != Navigation.UNCATEGORIZED && Prefs
        .getBoolean(PREF_ENABLE_SWIPE, true)) {
      itemTouchHelper.attachToRecyclerView(binding.list);
    } else {
      itemTouchHelper.attachToRecyclerView(null); // // 传入 null 即可禁用滑动
    }
  }

  private void swipeNote(int swipedPosition) {
    try {
      Note note = listAdapter.getItem(swipedPosition);
      if (note.isLocked()) {
        PasswordHelper.requestPassword(mainActivity, passwordConfirmed -> {
          if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
            onNoteSwipedPerformAction(note);
          } else {
            onUndo(null);
          }
        });
      } else {
        onNoteSwipedPerformAction(note);
      }
    } catch (IndexOutOfBoundsException e) {
      LogDelegate.d("Please stop swiping in the zone beneath the last card");
    }
  }

  private void onNoteSwipedPerformAction(Note note) {
    getSelectedNotes().add(note);

    // Depending on settings and note status this action will...
    // ...restore
    if (checkNavigation(Navigation.TRASH)) {
      trashNotes(false);
    }
    // ...removes category
    else if (checkNavigation(Navigation.CATEGORY)) {
      categorizeNotesExecute(null);
    } else {
      // ...trash
      if (Prefs.getBoolean("settings_swipe_to_trash", false)
          || checkNavigation(Navigation.ARCHIVE)) {
        trashNotes(true);
        // ...archive
      } else {
        archiveNotes(true);
      }
    }
  }

  public void onEvent(PasswordRemovedEvent passwordRemovedEvent) {
    initNotesList(mainActivity.getIntent());
  }

  private void animateListView() {
    if (!isDebugBuild()) {
      animate(binding.progressWheel)
          .setDuration(getResources().getInteger(R.integer.list_view_fade_anim)).alpha(0);
      animate(binding.list).setDuration(getResources().getInteger(R.integer.list_view_fade_anim))
          .alpha(1);
    } else {
      binding.progressWheel.setVisibility(View.INVISIBLE);
      binding.list.setAlpha(1);
    }
  }


  private void restoreListScrollPosition() {
    if (listAdapter.getItemCount() > listViewPosition) {
      binding.list.getLayoutManager().scrollToPosition(listViewPosition);
      new Handler().postDelayed(fab::showFab, 150);
    } else {
      binding.list.getLayoutManager().scrollToPosition(0);
    }
  }


  /**
   * Batch note trashing
   */
  public void trashNotes(boolean trash) {
    int selectedNotesSize = getSelectedNotes().size();

    // Restore is performed immediately, otherwise undo bar is shown
    if (trash) {
      trackModifiedNotes(getSelectedNotes());
      for (Note note : getSelectedNotes()) {
        listAdapter.remove(note);
        ReminderHelper.removeReminder(OmniNotes.getAppContext(), note);
      }
    } else {
      trashNote(getSelectedNotes(), false);
    }

    listAdapter.notifyDataSetChanged();
    finishActionMode();

    // Advice to user
    if (trash) {
      mainActivity.showMessage(R.string.note_trashed, ONStyle.WARN);
    } else {
      mainActivity.showMessage(R.string.note_untrashed, ONStyle.INFO);
    }

    // Creation of undo bar
    if (trash) {
      ubc.showUndoBar(false, selectedNotesSize + " " + getString(R.string.trashed), null);
      fab.hideFab();
      undoTrash = true;
    } else {
      getSelectedNotes().clear();
    }
  }


  private ActionMode getActionMode() {
    return actionMode;
  }


  private List<Note> getSelectedNotes() {
    return selectedNotes;
  }


  /**
   * Single note logical deletion
   */
  @SuppressLint("NewApi")
  protected void trashNote(List<Note> notes, boolean trash) {
    listAdapter.remove(notes);
    new NoteProcessorTrash(notes, trash).process();
  }


  /**
   * Selects all notes in list
   */
  private void selectAllNotes() {
    for (int i = 0; i < binding.list.getChildCount(); i++) {
      LinearLayout v = binding.list.getChildAt(i).findViewById(R.id.card_layout);
      v.setBackgroundColor(getResources().getColor(R.color.list_bg_selected));
    }
    selectedNotes.clear();
    for (int i = 0; i < listAdapter.getItemCount(); i++) {
      selectedNotes.add(listAdapter.getItem(i));
      listAdapter.addSelectedItem(i);
    }
    prepareActionModeMenu();
    setCabTitle();
  }


  /**
   * Batch note permanent deletion
   */
  private void deleteNotes() {
    new MaterialDialog.Builder(mainActivity)
        .content(R.string.delete_note_confirmation)
        .positiveText(R.string.ok)
        .onPositive(
            (dialog, which) -> mainActivity.requestPassword(mainActivity, getSelectedNotes(),
                passwordConfirmed -> {
                  if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
                    deleteNotesExecute();
                  }
                }))
        .build()
        .show();
  }


  /**
   * Performs notes permanent deletion after confirmation by the user
   */
  private void deleteNotesExecute() {
    listAdapter.remove(getSelectedNotes());
    new NoteProcessorDelete(getSelectedNotes()).process();
    selectedNotes.clear();
    finishActionMode();
    mainActivity.showMessage(R.string.note_deleted, ONStyle.ALERT);
  }


  /**
   * Batch note archiviation
   */
  public void archiveNotes(boolean archive) {
    int selectedNotesSize = getSelectedNotes().size();
    // Used in undo bar commit
    sendToArchive = archive;

    if (!archive) {
      archiveNote(getSelectedNotes(), false);
    } else {
      trackModifiedNotes(getSelectedNotes());
    }

    for (Note note : getSelectedNotes()) {
      // If is restore it will be done immediately, otherwise the undo bar will be shown
      if (archive) {
        // Saves archived state to eventually undo
        undoArchivedMap.put(note, note.isArchived());
      }

      // If actual navigation is not "Notes" the item will not be removed but replaced to fit the new state
      if (checkNavigation(Navigation.NOTES)
          || (checkNavigation(Navigation.ARCHIVE) && !archive)
          || (checkNavigation(Navigation.CATEGORY) && Prefs.getBoolean(
          PREF_FILTER_ARCHIVED_IN_CATEGORIES + Navigation.getCategory(), false))) {
        listAdapter.remove(note);
      } else {
        note.setArchived(archive);
        listAdapter.replace(note, listAdapter.getPosition(note));
      }
    }

    listAdapter.notifyDataSetChanged();
    finishActionMode();

    // Advice to user
    int msg = archive ? R.string.note_archived : R.string.note_unarchived;
    Style style = archive ? ONStyle.WARN : ONStyle.INFO;
    mainActivity.showMessage(msg, style);

    // Creation of undo bar
    if (archive) {
      ubc.showUndoBar(false, selectedNotesSize + " " + getString(R.string.archived), null);
      fab.hideFab();
      undoArchive = true;
    } else {
      getSelectedNotes().clear();
    }
  }


  /**
   * Saves notes to be eventually restored at right position
   */
  private void trackModifiedNotes(List<Note> modifiedNotesToTrack) {
    for (Note note : modifiedNotesToTrack) {
      undoNotesMap.put(listAdapter.getPosition(note), note);
    }
  }


  private void archiveNote(List<Note> notes, boolean archive) {
    new NoteProcessorArchive(notes, archive).process();
    if (!checkNavigation(Navigation.CATEGORY)) {
      listAdapter.remove(notes);
    }
    LogDelegate.d("Notes" + (archive ? "archived" : "restored from archive"));
  }


  /**
   * Categories addition and editing
   */
  void editCategory(Category category) {
    Intent categoryIntent = new Intent(mainActivity, CategoryActivity.class);
    categoryIntent.putExtra(INTENT_CATEGORY, category);
    startActivityForResult(categoryIntent, REQUEST_CODE_CATEGORY);
  }


  /**
   * Associates to or removes categories
   */
  private void categorizeNotes() {
    var categories = DbHelper.getInstance().getCategories();

    var dialogBuilder = new MaterialDialog.Builder(mainActivity)
        .title(R.string.categorize_as)
        .positiveText(R.string.add_category)
        .positiveColorRes(R.color.colorPrimary)
        .negativeText(R.string.remove_category)
        .negativeColorRes(R.color.colorAccent)
        .onPositive((dialog1, which) -> {
          keepActionMode = true;
          Intent intent = new Intent(mainActivity, CategoryActivity.class);
          intent.putExtra("noHome", true);
          startActivityForResult(intent, REQUEST_CODE_CATEGORY_NOTES);
        }).onNegative((dialog12, which) -> categorizeNotesExecute(null));

    if (CollectionUtils.isNotEmpty(categories)) {
      dialogBuilder.adapter(new CategoryRecyclerViewAdapter(mainActivity, categories), null);
    }

    final var dialog = dialogBuilder.build();

    if (CollectionUtils.isNotEmpty(categories)) {
      RecyclerViewItemClickSupport.addTo(dialog.getRecyclerView())
          .setOnItemClickListener((recyclerView, position, v) -> {
            dialog.dismiss();
            categorizeNotesExecute(categories.get(position));
          });
    }

    dialog.show();
  }


  private void categorizeNotesExecute(Category category) {
    if (category != null) {
      categorizeNote(getSelectedNotes(), category);
    } else {
      trackModifiedNotes(getSelectedNotes());
    }
    for (Note note : getSelectedNotes()) {
      // If is restore it will be done immediately, otherwise the undo bar
      // will be shown
      if (category == null) {
        // Saves categories associated to eventually undo
        undoCategoryMap.put(note, note.getCategory());
      }
      // Update adapter content if actual navigation is the category
      // associated with actually cycled note
      if ((checkNavigation(Navigation.CATEGORY) && !Navigation
          .checkNavigationCategory(category)) ||
          checkNavigation(Navigation.UNCATEGORIZED)) {
        listAdapter.remove(note);
      } else {
        note.setCategory(category);
        listAdapter.replace(note, listAdapter.getPosition(note));
      }
    }

    finishActionMode();

    // Advice to user
    String msg;
    if (category != null) {
      msg = getResources().getText(R.string.notes_categorized_as) + " '" + category.getName() + "'";
    } else {
      msg = getResources().getText(R.string.notes_category_removed).toString();
    }
    mainActivity.showMessage(msg, ONStyle.INFO);

    // Creation of undo bar
    if (category == null) {
      ubc.showUndoBar(false, getString(R.string.notes_category_removed), null);
      fab.hideFab();
      undoCategorize = true;
      undoCategorizeCategory = null;
    } else {
      getSelectedNotes().clear();
    }
  }


  private void categorizeNote(List<Note> notes, Category category) {
    new NoteProcessorCategorize(notes, category).process();
  }


  /**
   * Bulk tag selected notes
   */
  private void tagNotes() {

    // Retrieves all available tags
    final List<Tag> tags = DbHelper.getInstance().getTags();

    // If there is no tag a message will be shown
    if (tags.isEmpty()) {
      finishActionMode();
      mainActivity.showMessage(R.string.no_tags_created, ONStyle.WARN);
      return;
    }

    final Integer[] preSelectedTags = TagsHelper.getPreselectedTagsArray(selectedNotes, tags);

    new MaterialDialog.Builder(mainActivity)
        .title(R.string.select_tags)
        .items(TagsHelper.getTagsArray(tags))
        .positiveText(R.string.ok)
        .itemsCallbackMultiChoice(preSelectedTags, (dialog, which, text) -> {
          dialog.dismiss();
          tagNotesExecute(tags, which, preSelectedTags);
          return false;
        }).build().show();
  }


  private void tagNotesExecute(List<Tag> tags, Integer[] selectedTags, Integer[] preSelectedTags) {
    for (Note note : getSelectedNotes()) {
      tagNote(tags, selectedTags, note);
    }

    if (getActionMode() != null) {
      getActionMode().finish();
    }

    mainActivity.showMessage(R.string.tags_added, ONStyle.INFO);
  }


  private void tagNote(List<Tag> tags, Integer[] selectedTags, Note note) {

    Pair<String, List<Tag>> taggingResult = TagsHelper.addTagToNote(tags, selectedTags, note);

    if (note.isChecklist()) {
      note.setTitle(note.getTitle() + System.getProperty("line.separator") + taggingResult.first);
    } else {
      StringBuilder sb = new StringBuilder(note.getContent());
      if (sb.length() > 0) {
        sb.append(System.getProperty("line.separator"))
            .append(System.getProperty("line.separator"));
      }
      sb.append(taggingResult.first);
      note.setContent(sb.toString());
    }

    eventuallyRemoveDeselectedTags(note, taggingResult.second);

    DbHelper.getInstance().updateNote(note, false);
  }

  private void eventuallyRemoveDeselectedTags(Note note, List<Tag> tagsToRemove) {
    if (CollectionUtils.isNotEmpty(tagsToRemove)) {
      String titleWithoutTags = TagsHelper.removeTags(note.getTitle(), tagsToRemove);
      note.setTitle(titleWithoutTags);
      String contentWithoutTags = TagsHelper.removeTags(note.getContent(), tagsToRemove);
      note.setContent(contentWithoutTags);
    }
  }

//	private void synchronizeSelectedNotes() {
//		new DriveSyncTask(mainActivity).execute(new ArrayList<Note>(getSelectedNotes()));
//		// Clears data structures
//		listAdapter.clearSelectedItems();
//		list.clearChoices();
//		finishActionMode();
//	}


  @Override
  public void onUndo(Parcelable undoToken) {
    // Cycles removed items to re-insert into adapter
    for (Integer notePosition : undoNotesMap.keySet()) {
      Note currentNote = undoNotesMap.get(notePosition);
      //   Manages uncategorize or archive  undo
      if ((undoCategorize && !Navigation.checkNavigationCategory(undoCategoryMap.get(currentNote)))
          || undoArchive && !checkNavigation(Navigation.NOTES)) {
        if (undoCategorize) {
          currentNote.setCategory(undoCategoryMap.get(currentNote));
        } else if (undoArchive) {
          currentNote.setArchived(undoArchivedMap.get(currentNote));
        }
        listAdapter.replace(currentNote, listAdapter.getPosition(currentNote));
        // Manages trash undo
      } else {
        listAdapter.add(notePosition, currentNote);
      }
    }

    listAdapter.notifyDataSetChanged();

    selectedNotes.clear();
    undoNotesMap.clear();

    undoTrash = false;
    undoArchive = false;
    undoCategorize = false;
    undoNotesMap.clear();
    undoCategoryMap.clear();
    undoArchivedMap.clear();
    undoCategorizeCategory = null;
    Crouton.cancelAllCroutons();

    if (getActionMode() != null) {
      getActionMode().finish();
    }
    ubc.hideUndoBar(false);
    fab.showFab();
  }


  void commitPending() {
    if (undoTrash || undoArchive || undoCategorize) {

      List<Note> notesList = new ArrayList<>(undoNotesMap.values());
      if (undoTrash) {
        trashNote(notesList, true);
      } else if (undoArchive) {
        archiveNote(notesList, sendToArchive);
      } else if (undoCategorize) {
        categorizeNote(notesList, undoCategorizeCategory);
      }

      undoTrash = false;
      undoArchive = false;
      undoCategorize = false;
      undoCategorizeCategory = null;

      // Clears data structures
      selectedNotes.clear();
      undoNotesMap.clear();
      undoCategoryMap.clear();
      undoArchivedMap.clear();

      ubc.hideUndoBar(false);
      fab.showFab();

      LogDelegate.d("Changes committed");
    }
    mainActivity.updateWidgets();
  }


  /**
   * Shares the selected note from the list
   */
  private void share() {
    // Only one note should be selected to perform sharing but they'll be cycled anyhow
    for (final Note note : getSelectedNotes()) {
      mainActivity.shareNote(note);
    }

    getSelectedNotes().clear();
    if (getActionMode() != null) {
      getActionMode().finish();
    }
  }


  public void merge() {
    EventBus.getDefault().post(new NotesMergeEvent(false));
  }


  /**
   * Merges all the selected notes
   */
  public void onEventAsync(NotesMergeEvent notesMergeEvent) {

    final Note finalMergedNote = NotesHelper
        .mergeNotes(getSelectedNotes(), notesMergeEvent.keepMergedNotes);
    new Handler(Looper.getMainLooper()).post(() -> {

      if (!notesMergeEvent.keepMergedNotes) {
        ArrayList<String> notesIds = new ArrayList<>();
        for (Note selectedNote : getSelectedNotes()) {
          notesIds.add(String.valueOf(selectedNote.get_id()));
        }
        mainActivity.getIntent().putExtra("merged_notes", notesIds);
      }

      getSelectedNotes().clear();
      if (getActionMode() != null) {
        getActionMode().finish();
      }

      mainActivity.getIntent().setAction(ACTION_MERGE);
      mainActivity.switchToDetail(finalMergedNote);
    });
  }


  /**
   * Excludes past reminders
   */
  private void filterReminders(boolean filter) {
    Prefs.edit().putBoolean(PREF_FILTER_PAST_REMINDERS, filter).apply();
    // Change list view
    initNotesList(mainActivity.getIntent());
    // Called to switch menu voices
    mainActivity.supportInvalidateOptionsMenu();
  }


  /**
   * Excludes archived notes in categories navigation
   */
  private void filterCategoryArchived(boolean filter) {
    if (filter) {
      Prefs.edit().putBoolean(PREF_FILTER_ARCHIVED_IN_CATEGORIES + Navigation.getCategory(), true)
          .apply();
    } else {
      Prefs.edit().remove(PREF_FILTER_ARCHIVED_IN_CATEGORIES + Navigation.getCategory()).apply();
    }
    // Change list view
    initNotesList(mainActivity.getIntent());
    // Called to switch menu voices
    mainActivity.supportInvalidateOptionsMenu();
  }


  private void filterByUncompleteChecklists() {
    initNotesList(new Intent(ACTION_SEARCH_UNCOMPLETE_CHECKLISTS));
  }

  private void filterByTags() {
    final List<Tag> tags = TagsHelper.getAllTags();

    if (tags.isEmpty()) {
      mainActivity.showMessage(R.string.no_tags_created, ONStyle.WARN);
      return;
    }

    var tagsDialog = new MaterialAlertDialogBuilder(mainActivity)
        .setTitle(R.string.select_tags)
        .setPositiveButton(R.string.ok, (dialog, which) -> {
          var items = ((AlertDialog) dialog).getListView().getCheckedItemPositions();
          var selectedTags = new ArrayList<String>();
          for(int i = 0; i < tags.size(); i++) {
            if (items.get(i)) {
              selectedTags.add(tags.get(i).getText());
            }
          }

          // Saved here to allow persisting search
          searchTags = selectedTags.toString().substring(1, selectedTags.toString().length() - 1)
              .replace(" ", "");

          // Hides keyboard
          searchView.clearFocus();
          KeyboardUtils.hideKeyboard(searchView);

          var intent = mainActivity.getIntent();
          intent.removeExtra(SearchManager.QUERY);
          initNotesList(intent);
        })
        .setMultiChoiceItems(TagsHelper.getTagsArray(tags), null, (dialog, which, isChecked) ->
            ((AlertDialog) dialog).getButton(BUTTON_POSITIVE)
            .setEnabled(((AlertDialog) dialog).getListView().getCheckedItemCount() > 0)
        ).create();
    tagsDialog.show();
    tagsDialog.getButton(BUTTON_POSITIVE).setEnabled(false);
  }

  public MenuItem getSearchMenuItem() {
    return searchMenuItem;
  }


  private boolean isFabAllowed() {
    return isFabAllowed(false);
  }


  private boolean isFabAllowed(boolean actionModeFinishing) {

    boolean isAllowed = true;

    // Actionmode check
    isAllowed = isAllowed && (getActionMode() == null || actionModeFinishing);
    // Navigation check
    int navigation = Navigation.getNavigation();
    isAllowed = isAllowed && navigation != Navigation.ARCHIVE && navigation != Navigation.REMINDERS
        && navigation
        != Navigation.TRASH;
    // Navigation drawer check
    isAllowed =
        isAllowed && mainActivity.getDrawerLayout() != null && !mainActivity.getDrawerLayout()
            .isDrawerOpen
                (GravityCompat.START);

    return isAllowed;
  }


}
