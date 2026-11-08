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
  // Compact light surfaces; role colors are shared across the whole app.
  static final int BG = 0xffe9eef0, SURFACE = 0xfff5f8f8, INK = 0xff25353c, MUTED = 0xff5d6e75,
                   ACCENT = 0xff285f5a, ON_ACCENT = 0xfff9fcfb, LINE = 0xffbfced2,
                   SOFT = 0xffddebe5, BLUE = 0xff3c6583, PURPLE = 0xff705980, GREEN = 0xff356e4d,
                   GREEN_BG = 0xffe0ece2, AMBER = 0xff895b25, AMBER_BG = 0xffeee4d4,
                   ROSE = 0xff8f5361;
  static final int DARK_BG = BG, DARK_SURFACE = SURFACE, DARK_TEXT = INK, DARK_MUTED = MUTED,
                   DARK_ACCENT = ACCENT, DARK_SUCCESS = GREEN, DARK_PREVIEW = 0xff172125,
                   DARK_TRACK = LINE, OVERLAY_ACCENT = 0xffb8e1d5, OVERLAY_SUCCESS = 0xffb8dfba;
  static final int PAGE = 16, RADIUS = 12, CONTROL = 48, ROW = 64, TITLE = 20, BODY = 16,
                   CAPTION = 12, ICON = 20, LEADING = 32;
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
        ColorStateList.valueOf(0x20285f5a), shape(color, radius), shape(0xffffffff, radius));
  }
  static int tint(String icon) {
    if ("people".equals(icon) || "download".equals(icon) || "restore".equals(icon))
      return BLUE;
    if ("groups".equals(icon))
      return PURPLE;
    if ("history".equals(icon) || "file".equals(icon))
      return AMBER;
    return ACCENT;
  }
  static int groupTint(String group) {
    return new int[] {BLUE, PURPLE, AMBER, ACCENT}[(group.hashCode() & 0x7fffffff) % 4];
  }
  static int tintSurface(int tint) {
    return Color.rgb((Color.red(SURFACE) * 7 + Color.red(tint)) / 8,
        (Color.green(SURFACE) * 7 + Color.green(tint)) / 8,
        (Color.blue(SURFACE) * 7 + Color.blue(tint)) / 8);
  }
  LinearLayout panel() {
    LinearLayout panel = column();
    panel.setPadding(dp(12), dp(12), dp(12), dp(12));
    panel.setBackground(shape(SURFACE, RADIUS));
    return panel;
  }
  GradientDrawable fieldBackground() {
    GradientDrawable d = shape(BG, 10);
    d.setStroke(dp(1), LINE);
    return d;
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
  void gap(LinearLayout parent, int height) {
    View v = new View(context);
    parent.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }
  TextView pill(String label, int ink, int fill) {
    TextView t = bold(label, CAPTION, ink);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(10), dp(6), dp(10), dp(6));
    t.setBackground(shape(fill, 20));
    return t;
  }
  Button button(String label, String icon, int fill, int ink, Runnable action) {
    Button b = new ActionButton(icon, ink);
    b.setText(label);
    b.setTextSize(BODY);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(ink);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(CONTROL));
    b.setMinimumHeight(dp(CONTROL));
    b.setMinWidth(0);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(icon == null ? 18 : 50), dp(12), dp(18), dp(12));
    GradientDrawable fillShape = shape(fill, 10);
    if (fill == SURFACE)
      fillShape.setStroke(dp(1), LINE);
    b.setBackground(
        new RippleDrawable(ColorStateList.valueOf(0x20285f5a), fillShape, shape(0xffffffff, 10)));
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
      int top = (getHeight() - dp(ICON)) / 2;
      glyph.setBounds(left, top, left + dp(ICON), top + dp(ICON));
      glyph.draw(canvas);
    }
  }
  ImageButton iconButton(String icon, String description, int fill, int ink, Runnable action) {
    ImageButton b = new ImageButton(context);
    b.setImageDrawable(icon(icon, ink));
    b.setContentDescription(description);
    b.setBackground(new InsetDrawable(ripple(fill, 10), dp(6)));
    b.setPadding(dp(14), dp(14), dp(14), dp(14));
    b.setOnClickListener(v -> action.run());
    b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    return b;
  }
  Drawable icon(String name, int color) {
    return new Glyph(name, color, dp(ICON));
  }
  View logo(int size) {
    ImageView v = new ImageView(context);
    v.setImageResource(com.anyoz.faceattendance.R.drawable.ic_mark);
    v.setBackground(shape(SOFT, Math.round(size * .3f)));
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
    TextView t = bold(initials.toUpperCase(java.util.Locale.ROOT), 16, MUTED);
    t.setGravity(Gravity.CENTER);
    t.setBackground(shape(BG, 20));
    t.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return t;
  }

  static final class Item {
    String title, subtitle, badge, icon;
    int badgeColor = GREEN, badgeFill = GREEN_BG, tint;
    Runnable action, longAction;
    Item(String title, String subtitle, String icon, Runnable action) {
      this.title = title;
      this.subtitle = subtitle;
      this.icon = icon;
      this.action = action;
    }
    Item group(String name) {
      tint = groupTint(name);
      return this;
    }
    Item badge(String label, int ink, int fill) {
      badge = label;
      badgeColor = ink;
      badgeFill = fill;
      return this;
    }
  }
  /** The same grouped row is used for people, groups, history, reports and settings. */
  final class ItemView extends FrameLayout {
    final LinearLayout body = row(), labels = column();
    final ImageView glyph = new ImageView(context), arrow = new ImageView(context);
    final TextView initials = bold("", BODY, BLUE), title = bold("", BODY, INK),
                   subtitle = text("", CAPTION, MUTED), badge = bold("", CAPTION, GREEN);
    final FrameLayout leading = new FrameLayout(context);
    final View separator = new View(context), labelGap = new View(context);
    ItemView() {
      super(context);
      setPadding(dp(PAGE), 0, dp(PAGE), 0);
      body.setMinimumHeight(dp(ROW));
      body.setPadding(dp(12), dp(12), dp(12), dp(12));
      addView(body, new FrameLayout.LayoutParams(-1, -2));
      body.addView(leading, new LinearLayout.LayoutParams(dp(LEADING), dp(LEADING)));
      leading.addView(glyph, new FrameLayout.LayoutParams(dp(ICON), dp(ICON), Gravity.CENTER));
      initials.setGravity(Gravity.CENTER);
      leading.addView(initials, new FrameLayout.LayoutParams(-1, -1));
      labels.addView(title);
      labels.addView(labelGap, new LinearLayout.LayoutParams(1, dp(4)));
      labels.addView(subtitle);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.leftMargin = dp(10);
      lp.rightMargin = dp(10);
      body.addView(labels, lp);
      badge.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      body.addView(badge);
      LinearLayout.LayoutParams tail = new LinearLayout.LayoutParams(dp(ICON), dp(ICON));
      tail.leftMargin = dp(6);
      body.addView(arrow, tail);
      separator.setBackgroundColor(LINE);
      FrameLayout.LayoutParams rule = new FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM);
      rule.leftMargin = dp(56);
      rule.rightMargin = dp(12);
      addView(separator, rule);
      title.setMaxLines(2);
      subtitle.setMaxLines(2);
    }
    void bind(Item item) {
      bind(item, true, true);
    }
    void bind(Item item, boolean first, boolean last) {
      title.setText(item.title);
      subtitle.setText(item.subtitle);
      boolean secondary = item.subtitle != null && !item.subtitle.isEmpty();
      subtitle.setVisibility(secondary ? VISIBLE : GONE);
      labelGap.setVisibility(secondary ? VISIBLE : GONE);
      int color = item.tint != 0 ? item.tint : item.icon == null ? BLUE : tint(item.icon);
      leading.setBackground(shape(tintSurface(color), 10));
      if (item.icon != null) {
        glyph.setImageDrawable(icon(item.icon, color));
        glyph.setVisibility(VISIBLE);
        initials.setVisibility(GONE);
      } else {
        initials.setText(avatar(item.title, LEADING).getText());
        initials.setTextColor(color);
        glyph.setVisibility(GONE);
        initials.setVisibility(VISIBLE);
      }
      float r = dp(RADIUS);
      GradientDrawable fill = shape(SURFACE, 0);
      fill.setCornerRadii(new float[] {first ? r : 0, first ? r : 0, first ? r : 0, first ? r : 0,
          last ? r : 0, last ? r : 0, last ? r : 0, last ? r : 0});
      body.setBackground(new RippleDrawable(
          ColorStateList.valueOf(0x20285f5a), fill, fill.getConstantState().newDrawable()));
      separator.setVisibility(last ? GONE : VISIBLE);
      badge.setVisibility(item.badge == null ? GONE : VISIBLE);
      badge.setText(item.badge);
      badge.setTextColor(item.badgeColor);
      badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
          "Есть".equals(item.badge) ? icon("check", GREEN) : null, null, null, null);
      badge.setCompoundDrawablePadding(dp(4));
      arrow.setImageDrawable(icon(item.icon == null ? "more" : "chevron", MUTED));
      arrow.setVisibility(item.badge == null && item.action != null ? VISIBLE : GONE);
      body.setOnClickListener(v -> {
        if (item.action != null)
          item.action.run();
      });
      body.setOnLongClickListener(item.longAction == null ? null : v -> {
        item.longAction.run();
        return true;
      });
      body.setLongClickable(item.longAction != null);
      body.setClickable(item.action != null);
      body.setFocusable(item.action != null);
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
        case "trash":
          line(c, 4, 6, 20, 6);
          line(c, 9, 6, 9, 3, 15, 3, 15, 6);
          line(c, 6, 6, 7, 21, 17, 21, 18, 6);
          line(c, 10, 10, 10, 17);
          line(c, 14, 10, 14, 17);
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
