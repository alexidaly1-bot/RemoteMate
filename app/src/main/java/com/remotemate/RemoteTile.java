package com.remotemate;

import android.app.PendingIntent;
import android.content.Intent;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile: tap to show or hide the remote. */
public class RemoteTile extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(RemoteService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("RemoteMate");
        t.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // the Intent version is the only one on Android 13
    public void onClick() {
        if (RemoteService.running && Settings.canDrawOverlays(this)) {
            startService(new Intent(this, RemoteService.class).setAction(RemoteService.TOGGLE));
            collapse();
        } else {
            Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
            else startActivityAndCollapse(i);
        }
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("WrongConstant")
    private void collapse() {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("collapsePanels").invoke(sb);
        } catch (Exception ignored) { }
    }
}
