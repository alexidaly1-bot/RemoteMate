package com.remotemate;

import android.view.View;
import android.view.WindowInsets;

/** Android 15+ draws apps edge to edge. This keeps the page clear of the status bar, nav bar and keyboard. */
final class Insets {
    private Insets() {}

    static void pad(View v) {
        v.setOnApplyWindowInsetsListener((view, in) -> {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
    }
}
