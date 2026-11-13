package com.max.pod;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;

import java.util.List;
import java.util.Locale;

/**
 * The folder Pod looks for music in. Stored as a storage volume ("primary" for the phone's own
 * shared storage, or an SD card's id) plus a path inside it ("Music"); no volume means all music.
 */
final class Folder {
    private Folder() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pod", Context.MODE_PRIVATE);
    }

    static String volume(Context c) { return prefs(c).getString("root_volume", null); }

    static String path(Context c) { return prefs(c).getString("root_path", ""); }

    static String key(Context c) { return volume(c) + ":" + path(c); }

    static void set(Context c, String volume, String path) {
        prefs(c).edit().putString("root_volume", volume).putString("root_path", path).apply();
    }

    static void clear(Context c) {
        prefs(c).edit().remove("root_volume").remove("root_path").apply();
    }

    /** "Internal shared storage/Music", or null when every folder is searched. */
    static String label(Context c) {
        String vol = volume(c), path = path(c);
        if (vol == null) return null;
        String name = vol.equals("primary") ? "Internal shared storage" : "SD card";
        try {
            for (StorageVolume v : c.getSystemService(StorageManager.class).getStorageVolumes()) {
                if (vol.equals("primary") ? v.isPrimary() : vol.equalsIgnoreCase(v.getUuid())) {
                    name = v.getDescription(c);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return path.isEmpty() ? name : name + "/" + path;
    }

    /** Extra SQL for the MediaStore query: only songs in the folder and the folders inside it. */
    static String where(Context c, List<String> args) {
        String vol = volume(c), path = path(c);
        if (vol == null) return "";
        StringBuilder sql = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 29) {
            sql.append(" AND ").append(MediaStore.Audio.Media.VOLUME_NAME).append("=?");
            args.add(vol.equals("primary") ? MediaStore.VOLUME_EXTERNAL_PRIMARY : vol.toLowerCase(Locale.ROOT));
            if (!path.isEmpty()) {
                sql.append(" AND ").append(MediaStore.Audio.Media.RELATIVE_PATH).append(" LIKE ? ESCAPE '\\'");
                args.add(escape(path) + "/%");
            }
        } else {
            String base = vol.equals("primary") ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
            sql.append(" AND ").append(MediaStore.Audio.Media.DATA).append(" LIKE ? ESCAPE '\\'");
            args.add(escape(path.isEmpty() ? base : base + "/" + path) + "/%");
        }
        return sql.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
