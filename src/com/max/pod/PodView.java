package com.max.pod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.SoundPool;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * The working iPod. Hardware paints the body; this class draws what is on the screen and turns
 * touches on the wheel into scrolling and button presses.
 *
 * The screen is drawn in the iPod's own 320x240 pixel grid (scaled up to the phone), and the
 * positions, sizes and colours below were measured from Apple's screen captures of the real
 * interface, so they are deliberately "magic": changing one moves it away from the original.
 */
public class PodView extends View {
    static final int MAIN = 0, MUSIC = 1, SETTINGS = 2, ARTISTS = 3, ALBUMS = 4, SONGS = 5, NOW = 6, MSG = 7;
    static final int ROWS = 8;                                   // whole rows on screen; a 9th is cut off
    static final float STEP = (float) (Math.PI * 2 / 24);        // wheel travel per click
    static final int SLIDE_MS = 260;
    static final String NOW_PLAYING = "Now Playing";
    static final Shader.TileMode CLAMP = Shader.TileMode.CLAMP;

    static class Screen {
        final int type;
        final String title;
        final List<String> items = new ArrayList<>();
        List<Song> songs;
        int sel, top;

        Screen(int type, String title, String... items) {
            this.type = type;
            this.title = title;
            Collections.addAll(this.items, items);
        }

        /** Top-level menus share the screen with artwork; everything else uses the full width. */
        boolean split() { return type == MAIN || type == MUSIC || type == SETTINGS || type == MSG; }
    }

    /** One picture drifting across the right half of the menus. */
    static class Slide {
        Bitmap bmp;
        long start;
        float x0, y0, z0, x1, y1, z1;
    }

    final MainActivity act;
    final Hardware hw = new Hardware();
    final SharedPreferences prefs;
    final Vibrator vib;
    final AudioManager am;
    final Typeface bold, regular;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    final Path path = new Path();
    final RectF rect = new RectF();
    final Rect src = new Rect();
    final Matrix matrix = new Matrix();
    final Runnable ticker = this::invalidate;

    final List<Screen> stack = new ArrayList<>();
    List<Song> all = new ArrayList<>();
    Screen leaving;                                              // screen sliding out, if any
    long slideStart;
    int slideDir;

    // wheel
    boolean onWheel, onCenter, moved, held;
    int sector = -1;                                             // 0 menu, 1 next, 2 play, 3 previous
    float lastAngle, accum, travelled;
    final Runnable hold = this::onHold;

    // now playing
    boolean scrub, clicker;
    long volUntil, scrubUntil;
    long artId = -1;
    Bitmap art, noArt;

    // menu artwork
    Slide slide, oldSlide;
    long slideNext;
    boolean slideLoading;

    int battery = 100;
    long batteryNext;
    SoundPool pool;
    int clickId;

    PodView(MainActivity a) {
        super(a);
        act = a;
        hw.finish = Hardware.pick(new java.util.Random());
        prefs = a.getSharedPreferences("pod", Context.MODE_PRIVATE);
        vib = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
        am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        bold = font("heros-bold.otf", Typeface.DEFAULT_BOLD);
        regular = font("heros-regular.otf", Typeface.DEFAULT);
        tp.setHinting(Paint.HINTING_OFF);
        PlayerService.shuffle = prefs.getBoolean("shuffle", false);
        PlayerService.repeat = prefs.getInt("repeat", 0);
        clicker = prefs.getBoolean("clicker", true);
        stack.add(new Screen(MAIN, "iPod"));
        loadClick();
    }

