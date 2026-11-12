package com.max.pod;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * The physical player: aluminium face, chrome edge, glass window and click wheel, painted once
 * into a bitmap. Every size is the real one in millimetres (taken from Apple's front-view drawing
 * of the iPod classic) multiplied by u, the number of pixels per millimetre.
 */
final class Hardware {
    static final float W = 61.8f, H = 103.5f, CORNER = 6.8f;                       // body
    static final float WIN_X = 4.85f, WIN_Y = 4.85f, WIN_W = 52.1f, WIN_H = 39.6f; // glass window
    static final float LCD_W = 49.9f;                                             // lit 4:3 panel
    static final float WHEEL_Y = 72.86f, WHEEL_R = 18.93f, BUTTON_R = 6.82f;

    /** The colours of one model. Everything else (shape, steel back, glass) is shared. */
    static final class Finish {
        final String name;
        final boolean dark;                 // dark wheel: labels and press hints are light
        final int[] face, edge, wheel, button, recess;
        final int label, roll, grain;

        Finish(String name, boolean dark, int[] face, int[] edge, int roll, int[] wheel, int label,
               int[] button, int[] recess, int grain) {
            this.name = name; this.dark = dark; this.face = face; this.edge = edge; this.roll = roll;
            this.wheel = wheel; this.label = label; this.button = button; this.recess = recess; this.grain = grain;
        }
    }

    static final Finish SILVER = new Finish("silver", false,
            new int[]{0xffe6e8eb, 0xffd9dbdf, 0xffc6c8cd}, new int[]{0xd0ffffff, 0x70ffffff, 0x20ffffff, 0x40ffffff},
            0x42000000, new int[]{0xffffffff, 0xfff4f4f5, 0xffe5e6e8}, 0xffa7a9ae,
            new int[]{0xffc3c5c9, 0xffd7d9dc, 0xffeaebed}, new int[]{0xff64676c, 0xffb4b7bb, 0xffffffff}, 12);
    static final Finish BLACK = new Finish("black", true,
            new int[]{0xff303134, 0xff222326, 0xff141517}, new int[]{0x80ffffff, 0x38ffffff, 0x0cffffff, 0x1cffffff},
            0x70000000, new int[]{0xff2b2c2f, 0xff1f2023, 0xff151618}, 0xffe9eaec,
            new int[]{0xff121315, 0xff1c1d20, 0xff2a2b2e}, new int[]{0xff000000, 0xff2a2b2e, 0xff6a6c70}, 9);
    static final Finish RED = new Finish("red", false,
            new int[]{0xffdd2a35, 0xffc81d28, 0xffa3121b}, new int[]{0xb0ffffff, 0x58ffffff, 0x14ffffff, 0x30ffffff},
            0x50000000, new int[]{0xffffffff, 0xfff4f4f5, 0xffe5e6e8}, 0xffa7a9ae,
            new int[]{0xffa0111a, 0xffbb1923, 0xffd12631}, new int[]{0xff5e0a10, 0xffb8323a, 0xffff9aa0}, 12);

    /** Mostly silver; now and then the black one, or the rare red one. */
    static Finish pick(Random rnd) {
        int roll = rnd.nextInt(100);
        return roll < 10 ? BLACK : roll < 20 ? RED : SILVER;
    }

    Finish finish = SILVER;

    final RectF body = new RectF(), window = new RectF(), lcd = new RectF();
    final Path windowPath = new Path(), ring = new Path();
    float u, cx, cy, R, r;
    Bitmap bitmap;
    Shader glare;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF t = new RectF();
    private final Path path = new Path();

    private static final Shader.TileMode CLAMP = Shader.TileMode.CLAMP;

    private Paint fill(int color) {
        p.setShader(null);
        p.setMaskFilter(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private Paint shade(Shader s) {
        fill(0xff000000);
        p.setShader(s);
        return p;
    }

    private Paint stroke(float width, float blur) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setMaskFilter(blur > 0 ? new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) : null);
        return p;
    }

