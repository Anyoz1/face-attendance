package com.anyoz.faceattendance;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Small native UI primitives shared by Rollmark's screens. No bitmap assets or network fonts. */
final class Ui {
  static final int BG = 0xfff5f7fb, INK = 0xff172c48, MUTED = 0xff65758c, BLUE = 0xff4366ed,
                   NAVY = 0xff192e4c, LIME = 0xffdef79b, LINE = 0xffe5eaf3, SOFT = 0xffeaf0ff,
                   GREEN = 0xff18765a, GREEN_BG = 0xffe5f5ed, AMBER = 0xff9a621c,
                   AMBER_BG = 0xfffff2dc;
  final Context context;
  Ui(Context context) {
    this.context = context;
  }
  int dp(float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
  GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }
  Drawable ripple(int color, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x184366ed), shape(color, radius), shape(0xffffffff, radius));
  }
  TextView text(String value, int size, int color) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    t.setLineSpacing(dp(3), 1);
    return t;
  }
  TextView bold(String value, int size, int color) {
    TextView t = text(value, size, color);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return t;
  }
  LinearLayout column() {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }
  LinearLayout row() {
    LinearLayout l = new LinearLayout(context);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }
  LinearLayout card() {
    LinearLayout l = column();
    l.setPadding(dp(20), dp(20), dp(20), dp(20));
    l.setBackground(shape(Color.WHITE, 24));
    return l;
  }
  void gap(LinearLayout parent, int height) {
    View v = new View(context);
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }
  TextView pill(String label, int ink, int fill) {
    TextView t = bold(label, 11, ink);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(10), dp(6), dp(10), dp(6));
    t.setBackground(shape(fill, 20));
    return t;
  }
  Button button(String label, String icon, int fill, int ink, Runnable action) {
    Button b = new ActionButton(icon, ink);
    b.setText(label);
    b.setTextSize(15);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(ink);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(56));
    b.setMinimumHeight(dp(56));
    b.setMinWidth(0);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(icon == null ? 18 : 50), dp(12), dp(18), dp(12));
    b.setBackground(ripple(fill, 18));
    b.setOnClickListener(v -> action.run());
    return b;
  }
  /** Keeps an icon and its caption centered together, including multiline captions. */
  private final class ActionButton extends Button {
    final Drawable glyph;
    ActionButton(String name, int ink) {
      super(context);
      glyph = name == null ? null : icon(name, ink);
    }
    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      if (glyph == null || getLayout() == null)
        return;
      float labelWidth = 0;
      for (int line = 0; line < getLayout().getLineCount(); line++)
        labelWidth = Math.max(labelWidth, getLayout().getLineWidth(line));
      int left = Math.round((getWidth() - labelWidth - dp(32)) / 2f);
      int top = (getHeight() - dp(22)) / 2;
      glyph.setBounds(left, top, left + dp(22), top + dp(22));
      glyph.draw(canvas);
    }
  }
  ImageButton iconButton(String icon, String description, int fill, int ink, Runnable action) {
    ImageButton b = new ImageButton(context);
    b.setImageDrawable(icon(icon, ink));
    b.setContentDescription(description);
    b.setBackground(ripple(fill, 18));
    b.setPadding(dp(13), dp(13), dp(13), dp(13));
    b.setOnClickListener(v -> action.run());
    b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    return b;
  }
  Drawable icon(String name, int color) {
    return new Glyph(name, color, dp(22));
  }
  View logo(int size) {
    ImageView v = new ImageView(context);
    v.setImageResource(com.anyoz.faceattendance.R.drawable.ic_mark);
    v.setBackground(shape(NAVY, Math.round(size * .3f)));
    v.setPadding(dp(size * .12f), dp(size * .12f), dp(size * .12f), dp(size * .12f));
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return v;
  }
  TextView avatar(String name, int size) {
    String[] words = name.trim().split("\\s+");
    String initials = words[0].isEmpty() ? "?" : words[0].substring(0, 1);
    if (words.length > 1)
      initials += words[words.length - 1].substring(0, 1);
    int[] fills = {0xffedf0ff, 0xffe5f3ed, 0xffffefdf, 0xffeee9fa};
    int[] inks = {BLUE, GREEN, AMBER, 0xff7e55ae};
    int i = (name.hashCode() & 0x7fffffff) % fills.length;
    TextView t = bold(initials.toUpperCase(java.util.Locale.ROOT), 16, inks[i]);
    t.setGravity(Gravity.CENTER);
    t.setBackground(shape(fills[i], 16));
    t.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return t;
  }

  static final class Item {
    String title, subtitle, badge, icon;
    int badgeColor = GREEN, badgeFill = GREEN_BG;
    Runnable action;
    Item(String title, String subtitle, String icon, Runnable action) {
      this.title = title;
      this.subtitle = subtitle;
      this.icon = icon;
      this.action = action;
    }
    Item badge(String label, int ink, int fill) {
      badge = label;
      badgeColor = ink;
      badgeFill = fill;
      return this;
    }
  }
  final class ItemView extends FrameLayout {
    final LinearLayout body = row(), labels = column();
    final ImageView glyph = new ImageView(context);
    final TextView initials = bold("", 16, BLUE), title = bold("", 16, INK),
                   subtitle = text("", 12, MUTED), badge = pill("", GREEN, GREEN_BG);
    final FrameLayout leading = new FrameLayout(context);
    final ImageView arrow = new ImageView(context);
    ItemView() {
      super(context);
      setPadding(dp(20), 0, dp(20), dp(8));
      body.setPadding(dp(14), dp(15), dp(12), dp(15));
      body.setBackground(ripple(Color.WHITE, 20));
      addView(body, new FrameLayout.LayoutParams(-1, -2));
      leading.setBackground(shape(SOFT, 16));
      body.addView(leading, new LinearLayout.LayoutParams(dp(46), dp(46)));
      FrameLayout.LayoutParams centered =
          new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
      leading.addView(glyph, centered);
      initials.setGravity(Gravity.CENTER);
      leading.addView(initials, new FrameLayout.LayoutParams(-1, -1));
      labels.addView(title);
      gap(labels, 5);
      labels.addView(subtitle);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.leftMargin = dp(12);
      lp.rightMargin = dp(6);
      body.addView(labels, lp);
      body.addView(badge);
      arrow.setImageDrawable(icon("chevron", MUTED));
      LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(18), dp(22));
      a.leftMargin = dp(6);
      body.addView(arrow, a);
      title.setMaxLines(2);
      subtitle.setMaxLines(2);
    }
    void bind(Item item) {
      title.setText(item.title);
      subtitle.setText(item.subtitle);
      if (item.icon != null) {
        glyph.setImageDrawable(icon(item.icon, BLUE));
        glyph.setVisibility(VISIBLE);
        initials.setVisibility(GONE);
      } else {
        TextView sample = avatar(item.title, 46);
        initials.setText(sample.getText());
        initials.setTextColor(sample.getCurrentTextColor());
        leading.setBackground(sample.getBackground());
        glyph.setVisibility(GONE);
        initials.setVisibility(VISIBLE);
      }
      if (item.icon != null)
        leading.setBackground(shape(SOFT, 16));
      badge.setVisibility(item.badge == null ? GONE : VISIBLE);
      badge.setText(item.badge);
      badge.setTextColor(item.badgeColor);
      badge.setBackground(shape(item.badgeFill, 16));
      arrow.setVisibility(item.badge == null && item.action != null ? VISIBLE : GONE);
      body.setOnClickListener(v -> {
        if (item.action != null)
          item.action.run();
      });
      body.setClickable(item.action != null);
      body.setFocusable(item.action != null);
    }
  }
  final class Ring extends View {
    final Paint paint = new Paint(3);
    final RectF bounds = new RectF();
    final Typeface ringTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final int present, total;
    final boolean dark;
    Ring(int present, int total, boolean dark) {
      super(context);
      this.present = present;
      this.total = total;
      this.dark = dark;
      setContentDescription(present + " из " + total + " отмечены");
    }
    @Override
    protected void onDraw(Canvas canvas) {
      float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 - dp(5);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(6));
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setColor(dark ? 0xff3b4e65 : LINE);
      bounds.set(cx - r, cy - r, cx + r, cy + r);
      canvas.drawOval(bounds, paint);
      paint.setColor(dark ? LIME : BLUE);
      canvas.drawArc(bounds, -90, total == 0 ? 0 : 360f * present / total, false, paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setTypeface(ringTypeface);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(dp(20));
      paint.setColor(dark ? Color.WHITE : INK);
      String value = total == 0 ? "0%" : Math.round(present * 100f / total) + "%";
      canvas.drawText(value, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
    }
  }
  private static final class Glyph extends Drawable {
    final String name;
    final Paint p = new Paint(3);
    final int size;
    Glyph(String name, int color, int size) {
      this.name = name;
      this.size = size;
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
    }
    void line(Canvas c, float... points) {
      Path path = new Path();
      path.moveTo(points[0], points[1]);
      for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
      c.drawPath(path, p);
    }
    void rect(Canvas c, float l, float t, float r, float b, float radius) {
      c.drawRoundRect(l, t, r, b, radius, radius, p);
    }
    @Override
    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      switch (name) {
        case "home":
          line(c, 3, 10, 12, 3, 21, 10, 21, 21, 15, 21, 15, 14, 9, 14, 9, 21, 3, 21, 3, 10);
          break;
        case "people":
          c.drawCircle(9, 8, 3, p);
          line(c, 3, 21, 3, 18, 5, 14, 13, 14, 15, 18, 15, 21);
          line(c, 17, 5, 20, 7, 20, 10, 17, 12);
          line(c, 18, 15, 21, 18, 21, 21);
          break;
        case "groups":
          rect(c, 3, 3, 10, 10, 2);
          rect(c, 14, 3, 21, 10, 2);
          rect(c, 3, 14, 10, 21, 2);
          rect(c, 14, 14, 21, 21, 2);
          break;
        case "history":
          c.drawArc(new RectF(4, 4, 21, 21), -140, 310, false, p);
          line(c, 3, 4, 3, 10, 9, 10);
          line(c, 12, 7, 12, 13, 16, 15);
          break;
        case "settings":
          c.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            line(c, 12, 2, 12, 5);
            c.restore();
          }
          c.drawCircle(12, 12, 8, p);
          break;
        case "plus":
          line(c, 12, 5, 12, 19);
          line(c, 5, 12, 19, 12);
          break;
        case "search":
          c.drawCircle(10, 10, 6, p);
          line(c, 15, 15, 21, 21);
          break;
        case "back":
          line(c, 14, 5, 7, 12, 14, 19);
          break;
        case "down":
          line(c, 6, 9, 12, 15, 18, 9);
          break;
        case "chevron":
          line(c, 9, 5, 16, 12, 9, 19);
          break;
        case "arrow":
          line(c, 4, 12, 20, 12, 14, 6);
          line(c, 20, 12, 14, 18);
          break;
        case "check":
          line(c, 5, 12, 10, 17, 20, 6);
          break;
        case "close":
          line(c, 6, 6, 18, 18);
          line(c, 18, 6, 6, 18);
          break;
        case "camera":
          rect(c, 3, 6, 21, 20, 3);
          line(c, 7, 6, 9, 3, 15, 3, 17, 6);
          c.drawCircle(12, 13, 4, p);
          break;
        case "scan":
          line(c, 8, 3, 3, 3, 3, 8);
          line(c, 16, 3, 21, 3, 21, 8);
          line(c, 3, 16, 3, 21, 8, 21);
          line(c, 16, 21, 21, 21, 21, 16);
          c.drawOval(8, 5, 16, 18, p);
          break;
        case "flip":
          line(c, 3, 8, 18, 8, 15, 5);
          line(c, 21, 16, 6, 16, 9, 19);
          line(c, 18, 8, 15, 11);
          line(c, 6, 16, 9, 13);
          break;
        case "download":
          line(c, 12, 3, 12, 16, 7, 11);
          line(c, 12, 16, 17, 11);
          line(c, 4, 17, 4, 21, 20, 21, 20, 17);
          break;
        case "restore":
          rect(c, 4, 4, 20, 21, 3);
          line(c, 8, 12, 12, 8, 16, 12);
          line(c, 12, 8, 12, 17);
          break;
        case "shield":
          line(c, 12, 2, 21, 6, 20, 15, 16, 20, 12, 22, 8, 20, 4, 15, 3, 6, 12, 2);
          line(c, 8, 12, 11, 15, 16, 9);
          break;
        case "more":
          c.drawCircle(5, 12, 1, p);
          c.drawCircle(12, 12, 1, p);
          c.drawCircle(19, 12, 1, p);
          break;
        default:
          rect(c, 5, 3, 19, 21, 3);
          line(c, 9, 9, 15, 9);
          line(c, 9, 14, 15, 14);
      }
      c.restore();
    }
    @Override
    public int getIntrinsicWidth() {
      return size;
    }
    @Override
    public int getIntrinsicHeight() {
      return size;
    }
    @Override
    public void setAlpha(int alpha) {
      p.setAlpha(alpha);
    }
    @Override
    public void setColorFilter(ColorFilter filter) {
      p.setColorFilter(filter);
    }
    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
