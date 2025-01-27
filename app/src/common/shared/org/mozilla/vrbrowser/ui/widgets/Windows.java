package org.mozilla.vrbrowser.ui.widgets;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import org.jetbrains.annotations.NotNull;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.vrbrowser.R;
import org.mozilla.vrbrowser.VRBrowserApplication;
import org.mozilla.vrbrowser.browser.Accounts;
import org.mozilla.vrbrowser.browser.Media;
import org.mozilla.vrbrowser.browser.PromptDelegate;
import org.mozilla.vrbrowser.browser.SettingsStore;
import org.mozilla.vrbrowser.browser.engine.Session;
import org.mozilla.vrbrowser.browser.engine.SessionState;
import org.mozilla.vrbrowser.browser.engine.SessionStore;
import org.mozilla.vrbrowser.telemetry.TelemetryWrapper;
import org.mozilla.vrbrowser.ui.widgets.settings.SettingsWidget;
import org.mozilla.vrbrowser.utils.BitmapCache;
import org.mozilla.vrbrowser.utils.SystemUtils;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.stream.Collectors;

import mozilla.components.concept.sync.AccountObserver;
import mozilla.components.concept.sync.AuthType;
import mozilla.components.concept.sync.OAuthAccount;
import mozilla.components.concept.sync.Profile;

