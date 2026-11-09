package com.remotemate;

/**
 * Generic remote buttons. Layouts only ever name these, so a layout works on any TV brand.
 * Each driver maps them to its own codes (see GoogleTvDriver.CODES, RokuDriver.KEYS).
 */
public enum Button {
    POWER, MUTE, VOL_UP, VOL_DOWN, CH_UP, CH_DOWN,
    INPUT, USB, HDMI1, HDMI2, HDMI3, HDMI4, LIVE_TV,
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, SETTINGS, PROFILE, GUIDE, INFO, MENU, APPS,
    PLAY_PAUSE, PLAY, PAUSE, STOP, REWIND, FFWD, PREV, NEXT, REPLAY,
    SEARCH, CAPTIONS, RED, GREEN, YELLOW, BLUE,
    NUM_0, NUM_1, NUM_2, NUM_3, NUM_4, NUM_5, NUM_6, NUM_7, NUM_8, NUM_9,
    DEL, ENTER, FIND_REMOTE;

    /** Press directions (same numbers as the Google TV protocol). */
    public static final int SHORT = 3, START_LONG = 1, END_LONG = 2;

    public static Button parse(String name) {
        try { return valueOf(name); } catch (Exception e) { return null; }
    }
}
