package com.max.pod;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
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
    String loadedFor;                    // which music folder the list on screen came from

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(0xff09090a));
        view = new PodView(this);
        String forced = getIntent().getStringExtra("finish");   // for screenshots: adb ... -e finish black
        if ("black".equals(forced)) view.hw.finish = Hardware.BLACK;
        else if ("red".equals(forced)) view.hw.finish = Hardware.RED;
        else if ("silver".equals(forced)) view.hw.finish = Hardware.SILVER;
        setContentView(view);
        PlayerService.watcher = view;
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        if (granted()) load(); else { view.setSongs(null, false); ask(); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (loadedFor != null && !loadedFor.equals(Folder.key(this)) && granted()) load();
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

    /** Reads the library on a background thread so a big collection never stalls the screen. */
    void load() {
        final boolean reset = loadedFor != null;
        final String key = Folder.key(this);
        loadedFor = key;
        new Thread(() -> {
            final List<Song> songs = query();
            runOnUiThread(() -> { if (key.equals(loadedFor)) view.setSongs(songs, reset); });
        }).start();
    }

    List<Song> query() {
        List<Song> songs = new ArrayList<>();
        String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK};
        List<String> args = new ArrayList<>();
        String where = MediaStore.Audio.Media.IS_MUSIC + "!=0" + Folder.where(this, args);
        try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                where, args.toArray(new String[0]), MediaStore.Audio.Media.TITLE + " COLLATE NOCASE")) {
            while (c != null && c.moveToNext()) {
                Song s = new Song();
                s.id = c.getLong(0);
                s.title = c.getString(1) == null ? "Untitled" : c.getString(1);
                s.artist = clean(c.getString(2), "Unknown Artist");
                s.album = clean(c.getString(3), "Unknown Album");
                s.duration = c.getInt(4);
                s.track = c.getInt(5);
                songs.add(s);
            }
        } catch (Exception ignored) {
        }
        return songs;
    }

    static String clean(String s, String fallback) {
        return s == null || s.isEmpty() || s.equals("<unknown>") ? fallback : s;
    }

    @Override protected void onDestroy() {
        if (PlayerService.watcher == view) PlayerService.watcher = null;
        super.onDestroy();
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