public class Windows implements TrayListener, TopBarWidget.Delegate, TitleBarWidget.Delegate,
        GeckoSession.ContentDelegate, WindowWidget.WindowListener, TabsWidget.TabDelegate {

    private static final String LOGTAG = SystemUtils.createLogtag(Windows.class);

    private static final String WINDOWS_SAVE_FILENAME = "windows_state.json";

    class WindowState {
        WindowPlacement placement;
        int textureWidth;
        int textureHeight;
        float worldWidth;
        int tabIndex = -1;

        public void load(WindowWidget aWindow, WindowsState aState, int aTabIndex) {
            WidgetPlacement widgetPlacement;
            if (aWindow.isFullScreen()) {
                widgetPlacement = aWindow.getBeforeFullscreenPlacement();
                placement = aWindow.getmWindowPlacementBeforeFullscreen();
            } else if (aWindow.isResizing()) {
                widgetPlacement = aWindow.getBeforeResizePlacement();
                placement = aWindow.getWindowPlacement();
            } else {
                widgetPlacement = aWindow.getPlacement();
                placement = aWindow.getWindowPlacement();
            }

            textureWidth = widgetPlacement.width;
            textureHeight = widgetPlacement.height;
            worldWidth = widgetPlacement.worldWidth;
            tabIndex = aTabIndex;
        }
    }

    class WindowsState {
        WindowPlacement focusedWindowPlacement = WindowPlacement.FRONT;
        ArrayList<WindowState> regularWindowsState = new ArrayList<>();
        ArrayList<SessionState> tabs = new ArrayList<>();
        boolean privateMode = false;
    }

    private Context mContext;
    private WidgetManagerDelegate mWidgetManager;
    private Delegate mDelegate;
    private ArrayList<WindowWidget> mRegularWindows;
    private ArrayList<WindowWidget> mPrivateWindows;
    private WindowWidget mFocusedWindow;
    private static int sIndex;
    private boolean mPrivateMode = false;
    public static final int MAX_WINDOWS = 3;
    private WindowWidget mFullscreenWindow;
    private WindowPlacement mRegularWindowPlacement;
    private WindowPlacement mPrivateWindowPlacement;
    private boolean mStoredCurvedMode = false;
    private boolean mForcedCurvedMode = false;
    private boolean mIsPaused = false;
    private PromptDelegate mPromptDelegate;
    private TabsWidget mTabsWidget;
    private Accounts mAccounts;

    public enum WindowPlacement{
        FRONT(0),
        LEFT(1),
        RIGHT(2);

        private final int value;

        WindowPlacement(final int aValue) {
            value = aValue;
        }

        public int getValue() { return value; }
    }

    public interface Delegate {
        void onFocusedWindowChanged(@NonNull WindowWidget aFocusedWindow, @Nullable WindowWidget aPrevFocusedWindow);
        void onWindowBorderChanged(@NonNull WindowWidget aChangeWindow);
        void onWindowsMoved();
        void onWindowClosed();
    }

    public Windows(Context aContext) {
        mContext = aContext;
        mWidgetManager = (WidgetManagerDelegate) aContext;
        mRegularWindows = new ArrayList<>();
        mPrivateWindows = new ArrayList<>();

        mRegularWindowPlacement = WindowPlacement.FRONT;
        mPrivateWindowPlacement = WindowPlacement.FRONT;

        mPromptDelegate = new PromptDelegate(mContext);

        mStoredCurvedMode = SettingsStore.getInstance(mContext).getCylinderDensity() > 0.0f;

        mAccounts = ((VRBrowserApplication)mContext.getApplicationContext()).getAccounts();
        mAccounts.addAccountListener(mAccountObserver);

        restoreWindows();
    }

    private void saveState() {
        File file = new File(mContext.getFilesDir(), WINDOWS_SAVE_FILENAME);
        try (Writer writer = new FileWriter(file)) {
            WindowsState state = new WindowsState();
            state.privateMode = mPrivateMode;
            state.focusedWindowPlacement = mFocusedWindow.isFullScreen() ?  mFocusedWindow.getmWindowPlacementBeforeFullscreen() : mFocusedWindow.getWindowPlacement();
            ArrayList<Session> sessions = SessionStore.get().getSortedSessions(false);
            state.tabs = sessions.stream().map(Session::getSessionState).collect(Collectors.toCollection(ArrayList::new));
            for (WindowWidget window : mRegularWindows) {
                WindowState windowState = new WindowState();
                windowState.load(window, state, sessions.indexOf(window.getSession()));
                state.regularWindowsState.add(windowState);
            }
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(state, writer);

            Log.d(LOGTAG, "Windows state saved");

        } catch (IOException e) {
            Log.e(LOGTAG, "Error saving windows state: " + e.getLocalizedMessage());
            file.delete();
        }
    }

    private WindowsState restoreState() {
        WindowsState restored = null;

        File file = new File(mContext.getFilesDir(), WINDOWS_SAVE_FILENAME);
        try (Reader reader = new FileReader(file)) {
            Gson gson = new GsonBuilder().create();
            Type type = new TypeToken<WindowsState>() {}.getType();
            restored = gson.fromJson(reader, type);

            Log.d(LOGTAG, "Windows state restored");

        } catch (Exception e) {
            Log.w(LOGTAG, "Error restoring windows state: " + e.getLocalizedMessage());

        } finally {
            file.delete();
        }

        return restored;
    }

    public void setDelegate(Delegate aDelegate) {
        mDelegate = aDelegate;
    }

    public WindowWidget getFocusedWindow() {
        if (mFullscreenWindow != null) {
            return mFullscreenWindow;
        }
        return mFocusedWindow;
    }

    public WindowWidget addWindow() {
        if (getCurrentWindows().size() >= MAX_WINDOWS) {
            return null;
        }

        if (mFullscreenWindow != null) {
            mFullscreenWindow.getSession().exitFullScreen();
            onFullScreen(mFullscreenWindow.getSession().getGeckoSession(), false);
        }

        WindowWidget frontWindow = getFrontWindow();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();

        WindowWidget newWindow = createWindow(null);
        WindowWidget focusedWindow = getFocusedWindow();

        if (frontWindow == null) {
            // First window
            placeWindow(newWindow, WindowPlacement.FRONT);
        } else if (leftWindow == null && rightWindow == null) {
            // Opening a new window from one window
            placeWindow(newWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.LEFT);
        } else if (leftWindow != null && focusedWindow == leftWindow) {
            // Opening a new window from left window
            placeWindow(newWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.RIGHT);
        } else if (leftWindow != null && focusedWindow == frontWindow) {
            // Opening a new window from front window
            placeWindow(newWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.RIGHT);
        } else if (rightWindow != null && focusedWindow == rightWindow) {
            // Opening a new window from right window
            placeWindow(newWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.LEFT);
        } else if (rightWindow != null && focusedWindow == frontWindow) {
            // Opening a new window from right window
            placeWindow(newWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.LEFT);
        }

        updateMaxWindowScales();
        mWidgetManager.addWidget(newWindow);
        focusWindow(newWindow);
        updateCurvedMode(true);
        updateViews();
        return newWindow;
    }

    private WindowWidget addRestoredWindow(@NonNull WindowState aState, @NonNull Session aSession) {
        if (getCurrentWindows().size() >= MAX_WINDOWS) {
            return null;
        }

        WindowWidget newWindow = createWindow(aSession);
        newWindow.getPlacement().width = aState.textureWidth;
        newWindow.getPlacement().height = aState.textureHeight;
        newWindow.getPlacement().worldWidth = aState.worldWidth;
        newWindow.setRestored(true);
        placeWindow(newWindow, aState.placement);
        updateCurvedMode(true);

        mWidgetManager.addWidget(newWindow);
        return newWindow;
    }

    public void closeWindow(@NonNull WindowWidget aWindow) {
        WindowWidget frontWindow = getFrontWindow();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();

        aWindow.hideBookmarks();
        aWindow.hideHistory();

        if (leftWindow == aWindow) {
            removeWindow(leftWindow);
            if (mFocusedWindow == leftWindow) {
                focusWindow(frontWindow);
            }
        } else if (rightWindow == aWindow) {
            removeWindow(rightWindow);
            if (mFocusedWindow == rightWindow) {
                focusWindow(frontWindow);
            }
        } else if (frontWindow == aWindow) {
            removeWindow(frontWindow);
            if (rightWindow != null) {
                placeWindow(rightWindow, WindowPlacement.FRONT);
            } else if (leftWindow != null) {
                placeWindow(leftWindow, WindowPlacement.FRONT);
            }

            if (mFocusedWindow == frontWindow && !getCurrentWindows().isEmpty()) {
                focusWindow(getFrontWindow());
            }

        }

        boolean empty = getCurrentWindows().isEmpty();
        if (empty && isInPrivateMode()) {
            // Exit private mode if the only window is closed.
            exitPrivateMode();
        } else if (empty) {
            // Ensure that there is at least one window.
            WindowWidget window = addWindow();
            if (window != null) {
                window.loadHome();
            }
        }

        updateViews();
        if (mDelegate != null) {
            mDelegate.onWindowClosed();
        }
    }

    public void moveWindowRight(@NonNull WindowWidget aWindow) {
        WindowWidget frontWindow = getFrontWindow();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();

        if (aWindow == leftWindow) {
            placeWindow(leftWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.LEFT);
            switchTopBars(leftWindow, frontWindow);
        } else if (aWindow == frontWindow) {
            if (rightWindow != null) {
                placeWindow(rightWindow, WindowPlacement.FRONT);
                switchTopBars(rightWindow, frontWindow);
            } else if (leftWindow != null) {
                placeWindow(leftWindow, WindowPlacement.FRONT);
                switchTopBars(leftWindow, frontWindow);
            }
            placeWindow(frontWindow, WindowPlacement.RIGHT);
        }
        updateViews();
        if (mDelegate != null) {
            mDelegate.onWindowsMoved();
        }
    }

    public void moveWindowLeft(@NonNull WindowWidget aWindow) {
        WindowWidget frontWindow = getFrontWindow();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();

        if (aWindow == rightWindow) {
            placeWindow(rightWindow, WindowPlacement.FRONT);
            placeWindow(frontWindow, WindowPlacement.RIGHT);
            switchTopBars(rightWindow, frontWindow);
        } else if (aWindow == frontWindow) {
            if (leftWindow != null) {
                placeWindow(leftWindow, WindowPlacement.FRONT);
                switchTopBars(leftWindow, frontWindow);
            } else if (rightWindow != null) {
                placeWindow(rightWindow, WindowPlacement.FRONT);
                switchTopBars(rightWindow, frontWindow);
            }
            placeWindow(frontWindow, WindowPlacement.LEFT);
        }
        updateViews();
        if (mDelegate != null) {
            mDelegate.onWindowsMoved();
        }
    }

    public void focusWindow(@NonNull WindowWidget aWindow) {
        if (aWindow != mFocusedWindow) {
            WindowWidget prev = mFocusedWindow;
            mFocusedWindow = aWindow;
            if (prev != null && getCurrentWindows().contains(prev)) {
                prev.setActiveWindow(false);
                if (prev.isVisible()) {
                    prev.getTitleBar().setVisible(true);
                }
            }
            mFocusedWindow.getTitleBar().setVisible(false);
            mFocusedWindow.setActiveWindow(true);
            if (mDelegate != null) {
                mDelegate.onFocusedWindowChanged(mFocusedWindow, prev);
            }

            mPromptDelegate.attachToWindow(mFocusedWindow);
        }
    }

    public void pauseCompositor() {
        for (WindowWidget window: mRegularWindows) {
            window.pauseCompositor();
        }
        for (WindowWidget window: mPrivateWindows) {
            window.pauseCompositor();
        }
    }

    public void resumeCompositor() {
        for (WindowWidget window: mRegularWindows) {
            window.resumeCompositor();
        }
        for (WindowWidget window: mPrivateWindows) {
            window.resumeCompositor();
        }
    }

    public void onPause() {
        mIsPaused = true;

        saveState();
        for (WindowWidget window: mRegularWindows) {
            window.onPause();
        }
        for (WindowWidget window: mPrivateWindows) {
            window.onPause();
        }
    }

    public void onResume() {
        mIsPaused = false;

        for (WindowWidget window: mRegularWindows) {
            window.onResume();
        }
        for (WindowWidget window: mPrivateWindows) {
            window.onResume();
        }
    }

    public void onDestroy() {
        mDelegate = null;
        mPromptDelegate.detachFromWindow();
        for (WindowWidget window: mRegularWindows) {
            window.close();
        }
        for (WindowWidget window: mPrivateWindows) {
            window.close();
        }
        mAccounts.removeAccountListener(mAccountObserver);
    }

    public boolean isInPrivateMode() {
        return mPrivateMode;
    }

    public void enterImmersiveMode() {
        if (!isInPrivateMode()) {
            for (WindowWidget window: mRegularWindows) {
                if (window != mFocusedWindow) {
                    window.onPause();
                }
            }

        } else {
            for (WindowWidget window: mPrivateWindows) {
                if (window != mFocusedWindow) {
                    window.onPause();
                }
            }
        }
    }

    public void exitImmersiveMode() {
        if (mIsPaused) {
            return;
        }

        if (!isInPrivateMode()) {
            for (WindowWidget window: mRegularWindows) {
                if (window != mFocusedWindow) {
                    window.onResume();
                }
            }

        } else {
            for (WindowWidget window: mPrivateWindows) {
                if (window != mFocusedWindow) {
                    window.onResume();
                }
            }
        }
    }

    public void enterPrivateMode() {
        if (mPrivateMode) {
            return;
        }
        mPrivateMode = true;
        updateCurvedMode(true);
        if (mFocusedWindow != null) {
            mRegularWindowPlacement = mFocusedWindow.getWindowPlacement();

        } else {
            mRegularWindowPlacement = WindowPlacement.FRONT;
        }
        for (WindowWidget window: mRegularWindows) {
            setWindowVisible(window, false);
        }
        for (WindowWidget window: mPrivateWindows) {
            setWindowVisible(window, true);
        }

        if (mPrivateWindows.size() == 0) {
            WindowWidget window = addWindow();
            if (window != null) {
                window.loadHome();
            }

        } else {
            focusWindow(getWindowWithPlacement(mPrivateWindowPlacement));
        }
        updateViews();
        mWidgetManager.pushWorldBrightness(this, WidgetManagerDelegate.DEFAULT_DIM_BRIGHTNESS);
    }

    public void exitPrivateMode() {
        if (!mPrivateMode) {
            return;
        }
        mPrivateMode = false;
        updateCurvedMode(true);
        if (mFocusedWindow != null) {
            mPrivateWindowPlacement = mFocusedWindow.getWindowPlacement();

        } else {
            mPrivateWindowPlacement = WindowPlacement.FRONT;
        }
        for (WindowWidget window: mPrivateWindows) {
            setWindowVisible(window, false);
        }
        for (WindowWidget window: mRegularWindows) {
            setWindowVisible(window, true);
        }
        focusWindow(getWindowWithPlacement(mRegularWindowPlacement));
        updateViews();
        mWidgetManager.popWorldBrightness(this);
    }

    public boolean handleBack() {
        if (mFocusedWindow == null) {
            return false;
        }
        if (mFocusedWindow.getSession().canGoBack()) {
            mFocusedWindow.getSession().goBack();
            return true;
        } else if (isInPrivateMode()) {
            exitPrivateMode();
            return true;
        }

        return false;
    }

    private void updateMaxWindowScales() {
        float maxScale = 3;
        if (mFullscreenWindow == null && getCurrentWindows().size() >= 3) {
            maxScale = 1.5f;
        } else if (mFullscreenWindow == null && getCurrentWindows().size() == 2) {
            maxScale = 2.0f;
        }

        for (WindowWidget window: getCurrentWindows()) {
            window.setMaxWindowScale(maxScale);
        }
    }

    public ArrayList<WindowWidget> getCurrentWindows() {
        return mPrivateMode ? mPrivateWindows : mRegularWindows;
    }

    private WindowWidget getWindowWithPlacement(WindowPlacement aPlacement) {
        for (WindowWidget window: getCurrentWindows()) {
            if (window.getWindowPlacement() == aPlacement) {
                return window;
            }
        }
        return null;
    }

    private WindowWidget getFrontWindow() {
        if (mFullscreenWindow != null) {
            return mFullscreenWindow;
        }
        return getWindowWithPlacement(WindowPlacement.FRONT);
    }

    private WindowWidget getLeftWindow() {
        return getWindowWithPlacement(WindowPlacement.LEFT);
    }

    private WindowWidget getRightWindow() {
        return getWindowWithPlacement(WindowPlacement.RIGHT);
    }

    private void restoreWindows() {
        WindowsState windowsState = restoreState();
        if (windowsState != null) {
            ArrayList<Session> restoredSessions = new ArrayList<>();
            if (windowsState.tabs != null) {
                windowsState.tabs.forEach(state -> restoredSessions.add(SessionStore.get().createSession(state)));
            }
            mPrivateMode = false;
            for (WindowState windowState : windowsState.regularWindowsState) {
                if (windowState.tabIndex >= 0 && windowState.tabIndex < restoredSessions.size()) {
                    addRestoredWindow(windowState, restoredSessions.get(windowState.tabIndex));
                }
            }
            mPrivateMode = !windowsState.privateMode;
            if (windowsState.privateMode) {
                enterPrivateMode();
            } else {
                exitPrivateMode();
            }

            WindowWidget windowToFocus = getWindowWithPlacement(windowsState.focusedWindowPlacement);
            if (windowToFocus == null) {
                windowToFocus = getFrontWindow();
                if (windowToFocus == null && getCurrentWindows().size() > 0) {
                    windowToFocus = getCurrentWindows().get(0);
                }
            }
            if (windowToFocus != null) {
                focusWindow(windowToFocus);
            }

        }
        if (getCurrentWindows().size() == 0) {
            WindowWidget window = addWindow();
            focusWindow(window);
        }
        updateMaxWindowScales();
        updateViews();
    }

    private void removeWindow(@NonNull WindowWidget aWindow) {
        BitmapCache.getInstance(mContext).removeBitmap(aWindow.getSession().getId());
        mWidgetManager.removeWidget(aWindow);
        mRegularWindows.remove(aWindow);
        mPrivateWindows.remove(aWindow);
        aWindow.getTopBar().setVisible(false);
        aWindow.getTopBar().setDelegate((TopBarWidget.Delegate) null);
        aWindow.removeWindowListener(this);
        aWindow.getTitleBar().setVisible(false);
        aWindow.getTitleBar().setDelegate((TitleBarWidget.Delegate) null);
        aWindow.getSession().removeContentListener(this);
        aWindow.close();
        updateMaxWindowScales();
        updateCurvedMode(true);

        if (mPrivateMode) {
            TelemetryWrapper.openWindowsEvent(mPrivateWindows.size() + 1, mPrivateWindows.size(), true);
        } else {
            TelemetryWrapper.openWindowsEvent(mRegularWindows.size() + 1, mRegularWindows.size(), false);
        }
    }

    private void setWindowVisible(@NonNull WindowWidget aWindow, boolean aVisible) {
        aWindow.setVisible(aVisible);
        aWindow.getTopBar().setVisible(aVisible);
        aWindow.getTitleBar().setVisible(aVisible);
    }

    private void placeWindow(@NonNull WindowWidget aWindow, WindowPlacement aPosition) {
        placeWindow(aWindow, aPosition, mStoredCurvedMode || mForcedCurvedMode);
    }

    private void placeWindow(@NonNull WindowWidget aWindow, WindowPlacement aPosition, boolean curvedMode) {
        WidgetPlacement placement = aWindow.getPlacement();
        aWindow.setWindowPlacement(aPosition);
        switch (aPosition) {
            case FRONT:
                placement.anchorX = 0.5f;
                placement.anchorY = 0.0f;
                placement.rotation = 0;
                placement.rotationAxisX = 0;
                placement.rotationAxisY = 0;
                placement.rotationAxisZ = 0;
                placement.translationX = 0.0f;
                placement.translationY = WidgetPlacement.unitFromMeters(mContext, R.dimen.window_world_y);
                placement.translationZ = WidgetPlacement.unitFromMeters(mContext, R.dimen.window_world_z);
                break;
            case LEFT:
                placement.anchorX = 1.0f;
                placement.anchorY = 0.0f;
                placement.parentAnchorX = 0.0f;
                placement.parentAnchorY = 0.0f;
                placement.rotationAxisX = 0;
                placement.rotationAxisZ = 0;
                if (curvedMode) {
                    placement.rotationAxisY = 0;
                    placement.rotation = 0;
                } else {
                    placement.rotationAxisY = 1.0f;
                    placement.rotation = (float) Math.toRadians(WidgetPlacement.floatDimension(mContext, R.dimen.multi_window_angle));
                }
                placement.translationX = -WidgetPlacement.dpDimension(mContext, R.dimen.multi_window_padding);
                placement.translationY = 0.0f;
                placement.translationZ = 0.0f;
                break;
            case RIGHT:
                placement.anchorX = 0.0f;
                placement.anchorY = 0.0f;
                placement.parentAnchorX = 1.0f;
                placement.parentAnchorY = 0.0f;
                placement.rotationAxisX = 0;
                placement.rotationAxisZ = 0;
                if (curvedMode) {
                    placement.rotationAxisY = 0;
                    placement.rotation = 0;
                } else {
                    placement.rotationAxisY = 1.0f;
                    placement.rotation = (float) Math.toRadians(-WidgetPlacement.floatDimension(mContext, R.dimen.multi_window_angle));
                }

                placement.translationX = WidgetPlacement.dpDimension(mContext, R.dimen.multi_window_padding);
                placement.translationY = 0.0f;
                placement.translationZ = 0.0f;
        }
    }

    public void updateCurvedMode(boolean force) {
        float density = SettingsStore.getInstance(mContext).getCylinderDensity();
        boolean storedCurvedMode = density > 0.0f;
        boolean forcedCurvedMode = getCurrentWindows().size() > 1;

        if (force) {
            boolean curved = forcedCurvedMode || storedCurvedMode;

            for (WindowWidget window : getCurrentWindows()) {
                placeWindow(window, window.getWindowPlacement(), curved);
            }
            updateViews();
            mWidgetManager.setCylinderDensity(curved ? SettingsStore.CYLINDER_DENSITY_ENABLED_DEFAULT : density);

        } else if ((storedCurvedMode != mStoredCurvedMode) || (forcedCurvedMode != mForcedCurvedMode)) {
            mStoredCurvedMode = storedCurvedMode;
            mForcedCurvedMode = forcedCurvedMode;

            boolean curved = mStoredCurvedMode || mForcedCurvedMode;
            for (WindowWidget window : getCurrentWindows()) {
                placeWindow(window, window.getWindowPlacement(), curved);
            }
            updateViews();
            mWidgetManager.setCylinderDensity(curved ? SettingsStore.CYLINDER_DENSITY_ENABLED_DEFAULT : density);
        }
    }

    public int getWindowsCount() {
        return getCurrentWindows().size();
    }

    public boolean canOpenNewWindow() {
        return getWindowsCount() < MAX_WINDOWS;
    }

    private void switchTopBars(WindowWidget w1, WindowWidget w2) {
        // Used to fix a minor visual glitch.
        // See https://github.com/MozillaReality/FirefoxReality/issues/1722
        TopBarWidget bar1 = w1.getTopBar();
        TopBarWidget bar2 = w2.getTopBar();
        w1.setTopBar(bar2);
        w2.setTopBar(bar1);
    }

    private void updateViews() {
        WindowWidget frontWindow = getFrontWindow();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();
        // Make sure that left or right window have the correct parent
        if (frontWindow != null && leftWindow != null) {
            leftWindow.getPlacement().parentHandle = frontWindow.getHandle();
        }
        if (frontWindow != null &&  rightWindow != null) {
            rightWindow.getPlacement().parentHandle = frontWindow.getHandle();
        }
        if (frontWindow != null) {
            frontWindow.getPlacement().parentHandle = -1;
        }

        updateTopBars();
        updateTitleBars();

        ArrayList<WindowWidget> windows = getCurrentWindows();
        // Sort windows so frontWindow is the first one. Required for proper native matrix updates.
        windows.sort((o1, o2) -> o1 == frontWindow ? -1 : 0);
        for (WindowWidget window: getCurrentWindows()) {
            mWidgetManager.updateWidget(window);
            mWidgetManager.updateWidget(window.getTopBar());
            mWidgetManager.updateWidget(window.getTitleBar());
        }
    }

    private void updateTopBars() {
        ArrayList<WindowWidget> windows = getCurrentWindows();
        WindowWidget leftWindow = getLeftWindow();
        WindowWidget rightWindow = getRightWindow();
        boolean visible = mFullscreenWindow == null && (windows.size() > 1 || isInPrivateMode());
        for (WindowWidget window: windows) {
            window.getTopBar().setVisible(visible);
            window.getTopBar().setClearMode((windows.size() == 1 && isInPrivateMode()));
            if (visible) {
                window.getTopBar().setMoveLeftButtonEnabled(window != leftWindow);
                window.getTopBar().setMoveRightButtonEnabled(window != rightWindow);
            }
        }
        if (isInPrivateMode() && mPrivateWindows.size() == 1) {
            if (mFocusedWindow != null) {
                mFocusedWindow.getTopBar().setMoveLeftButtonEnabled(false);
                mFocusedWindow.getTopBar().setMoveRightButtonEnabled(false);
            }
        }
    }

    private void updateTitleBars() {
        ArrayList<WindowWidget> windows = getCurrentWindows();
        for (WindowWidget window: windows) {
            if (window == mFocusedWindow) {
                window.getTitleBar().setVisible(false);

            } else {
                if (mFullscreenWindow != null) {
                    window.getTitleBar().setVisible(false);

                } else {
                    window.getTitleBar().setVisible(true);
                }
            }
        }
    }

    private WindowWidget createWindow(@Nullable Session aSession) {
        int newWindowId = sIndex++;
        WindowWidget window;
        if (aSession != null) {
            window = new WindowWidget(mContext, newWindowId, aSession);
        } else {
            window = new WindowWidget(mContext, newWindowId, mPrivateMode);
        }

        window.addWindowListener(this);
        getCurrentWindows().add(window);
        window.getTopBar().setDelegate(this);
        window.getTitleBar().setDelegate(this);
        window.getSession().addContentListener(this);

        if (mPrivateMode) {
            TelemetryWrapper.openWindowsEvent(mPrivateWindows.size() - 1, mPrivateWindows.size(), true);
        } else {
            TelemetryWrapper.openWindowsEvent(mRegularWindows.size() - 1, mRegularWindows.size(), false);
        }

        mForcedCurvedMode = getCurrentWindows().size() > 1;

        return window;
    }

    public void enterResizeMode() {
        if (mFullscreenWindow == null) {
            for (WindowWidget window : getCurrentWindows()) {
                window.getTopBar().setVisible(false);
            }
        }
    }

    public void exitResizeMode() {
        if (mFullscreenWindow == null) {
            for (WindowWidget window : getCurrentWindows()) {
                if (getCurrentWindows().size() > 1 || isInPrivateMode()) {
                    window.getTopBar().setVisible(true);
                }
            }
        }
    }

    private AccountObserver mAccountObserver = new AccountObserver() {
        @Override
        public void onLoggedOut() {

        }

        @Override
        public void onAuthenticated(@NotNull OAuthAccount oAuthAccount, @NotNull AuthType authType) {
            switch (mAccounts.getLoginOrigin()) {
                case BOOKMARKS:
                    getFocusedWindow().switchBookmarks();
                    break;

                case HISTORY:
                    getFocusedWindow().switchHistory();
                    break;

                case SETTINGS:
                    mWidgetManager.getTray().toggleSettingsDialog(SettingsWidget.SettingDialog.FXA);
                    break;
            }
        }

        @Override
        public void onProfileUpdated(@NotNull Profile profile) {

        }

        @Override
        public void onAuthenticationProblems() {

        }
    };

    // Tray Listener
    @Override
    public void onBookmarksClicked() {
        mFocusedWindow.switchBookmarks();
    }

    @Override
    public void onPrivateBrowsingClicked() {
        if (mPrivateMode) {
            exitPrivateMode();
        } else {
            enterPrivateMode();
        }
    }

    @Override
    public void onAddWindowClicked() {
        WindowWidget window = addWindow();
        if (window != null) {
            window.loadHome();
        }
    }

    @Override
    public void onHistoryClicked() {
        mFocusedWindow.switchHistory();
    }

    @Override
    public void onTabsClicked() {
        if (mTabsWidget == null) {
            mTabsWidget = new TabsWidget(mContext);
            mTabsWidget.setTabDelegate(this);
        }

        if (mFocusedWindow != null) {
            mTabsWidget.getPlacement().parentHandle = mFocusedWindow.getHandle();
            mTabsWidget.attachToWindow(mFocusedWindow);
            mTabsWidget.show(UIWidget.KEEP_FOCUS);
        }
    }

    // TopBarWidget Delegate
    @Override
    public void onCloseClicked(TopBarWidget aWidget) {
        WindowWidget window = aWidget.getAttachedWindow();
        if (window != null) {
            closeWindow(window);
        }
    }

    @Override
    public void onMoveLeftClicked(TopBarWidget aWidget) {
        WindowWidget window = aWidget.getAttachedWindow();
        if (window != null) {
            TelemetryWrapper.windowsMoveEvent();

            moveWindowLeft(window);
        }
    }

    @Override
    public void onMoveRightClicked(TopBarWidget aWidget) {
        WindowWidget window = aWidget.getAttachedWindow();
        if (window != null) {
            TelemetryWrapper.windowsMoveEvent();

            moveWindowRight(window);
        }
    }

    // Title Bar Delegate
    @Override
    public void onTitleClicked(@NonNull TitleBarWidget titleBar) {
        focusWindow(titleBar.getAttachedWindow());
    }

    @Override
    public void onMediaPlayClicked(@NonNull TitleBarWidget titleBar) {
        for (WindowWidget window : getCurrentWindows()) {
            if (window.getTitleBar() == titleBar) {
                window.getSession().getFullScreenVideo().play();
            }
        }
    }

    @Override
    public void onMediaPauseClicked(@NonNull TitleBarWidget titleBar) {
        for (WindowWidget window : getCurrentWindows()) {
            if (window.getTitleBar() == titleBar) {
                window.getSession().getFullScreenVideo().pause();
            }
        }
    }

    private void setFullScreenSize(WindowWidget aWindow) {
        final float minScale = WidgetPlacement.floatDimension(mContext, R.dimen.window_fullscreen_min_scale);
        // Set browser fullscreen size
        float aspect = SettingsStore.getInstance(mContext).getWindowAspect();
        Session session = mFocusedWindow.getSession();
        if (session == null) {
            return;
        }
        Media media = session.getFullScreenVideo();
        if (media != null && media.getWidth() > 0 && media.getHeight() > 0) {
            aspect = (float)media.getWidth() / (float)media.getHeight();
        }
        float scale = aWindow.getCurrentScale();
        // Enforce min fullscreen size.
        // If current window area is larger only resize if the aspect changes (e.g. media).
        if (scale < minScale || aspect != aWindow.getCurrentAspect()) {
            aWindow.resizeByMultiplier(aspect, Math.max(scale, minScale));
        }
    }

    // Content delegate
    @Override
    public void onFullScreen(GeckoSession session, boolean aFullScreen) {
        WindowWidget window = getWindowWithSession(session);
        if (window == null) {
            return;
        }

        if (aFullScreen) {
            mFullscreenWindow = window;
            window.saveBeforeFullscreenPlacement();
            setFullScreenSize(window);
            placeWindow(window, WindowPlacement.FRONT);
            focusWindow(window);
            for (WindowWidget win: getCurrentWindows()) {
                setWindowVisible(win, win == mFullscreenWindow);
            }
            updateMaxWindowScales();
            updateViews();
        } else if (mFullscreenWindow != null) {
            window.restoreBeforeFullscreenPlacement();
            mFullscreenWindow = null;
            for (WindowWidget win : getCurrentWindows()) {
                setWindowVisible(win, true);
            }
            updateMaxWindowScales();
            updateViews();
        }
    }

    @Nullable
    private WindowWidget getWindowWithSession(GeckoSession aSession) {
        for (WindowWidget window: getCurrentWindows()) {
            if (window.getSession().getGeckoSession() == aSession) {
                return window;
            }
        }
        return null;
    }

    @Nullable
    private WindowWidget getWindowWithSession(Session aSession) {
        for (WindowWidget window: getCurrentWindows()) {
            if (window.getSession() == aSession) {
                return window;
            }
        }
        return null;
    }

    // WindowWidget.Delegate
    @Override
    public void onFocusRequest(WindowWidget aWindow) {
        focusWindow(aWindow);
    }

    @Override
    public void onBorderChanged(WindowWidget aWindow) {
        if (mDelegate != null) {
            mDelegate.onWindowBorderChanged(aWindow);
        }

    }

    @Override
    public void onTabSelect(Session aTab) {
        WindowWidget targetWindow = mFocusedWindow;
        WindowWidget windowToMove = getWindowWithSession(aTab);
        if (windowToMove != null && windowToMove != targetWindow) {
            // Move session between windows
            Session moveFrom = windowToMove.getSession();
            Session moveTo = targetWindow.getSession();
            windowToMove.releaseDisplay(moveFrom.getGeckoSession());
            targetWindow.releaseDisplay(moveTo.getGeckoSession());
            windowToMove.setSession(moveTo);
            targetWindow.setSession(moveFrom);
            SessionStore.get().setActiveSession(targetWindow.getSession());

        } else{
            targetWindow.getSession().setActive(false);
            targetWindow.setSession(aTab);
            targetWindow.getSession().setActive(true);
            SessionStore.get().setActiveSession(aTab);
        }
    }

    public void addTab(WindowWidget targetWindow) {
        Session session = SessionStore.get().createSession(targetWindow.getSession().isPrivateMode());
        targetWindow.setFirstPaintReady(false);
        targetWindow.setFirstDrawCallback(() -> {
            if (!targetWindow.isFirstPaintReady()) {
                targetWindow.setFirstPaintReady(true);
                mWidgetManager.updateWidget(targetWindow);
            }
        });
        mWidgetManager.updateWidget(targetWindow);
        targetWindow.getSession().setActive(false);
        targetWindow.setSession(session);
        session.loadHomePage();
        SessionStore.get().setActiveSession(session);
    }

    public void addBackgroundTab(WindowWidget targetWindow, String aUri) {
        Session session = SessionStore.get().createSession(targetWindow.getSession().isPrivateMode());
        session.loadUri(aUri);
        session.updateLastUse();
        mFocusedWindow.getSession().updateLastUse();
    }

    @Override
    public void onTabAdd() {
        addTab(mFocusedWindow);
    }

    @Override
    public void onTabsClose(ArrayList<Session> aTabs) {
        WindowWidget targetWindow = mFocusedWindow;
        // Prepare available tabs to choose from
        ArrayList<Session> available = SessionStore.get().getSortedSessions(mPrivateMode);
        available.removeAll(aTabs);
        available.removeIf(session -> getWindowWithSession(session) != null);

        // Sort windows by priority to take an available tab
        WindowWidget front = getFrontWindow();
        ArrayList<WindowWidget> windows =  new ArrayList<>(getCurrentWindows());
        windows.sort((w1, w2) -> {
            // Max priority for the target window
            if (w1 == targetWindow) {
                return -1;
            }
            if (w2 == targetWindow) {
                return 1;
            }
            // Front window has next max priority
            if (w1 == front) {
                return -1;
            }
            if (w2 == front) {
                return 1;
            }
            return 0;
        });

        // Take tabs for each window
        for (WindowWidget window: windows) {
            if (!aTabs.contains(window.getSession())) {
                // Window already contains a no closed tab
                continue;
            }
            if (available.size() > 0) {
                // Window contains a closed tab and we have a tab available from the list
                window.setSession(available.get(0));
                window.getSession().setActive(true);
                available.remove(0);
            } else {
                // We don't have more tabs available for the front window, load home.
                addTab(window);
            }
        }

        BitmapCache cache = BitmapCache.getInstance(mContext);
        for (Session session: aTabs) {
            cache.removeBitmap(session.getId());
            SessionStore.get().destroySession(session);
        }

        SessionStore.get().setActiveSession(targetWindow.getSession());
    }
}
