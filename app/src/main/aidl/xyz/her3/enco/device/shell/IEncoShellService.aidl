package xyz.her3.enco.device.shell;

import xyz.her3.enco.device.shell.ScreenFrame;
import xyz.her3.enco.device.shell.SettingsFrame;

interface IEncoShellService {
    int getProcessUid() = 0;
    ScreenFrame captureScreen() = 1;
    boolean tap(long frameId, int x, int y) = 2;
    boolean swipe(long frameId, int fromX, int fromY, int toX, int toY) = 3;
    boolean pressBack() = 4;
    boolean pressHome() = 5;
    @nullable SettingsFrame observeSettings() = 6;
    boolean performSettingsAction(long frameId, int target, int command) = 7;
    boolean typeWeChatText(long frameId, String text) = 8;
    @nullable String[] readGmailInbox(int count) = 9;
    void destroy() = 16777114;
}
