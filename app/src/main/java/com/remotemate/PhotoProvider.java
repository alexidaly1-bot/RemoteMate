package com.remotemate;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the camera app save one photo into our cache (the TV's About screen), so we don't need the camera permission.
 * Only ever serves cache/tv-photo.jpg.
 */
public class PhotoProvider extends ContentProvider {
    static final String FILE = "tv-photo.jpg";

    static Uri uri(android.content.Context c) { return Uri.parse("content://" + c.getPackageName() + ".photo/" + FILE); }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE.equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        File f = new File(getContext().getCacheDir(), FILE);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode) | ParcelFileDescriptor.MODE_CREATE);
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
