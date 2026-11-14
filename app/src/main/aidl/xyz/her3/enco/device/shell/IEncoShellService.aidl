package xyz.her3.enco.device.shell;

import xyz.her3.enco.device.shell.ScreenFrame;
import xyz.her3.enco.device.shell.SettingsFrame;
import xyz.her3.enco.device.shell.ShellFrame;

interface IEncoShellService {
    const int APP_SETTINGS = 1;
    const int APP_WECHAT = 2;
    const int APP_GMAIL = 3;

    int getProcessUid() = 0;
    ScreenFrame captureScreen() = 1;
    boolean tap(long frameId, int x, int y) = 2;
    boolean swipe(long frameId, int fromX, int fromY, int toX, int toY) = 3;
    boolean pressBack() = 4;
    boolean pressHome() = 5;
    @nullable SettingsFrame observeSettings() = 6;
    boolean performSettingsAction(long frameId, int target, int command) = 7;
    boolean pasteWeChatText(long frameId, String text) = 8;
    @nullable String[] readGmailInbox(int count) = 9;
    boolean launchApp(int app) = 10;
    int grantSelfPermissions() = 11;
    ShellFrame observeMainScreen() = 12;
    boolean longPress(long frameId, int x, int y) = 13;
    boolean pressKey(int keyCode) = 14;
    boolean launchPackage(String packageName) = 15;
    boolean setText(long frameId, in int[] path, String text) = 16;
    void destroy() = 16777114;
}