    private static Shader vertical(float y0, float y1, int[] colors, float[] stops) {
        return new LinearGradient(0, y0, 0, y1, colors, stops, CLAMP);
    }

    /** Light comes from the upper left, so a recess is dark there and bright at the lower right. */
    private Shader recess(float x0, float y0, float x1, float y1) {
        return new LinearGradient(x0, y0, x1, y1, finish.recess, new float[]{0f, 0.5f, 1f}, CLAMP);
    }

    void render(int w, int h) {
        u = Math.min(w * 0.94f / W, h * 0.95f / H);
        float bw = W * u, bh = H * u, bx = (w - bw) / 2, by = (h - bh) / 2;
        body.set(bx, by, bx + bw, by + bh);
        window.set(bx + WIN_X * u, by + WIN_Y * u, bx + (WIN_X + WIN_W) * u, by + (WIN_Y + WIN_H) * u);
        float lw = Math.round(LCD_W * u), lh = Math.round(LCD_W * u * 0.75f);
        float lx = Math.round(window.centerX() - lw / 2), ly = Math.round(window.centerY() - lh / 2);
        lcd.set(lx, ly, lx + lw, ly + lh);
        cx = body.centerX();
        cy = by + WHEEL_Y * u;
        R = WHEEL_R * u;
        r = BUTTON_R * u;
        windowPath.rewind();
        windowPath.addRoundRect(window, u, u, Path.Direction.CW);
        ring.rewind();
        ring.addCircle(cx, cy, R, Path.Direction.CW);
        ring.addCircle(cx, cy, r, Path.Direction.CCW);
        glare = new LinearGradient(window.left, window.top,
                window.left + window.width() * 0.55f, window.top + window.height() * 0.95f,
                new int[]{0x26ffffff, 0x10ffffff, 0x00ffffff}, new float[]{0f, 0.45f, 1f}, CLAMP);

        if (bitmap != null) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Shader grain = new BitmapShader(noise(finish.grain), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        float rc = CORNER * u;

        // backdrop: a dark surface with a pool of light behind the player
        c.drawPaint(shade(new RadialGradient(w / 2f, body.centerY() - bh * 0.1f, Math.max(w, h) * 0.7f,
                new int[]{0xff3d4046, 0xff1d1f22, 0xff09090a}, new float[]{0f, 0.55f, 1f}, CLAMP)));

        // shadow it casts: a wide soft one and a tight contact one
        fill(0xb0000000).setMaskFilter(new BlurMaskFilter(3.4f * u, BlurMaskFilter.Blur.NORMAL));
        t.set(body);
        t.offset(0, 1.9f * u);
        c.drawRoundRect(t, rc, rc, p);
        fill(0x90000000).setMaskFilter(new BlurMaskFilter(0.8f * u, BlurMaskFilter.Blur.NORMAL));
        t.set(body);
        t.offset(0, 0.5f * u);
        c.drawRoundRect(t, rc, rc, p);

        // hold switch peeking over the top edge
        t.set(bx + bw * 0.137f, by - 0.55f * u, bx + bw * 0.31f, by + 1.5f * u);
        c.drawRoundRect(t, 0.3f * u, 0.3f * u, shade(vertical(t.top, by,
                new int[]{0xfff4f5f6, 0xffa9acb0}, null)));

        // polished steel back, seen as a thin rim around the face
        c.drawRoundRect(body, rc, rc, shade(vertical(body.top, body.bottom,
                new int[]{0xffffffff, 0xffb1b4b9, 0xffe9eaec, 0xffa3a6ab, 0xff6c6f75, 0xffc2c5c9, 0xff7f8288},
                new float[]{0f, 0.03f, 0.085f, 0.5f, 0.93f, 0.975f, 1f})));
        c.drawRoundRect(body, rc, rc, shade(new LinearGradient(body.left, 0, body.right, 0,
                new int[]{0x80ffffff, 0x00ffffff, 0x00000000, 0x50000000},
                new float[]{0f, 0.05f, 0.95f, 1f}, CLAMP)));

        // anodised aluminium face
        float e = 0.42f * u, rf = rc - e;
        t.set(body);
        t.inset(e, e);
        Path face = new Path();
        face.addRoundRect(t, rf, rf, Path.Direction.CW);
        fill(0x70000000);
        stroke(0.12f * u, 0);
        c.drawPath(face, p);                                   // seam between steel and aluminium
        c.drawPath(face, shade(vertical(t.top, t.bottom,
                finish.face, new float[]{0f, 0.45f, 1f})));
        c.save();
        c.clipPath(face);
        fill(finish.roll);
        stroke(2.6f * u, 1.1f * u);
        c.drawPath(face, p);                                   // the face rolls off toward its edge
        path.rewind();
        t.inset(0.55f * u, 0.55f * u);
        path.addRoundRect(t, rf - 0.55f * u, rf - 0.55f * u, Path.Direction.CW);
        shade(vertical(body.top, body.bottom,
                finish.edge, new float[]{0f, 0.1f, 0.6f, 1f}));
        stroke(0.4f * u, 0.45f * u);
        c.drawPath(path, p);                                   // light catching that rolled edge
        c.drawPaint(shade(grain));
        c.restore();

        // screen: bevelled opening, black glass, dark panel (the lit picture is drawn live)
        t.set(window);
        t.inset(-0.22f * u, -0.22f * u);
        c.drawRoundRect(t, 1.2f * u, 1.2f * u, shade(recess(t.left, t.top, t.right, t.bottom)));
        c.drawPath(windowPath, shade(vertical(window.top, window.bottom,
                new int[]{0xff17181a, 0xff050506}, null)));
        c.drawRect(lcd, fill(0xff000000));

        // click wheel
        c.drawCircle(cx, cy, R + 0.2f * u, shade(recess(cx - R, cy - R, cx + R, cy + R)));
        c.drawCircle(cx, cy, R, shade(new LinearGradient(cx - R * 0.5f, cy - R, cx + R * 0.5f, cy + R,
                finish.wheel, new float[]{0f, 0.5f, 1f}, CLAMP)));
        c.drawCircle(cx, cy, R, shade(new RadialGradient(cx, cy, R,
                new int[]{0x00000000, 0x00000000, 0x14000000, 0x38000000},
                new float[]{0f, 0.95f, 0.985f, 1f}, CLAMP)));

        fill(finish.label);
        menuLabel(c, cx, cy - 0.815f * R, 0.1015f * R);
        float ih = 0.049f * R;                                 // half the height of the symbols
        skipLabel(c, cx + 0.81f * R, cy, ih, 1);
        skipLabel(c, cx - 0.81f * R, cy, ih, -1);
        playLabel(c, cx, cy + 0.822f * R, ih);

        // centre button: same aluminium, very slightly dished
        c.drawCircle(cx, cy, r + 0.2f * u, shade(recess(cx - r, cy - r, cx + r, cy + r)));
        c.drawCircle(cx, cy, r, shade(vertical(cy - r, cy + r,
                finish.button, new float[]{0f, 0.55f, 1f})));
        c.save();
        path.rewind();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        c.clipPath(path);
        shade(vertical(cy - r, cy + r,
                new int[]{0x58000000, 0x00000000, 0x00ffffff, finish.dark ? 0x40ffffff : 0xc0ffffff},
                new float[]{0f, 0.45f, 0.55f, 1f}));
        stroke(1.0f * u, 0.5f * u);
        c.drawCircle(cx, cy, r, p);
        c.drawPaint(shade(grain));
        c.restore();
    }

    /** Fine random speckle that gives the metal its bead-blasted look. */
    private static Bitmap noise(int strength) {
        int n = 160;
        int[] px = new int[n * n];
        Random rnd = new Random(11);
        for (int i = 0; i < px.length; i++)
            px[i] = (rnd.nextInt(strength) << 24) | (rnd.nextBoolean() ? 0xffffff : 0x000000);
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** "MENU", drawn as shapes so it matches the printed label on any phone. hc = cap height. */
    private void menuLabel(Canvas c, float midX, float midY, float hc) {
        float gap = 0.26f, total = 1.03f + 0.596f + 0.761f + 0.771f + 3 * gap;
        c.save();
        c.translate(midX - total * hc / 2, midY - hc / 2);
        c.scale(hc, hc);
        path.rewind();                                         // M
        float[] m = {0, 1, 0.105f, 0, 0.29f, 0, 0.515f, 0.66f, 0.74f, 0, 0.925f, 0, 1.03f, 1,
                0.86f, 1, 0.79f, 0.36f, 0.572f, 1, 0.458f, 1, 0.24f, 0.36f, 0.17f, 1};
        path.moveTo(m[0], m[1]);
        for (int i = 2; i < m.length; i += 2) path.lineTo(m[i], m[i + 1]);
        path.close();
        float x = 1.03f + gap;                                 // E
        path.addRect(x, 0, x + 0.175f, 1, Path.Direction.CW);
        path.addRect(x, 0, x + 0.57f, 0.155f, Path.Direction.CW);
        path.addRect(x, 0.42f, x + 0.53f, 0.57f, Path.Direction.CW);
        path.addRect(x, 0.845f, x + 0.596f, 1, Path.Direction.CW);
        x += 0.596f + gap;                                     // N
        path.addRect(x, 0, x + 0.17f, 1, Path.Direction.CW);
        path.addRect(x + 0.591f, 0, x + 0.761f, 1, Path.Direction.CW);
        path.moveTo(x, 0);
        path.lineTo(x + 0.19f, 0);
        path.lineTo(x + 0.761f, 1);
        path.lineTo(x + 0.571f, 1);
        path.close();
        x += 0.761f + gap;                                     // U
        path.moveTo(x, 0);
        path.lineTo(x + 0.175f, 0);
        path.lineTo(x + 0.175f, 0.62f);
        t.set(x + 0.175f, 0.395f, x + 0.596f, 0.845f);
        path.arcTo(t, 180, -180);
        path.lineTo(x + 0.596f, 0);
        path.lineTo(x + 0.771f, 0);
        path.lineTo(x + 0.771f, 0.62f);
        t.set(x, 0.23f, x + 0.771f, 1.01f);
        path.arcTo(t, 0, 180);
        path.close();
        c.drawPath(path, p);
        c.restore();
    }

    private void triangle(float x, float y, float halfH, float width) {
        path.moveTo(x, y - halfH);
        path.lineTo(x + width, y);
        path.lineTo(x, y + halfH);
        path.close();
    }

    /** Next/previous symbol: two triangles running into a bar. dir = 1 points right. */
    private void skipLabel(Canvas c, float midX, float midY, float ih, int dir) {
        float tri = 0.0714f * R, bar = 0.022f * R, start = midX - dir * (tri * 2 + bar) / 2;
        path.rewind();
        triangle(start, midY, ih, dir * tri);
        triangle(start + dir * tri, midY, ih, dir * tri);
        float b0 = start + dir * tri * 2, b1 = b0 + dir * bar;
        path.addRect(Math.min(b0, b1), midY - ih, Math.max(b0, b1), midY + ih, Path.Direction.CW);
        c.drawPath(path, p);
    }

    private void playLabel(Canvas c, float midX, float midY, float ih) {
        float tri = 0.0943f * R, gap = 0.053f * R, bar = 0.022f * R, between = 0.0238f * R;
        float x = midX - (tri + gap + bar * 2 + between) / 2;
        path.rewind();
        triangle(x, midY, ih, tri);
        x += tri + gap;
        path.addRect(x, midY - ih, x + bar, midY + ih, Path.Direction.CW);
        x += bar + between;
        path.addRect(x, midY - ih, x + bar, midY + ih, Path.Direction.CW);
        c.drawPath(path, p);
    }
}