    Typeface font(String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(act.getAssets(), name);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ menus

    /** songs == null means we have no permission to read the library yet. */
    void setSongs(List<Song> songs) {
        boolean locked = cur().type == MSG;
        if (songs == null) {
            stack.clear();
            stack.add(new Screen(MSG, "iPod", "Allow Access"));
        } else {
            all = songs;
            if (locked || stack.isEmpty() || cur().items.isEmpty()) {
                stack.clear();
                stack.add(new Screen(MAIN, "iPod", "Music", "Settings", "Shuffle Songs"));
            }
        }
        invalidate();
    }

    Screen cur() { return stack.get(stack.size() - 1); }

    void push(Screen s) {
        leaving = cur();
        slideDir = 1;
        slideStart = SystemClock.uptimeMillis();
        stack.add(s);
    }

    Screen songScreen(String title, List<Song> songs) {
        Screen s = new Screen(SONGS, title);
        s.songs = songs;
        for (Song x : songs) s.items.add(x.title);
        return s;
    }

    void nowPlaying() {
        scrub = false;
        volUntil = 0;
        push(new Screen(NOW, NOW_PLAYING));
    }

    void select() {
        Screen s = cur();
        if (s.type == NOW) {
            scrub = !scrub;
            scrubUntil = SystemClock.uptimeMillis() + 5000;
            volUntil = 0;
            invalidate();
            return;
        }
        if (s.items.isEmpty()) return;
        String item = s.items.get(s.sel);
        switch (s.type) {
            case MSG:
                act.ask();
                break;
            case MAIN:
                if (item.equals("Music")) push(new Screen(MUSIC, "Music", "Artists", "Albums", "Songs"));
                else if (item.equals("Settings")) push(new Screen(SETTINGS, "Settings", "Shuffle", "Repeat", "Clicker"));
                else if (item.equals(NOW_PLAYING)) { if (PlayerService.current() != null) nowPlaying(); }
                else if (!all.isEmpty()) {                       // Shuffle Songs
                    PlayerService.play(act, all, 0, true);
                    nowPlaying();
                }
                break;
            case MUSIC:
                if (item.equals("Songs")) { push(songScreen("Songs", all)); break; }
                boolean byArtist = item.equals("Artists");
                Screen g = new Screen(byArtist ? ARTISTS : ALBUMS, item);
                TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Song x : all) names.add(byArtist ? x.artist : x.album);
                g.items.addAll(names);
                push(g);
                break;
            case SETTINGS:
                if (s.sel == 0) PlayerService.shuffle = !PlayerService.shuffle;
                else if (s.sel == 1) PlayerService.repeat = (PlayerService.repeat + 1) % 3;
                else clicker = !clicker;
                prefs.edit().putBoolean("shuffle", PlayerService.shuffle)
                        .putInt("repeat", PlayerService.repeat).putBoolean("clicker", clicker).apply();
                break;
            case ARTISTS:
            case ALBUMS:
                List<Song> sub = new ArrayList<>();
                for (Song x : all) if ((s.type == ARTISTS ? x.artist : x.album).equalsIgnoreCase(item)) sub.add(x);
                Collections.sort(sub, (a, b) -> {
                    int byAlbum = a.album.compareToIgnoreCase(b.album);
                    return byAlbum != 0 ? byAlbum : a.track != b.track ? a.track - b.track
                            : a.title.compareToIgnoreCase(b.title);
                });
                push(songScreen(item, sub));
                break;
            case SONGS:
                PlayerService.play(act, s.songs, s.sel, false);
                nowPlaying();
                break;
        }
        invalidate();
    }

    /** MENU button: back one level. Returns false when already at the top. */
    boolean menu() {
        if (stack.size() <= 1) return false;
        leaving = stack.remove(stack.size() - 1);
        slideDir = -1;
        slideStart = SystemClock.uptimeMillis();
        scrub = false;
        invalidate();
        return true;
    }

    String settingValue(int i) {
        if (i == 0) return PlayerService.shuffle ? "Songs" : "Off";
        if (i == 1) return PlayerService.repeat == 0 ? "Off" : PlayerService.repeat == 1 ? "One" : "All";
        return clicker ? "On" : "Off";
    }

    void playPause() {
        if (PlayerService.inst != null) PlayerService.inst.toggle();
        else if (!all.isEmpty()) PlayerService.play(act, all, 0, false);
        invalidate();
    }

    /** One click of wheel travel. */
    void tick(int dir) {
        Screen s = cur();
        long now = SystemClock.uptimeMillis();
        if (s.type == NOW) {
            if (scrub && now < scrubUntil) {
                if (PlayerService.inst != null) PlayerService.inst.seekBy(dir * 4000);
                scrubUntil = now + 5000;
            } else {
                scrub = false;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                volUntil = now + 2500;
            }
        } else {
            int n = s.sel + dir;
            if (n < 0 || n >= s.items.size()) return;
            s.sel = n;
            if (n < s.top) s.top = n;
            if (n >= s.top + ROWS) s.top = n - ROWS + 1;
        }
        buzz(false);
        if (clicker && pool != null) pool.play(clickId, 1f, 1f, 1, 0, 1f);
        invalidate();
    }

    // ------------------------------------------------------------------ feel

