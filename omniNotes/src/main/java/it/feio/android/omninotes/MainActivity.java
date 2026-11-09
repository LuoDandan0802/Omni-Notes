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

import static it.feio.android.omninotes.helpers.AppVersionHelper.isAppUpdated;
import static it.feio.android.omninotes.helpers.AppVersionHelper.updateAppVersionInPreferences;
import static it.feio.android.omninotes.helpers.BuildHelper.isDebugBuild;
import static it.feio.android.omninotes.helpers.ChangelogHelper.showChangelog;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_NOTIFICATION_CLICK;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_RESTART_APP;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_SEND_AND_EXIT;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_SHORTCUT;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_SHORTCUT_WIDGET;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_START_APP;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_WIDGET;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_WIDGET_TAKE_PHOTO;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_GOOGLE_NOW;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_KEY;
import static it.feio.android.omninotes.utils.ConstantsBase.INTENT_NOTE;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_PASSWORD;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.pixplicity.easyprefs.library.Prefs;
import de.greenrobot.event.EventBus;
import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;
import it.feio.android.omninotes.async.UpdateWidgetsTask;
import it.feio.android.omninotes.async.bus.NotificationsGrantedEvent;
import it.feio.android.omninotes.async.bus.PasswordRemovedEvent;
import it.feio.android.omninotes.async.bus.SwitchFragmentEvent;
import it.feio.android.omninotes.async.notes.NoteProcessorDelete;
import it.feio.android.omninotes.databinding.ActivityMainBinding;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.NotesHelper;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import it.feio.android.omninotes.intro.IntroActivity;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.ONStyle;
import it.feio.android.omninotes.utils.FileProviderHelper;
import it.feio.android.omninotes.utils.PasswordHelper;
import it.feio.android.omninotes.utils.SystemHelper;
import it.feio.android.pixlui.links.UrlCompleter;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import lombok.Getter;
import lombok.Setter;


