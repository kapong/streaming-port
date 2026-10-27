package com.nailem.streamport;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The screen shown while nobody shares: how to connect from Apple devices (AirPlay) and from any browser (QR
 * code), and a notice while the LAN rebroadcast is on. Laid out in TV pixels on a fixed 1920x1080 canvas (the
 * design in {@code streamport/mock/idle.html}); {@link StreamActivity} scales it to the window, and the
 * rebroadcast draws it off screen with {@link #render} for VLC viewers while nobody shares.
 */
final class IdleView extends FrameLayout {

    static final int W = 1920;
    static final int H = 1080;
    private static final int SIDE = 120;
    private static final int TEAL = 0xFF30E0C4;
    private static final int[] BRAND = {0xFF30E094, 0xFF30E0C4, 0xFF3679F2, 0xFFFF0E36};

    private static final Typeface[] FONTS = new Typeface[10];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final TextView name;
    private final TextView limit;
    private final TextView appleSteps;
    private final TextView url;
    private final ImageView qr;
    private final View notice;
    private final TextView admin;
    private String boundUrl = "";

    IdleView(Context c) {
        super(c);
        setWillNotDraw(false);

        // Header: "Share your screen to", the receiver's name, the brand line, how many screens.
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(text(c, c.getString(R.string.idle_eyebrow), 30, 300, 0x9EFFFFFF));
        name = text(c, "", 132, 700, Color.WHITE);
        name.setMaxLines(1);
        name.setAutoSizeTextTypeUniformWithConfiguration(60, 132, 2, TypedValue.COMPLEX_UNIT_PX);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(1300, 150);
        np.topMargin = 4;
        head.addView(name, np);
        View rule = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, BRAND);
        g.setCornerRadius(3);
        rule.setBackground(g);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(240, 6);
        rp.topMargin = 14;
        head.addView(rule, rp);
        limit = text(c, "", 28, 300, 0xB3FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = 22;
        head.addView(limit, lp);
        addView(head, at(SIDE, 84, -2, -2));

        // Two cards: Apple devices, any browser.
        int cardsTop = 420;
        int cardH = 452;
        int gap = 36;
        int inner = W - 2 * SIDE - gap;
        int appleW = Math.round(inner / 2.25f);
        int webW = inner - appleW;

        LinearLayout apple = card(c, R.drawable.ic_monitor, 0x2E3679F2);
        apple.addView(text(c, c.getString(R.string.apple_title), 36, 600, Color.WHITE));
        apple.addView(who(c, R.string.apple_who));
        appleSteps = text(c, "", 25, 300, 0xE0FFFFFF);
        appleSteps.setLineSpacing(0, 1.22f);
        apple.addView(appleSteps, topMargin(26));
        apple.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1));
        apple.addView(fine(c, R.string.apple_fine));
        addView(apple, at(SIDE, cardsTop, appleW, cardH));

        // The QR code sits top right, level with the icon.
        LinearLayout web = card(c, 0, 0);
        LinearLayout split = new LinearLayout(c);
        split.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(icon(c, R.drawable.ic_globe, 0x2930E0C4), iconParams());
        left.addView(text(c, c.getString(R.string.web_title), 36, 600, Color.WHITE));
        left.addView(who(c, R.string.web_who));
        left.addView(text(c, c.getString(R.string.web_scan), 25, 300, 0xE0FFFFFF), topMargin(26));
        url = text(c, "", 33, 600, TEAL);
        url.setMaxLines(1);
        left.addView(url, topMargin(12));
        split.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout qrBox = new FrameLayout(c);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(20);
        qrBox.setBackground(white);
        qr = new ImageView(c);
        qrBox.addView(qr, new FrameLayout.LayoutParams(216, 216, Gravity.CENTER));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(240, 240);
        qp.leftMargin = 36;
        split.addView(qrBox, qp);
        web.addView(split);
        web.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1));
        web.addView(fine(c, R.string.web_fine));
        addView(web, at(SIDE + appleW + gap, cardsTop, webW, cardH));

        // While the rebroadcast is switched on: say so, before anyone shares, with the address to watch.
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(30, 0, 30, 0);
        GradientDrawable red = new GradientDrawable();
        red.setColor(0x1AFF0E36);
        red.setStroke(1, 0x59FF0E36);
        red.setCornerRadius(22);
        bar.setBackground(red);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFFF0E36);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(14, 14);
        dp.rightMargin = 18;
        bar.addView(dot, dp);
        TextView say = text(c, "", 25, 300, 0xE0FFFFFF);
        say.setText(semibold(html(c.getString(R.string.rebroadcast_notice))));
        say.setMaxLines(1);
        say.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(say, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.leftMargin = 24;
        bar.addView(text(c, com.nailem.streamport.rebroadcast.Rebroadcaster.URL, 25, 600, Color.WHITE), ap);
        notice = bar;
        notice.setVisibility(GONE);
        addView(notice, at(SIDE, 904, W - 2 * SIDE, 72));

        // Footer.
        admin = text(c, "", 24, 300, 0x8CFFFFFF);
        addView(admin, at(SIDE, H - 44 - 32, -2, -2));
        TextView brand = text(c, c.getString(R.string.app_name), 24, 300, 0x8CFFFFFF);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bp.topMargin = H - 44 - 32;
        bp.rightMargin = SIDE;
        addView(brand, bp);
    }

    /** Fills in what can change: the name, the address, the screen limit, whether the rebroadcast is on. */
    void bind(String receiverName, String ipv4, int maxStreams, boolean rebroadcast) {
        Context c = getContext();
        name.setText(receiverName);
        limit.setText(c.getString(R.string.idle_limit, maxStreams));
        appleSteps.setText(semibold(html(c.getString(R.string.apple_steps, TextUtils.htmlEncode(receiverName)))));
        notice.setVisibility(rebroadcast ? VISIBLE : GONE);
        if (ipv4.isEmpty()) {
            url.setText("");
            admin.setText("");
            qr.setImageBitmap(null);
            boundUrl = "";
            return;
        }
        String share = "https://" + ipv4 + ":" + com.nailem.streamport.webrtc.ShareServer.PORT;
        url.setText(share);
        admin.setText(semibold(html(c.getString(R.string.admin_url, ipv4,
                com.nailem.streamport.admin.AdminServer.PORT))));
        if (!share.equals(boundUrl)) {
            qr.setImageBitmap(qrCode(share));
            boundUrl = share;
        }
    }

    /** Draws the screen off screen at 1920x1080, for the rebroadcast while nobody shares. Main thread. */
    static Bitmap render(Context c, String receiverName, String ipv4, int maxStreams, boolean rebroadcast) {
        IdleView v = new IdleView(c);
        v.bind(receiverName, ipv4, maxStreams, rebroadcast);
        v.measure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
        v.layout(0, 0, W, H);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b));
        return b;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Always the design size; the activity scales the whole view to its window.
        super.onMeasure(MeasureSpec.makeMeasureSpec(W, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(H, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF07080A);
        // Soft brand-coloured glows, as on the launcher's background.
        glow(canvas, 0.12f * W, 0.08f * H, 900, 520, 0x2430E094);
        glow(canvas, 0.92f * W, 0.18f * H, 800, 520, 0x293679F2);
        glow(canvas, 0.80f * W, 1.05f * H, 700, 420, 0x1AFF0E36);
        // A faint dot field that fades out towards the edges.
        paint.setShader(null);
        float cx = W * 0.5f;
        float cy = H * 0.4f;
        for (int y = 7; y < H; y += 14) {
            for (int x = 7; x < W; x += 14) {
                float dx = (x - cx) / 1200f;
                float dy = (y - cy) / 700f;
                float fade = 1f - (float) Math.sqrt(dx * dx + dy * dy) / 0.75f;
                if (fade <= 0) continue;
                paint.setColor(Color.argb(Math.round(20 * fade), 255, 255, 255));
                canvas.drawCircle(x, y, 1.3f, paint);
            }
        }
        // The four-screen mark, top right.
        int cw = 150;
        int ch = 84;
        int gap = 14;
        int left = W - SIDE - 2 * cw - gap;
        for (int i = 0; i < 4; i++) {
            int x = left + (i % 2) * (cw + gap);
            int y = 110 + (i / 2) * (ch + gap);
            paint.setColor((BRAND[i] & 0x00FFFFFF) | 0x73000000);
            rect.set(x, y, x + cw, y + ch);
            canvas.drawRoundRect(rect, 14, 14, paint);
        }
    }

    private void glow(Canvas canvas, float x, float y, float rx, float ry, int color) {
        RadialGradient rg = new RadialGradient(0, 0, 1, new int[]{color, color & 0x00FFFFFF},
                new float[]{0f, 0.7f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(x, y);
        rg.setLocalMatrix(m);
        paint.setShader(rg);
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);
    }

    private static LinearLayout card(Context c, int icon, int iconBg) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(44, 40, 44, 36);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC71A1B20);
        bg.setStroke(1, 0x14FFFFFF);
        bg.setCornerRadius(32);
        card.setBackground(bg);
        if (icon != 0) card.addView(icon(c, icon, iconBg), iconParams());
        return card;
    }

    private static View icon(Context c, int icon, int iconBg) {
        FrameLayout box = new FrameLayout(c);
        GradientDrawable b = new GradientDrawable();
        b.setColor(iconBg);
        b.setCornerRadius(18);
        box.setBackground(b);
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        box.addView(i, new FrameLayout.LayoutParams(34, 34, Gravity.CENTER));
        return box;
    }

    private static LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(64, 64);
        p.bottomMargin = 26;
        return p;
    }

    private static View who(Context c, int res) {
        TextView t = text(c, c.getString(res), 24, 300, 0xA8FFFFFF);
        t.setLayoutParams(topMargin(6));
        return t;
    }

    private static View fine(Context c, int res) {
        TextView t = text(c, c.getString(res), 24, 300, 0x99FFFFFF);
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private static LinearLayout.LayoutParams topMargin(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = px;
        return p;
    }

    private static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        p.leftMargin = x;
        p.topMargin = y;
        return p;
    }

    private static TextView text(Context c, String s, int px, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTypeface(font(c, weight));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static Spanned html(String s) {
        return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY);
    }

    /** {@code <b>} from the strings, drawn in Outfit semibold instead of a faked bold. */
    private CharSequence semibold(Spanned in) {
        SpannableStringBuilder b = new SpannableStringBuilder(in);
        for (StyleSpan s : b.getSpans(0, b.length(), StyleSpan.class)) {
            int start = b.getSpanStart(s);
            int end = b.getSpanEnd(s);
            b.removeSpan(s);
            b.setSpan(new TypefaceSpan(font(getContext(), 600)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    /** Outfit (OFL, the launcher's font) at a weight; each weight is built once per process. */
    static Typeface font(Context c, int weight) {
        int i = weight / 100;
        synchronized (FONTS) {
            if (FONTS[i] == null) {
                FONTS[i] = new Typeface.Builder(c.getAssets(), "fonts/outfit.ttf")
                        .setFontVariationSettings("'wght' " + weight).build();
            }
            return FONTS[i];
        }
    }

    private static Bitmap qrCode(String text) {
        try {
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 216, 216, hints);
            Bitmap b = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            return b;
        } catch (com.google.zxing.WriterException e) {
            return null;
        }
    }

    /** The box's IPv4 address on its current network, or "" when offline. */
    static String ipv4(Context c) {
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
        if (lp != null) {
            for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof java.net.Inet4Address) return a.getAddress().getHostAddress();
            }
        }
        return "";
    }
}