    void buzz(boolean strong) {
        if (vib == null || !vib.hasVibrator()) return;
        VibrationEffect fx = Build.VERSION.SDK_INT >= 29
                ? VibrationEffect.createPredefined(strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK)
                : VibrationEffect.createOneShot(strong ? 14 : 6, strong ? 200 : 90);
        // "sonification" marks it as touch feedback, so the phone's touch-vibration setting governs it
        vib.vibrate(fx, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
    }

    /** The clicker: a short damped tick, played on the music stream like the iPod plays it in the earphones. */
    void loadClick() {
        try {
            int rate = 44100, n = rate * 30 / 1000;      // the tick itself dies away within ~4 ms
            ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2)
                    .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
            for (int i = 0; i < n; i++) {
                double t = i / (double) rate;
                b.putShort((short) (Math.sin(2 * Math.PI * 2500 * t) * Math.exp(-t / 0.0007) * 0.35 * 32767));
            }
            File f = new File(act.getCacheDir(), "click.wav");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(b.array());
            }
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            clickId = pool.load(f.getPath(), 1);
        } catch (Exception e) {
            pool = null;
        }
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getX() - hw.cx, dy = e.getY() - hw.cy;
        float d = (float) Math.hypot(dx, dy), a = (float) Math.atan2(dy, dx);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onCenter = d < hw.r;
                onWheel = !onCenter && d <= hw.R * 1.06f;
                moved = held = false;
                lastAngle = a;
                accum = travelled = 0;
                if (onWheel) {
                    double deg = Math.toDegrees(a);              // 0 = right, 90 = down
                    sector = deg > -135 && deg <= -45 ? 0 : deg > -45 && deg <= 45 ? 1 : deg > 45 && deg <= 135 ? 2 : 3;
                    postDelayed(hold, 600);
                } else sector = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!onWheel || d < hw.r * 0.6f) { lastAngle = a; return true; }
                float da = a - lastAngle;
                if (da > Math.PI) da -= 2 * Math.PI;
                if (da < -Math.PI) da += 2 * Math.PI;
                lastAngle = a;
                accum += da;
                travelled += Math.abs(da);
                if (!moved && travelled > STEP * 0.7f) {         // this is a scroll, not a button press
                    moved = true;
                    removeCallbacks(hold);
                    invalidate();
                }
                if (held) return true;
                while (accum >= STEP) { accum -= STEP; tick(1); }
                while (accum <= -STEP) { accum += STEP; tick(-1); }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(hold);
                if (onCenter && d < hw.r * 1.4f) {
                    buzz(true);
                    select();
                } else if (onWheel && !moved && !held) {
                    buzz(true);
                    PlayerService ps = PlayerService.inst;
                    if (sector == 0) menu();
                    else if (sector == 1) { if (ps != null) ps.next(false); }
                    else if (sector == 2) playPause();
                    else if (ps != null) ps.prev();
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hold);
                onCenter = onWheel = false;
                sector = -1;
                invalidate();
                return true;
        }
        return true;
    }

    /** A button kept down: MENU jumps to the main menu, next/previous wind through the song. */
    void onHold() {
        if (!onWheel || moved) return;
        PlayerService ps = PlayerService.inst;
        if (sector == 0 && !held) {
            held = true;
            if (stack.size() > 1) {
                buzz(true);
                leaving = cur();
                while (stack.size() > 1) stack.remove(stack.size() - 1);
                slideDir = -1;
                slideStart = SystemClock.uptimeMillis();
                scrub = false;
            }
        } else if ((sector == 1 || sector == 3) && ps != null) {
            if (!held) buzz(true);
            held = true;
            ps.seekBy(sector == 1 ? 5000 : -5000);
            postDelayed(hold, 250);
        }
        invalidate();
    }

    // ------------------------------------------------------------------ drawing helpers

    Paint fill(int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    Paint shade(Shader s) {
        fill(0xff000000);
        p.setShader(s);
        return p;
    }

    static Shader vertical(float y0, float y1, int[] colors, float[] stops) {
        return new LinearGradient(0, y0, 0, y1, colors, stops, CLAMP);
    }

    void text(Canvas c, String s, float x, float y, float maxW, float size, int color, Typeface face, Paint.Align al) {
        tp.setTypeface(face);
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTextAlign(al);
        CharSequence cs = TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END);
        c.drawText(cs, 0, cs.length(), x, y, tp);
    }

    // gradients sampled from the real interface, in 320x240 screen pixels
    static final Shader HEADER = vertical(0, 19,
            new int[]{0xfffdfdfd, 0xffededed, 0xffe7e7e7, 0xffd8d8d8, 0xffbbbbbb, 0xffababab},
            new float[]{0f, 0.2f, 0.39f, 0.5f, 0.76f, 1f});
    static final Shader SELECTED = vertical(1, 21,
            new int[]{0xff4aabd6, 0xff3eabe3, 0xff3ba1e3, 0xff2b93da, 0xff1584d3},
            new float[]{0f, 0.1f, 0.35f, 0.6f, 1f});
    static final Shader BATTERY = vertical(4, 14,
            new int[]{0xff98ca85, 0xffc1fda7, 0xffd1febb, 0xffbef0a9, 0xff9ce685, 0xff4da537, 0xff3e9f2e, 0xff339d21, 0xff388a28},
            new float[]{0f, 0.11f, 0.22f, 0.33f, 0.47f, 0.5f, 0.62f, 0.85f, 1f});
    static final Shader BATTERY_LOW = vertical(4, 14,
            new int[]{0xffe59a8c, 0xffffc1b3, 0xfff2a092, 0xffd9432f, 0xffc0301e, 0xffa82a1a},
            new float[]{0f, 0.2f, 0.47f, 0.5f, 0.8f, 1f});
    static final Shader BATTERY_EMPTY = vertical(4, 14, new int[]{0xffc9c9c9, 0xfff4f4f4, 0xffe2e2e2}, null);
    static final Shader BATTERY_CASE = vertical(3, 15, new int[]{0xff3a4b3a, 0xff5c7357}, null);
    static final Shader PLAY = vertical(3.3f, 14.3f,
            new int[]{0xff2a77a5, 0xff3b9ed9, 0xff52dcff, 0xff36a8e4, 0xff236a96},
            new float[]{0f, 0.22f, 0.5f, 0.76f, 1f});
    static final Shader TRACK = vertical(207, 219,
            new int[]{0xfffbfbfb, 0xffffffff, 0xfff6f6f6, 0xfff0f0f0, 0xffe3e3e3, 0xffe5e5e5, 0xfff2f2f2, 0xffededed},
            new float[]{0f, 0.08f, 0.25f, 0.42f, 0.58f, 0.67f, 0.83f, 1f});
    static final Shader PROGRESS = vertical(207, 219,
            new int[]{0xff87abff, 0xff6c99f6, 0xff6da3f9, 0xff66a3ff, 0xff3d89eb, 0xff3698f9, 0xff32a3ff, 0xff41b3fc, 0xff5cbbff},
            new float[]{0f, 0.17f, 0.33f, 0.46f, 0.5f, 0.58f, 0.67f, 0.83f, 1f});
    static final Shader PROGRESS_GLOW = vertical(220, 229, new int[]{0x405cbbff, 0x385cbbff, 0x005cbbff}, new float[]{0f, 0.3f, 1f});
    static final Shader PANE_SHADOW = new LinearGradient(160, 0, 171, 0,
            new int[]{0x66000000, 0x42000000, 0x22000000, 0x00000000}, new float[]{0f, 0.15f, 0.5f, 1f}, CLAMP);
    static final Shader SCROLL_THUMB = new LinearGradient(313, 0, 319, 0,
            new int[]{0xffa9aeb6, 0xff8b9199, 0xff6d737b}, null, CLAMP);
    static final Shader REFLECTION = new LinearGradient(0, 0, 0, 0.24f, 0xb0ffffff, 0xffffffff, CLAMP);

    // ------------------------------------------------------------------ drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w > 0 && h > 0) hw.render(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        if (hw.bitmap == null) return;
        long now = SystemClock.uptimeMillis();
        c.drawBitmap(hw.bitmap, 0, 0, null);

        // the lit screen, in iPod pixels
        c.save();
        c.clipRect(hw.lcd);
        c.translate(hw.lcd.left, hw.lcd.top);
        c.scale(hw.lcd.width() / 320f, hw.lcd.height() / 240f);
        boolean sliding = drawScreens(c, now);
        c.restore();

        // soft reflection on the glass in front of it
        c.drawPath(hw.windowPath, shade(hw.glare));

        // parts of the wheel dip under the thumb
        if (onCenter) {
            c.drawCircle(hw.cx, hw.cy, hw.r, shade(new RadialGradient(hw.cx, hw.cy, hw.r,
                    hw.finish.dark ? new int[]{0x22ffffff, 0x10ffffff, 0x24ffffff}
                            : new int[]{0x2e000000, 0x16000000, 0x34000000}, new float[]{0f, 0.8f, 1f}, CLAMP)));
        } else if (onWheel && !moved && sector >= 0) {
            float[] ox = {0, 1, 0, -1}, oy = {-1, 0, 1, 0};
            float px = hw.cx + ox[sector] * hw.R * 0.72f, py = hw.cy + oy[sector] * hw.R * 0.72f;
            c.save();
            c.clipPath(hw.ring);
            c.drawCircle(px, py, hw.R * 0.75f, shade(new RadialGradient(px, py, hw.R * 0.75f,
                    hw.finish.dark ? new int[]{0x26ffffff, 0x10ffffff, 0x00ffffff}
                            : new int[]{0x30000000, 0x14000000, 0x00000000}, new float[]{0f, 0.5f, 1f}, CLAMP)));
            c.restore();
        }

        // keep animating only while something on screen is moving
        Screen s = cur();
        removeCallbacks(ticker);
        long wait = sliding ? 0 : s.split() ? 40
                : s.type == NOW && (PlayerService.playing || now < Math.max(volUntil, scrubUntil)) ? 200 : 30000;
        if (wait == 0) postInvalidateOnAnimation(); else postDelayed(ticker, wait);
    }

    /** Draws the current screen, or two screens mid-slide. Returns true while a slide is running. */
    boolean drawScreens(Canvas c, long now) {
        Screen s = cur();
        if (s.type == MAIN) {                                    // "Now Playing" exists only once something is loaded
            boolean has = s.items.contains(NOW_PLAYING), want = PlayerService.current() != null;
            if (want && !has) s.items.add(NOW_PLAYING);
            if (!want && has) { s.items.remove(NOW_PLAYING); s.sel = Math.min(s.sel, s.items.size() - 1); }
        }
        float t = leaving == null ? 1f : (now - slideStart) / (float) SLIDE_MS;
        if (t >= 1f) {
            leaving = null;
            if (s.split()) drawArt(c, now);
            drawScreen(c, s);
            return false;
        }
        float ease = 1 - (1 - t) * (1 - t);
        boolean menus = s.split() && leaving.split();            // only the menu half moves, artwork stays
        float span = menus ? 160 : 320;
        if (menus) {
            drawArt(c, now);
            c.save();
            c.clipRect(0, 0, 160, 240);
        }
        for (int i = 0; i < 2; i++) {
            Screen which = i == 0 ? leaving : s;
            c.save();
            c.translate(i == 0 ? -slideDir * ease * span : slideDir * (1 - ease) * span, 0);
            if (!menus) c.clipRect(0, 0, 320, 240);
            if (!menus && which.split()) drawArt(c, now);
            drawScreen(c, which);
            c.restore();
        }
        if (menus) c.restore();
        return true;
    }

    void drawScreen(Canvas c, Screen s) {
        if (s.type == NOW) { drawNowPlaying(c); return; }
        boolean split = s.split();
        float w = split ? 160 : 320, inset = split ? 6 : 9;
        int n = s.items.size();
        boolean bar = !split && n > ROWS;
        float right = bar ? 311 : w;
        c.drawRect(0, 0, w, 240, fill(0xffffffff));
        boolean arrows = s.type != SONGS && s.type != SETTINGS && s.type != MSG;
        for (int i = s.top; i < Math.min(n, s.top + ROWS + 1); i++) {
            float y = 20 + 25 * (i - s.top);
            boolean sel = i == s.sel;
            String value = s.type == SETTINGS ? settingValue(i) : null;
            boolean arrow = sel && arrows && !s.items.get(i).equals("Shuffle Songs");
            c.save();
            c.translate(0, y);
            if (sel) {
                c.drawRect(0, 0, right, 1, fill(0xff9addf8));
                c.drawRect(0, 1, right, 21, shade(SELECTED));
                c.drawRect(0, 21, right, 22, fill(0xff1d7bc1));
                c.drawRect(0, 22, right, 23, fill(0xff154e83));
            }
            int ink = sel ? 0xffffffff : 0xff000000;
            float room = right - inset - (arrow ? 17 : 6);
            if (value != null) {
                text(c, value, right - 7, 16, 60, 15, ink, bold, Paint.Align.RIGHT);
                room -= tp.measureText(value) + 6;
            }
            text(c, s.items.get(i), inset, 16, room, 15, ink, bold, Paint.Align.LEFT);
            if (arrow) {
                path.rewind();
                path.moveTo(right - 11.4f, 7.5f);
                path.lineTo(right - 7.5f, 11.5f);
                path.lineTo(right - 11.4f, 15.5f);
                fill(0xffffffff).setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.9f);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(path, p);
            }
            c.restore();
        }
        if (n == 0) text(c, s.type == SONGS ? "No Songs" : "No Music", w / 2, 128, w - 20, 15, 0xff8a8a8a, bold, Paint.Align.CENTER);
        if (bar) {
            c.drawRect(311, 20, 320, 240, fill(0xfff1f1f1));
            c.drawRect(311, 20, 312, 240, fill(0xffc4c4c4));
            float h = 220, th = Math.max(18, h * ROWS / n), ty = 20 + (h - th) * s.top / (float) (n - ROWS);
            rect.set(313.5f, ty + 2, 318.5f, ty + th - 2);
            c.drawRoundRect(rect, 2.5f, 2.5f, shade(SCROLL_THUMB));
        }
        header(c, w, s.title, inset);
    }

    /** Title bar with the play state and battery at its right-hand end. */
    void header(Canvas c, float w, String title, float inset) {
        c.drawRect(0, 0, w, 19, shade(HEADER));
        c.drawRect(0, 19, w, 20, fill(0xff6a6a6a));
        text(c, title, inset, 14, w - inset - 54, 12, 0xff000000, bold, Paint.Align.LEFT);

        long now = SystemClock.uptimeMillis();
        if (now >= batteryNext) {
            batteryNext = now + 30000;
            int lvl = ((BatteryManager) act.getSystemService(Context.BATTERY_SERVICE))
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            battery = lvl > 0 && lvl <= 100 ? lvl : 100;
        }
        c.save();
        float nudge = w < 320 ? 1 : 0;                          // the half-width bar sits a pixel further in
        c.translate(w - 29 - nudge, 0);
        c.drawRect(0, 3, 24, 15, shade(BATTERY_CASE));
        c.drawRect(24, 7, 26, 11, fill(0xff6f716d));
        c.drawRect(1, 4, 23, 14, shade(BATTERY_EMPTY));
        c.drawRect(1, 4, 1 + 22 * battery / 100f, 14, shade(battery > 20 ? BATTERY : BATTERY_LOW));
        c.restore();

        if (PlayerService.current() == null) return;
        c.save();
        c.translate(w - 45 - nudge, 0);
        path.rewind();
        if (PlayerService.playing) {
            path.moveTo(0.4f, 3.3f);
            path.lineTo(10.9f, 8.8f);
            path.lineTo(0.4f, 14.3f);
            path.close();
        } else {
            path.addRect(0.6f, 3.9f, 4.2f, 13.7f, Path.Direction.CW);
            path.addRect(6.4f, 3.9f, 10f, 13.7f, Path.Direction.CW);
        }
        c.drawPath(path, shade(PLAY));
        fill(0xff16528a).setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(0.9f);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(path, p);
        c.restore();
    }

    // ---- artwork beside the menus

    void drawArt(Canvas c, long now) {
        if (!slideLoading && now >= slideNext && !all.isEmpty()) {
            slideLoading = true;
            final List<Song> pool = all;
            new Thread(() -> {
                Bitmap found = null;
                for (int i = 0; i < 5 && found == null; i++)
                    found = decode(pool.get((int) (Math.random() * pool.size())));
                final Bitmap bmp = found;
                post(() -> {
                    slideLoading = false;
                    long t = SystemClock.uptimeMillis();
                    slideNext = t + (bmp == null ? 60000 : 11000);
                    if (bmp == null) return;
                    Slide s = new Slide();
                    s.bmp = bmp;
                    s.start = t;
                    boolean in = Math.random() < 0.5;            // zoom in or out, drifting corner to corner
                    s.z0 = in ? 1.12f : 1.34f;
                    s.z1 = in ? 1.34f : 1.12f;
                    s.x0 = (float) Math.random() * 0.5f;
                    s.x1 = 0.5f + (float) Math.random() * 0.5f;
                    s.y0 = (float) Math.random();
                    s.y1 = 1 - s.y0;
                    oldSlide = slide;
                    slide = s;
                    invalidate();
                });
            }).start();
        }
        c.save();
        c.clipRect(160, 0, 320, 240);
        if (slide == null) {
            Bitmap b = placeholder();
            rect.set(120, 0, 360, 240);
            c.drawBitmap(b, null, rect, bp);
        } else {
            float fade = Math.min(1f, (now - slide.start) / 1200f);
            if (oldSlide != null && fade < 1f) drawSlide(c, oldSlide, now, 255);
            else oldSlide = null;
            drawSlide(c, slide, now, (int) (fade * 255));
        }
        c.drawRect(160, 0, 171, 240, shade(PANE_SHADOW));       // the menu pane sits above the picture
        c.restore();
    }

    void drawSlide(Canvas c, Slide s, long now, int alpha) {
        float t = Math.min(1f, (now - s.start) / 14000f);
        float size = 240 * (s.z0 + (s.z1 - s.z0) * t);
        float x = 160 - (size - 160) * (s.x0 + (s.x1 - s.x0) * t), y = -(size - 240) * (s.y0 + (s.y1 - s.y0) * t);
        square(s.bmp);
        rect.set(x, y, x + size, y + size);
        bp.setAlpha(alpha);
        c.drawBitmap(s.bmp, src, rect, bp);
        bp.setAlpha(255);
    }

    /** Sets src to the centred square of a bitmap. */
    void square(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), side = Math.min(w, h);
        src.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
    }

    /** Stand-in cover for songs that have no artwork. */
    Bitmap placeholder() {
        if (noArt != null) return noArt;
        noArt = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(noArt);
        c.drawPaint(shade(vertical(0, 300, new int[]{0xfff0f1f3, 0xffc5c9cf}, null)));
        fill(0xff9a9fa7);
        c.save();
        c.rotate(-20, 122, 205);
        rect.set(96, 188, 148, 222);
        c.drawOval(rect, p);
        c.restore();
        c.save();
        c.rotate(-20, 202, 185);
        rect.set(176, 168, 228, 202);
        c.drawOval(rect, p);
        c.restore();
        c.drawRect(138, 92, 147, 200, p);
        c.drawRect(218, 72, 227, 180, p);
        path.rewind();
        path.moveTo(138, 92);
        path.lineTo(227, 72);
        path.lineTo(227, 100);
        path.lineTo(138, 120);
        path.close();
        c.drawPath(path, p);
        return noArt;
    }

    Bitmap decode(Song s) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return act.getContentResolver().loadThumbnail(s.uri(), new Size(600, 600), null);
            } catch (Exception ignored) {
            }
        }
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(act, s.uri());
            byte[] pic = mr.getEmbeddedPicture();
            if (pic == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
            o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 600);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            try { mr.release(); } catch (Exception ignored) { }
        }
    }

    // ---- now playing

    void drawNowPlaying(Canvas c) {
        c.drawRect(0, 0, 320, 240, fill(0xffffffff));
        Song s = PlayerService.current();
        if (s != null && artId != s.id) {                        // keep the old cover up until the new one is ready
            artId = s.id;
            final Song want = s;
            new Thread(() -> {
                final Bitmap b = decode(want);
                post(() -> { if (artId == want.id) { art = b; invalidate(); } });
            }).start();
        }
        drawCover(c, art != null ? art : placeholder());
        header(c, 320, NOW_PLAYING, 9);
        if (s == null) return;
        if (PlayerService.shuffled) shuffleIcon(c);
        if (PlayerService.repeat != 0) repeatIcon(c, PlayerService.repeat == 1);

        text(c, s.title, 160, 71, 154, 16, 0xff000000, bold, Paint.Align.LEFT);
        text(c, s.artist, 160, 90, 154, 14, 0xff333333, regular, Paint.Align.LEFT);
        text(c, s.album, 160, 108, 154, 14, 0xff333333, regular, Paint.Align.LEFT);
        text(c, (PlayerService.index + 1) + " of " + PlayerService.queue.size(), 160, 144, 154, 14,
                0xff262626, regular, Paint.Align.LEFT);

        long now = SystemClock.uptimeMillis();
        if (scrub && now >= scrubUntil) scrub = false;
        boolean vol = !scrub && now < volUntil;
        int dur = PlayerService.duration(), pos = Math.min(PlayerService.position(), dur);
        float frac = vol ? am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                : dur > 0 ? pos / (float) dur : 0;
        float x0 = 58, x1 = 262, fx = x0 + (x1 - x0) * Math.max(0f, Math.min(1f, frac));
        c.drawRect(x0, 206, x1, 207, fill(0xffe1e1e1));
        c.drawRect(x0, 207, x1, 219, shade(TRACK));
        c.drawRect(x0, 219, x1, 220, fill(0xffc5c5c5));
        if (fx > x0 + 0.5f) {
            c.drawRect(x0, 206, fx, 207, fill(0xff7e97d7));
            c.drawRect(x0, 207, fx, 219, shade(PROGRESS));
            c.drawRect(x0, 219, fx, 220, fill(0xff5c9ed0));
            c.drawRect(x0, 206, x0 + 1, 220, fill(0xff6796e4));
            c.drawRect(x0, 220, fx, 229, shade(PROGRESS_GLOW));
        }
        if (vol) {
            speaker(c, 36, false);
            speaker(c, 275, true);
        } else {
            text(c, time(pos), 47.5f, 219, 50, 13, 0xff000000, bold, Paint.Align.RIGHT);
            text(c, "-" + time(dur - pos), 273.5f, 219, 50, 13, 0xff000000, bold, Paint.Align.LEFT);
        }
        if (scrub) {                                             // diamond playhead
            path.rewind();
            path.moveTo(fx, 202.5f);
            path.lineTo(fx + 7, 213);
            path.lineTo(fx, 223.5f);
            path.lineTo(fx - 7, 213);
            path.close();
            c.drawPath(path, shade(vertical(202.5f, 223.5f, new int[]{0xff8e9299, 0xff3b3e44}, null)));
            fill(0xffffffff).setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f);
            p.setStrokeJoin(Paint.Join.MITER);
            c.drawPath(path, p);
        }
    }

    /** The cover stands at an angle, as on the real screen, with its mirror image fading below. */
    void drawCover(Canvas c, Bitmap b) {
        square(b);
        float[] unit = {0, 0, 1, 0, 1, 1, 0, 1};
        rect.set(0, 0, 1, 1);
        matrix.setPolyToPoly(unit, 0, new float[]{10, 34.5f, 144.5f, 44.3f, 144.5f, 160.4f, 10, 168.9f}, 0, 4);
        c.save();
        c.concat(matrix);
        c.drawBitmap(b, src, rect, bp);
        c.restore();
        // mirrored copy hinged on the bottom edge
        matrix.setPolyToPoly(unit, 0, new float[]{10, 303.3f, 144.5f, 276.5f, 144.5f, 160.4f, 10, 168.9f}, 0, 4);
        c.save();
        c.concat(matrix);
        c.drawBitmap(b, src, rect, bp);
        c.scale(1, -1, 0, 0.5f);                                 // gradient runs from the hinge outward
        c.drawRect(-0.01f, 0, 1.01f, 1.01f, shade(REFLECTION));
        c.restore();
    }

    static String time(int ms) {
        int sec = Math.max(0, ms) / 1000;
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    Paint line(float width) {
        fill(0xff1e1e1e).setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    void arrowHead(Canvas c, float tipX, float y, int dir) {
        path.rewind();
        path.moveTo(tipX, y);
        path.lineTo(tipX - dir * 4.2f, y - 3.3f);
        path.lineTo(tipX - dir * 4.2f, y + 3.3f);
        path.close();
        c.drawPath(path, fill(0xff1e1e1e));
    }

    void shuffleIcon(Canvas c) {
        path.rewind();
        path.moveTo(268, 26.3f);
        path.lineTo(273.2f, 26.3f);
        path.lineTo(279.3f, 34);
        path.lineTo(284, 34);
        path.moveTo(268, 34);
        path.lineTo(273.2f, 34);
        path.lineTo(279.3f, 26.3f);
        path.lineTo(284, 26.3f);
        c.drawPath(path, line(2));
        arrowHead(c, 288, 26.3f, 1);
        arrowHead(c, 288, 34, 1);
    }

    void repeatIcon(Canvas c, boolean one) {
        path.rewind();
        path.moveTo(294, 30.6f);
        path.lineTo(294, 29.3f);
        path.quadTo(294, 26.3f, 297, 26.3f);
        path.lineTo(305, 26.3f);
        path.moveTo(311.5f, 29.7f);
        path.lineTo(311.5f, 31);
        path.quadTo(311.5f, 34, 308.5f, 34);
        path.lineTo(300.5f, 34);
        c.drawPath(path, line(2));
        arrowHead(c, 309, 26.3f, 1);
        arrowHead(c, 296.5f, 34, -1);
        if (one) text(c, "1", 302.8f, 33.2f, 10, 7.5f, 0xff1e1e1e, bold, Paint.Align.CENTER);
    }

    void speaker(Canvas c, float x, boolean loud) {
        path.rewind();
        path.moveTo(x, 210);
        path.lineTo(x + 3.5f, 210);
        path.lineTo(x + 8, 206);
        path.lineTo(x + 8, 220);
        path.lineTo(x + 3.5f, 216);
        path.lineTo(x, 216);
        path.close();
        c.drawPath(path, fill(0xff3a3a3a));
        if (!loud) return;
        line(1.4f).setColor(0xff3a3a3a);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float rr = 3.2f + i * 3;
            rect.set(x + 8 - rr, 213 - rr, x + 8 + rr, 213 + rr);
            c.drawArc(rect, -42, 84, false, p);
        }
    }
}