public class MainActivity extends BaseActivity implements
    SharedPreferences.OnSharedPreferenceChangeListener {

  private boolean isPasswordAccepted = false;
  public static final String FRAGMENT_DRAWER_TAG = "fragment_drawer";
  public static final String FRAGMENT_LIST_TAG = "fragment_list";
  public static final String FRAGMENT_DETAIL_TAG = "fragment_detail";
  public static final String FRAGMENT_SKETCH_TAG = "fragment_sketch";
  @Getter @Setter
  private Uri sketchUri;
  boolean prefsChanged = false;
  private FragmentManager mFragmentManager;

  ActivityMainBinding binding;
  /**
   * @description 主界面创建入口，负责 UI 渲染、全局监听注册、通知权限申请及返回键拦截调度。
   * @why 绑定主布局并建立通信机制确保实时响应数据变化；拦截返回键优先交由自定义逻辑处理，未消费时安全交还系统默认行为。
   */
  @Override
  protected void onCreate(Bundle savedInstanceState) { // 重写 Activity 的 onCreate 生命周期；调用父类完成底层初始化；通过 setTheme 设置当前 Activity 的全局 UI 主题
    super.onCreate(savedInstanceState);
    setTheme(R.style.OmniNotesTheme_ApiSpec); // setTheme 设置当前 Activity 的全局 UI 主题

    /// 实例化主界面的 UI，并完成上屏
    binding = ActivityMainBinding.inflate(getLayoutInflater());
    View view = binding.getRoot();
    setContentView(view);

    ///建立组件间通信，让主界面能响应全局的数据和设置变化
    EventBus.getDefault().register(this);
    Prefs.getPreferences().registerOnSharedPreferenceChangeListener(this);
    new NotificationsHelper(this).askToEnableNotifications(this);// 提前获取通知权限，防止后续闹钟提醒、常驻便签等功能因为没权限而失效

    /// 注册物理返回键拦截回调
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        // 若自定义逻辑未处理（返回 false），则临时禁用拦截，交还系统默认逻辑（如退出或出栈）
        if (!handleBackPressed()) {
          // Fall through to the default behavior (fragment back stack, activity finish)
          setEnabled(false);
          getOnBackPressedDispatcher().onBackPressed();
          setEnabled(true);
        }
      }
    });

    initUI(); // 调用自定义的 initUI() 方法，内部主要执行 setSupportActionBar(toolbar) 并开启左上角的导航按钮
  }
  /**
   * @description 处理 Activity 创建完成后的收尾工作，负责新手引导与版本更新日志的调度。
   * @why 确保界面完全就绪后再弹窗；通过逻辑短路避免新手引导与更新日志同时弹出；
   * 仅当 App 版本升级且为正式包时，才展示更新日志并刷新本地版本号。
   */
  @Override
  protected void onPostCreate(@Nullable Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // 拦截新手引导；若未触发，则检查是否为正式版且发生了版本更新（利用 && 短路特性防止弹窗冲突）
    if (!launchIntroIfRequired() && isAppUpdated(getApplicationContext()) && !isDebugBuild()) {
      showChangelogAndUpdateCurrentVersion(); // 给用户展示最新的更新日志，偷偷把当前的新版本号记在手机里
    }
  }
  /**
   * @description 弹出更新日志并同步当前版本号到偏好设置。
   * @why 防止用户每次打开 App 都重复看到更新弹窗。
   */
  private void showChangelogAndUpdateCurrentVersion() {
    showChangelog(this); // 在图片上面渲染一个对话框，显示写好的“更新日志”
    updateAppVersionInPreferences(getApplicationContext()); // 记住当前的版本号，如果下次登入，对比当前版本和此时的记录版本，如果不一样则表示更新，需要弹出提示
  }
  /**
   * @description 检查是否需要展示新手指引，是新手指引的触发器
   * @why 首次安装或清除数据后需要引导用户熟悉功能。
   * @return 如果触发了引导页返回 true，否则返回 false。
   */
  private boolean launchIntroIfRequired() {
    if (IntroActivity.mustRun()) {
      startActivity(new Intent(getApplicationContext(), IntroActivity.class)); //执行跳转，并且告诉外界我已经处理
      return true;
    }
    return false;
  }
  /**
   * @description 页面回到前台时的生命周期回调。
   * @why 根据密码状态决定是直接初始化主界面，还是先弹出密码验证拦截。
   */
  @Override
  protected void onResume() {
    super.onResume();
    if (isPasswordAccepted) {
      init();
    } else {
      checkPassword();
    }
  }
  /**
   * @description 页面不可见时的生命周期回调。
   * @why 注销 EventBus，防止内存泄漏。
   */

  @Override
  protected void onStop() {
    super.onStop();
    EventBus.getDefault().unregister(this);
  }

  /**
   * @description 初始化顶部导航栏（Toolbar）。
   * @why 设置应用栏并开启左上角的返回/汉堡按钮。
   */
  private void initUI() {
    setSupportActionBar(binding.toolbar.toolbar);
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    getSupportActionBar().setHomeButtonEnabled(true);
  }


  /**
   * This method starts the bootstrap chain.
   */
  /**
   * @description 检查并请求安全密码，这是整个 App 启动的引导链。
   * @why 如果用户设置了密码且开启了访问保护，必须验证通过才能加载数据；否则直接初始化。
   */
  private void checkPassword() {
    if (Prefs.getString(PREF_PASSWORD, null) != null
        && Prefs.getBoolean("settings_password_access", false)) {
      PasswordHelper.requestPassword(this, passwordConfirmed -> {
        switch (passwordConfirmed) {
          case SUCCEED:
            init();
            break;
          case FAIL:
            finish(); // 密码错误直接退出应用
            break;
          case RESTORE:
            PasswordHelper.resetPassword(this); // 选择重置密码
        }
      });
    } else {
      init();
    }
  }

  /**
   * @description 监听密码被移除的事件。
   * @why 密码清除后，需要重新初始化界面以解除锁定状态，并给出提示。
   */
  public void onEvent(PasswordRemovedEvent passwordRemovedEvent) {
    showMessage(R.string.password_successfully_removed, ONStyle.ALERT);
    init();
  }
  /**
   * @description 监听通知权限申请结果。
   * @why 如果用户拒绝了通知权限，弹出 Toast 告知用户可能会错过提醒。
   */
  public void onEvent(NotificationsGrantedEvent notificationsGrantedEvent) {
    if (!notificationsGrantedEvent.granted) {
      showToast(getString(R.string.denied_notifications_permission), Toast.LENGTH_LONG);
    }
  }
  /**
   * @description 核心初始化方法，负责装配侧边栏和列表页。
   * @why 将核心 Fragment 挂载到 Activity 上，并处理外部传来的 Intent，是应用真正“活”起来的起点。
   */
  private void init() {
    isPasswordAccepted = true;

    getFragmentManagerInstance();
    // 挂载左侧导航抽屉（侧边栏）
    NavigationDrawerFragment mNavigationDrawerFragment = (NavigationDrawerFragment) getFragmentManagerInstance()
        .findFragmentById(R.id.navigation_drawer);
    if (mNavigationDrawerFragment == null) {
      FragmentTransaction fragmentTransaction = getFragmentManagerInstance().beginTransaction();
      fragmentTransaction.replace(R.id.navigation_drawer, new NavigationDrawerFragment(),
          FRAGMENT_DRAWER_TAG).commit();
    }
    // 挂载右侧便签列表主界面
    if (getFragmentManagerInstance().findFragmentByTag(FRAGMENT_LIST_TAG) == null) {
      FragmentTransaction fragmentTransaction = getFragmentManagerInstance().beginTransaction();
      fragmentTransaction.add(R.id.fragment_container, new ListFragment(), FRAGMENT_LIST_TAG)
          .commit();
    }

    handleIntents();
  }
  /**
   * @description 获取 FragmentManager 的单例，采用懒加载。
   * @why 避免重复获取，提高效率。
   */
  private FragmentManager getFragmentManagerInstance() {
    if (mFragmentManager == null) {
      mFragmentManager = getSupportFragmentManager();
    }
    return mFragmentManager;
  }
  /**
   * @description 处理新的 Intent 传入（应用在后台时被再次唤醒）。
   * @why 比如用户点击了通知栏，或者再次从桌面快捷方式进入，需要更新 Intent 并重新解析路由。
   */
  @Override
  protected void onNewIntent(Intent intent) {
    if (intent.getAction() == null) {
      intent.setAction(ACTION_START_APP);
    }
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntents();
    LogDelegate.d("onNewIntent");
  }

  /**
   * @description 获取当前列表页的搜索菜单项。
   * @why 供外部或其他组件控制搜索框的展开与折叠。
   */
  public MenuItem getSearchMenuItem() {
    Fragment f = checkFragmentInstance(R.id.fragment_container, ListFragment.class);
    if (f != null) {
      return ((ListFragment) f).getSearchMenuItem();
    } else {
      return null;
    }
  }
  /**
   * @description 编辑指定的标签（分类）。
   * @why 供侧边栏长按分类时调用，跳转到分类编辑页。
   */
  public void editTag(Category tag) {
    Fragment f = checkFragmentInstance(R.id.fragment_container, ListFragment.class);
    if (f != null) {
      ((ListFragment) f).editCategory(tag);
    }
  }
  /**
   * @description 根据外部 Intent 初始化便签列表（如通过标签搜索跳转过来）。
   * @why 需要先启动一个带搜索焦点状态的列表页，然后再将 Intent 传给它进行数据加载。
   */
  public void initNotesList(Intent intent) {
    if (intent != null) {
      Fragment searchTagFragment = startSearchView();
      // 使用 Handler 延后执行，确保 Fragment 视图已经初始化完毕再加载数据
      new Handler(getMainLooper()).post(() -> ((ListFragment) searchTagFragment).initNotesList(intent));
    }
  }
  /**
   * @description 启动一个新的带搜索框的列表页（通常用于标签搜索）。
   * @why 替换当前列表页，并强制弹出软键盘及聚焦搜索框。
   */
  public Fragment startSearchView() {
    FragmentTransaction transaction = getFragmentManagerInstance().beginTransaction();
    animateTransition(transaction, TRANSITION_HORIZONTAL);
    ListFragment mListFragment = new ListFragment();
    transaction.replace(R.id.fragment_container, mListFragment, FRAGMENT_LIST_TAG).addToBackStack
        (FRAGMENT_DETAIL_TAG).commit();
    Bundle args = new Bundle();
    args.putBoolean("setSearchFocus", true);
    mListFragment.setArguments(args);
    return mListFragment;
  }

  /**
   * @description 提交列表页中待处理的撤销操作（如撤销删除）。
   * @why 当用户点击侧边栏或进行其他操作导致页面切换时，需要立即确认之前的修改，防止撤销状态悬挂。
   */
  public void commitPending() {
    Fragment f = checkFragmentInstance(R.id.fragment_container, ListFragment.class);
    if (f != null) {
      ((ListFragment) f).commitPending();
    }
  }


  /**
   * Checks if allocated fragment is of the required type and then returns it or returns null
   */
  /**
   * @description 辅助方法，检查指定 ID 的 Fragment 是否属于特定类型。
   * @why 在多 Fragment 切换的场景下，安全地获取当前处于激活状态的 Fragment。
   */
  private Fragment checkFragmentInstance(int id, Object instanceClass) {
    Fragment result = null;
    Fragment fragment = getFragmentManagerInstance().findFragmentById(id);
    if (fragment != null && instanceClass.equals(fragment.getClass())) {
      result = fragment;
    }
    return result;
  }

  /**
   * Handles the back navigation logic. Returns {@code false} when the event must be dispatched
   * to the default dispatcher behavior (fragment back stack, activity finish).
   */
  /**
   * @description 处理物理返回键逻辑的核心枢纽。
   * @why
   * 1. 处理画板页：保存草图并退出。
   * 2. 处理详情页：触发自动保存并退回到列表。
   * 3. 处理列表页：处理侧边栏开合逻辑，如果是主界面则执行退出或最小化。
   * @return true 表示已消费该事件；false 表示交由系统默认处理（退出 App）。
   */
  private boolean handleBackPressed() {
    // SketchFragment
    // 处理画板
    Fragment f = checkFragmentInstance(R.id.fragment_container, SketchFragment.class);
    if (f != null) {
      ((SketchFragment) f).save();

      // Removes forced portrait orientation for this fragment
      setRequestedOrientation(
          ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

      getFragmentManagerInstance().popBackStack();
      return true;
    }
    // 处理详情
    // DetailFragment
    f = checkFragmentInstance(R.id.fragment_container, DetailFragment.class);
    if (f != null) {
      var detailDragment = (DetailFragment) f;
      detailDragment.goBack = true;
      detailDragment.saveAndExit();
      return true;
    }

    // ListFragment
    // 处理列表
    f = checkFragmentInstance(R.id.fragment_container, ListFragment.class);
    if (f != null) {
      // 退出前打开侧边栏的特殊设置
      // Before exiting from app the navigation drawer is opened
      if (Prefs.getBoolean("settings_navdrawer_on_exit", false) && getDrawerLayout() != null &&
          !getDrawerLayout().isDrawerOpen(GravityCompat.START)) {
        getDrawerLayout().openDrawer(GravityCompat.START);
      } else if (!Prefs.getBoolean("settings_navdrawer_on_exit", false) && getDrawerLayout() != null
          &&
          getDrawerLayout().isDrawerOpen(GravityCompat.START)) {
        // 如果侧边栏开着且不需要在退出时打开，则优先关闭侧边栏
        getDrawerLayout().closeDrawer(GravityCompat.START);
      } else {
        // 关闭悬浮按钮 (FAB)；如果 FAB 未展开且无法关闭，说明真的可以退出 App 了
        if (!((ListFragment) f).closeFab()) {
          isPasswordAccepted = false; // 返回 false 会触发默认的 finish() 逻辑
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * @description 保存 Activity 的临时状态。
   * @why 屏幕旋转时，保存当前的临时导航状态（如来自小部件的跳转）。
   */
  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString("navigationTmp", navigationTmp);
  }

  /**
   * @description 页面暂停时清除所有悬浮提示（Crouton）。
   * @why 防止用户离开页面后，未消失的提示框泄露到其他应用界面上。
   */
  @Override
  protected void onPause() {
    super.onPause();
    Crouton.cancelAllCroutons();
  }


  public DrawerLayout getDrawerLayout() {
    return binding.drawerLayout;
  }


  public ActionBarDrawerToggle getDrawerToggle() {
    if (getFragmentManagerInstance().findFragmentById(R.id.navigation_drawer) != null) {
      return ((NavigationDrawerFragment) getFragmentManagerInstance().findFragmentById(
          R.id.navigation_drawer)).mDrawerToggle;
    } else {
      return null;
    }
  }


  /**
   * Finishes multiselection mode started by ListFragment
   */
  public void finishActionMode() {
    ListFragment fragment = (ListFragment) getFragmentManagerInstance()
        .findFragmentByTag(FRAGMENT_LIST_TAG);
    if (fragment != null) {
      fragment.finishActionMode();
    }
  }


  Toolbar getToolbar() {
    return binding.toolbar.toolbar;
  }

  /**
   * @description 统一解析外部唤醒 App 的 Intent 并路由到对应页面。
   * @why 处理比如：点击通知打开某条便签、从第三方分享文本过来、点击快捷方式等等复杂的外部入口。
   */
  private void handleIntents() {
    Intent i = getIntent();

    if (i.getAction() == null) {
      return;
    }

    if (ACTION_RESTART_APP.equals(i.getAction())) {
      SystemHelper.restartApp();
    }
// 处理如通知栏点击、小部件点击、分享等需要跳转到详情页的 Intent
    if (receivedIntent(i)) {
      Note note = i.getParcelableExtra(INTENT_NOTE);
      if (note == null) {
        note = DbHelper.getInstance().getNote(i.getIntExtra(INTENT_KEY, 0));
      }
      // 检查是否已经打开了这条便签，避免重复打开
      // Checks if the same note is already opened to avoid to open again
      if (note != null && noteAlreadyOpened(note)) {
        return;
      }
      // Empty note instantiation
      if (note == null) {
        note = new Note();
      }
      switchToDetail(note);
      return;
    }
    // 处理快速保存并退出的 Intent (如 Tasker 自动化)
    if (ACTION_SEND_AND_EXIT.equals(i.getAction())) {
      saveAndExit(i);
      return;
    }
    // 处理标签搜索
    // Tag search
    if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getDataString()
        .startsWith(UrlCompleter.HASHTAG_SCHEME)) {
      switchToList();
      return;
    }
    // 处理桌面快捷方式打开特定便签
    // Home launcher shortcut widget
    if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
      Long id = Long.valueOf(Uri.parse(i.getDataString()).getQueryParameter("id"));
      Note note = DbHelper.getInstance().getNote(id);
      if (note == null) {
        showMessage(R.string.note_doesnt_exist, ONStyle.ALERT);
        return;
      }
      switchToDetail(note);
      return;
    }
    // 处理桌面小部件新建便签快捷方式
    // Home launcher "new note" shortcut widget
    if (ACTION_SHORTCUT_WIDGET.equals(i.getAction())) {
      switchToDetail(new Note());
      return;
    }
  }


  /**
   * Used to perform a quick text-only note saving (eg. Tasker+Pushbullet)
   */
  /**
   * @description 快速执行保存纯文本便签并立即退出应用。
   * @why 为外部自动化工具（如 Tasker + Pushbullet）提供静默保存接口。
   */
  private void saveAndExit(Intent i) {
    Note note = new Note();
    note.setTitle(i.getStringExtra(Intent.EXTRA_SUBJECT));
    note.setContent(i.getStringExtra(Intent.EXTRA_TEXT));
    DbHelper.getInstance().updateNote(note, true);
    showToast(getString(R.string.note_updated), Toast.LENGTH_SHORT);
    finish();
  }

  /**
   * @description 判断该 Intent 是否属于需要直接打开具体便签详情页的类型。
   * @why 过滤并统一判断多种外部唤起路径。
   */
  private boolean receivedIntent(Intent i) {
    return ACTION_SHORTCUT.equals(i.getAction())
        || ACTION_NOTIFICATION_CLICK.equals(i.getAction())
        || ACTION_WIDGET.equals(i.getAction())
        || ACTION_WIDGET_TAKE_PHOTO.equals(i.getAction())
        || ((Intent.ACTION_SEND.equals(i.getAction())
        || Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())
        || INTENT_GOOGLE_NOW.equals(i.getAction())
        || Intent.ACTION_PROCESS_TEXT.equals(i.getAction()))
        && i.getType() != null)
        || i.getAction().contains(ACTION_NOTIFICATION_CLICK);
  }

  /**
   * @description 检查目标便签是否已经处于打开状态。
   * @why 避免用户连续点击通知导致同一个页面被叠加压栈多次。
   */
  private boolean noteAlreadyOpened(Note note) {
    DetailFragment detailFragment = (DetailFragment) getFragmentManagerInstance().findFragmentByTag(
        FRAGMENT_DETAIL_TAG);
    return detailFragment != null && NotesHelper.haveSameId(note, detailFragment.getCurrentNote());
  }

  /**
   * @description 切换回便签列表页。
   * @why 统一处理从搜索页或详情页返回到主列表的动画与状态。
   */
  public void switchToList() {
    FragmentTransaction transaction = getFragmentManagerInstance().beginTransaction();
    animateTransition(transaction, TRANSITION_HORIZONTAL);
    ListFragment mListFragment = new ListFragment();
    transaction.replace(R.id.fragment_container, mListFragment, FRAGMENT_LIST_TAG).addToBackStack
        (FRAGMENT_DETAIL_TAG).commitAllowingStateLoss();
    if (getDrawerToggle() != null) {
      getDrawerToggle().setDrawerIndicatorEnabled(false);
    }
    getFragmentManagerInstance().getFragments();
    EventBus.getDefault().post(new SwitchFragmentEvent(SwitchFragmentEvent.Direction.PARENT));
  }

  /**
   * @description 切换到指定便签的详情页。
   * @why 处理不同情况下的页面压栈逻辑：如果之前没开过详情页就正常替换；如果已经开着（比如点了其他通知），先出栈再压新栈。
   */
  public void switchToDetail(Note note) {
    FragmentTransaction transaction = getFragmentManagerInstance().beginTransaction();
    animateTransition(transaction, TRANSITION_HORIZONTAL);
    DetailFragment mDetailFragment = new DetailFragment();
    Bundle b = new Bundle();
    b.putParcelable(INTENT_NOTE, note);
    mDetailFragment.setArguments(b);
    if (getFragmentManagerInstance().findFragmentByTag(FRAGMENT_DETAIL_TAG) == null) {
      transaction.replace(R.id.fragment_container, mDetailFragment, FRAGMENT_DETAIL_TAG)
          .addToBackStack(FRAGMENT_LIST_TAG)
          .commitAllowingStateLoss();
    } else {
      // 如果当前栈里已经有 DetailFragment，先把它弹出去，再推入新的
      getFragmentManagerInstance().popBackStackImmediate();
      transaction.replace(R.id.fragment_container, mDetailFragment, FRAGMENT_DETAIL_TAG)
          .addToBackStack(FRAGMENT_DETAIL_TAG)
          .commitAllowingStateLoss();
    }
  }

  /**
   * @description 分享指定的便签。
   * @why 根据便签是否包含附件以及附件的数量，动态构建不同的系统分享 Intent（纯文本、单张图片、多附件）。
   */
  public void shareNote(Note note) {
    Intent shareIntent = new Intent();
    // Prepare sharing intent with only text
    // 情况一：只有纯文本内容
    if (note.getAttachmentsList().isEmpty()) {
      shareIntent.setAction(Intent.ACTION_SEND);
      shareIntent.setType("text/plain");

      // Intent with single image attachment
      // 情况二：带有一个附件
    } else if (note.getAttachmentsList().size() == 1) {
      Attachment attachment = note.getAttachmentsList().get(0);
      Uri shareableAttachmentUri = getShareableAttachmentUri(attachment);
      if (shareableAttachmentUri != null) {
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.setType(attachment.getMime_type());
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareableAttachmentUri);
      }
      // Intent with multiple images
      // 情况三：带有多个附件
    } else if (note.getAttachmentsList().size() > 1) {
      shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
      ArrayList<Uri> uris = new ArrayList<>();
      // A check to decide the mime type of attachments to share is done here
      // 统计不同附件的 MIME 类型，用于判断是否使用通配符 "*/*"
      HashMap<String, Boolean> mimeTypes = new HashMap<>();
      for (Attachment attachment : note.getAttachmentsList()) {
        Uri shareableAttachmentUri = getShareableAttachmentUri(attachment);
        if (shareableAttachmentUri != null) {
          uris.add(shareableAttachmentUri);
          mimeTypes.put(attachment.getMime_type(), true);
        }
      }
      // If many mime types are present a general type is assigned to intent
      // 如果包含多种类型的文件，只能把类型设为通配符
      if (mimeTypes.size() > 1) {
        shareIntent.setType("*/*");
      } else {
        shareIntent.setType((String) mimeTypes.keySet().toArray()[0]);
      }

      shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
    }
    shareIntent.putExtra(Intent.EXTRA_SUBJECT, note.getTitle());
    shareIntent.putExtra(Intent.EXTRA_TEXT, note.getContent());
    /// 唤起系统的分享选择器面板
    startActivity(Intent.createChooser(shareIntent, getResources().getString(R.string.share_message_chooser)));
  }

  public @Nullable Uri getShareableAttachmentUri(Attachment attachment) {
    try {
      return FileProviderHelper.getShareableUri(attachment);
    } catch (FileNotFoundException e) {
      LogDelegate.e(e.getMessage());
      Toast.makeText(this, R.string.attachment_not_found, Toast.LENGTH_SHORT).show();
      return null;
    }
  }


  /**
   * Single note permanent deletion
   *
   * @param note Note to be deleted
   */
  public void deleteNote(Note note) {
    new NoteProcessorDelete(Collections.singletonList(note)).process();
    BaseActivity.notifyAppWidgets(this);
    LogDelegate.d("Deleted permanently note with ID '" + note.get_id() + "'");
  }


  public void updateWidgets() {
    new UpdateWidgetsTask(getApplicationContext())
        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }


  public void showMessage(int messageId, Style style) {
    showMessage(getString(messageId), style);
  }


  public void showMessage(String message, Style style) {
    // ViewGroup used to show Crouton keeping compatibility with the new Toolbar
    runOnUiThread(
        () -> Crouton.makeText(this, message, style, binding.croutonHandle.croutonHandle).show());
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    prefsChanged = true;
  }

}
