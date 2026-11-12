package com.max.pod;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    PodView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        view = new PodView(this);
        setContentView(view);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (granted()) load(); else { view.setSongs(null); ask(); }
    }

    String perm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    boolean granted() {
        return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED;
    }

    void ask() {
        requestPermissions(Build.VERSION.SDK_INT >= 33
                ? new String[]{perm(), Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{perm()}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (granted()) load();
    }

    void load() {
        List<Song> songs = new ArrayList<>();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION};
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                Song s = new Song();
                s.id = c.getLong(0);
                s.title = c.getString(1) == null ? "Untitled" : c.getString(1);
                s.artist = clean(c.getString(2), "Unknown Artist");
                s.album = clean(c.getString(3), "Unknown Album");
                s.duration = c.getInt(4);
                songs.add(s);
            }
        } catch (Exception ignored) {
        }
        view.setSongs(songs);
    }

    static String clean(String s, String fallback) {
        return s == null || s.isEmpty() || s.equals("<unknown>") ? fallback : s;
    }

    @Override public void onBackPressed() {
        if (!view.menu()) moveTaskToBack(true);
    }

    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
