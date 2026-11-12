package com.max.pod;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

class Song {
    long id;
    String title, artist, album;
    int duration, track;

    Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
    }
}
