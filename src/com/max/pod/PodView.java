package com.max.pod;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** The whole device: LCD, menus and click wheel, drawn on one canvas. */
public class PodView extends View {
    static final int MAIN = 0, SONGS = 1, ARTISTS = 2, ALBUMS = 3, NOW = 4, MSG = 5, MUSIC = 6;
    static final int ROWS = 9;
    static final float STEP = (float) (Math.PI * 2 / 20);   // wheel travel per click

    static class Screen {
        int type, sel, top;
        String title;
        List<String> items = new ArrayList<>();
        List<Song> songs;

        Screen(int type, String title) { this.type = type; this.title = title; }
    }

    final MainActivity act;
    final Vibrator vib;
    final AudioManager am;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF lcd = new RectF(), tmp = new RectF();
    final List<Screen> stack = new ArrayList<>();
    List<Song> all = new ArrayList<>();

    // wheel geometry (set while drawing) and touch state
    float cx, cy, R, r;
    boolean onWheel, onCenter;
    float lastAngle, downAngle, accum, travelled;

    boolean scrub;
    long volUntil, scrubUntil;
    long artId = -1;
    Bitmap art, menuArt;
    long menuArtNext;
    final Rect src = new Rect();
    final Runnable ticker = this::invalidate;

    PodView(MainActivity a) {
        super(a);
        act = a;
        vib = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
        am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        stack.add(new Screen(MAIN, "iPod"));
    }

    // ---------- menus ----------

    void setSongs(List<Song> songs) {
        stack.clear();
        Screen s;
        if (songs == null) {
            s = new Screen(MSG, "iPod");
            s.items.add("Allow Music Access");
        } else {
            all = songs;
            s = new Screen(MAIN, "iPod");
            Collections.addAll(s.items, "Music", "Shuffle Songs");
        }
        stack.add(s);
        invalidate();
    }

    Screen cur() { return stack.get(stack.size() - 1); }

    Screen songScreen(String title, List<Song> songs) {
        Screen s = new Screen(SONGS, title);
        s.songs = songs;
        for (Song x : songs) s.items.add(x.title);
        if (songs.isEmpty()) s.items.add("No Songs");
        return s;
    }

