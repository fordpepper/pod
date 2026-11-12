package com.max.pod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Size;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plays the queue in a foreground service, so music keeps going while the app is in the
 * background or the phone is locked. A MediaSession publishes what is playing to Android:
 * the lock screen, the quick-settings media player, headset buttons, Bluetooth and Android
 * Auto all see it and can control it.
 */
public class PlayerService extends Service {
    static final String ACTION_TOGGLE = "com.max.pod.TOGGLE", ACTION_NEXT = "com.max.pod.NEXT",
            ACTION_PREV = "com.max.pod.PREV";

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
    AudioFocusRequest focusRequest;
    MediaSession session;
    Bitmap art;
    long artFor = -1;
    boolean pausedByFocus;
    final Handler main = new Handler(Looper.getMainLooper());

    final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && playing && prepared) {
            pausedByFocus = true;                       // a call or a navigation prompt: resume afterwards
            toggle();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS && playing && prepared) {
            pausedByFocus = false;
            toggle();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && pausedByFocus && !playing && prepared) {
            pausedByFocus = false;
            toggle();
        }
    };

    final Buttons receiver = new Buttons(this);

    // Nested classes are static on purpose: build-tools 34's d8 crashes on javac 21's inner classes.

    /** Notification buttons, and headphones being unplugged. */
    static final class Buttons extends BroadcastReceiver {
        final PlayerService ps;

        Buttons(PlayerService ps) { this.ps = ps; }

        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(a)) { if (playing) ps.toggle(); }
            else if (ACTION_TOGGLE.equals(a)) ps.toggle();
            else if (ACTION_NEXT.equals(a)) ps.next(false);
            else if (ACTION_PREV.equals(a)) ps.prev();
        }
    }

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
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focus).setWillPauseWhenDucked(false).build();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("playback", "Playback", NotificationManager.IMPORTANCE_LOW));

        session = new MediaSession(this, "Pod");
        session.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        session.setCallback(new Controls(this));
        session.setActive(true);

        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        f.addAction(ACTION_TOGGLE);
        f.addAction(ACTION_NEXT);
        f.addAction(ACTION_PREV);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    /** What the lock screen, headset and other media controllers ask for. */
    static final class Controls extends MediaSession.Callback {
        final PlayerService ps;

        Controls(PlayerService ps) { this.ps = ps; }

        @Override public void onPlay() { if (!playing) ps.toggle(); }
        @Override public void onPause() { if (playing) ps.toggle(); }
        @Override public void onStop() { if (playing) ps.toggle(); }
        @Override public void onSkipToNext() { ps.next(false); }
        @Override public void onSkipToPrevious() { ps.prev(); }
        @Override public void onSeekTo(long pos) {
            if (prepared && ps.mp != null) { ps.mp.seekTo((int) pos); ps.update(); }
        }
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
        pausedByFocus = false;
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            prepared = true;
            if (playing) {
                if (am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) p.start();
                else playing = false;
            }
            update();
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
        if (playing) mp.pause();
        else {
            pausedByFocus = false;
            if (am.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
            mp.start();
        }
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
        if (prepared && mp.getCurrentPosition() > 3000) { mp.seekTo(0); update(); return; }
        index = Math.max(0, index - 1);
        start();
    }

    void seekBy(int ms) {
        if (!prepared) return;
        mp.seekTo(Math.max(0, Math.min(mp.getDuration() - 500, mp.getCurrentPosition() + ms)));
        update();
    }

    /** Fetches the cover for the lock screen off the main thread, then republishes. */
    void loadArt(Song s) {
        artFor = s.id;
        art = null;
        new Thread(() -> {
            Bitmap b = null;
            try {
                b = getContentResolver().loadThumbnail(s.uri(), new Size(512, 512), null);
            } catch (Exception ignored) {
            }
            final Bitmap result = b;
            main.post(() -> {
                if (artFor == s.id && result != null) { art = result; update(); }
            });
        }).start();
    }

    PendingIntent action(String a, int code) {
        return PendingIntent.getBroadcast(this, code, new Intent(a).setPackage(getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Publishes the state to the session and notification. Foreground while playing. */
    void update() {
        if (watcher != null) watcher.postInvalidate();
        Song s = current();
        if (s != null && artFor != s.id) loadArt(s);

        long pos = prepared && mp != null ? mp.getCurrentPosition() : 0;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos,
                        playing && prepared ? 1f : 0f)
                .build());
        if (s != null) {
            MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                    .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, index + 1)
                    .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, queue.size());
            if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            session.setMetadata(m.build());
        }

        Notification.Builder b = new Notification.Builder(this, "playback")
                .setSmallIcon(R.drawable.note)
                .setContentTitle(s == null ? "Pod" : s.title)
                .setContentText(s == null ? "" : s.artist)
                .setSubText(s == null ? null : s.album)
                .setLargeIcon(art)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(session.getController().getSessionActivity())
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Previous",
                        action(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play), playing ? "Pause" : "Play",
                        action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next",
                        action(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
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
        am.abandonAudioFocusRequest(focusRequest);
        unregisterReceiver(receiver);
        session.setActive(false);
        session.release();
        getSystemService(NotificationManager.class).cancel(1);
        inst = null;
    }
}
