package xyz.her3.enco.device.shell;

import xyz.her3.enco.device.shell.SettingsElement;

parcelable SettingsFrame {
    long id;
    SettingsElement[] elements;
    boolean requiresTakeover;
}
