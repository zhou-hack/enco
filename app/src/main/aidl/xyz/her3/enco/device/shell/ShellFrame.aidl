package xyz.her3.enco.device.shell;

import android.graphics.Bitmap;
import xyz.her3.enco.device.shell.ShellNode;

parcelable ShellFrame {
    long id;
    Bitmap image;
    @nullable String packageName;
    int width;
    int height;
    int rotation;
    ShellNode[] nodes;
}
