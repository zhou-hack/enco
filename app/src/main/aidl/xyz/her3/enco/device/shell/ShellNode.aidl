package xyz.her3.enco.device.shell;

parcelable ShellNode {
    int[] nodePath;
    int left;
    int top;
    int right;
    int bottom;
    @nullable String packageName;
    @nullable String className;
    @nullable String text;
    @nullable String contentDescription;
    @nullable String hintText;
    boolean visible;
    boolean enabled;
    boolean canTap;
    boolean canLongPress;
    boolean password;
    boolean canEdit;
    boolean canScrollForward;
    boolean canScrollBackward;
}