    void select() {
        Screen s = cur();
        if (s.items.isEmpty()) return;
        String item = s.items.get(s.sel);
        switch (s.type) {
            case MSG:
                act.ask();
                break;
            case MAIN:
                if (item.equals("Music")) {
                    Screen mu = new Screen(MUSIC, "Music");
                    Collections.addAll(mu.items, "Artists", "Albums", "Songs");
                    stack.add(mu);
                } else if (item.equals("Shuffle Songs")) {
                    if (all.isEmpty()) break;
                    List<Song> q = new ArrayList<>(all);
                    Collections.shuffle(q);
                    PlayerService.play(act, q, 0);
                    stack.add(new Screen(NOW, "Now Playing"));
                } else if (PlayerService.current() != null) stack.add(new Screen(NOW, "Now Playing"));
                break;
            case MUSIC:
                if (item.equals("Songs")) { stack.add(songScreen("Songs", all)); break; }
                boolean byArtist = item.equals("Artists");
                Screen g = new Screen(byArtist ? ARTISTS : ALBUMS, item);
                TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Song x : all) names.add(byArtist ? x.artist : x.album);
                g.items.addAll(names);
                stack.add(g);
                break;
            case ARTISTS:
            case ALBUMS:
                List<Song> sub = new ArrayList<>();
                for (Song x : all) if ((s.type == ARTISTS ? x.artist : x.album).equalsIgnoreCase(item)) sub.add(x);
                stack.add(songScreen(item, sub));
                break;
            case SONGS:
                if (s.songs.isEmpty()) break;
                PlayerService.play(act, s.songs, s.sel);
                stack.add(new Screen(NOW, "Now Playing"));
                break;
            case NOW:
                scrub = !scrub;
                scrubUntil = SystemClock.uptimeMillis() + 4000;
                break;
        }
        invalidate();
    }

    /** MENU button: go back one level. Returns false at the top. */
    boolean menu() {
        if (stack.size() <= 1) return false;
        stack.remove(stack.size() - 1);
        scrub = false;
        invalidate();
        return true;
    }

    void playPause() {
        if (PlayerService.inst != null) PlayerService.inst.toggle();
        else if (!all.isEmpty()) PlayerService.play(act, all, 0);
    }

    void tick(int dir) {
        Screen s = cur();
        long now = SystemClock.uptimeMillis();
        if (s.type == NOW) {
            if (scrub && now < scrubUntil) {
                if (PlayerService.inst != null) PlayerService.inst.seekBy(dir * 5000);
                scrubUntil = now + 4000;
            } else {
                scrub = false;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                volUntil = now + 2000;
            }
        } else {
            int n = s.sel + dir;
            if (n < 0 || n >= s.items.size()) return;
            s.sel = n;
            if (n < s.top) s.top = n;
            if (n >= s.top + ROWS) s.top = n - ROWS + 1;
        }
        buzz(false);
        am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.4f);
        invalidate();
    }

    void buzz(boolean strong) {
        if (vib == null) return;
        if (Build.VERSION.SDK_INT >= 29)
            vib.vibrate(VibrationEffect.createPredefined(
                    strong ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
        else vib.vibrate(VibrationEffect.createOneShot(strong ? 14 : 6, strong ? 200 : 90));
    }

    // ---------- touch ----------

    @Override public boolean onTouchEvent(MotionEvent e) {
        float dx = e.getX() - cx, dy = e.getY() - cy;
        float d = (float) Math.hypot(dx, dy), a = (float) Math.atan2(dy, dx);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onCenter = d < r;
                onWheel = !onCenter && d <= R * 1.08f;
                lastAngle = downAngle = a;
                accum = travelled = 0;
                if (onCenter) buzz(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!onWheel || d < r * 0.5f) { lastAngle = a; return true; }
                float da = a - lastAngle;
                if (da > Math.PI) da -= 2 * Math.PI;
                if (da < -Math.PI) da += 2 * Math.PI;
                lastAngle = a;
                accum += da;
                travelled += Math.abs(da);
                while (accum >= STEP) { accum -= STEP; tick(1); }
                while (accum <= -STEP) { accum += STEP; tick(-1); }
                return true;
            case MotionEvent.ACTION_UP:
                if (onCenter && d < r * 1.3f) select();
                else if (onWheel && travelled < STEP * 0.6f) {
                    buzz(true);
                    double deg = Math.toDegrees(downAngle);          // 0 = right, 90 = down
                    if (deg > -135 && deg <= -45) menu();
                    else if (deg > -45 && deg <= 45) { if (PlayerService.inst != null) PlayerService.inst.next(false); }
                    else if (deg > 45 && deg <= 135) playPause();
                    else if (PlayerService.inst != null) PlayerService.inst.prev();
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                onCenter = onWheel = false;
                invalidate();
                return true;
        }
        return true;
    }

    // ---------- drawing ----------

    Shader grad(float y0, float y1, int c0, int c1) {
        return new LinearGradient(0, y0, 0, y1, c0, c1, Shader.TileMode.CLAMP);
    }

    void text(Canvas c, String s, float x, float y, float maxW, float size, int color, boolean bold, Paint.Align al) {
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tp.setTextAlign(al);
        c.drawText(TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString(), x, y, tp);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xff000000);
        c.drawRect(0, 0, w, h, p);

        // the device at its real proportions (61.8 x 103.5 mm), centred
        float bw = Math.min(w, h / 1.675f), bh = bw * 1.675f, bx = (w - bw) / 2, by = (h - bh) / 2;
        float rad = bw * 0.075f;
        tmp.set(bx, by, bx + bw, by + bh);
        p.setShader(grad(by, by + bh, 0xffeff0f2, 0xffc8cace));
        c.drawRoundRect(tmp, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);          // polished edge
        p.setStrokeWidth(bw * 0.007f);
        p.setColor(0xfffafafb);
        tmp.inset(bw * 0.0035f, bw * 0.0035f);
        c.drawRoundRect(tmp, rad, rad, p);
        p.setStyle(Paint.Style.FILL);

        R = bw * 0.31f;
        r = R * 0.34f;
        cx = bx + bw / 2;
        cy = by + bh * 0.705f;

        // black glass window with the 4:3 LCD inside
        float gw = bw * 0.85f, gh = gw * 0.76f, gx = cx - gw / 2, gy = by + bh * 0.065f;
        tmp.set(gx, gy, gx + gw, gy + gh);
        p.setColor(0xff0c0c0e);
        c.drawRoundRect(tmp, bw * 0.012f, bw * 0.012f, p);
        float lw = gw * 0.95f, lh = lw * 0.75f, ly = gy + (gh - lh) / 2;
        lcd.set(cx - lw / 2, ly, cx + lw / 2, ly + lh);
        c.save();
        c.clipRect(lcd);
        p.setColor(0xffffffff);
        c.drawRect(lcd, p);
        drawScreen(c);
        // faint glass glare
        p.setShader(grad(lcd.top, lcd.top + lcd.height() * 0.5f, 0x22ffffff, 0x00ffffff));
        c.drawRect(lcd, p);
        p.setShader(null);
        c.restore();

        drawWheel(c);

        removeCallbacks(ticker);
        postDelayed(ticker, PlayerService.playing
                || SystemClock.uptimeMillis() < Math.max(volUntil, scrubUntil) ? 250 : 1000);
    }

    void drawScreen(Canvas c) {
        Screen s = cur();
        float L = lcd.left, T = lcd.top, W = lcd.width(), H = lcd.height();
        float th = H * 0.09f;
        if (s.type == MAIN) {                     // "Now Playing" only exists while something is loaded
            boolean has = s.items.contains("Now Playing"), want = PlayerService.current() != null;
            if (want && !has) s.items.add("Now Playing");
            if (!want && has) { s.items.remove("Now Playing"); s.sel = Math.min(s.sel, s.items.size() - 1); }
        }

        // title bar
        p.setShader(grad(T, T + th, 0xfff7f8f9, 0xffb4b8bf));
        c.drawRect(L, T, lcd.right, T + th, p);
        p.setShader(null);
        p.setColor(0xff8a8e96);
        c.drawRect(L, T + th - 2, lcd.right, T + th, p);
        text(c, s.title, L + W / 2, T + th * 0.72f, W * 0.6f, th * 0.62f, 0xff111111, true, Paint.Align.CENTER);

        // play / pause indicator
        if (PlayerService.current() != null) {
            float ix = L + th * 0.4f, iy = T + th * 0.25f, is = th * 0.5f;
            p.setColor(0xff2f7de1);
            if (PlayerService.playing) {
                path.rewind();
                path.moveTo(ix, iy); path.lineTo(ix + is * 0.9f, iy + is / 2); path.lineTo(ix, iy + is); path.close();
                c.drawPath(path, p);
            } else {
                c.drawRect(ix, iy, ix + is * 0.32f, iy + is, p);
                c.drawRect(ix + is * 0.55f, iy, ix + is * 0.87f, iy + is, p);
            }
        }

        // battery (real level)
        int lvl = ((BatteryManager) act.getSystemService(Context.BATTERY_SERVICE))
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if (lvl <= 0 || lvl > 100) lvl = 100;
        float bw = th * 1.1f, bh = th * 0.48f, bx = lcd.right - bw - th * 0.45f, by = T + (th - bh) / 2;
        p.setColor(0xff6c7078);
        c.drawRect(bx, by, bx + bw, by + bh, p);
        c.drawRect(bx + bw, by + bh * 0.28f, bx + bw + bh * 0.18f, by + bh * 0.72f, p);
        p.setColor(0xffffffff);
        c.drawRect(bx + 2, by + 2, bx + bw - 2, by + bh - 2, p);
        p.setShader(grad(by, by + bh, lvl > 20 ? 0xff9be36a : 0xffff8a7a, lvl > 20 ? 0xff3fae2a : 0xffd63a2a));
        c.drawRect(bx + 3, by + 3, bx + 3 + (bw - 6) * lvl / 100f, by + bh - 3, p);
        p.setShader(null);

        if (s.type == NOW) { drawNowPlaying(c, T + th, H - th); return; }

        // list
        float rh = (H - th) / ROWS, n = s.items.size();
        boolean bar = n > ROWS;
        boolean split = s.type == MAIN || s.type == MUSIC;      // menu left, artwork right
        float listR = split ? L + W / 2 : lcd.right - (bar ? rh * 0.32f : 0);
        if (split) drawMenuArt(c, listR, T + th);
        boolean sub = s.type == MAIN || s.type == MUSIC || s.type == ARTISTS || s.type == ALBUMS;
        for (int i = s.top; i < Math.min(n, s.top + ROWS); i++) {
            float y = T + th + (i - s.top) * rh;
            boolean sel = i == s.sel;
            if (sel) {
                p.setShader(grad(y, y + rh, 0xff62b0f6, 0xff1a66d2));
                c.drawRect(L, y, listR, y + rh, p);
                p.setShader(null);
            }
            boolean chevron = sub && !s.items.get(i).equals("Shuffle Songs");
            text(c, s.items.get(i), L + rh * 0.3f, y + rh * 0.7f, listR - L - rh * (chevron ? 1.1f : 0.5f),
                    rh * 0.6f, sel ? 0xffffffff : 0xff111111, true, Paint.Align.LEFT);
            if (chevron) {
                float ax = listR - rh * 0.55f, ay = y + rh / 2, as = rh * 0.17f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(rh * 0.09f);
                p.setColor(sel ? 0xffffffff : 0xff555555);
                path.rewind();
                path.moveTo(ax - as * 0.6f, ay - as); path.lineTo(ax + as * 0.6f, ay); path.lineTo(ax - as * 0.6f, ay + as);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        if (bar && !split) {
            float top = T + th, hh = H - th;
            p.setColor(0xffe3e5e8);
            c.drawRect(listR, top, lcd.right, lcd.bottom, p);
            p.setShader(grad(top, lcd.bottom, 0xff9aa0aa, 0xff6f757f));
            float th2 = Math.max(hh * ROWS / n, rh * 0.6f);
            float ty = top + (hh - th2) * s.top / (n - ROWS);
            tmp.set(listR + 3, ty + 3, lcd.right - 3, ty + th2 - 3);
            c.drawRoundRect(tmp, 8, 8, p);
            p.setShader(null);
        }
    }

    void drawNowPlaying(Canvas c, float top, float H) {
        Song s = PlayerService.current();
        float L = lcd.left, W = lcd.width(), pad = W * 0.045f;
        if (s == null) {
            text(c, "Nothing Playing", L + W / 2, top + H / 2, W, H * 0.08f, 0xff555555, true, Paint.Align.CENTER);
            return;
        }
        float fs = H * 0.075f;
        text(c, (PlayerService.index + 1) + " of " + PlayerService.queue.size(), L + pad, top + fs * 1.5f,
                W, fs * 0.85f, 0xff444444, false, Paint.Align.LEFT);

        // album art
        float as = H * 0.44f, ax = L + pad, ay = top + H * 0.15f;
        if (artId != s.id) loadArt(s);
        tmp.set(ax, ay, ax + as, ay + as);
        if (art != null) {
            c.drawBitmap(art, null, tmp, p);
            c.save();                              // mirrored reflection under the cover
            c.clipRect(ax, ay + as, ax + as, ay + as * 1.22f);
            c.scale(1, -1, 0, ay + as);
            p.setAlpha(60);
            c.drawBitmap(art, null, tmp, p);
            p.setAlpha(255);
            c.restore();
        } else {
            p.setShader(grad(ay, ay + as, 0xffe9ebee, 0xffc3c7cd));
            c.drawRect(tmp, p);
            p.setShader(null);
            p.setColor(0xff8e939b);
            float nx = ax + as * 0.42f, ny = ay + as * 0.66f, nr = as * 0.1f;
            c.drawCircle(nx, ny, nr, p);
            c.drawRect(nx + nr * 0.6f, ny - as * 0.4f, nx + nr, ny, p);
            c.drawRect(nx + nr * 0.6f, ny - as * 0.4f, nx + nr * 2.6f, ny - as * 0.33f, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0xff9a9ea6);
        c.drawRect(tmp, p);
        p.setStyle(Paint.Style.FILL);

        float tx = ax + as + pad, tw = lcd.right - pad - tx;
        text(c, s.title, tx, ay + fs * 1.6f, tw, fs * 1.05f, 0xff111111, true, Paint.Align.LEFT);
        text(c, s.artist, tx, ay + fs * 3.2f, tw, fs * 0.95f, 0xff333333, false, Paint.Align.LEFT);
        text(c, s.album, tx, ay + fs * 4.6f, tw, fs * 0.95f, 0xff666666, false, Paint.Align.LEFT);

        // progress / volume / scrub bar
        long now = SystemClock.uptimeMillis();
        if (scrub && now >= scrubUntil) scrub = false;
        boolean vol = !scrub && now < volUntil;
        int dur = PlayerService.prepared ? PlayerService.inst.mp.getDuration() : s.duration;
        int pos = PlayerService.position();
        float frac = vol ? am.getStreamVolume(AudioManager.STREAM_MUSIC)
                / (float) am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                : dur > 0 ? Math.min(1f, pos / (float) dur) : 0;
        float bx = L + pad, bw = W - 2 * pad, bh = H * 0.06f, by = top + H * 0.76f;
        tmp.set(bx, by, bx + bw, by + bh);
        p.setShader(grad(by, by + bh, 0xffc9ccd1, 0xfff1f2f4));
        c.drawRoundRect(tmp, bh / 2, bh / 2, p);
        c.save();
        c.clipRect(bx, by, bx + bw * frac, by + bh);
        p.setShader(grad(by, by + bh, 0xff7fc0fb, 0xff1a66d2));
        c.drawRoundRect(tmp, bh / 2, bh / 2, p);
        c.restore();
        p.setShader(null);
        if (scrub) {
            float kx = bx + bw * frac, ky = by + bh / 2, ks = bh * 0.85f;
            p.setColor(0xff222222);
            path.rewind();
            path.moveTo(kx, ky - ks); path.lineTo(kx + ks, ky); path.lineTo(kx, ky + ks); path.lineTo(kx - ks, ky); path.close();
            c.drawPath(path, p);
        }
        float ty = by + bh + fs * 1.3f;
        if (vol) text(c, "Volume", L + W / 2, ty, W, fs * 0.85f, 0xff333333, false, Paint.Align.CENTER);
        else {
            text(c, time(pos), bx, ty, W, fs * 0.85f, 0xff333333, false, Paint.Align.LEFT);
            text(c, "-" + time(Math.max(0, dur - pos)), bx + bw, ty, W, fs * 0.85f, 0xff333333, false, Paint.Align.RIGHT);
        }
    }

    static String time(int ms) {
        int sec = ms / 1000;
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    Bitmap decode(Song s) {
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

    void loadArt(final Song s) {
        artId = s.id;
        art = null;
        new Thread(() -> {
            final Bitmap result = decode(s);
            post(() -> { if (artId == s.id) { art = result; invalidate(); } });
        }).start();
    }

    /** Right half of the top menus: a cover from the library, swapped every so often. */
    void drawMenuArt(Canvas c, float left, float top) {
        long now = SystemClock.uptimeMillis();
        if (now > menuArtNext && !all.isEmpty()) {
            menuArtNext = now + 12000;
            final List<Song> pool = all;
            new Thread(() -> {
                for (int i = 0; i < 6; i++) {
                    final Bitmap bmp = decode(pool.get((int) (Math.random() * pool.size())));
                    if (bmp != null) { post(() -> { menuArt = bmp; invalidate(); }); return; }
                }
            }).start();
        }
        tmp.set(left, top, lcd.right, lcd.bottom);
        if (menuArt != null) {
            float bw = menuArt.getWidth(), bh = menuArt.getHeight();
            float cw = Math.min(bw, bh * tmp.width() / tmp.height()), ch = cw * tmp.height() / tmp.width();
            src.set((int) ((bw - cw) / 2), (int) ((bh - ch) / 2), (int) ((bw + cw) / 2), (int) ((bh + ch) / 2));
            c.drawBitmap(menuArt, src, tmp, p);
        } else {
            p.setShader(grad(top, lcd.bottom, 0xffdfe2e6, 0xffaeb3ba));
            c.drawRect(tmp, p);
            p.setShader(null);
        }
        // the menu casts a soft shadow onto the picture
        p.setShader(new LinearGradient(left, 0, left + tmp.width() * 0.08f, 0, 0x55000000, 0, Shader.TileMode.CLAMP));
        c.drawRect(tmp, p);
        p.setShader(null);
    }

    void drawWheel(Canvas c) {
        // soft drop shadow, then the wheel face
        p.setColor(0x22000000);
        c.drawCircle(cx, cy + R * 0.015f, R * 1.012f, p);
        p.setShader(grad(cy - R, cy + R, 0xffffffff, 0xffecedef));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);

        int ink = 0xffaeb1b7;
        text(c, "MENU", cx, cy - R * 0.72f, R, R * 0.15f, ink, true, Paint.Align.CENTER);
        p.setColor(ink);
        float s = R * 0.075f;
        skip(c, cx + R * 0.74f, cy, s, 1);
        skip(c, cx - R * 0.74f, cy, s, -1);
        // play / pause glyph
        float px = cx - s * 1.5f, py = cy + R * 0.76f;
        tri(c, px, py, s, 1);
        c.drawRect(px + s * 1.5f, py - s, px + s * 2.0f, py + s, p);
        c.drawRect(px + s * 2.5f, py - s, px + s * 3.0f, py + s, p);

        // centre button
        p.setColor(0x30000000);
        c.drawCircle(cx, cy, r * 1.02f, p);
        p.setShader(onCenter ? grad(cy - r, cy + r, 0xffb9bbc0, 0xffd6d8dc)
                : grad(cy - r, cy + r, 0xffeceef0, 0xffc6c8cd));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    /** Triangle of half-height s with its flat edge at x, pointing in dir. */
    void tri(Canvas c, float x, float y, float s, int dir) {
        path.rewind();
        path.moveTo(x, y - s); path.lineTo(x + dir * s * 1.2f, y); path.lineTo(x, y + s); path.close();
        c.drawPath(path, p);
    }

    /** Skip glyph: two triangles and a bar, centred on x. */
    void skip(Canvas c, float x, float y, float s, int dir) {
        tri(c, x - dir * s * 1.5f, y, s, dir);
        tri(c, x - dir * s * 0.3f, y, s, dir);
        float bx = x + dir * s * 0.9f;
        c.drawRect(Math.min(bx, bx + dir * s * 0.4f), y - s, Math.max(bx, bx + dir * s * 0.4f), y + s, p);
    }
}
