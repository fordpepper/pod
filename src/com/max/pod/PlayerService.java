package com.max.pod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds the MediaPlayer so music keeps going with the screen off. */
public class PlayerService extends Service {
    static PlayerService inst;
    static List<Song> queue = new ArrayList<>();
    static int index = -1;
    static boolean playing, prepared;
    static boolean shuffle;          // setting: shuffle whatever gets played
    static int repeat;               // setting: 0 off, 1 one, 2 all
    static boolean shuffled;         // the current queue is in random order
    static View watcher;             // redrawn whenever playback changes

    MediaPlayer mp;
    AudioManager am;
    final AudioManager.OnAudioFocusChangeListener focus = change -> {
        boolean lost = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
        if (lost && playing && prepared) toggle();
    };

    static Song current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    /** Plays q starting at song i; a shuffled queue still starts with the song that was picked. */
    static void play(Context c, List<Song> q, int i, boolean shuffleAll) {
        queue = new ArrayList<>(q);
        shuffled = shuffleAll || shuffle;
        if (shuffled) {
            Song first = shuffleAll ? null : queue.remove(i);
            Collections.shuffle(queue);
            if (first != null) queue.add(0, first);
            i = 0;
        }
        index = i;
        c.startForegroundService(new Intent(c, PlayerService.class));
    }

    static int position() {
        PlayerService s = inst;
        return s != null && prepared && s.mp != null ? s.mp.getCurrentPosition() : 0;
    }

    static int duration() {
        PlayerService s = inst;
        if (s != null && prepared && s.mp != null) return s.mp.getDuration();
        Song song = current();
        return song == null ? 0 : song.duration;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        inst = this;
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        playing = true;
        update();
        start();
        return START_NOT_STICKY;
    }

    void start() {
        Song s = current();
        release();
        if (s == null) { playing = false; update(); return; }
        playing = true;
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            prepared = true;
            am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (playing) p.start();
        });
        mp.setOnCompletionListener(p -> next(true));
        mp.setOnErrorListener((p, a, b) -> { prepared = false; playing = false; update(); return true; });
        try {
            mp.setDataSource(this, s.uri());
            mp.prepareAsync();
        } catch (Exception e) {
            playing = false;
        }
        update();
    }

    void release() {
        prepared = false;
        if (mp != null) { mp.release(); mp = null; }
    }

    void toggle() {
        if (mp == null || !prepared) { if (current() != null) start(); return; }
        if (playing) mp.pause(); else mp.start();
        playing = !playing;
        update();
    }

    void next(boolean auto) {
        if (queue.isEmpty()) return;
        if (auto && repeat == 1) start();
        else if (index + 1 < queue.size()) { index++; start(); }
        else if (!auto || repeat == 2) { index = 0; start(); }
        else { index = 0; release(); playing = false; update(); }
    }

    void prev() {
        if (queue.isEmpty()) return;
        if (prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        index = Math.max(0, index - 1);
        start();
    }

    void seekBy(int ms) {
        if (!prepared) return;
        mp.seekTo(Math.max(0, Math.min(mp.getDuration() - 500, mp.getCurrentPosition() + ms)));
    }

    /** Foreground while playing; when paused the notification stays but the service may be reclaimed. */
    void update() {
        if (watcher != null) watcher.postInvalidate();
        Song s = current();
        Notification n = new Notification.Builder(this, "playback")
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle(s == null ? "Pod" : s.title)
                .setContentText(s == null ? "" : s.artist)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(playing)
                .build();
        if (playing) {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(1, n);
        } else {
            stopForeground(STOP_FOREGROUND_DETACH);
            getSystemService(NotificationManager.class).notify(1, n);
        }
    }

    @Override public void onTaskRemoved(Intent i) {
        if (!playing) stopSelf();
    }

    @Override public void onDestroy() {
        release();
        playing = false;
        am.abandonAudioFocus(focus);
        getSystemService(NotificationManager.class).cancel(1);
        inst = null;
    }
}
