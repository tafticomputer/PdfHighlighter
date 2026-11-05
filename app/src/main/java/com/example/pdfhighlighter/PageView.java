package com.example.pdfhighlighter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** نمایش یک صفحه‌ی PDF همراه با هایلایت‌ها؛ در حالت هایلایت، کشیدن انگشت یک مستطیل رنگی می‌سازد. */
public class PageView extends View {

    public static final int MODE_VIEW = 0;
    public static final int MODE_HIGHLIGHT = 1;
    public static final int MODE_ERASE = 2;

    private static final int HL_ALPHA = 0x70;

    public interface Listener {
        void onAdd(PageView v, int page, float l, float t, float r, float b);

        void onRemove(PageView v, int page, HighlightStore.H h);
    }

    private Bitmap bitmap;
    private volatile String renderKey;
    private int page;
    private int mode = MODE_VIEW;
    private int color = 0xFFFFEB3B;
    private List<HighlightStore.H> highlights = new ArrayList<>();
    private Listener listener;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint hlPaint = new Paint();
    private final Rect dst = new Rect();

    private boolean dragging;
    private float sx, sy, ex, ey;

    public PageView(Context context) {
        super(context);
        hlPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void bindPage(int page, List<HighlightStore.H> list, int mode, int color) {
        this.page = page;
        this.highlights = list;
        this.mode = mode;
        this.color = color;
        this.dragging = false;
        invalidate();
    }

    public void setHighlights(List<HighlightStore.H> list) {
        this.highlights = list;
        invalidate();
    }

    public void setMode(int mode) {
        this.mode = mode;
        this.dragging = false;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public void setBitmap(Bitmap b) {
        this.bitmap = b;
        invalidate();
    }

    public void setRenderKey(String key) {
        this.renderKey = key;
    }

    public String getRenderKey() {
        return renderKey;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawColor(Color.WHITE);
        if (bitmap != null) {
            dst.set(0, 0, w, h);
            c.drawBitmap(bitmap, null, dst, bmpPaint);
        }
        for (HighlightStore.H hl : highlights) {
            hlPaint.setColor(hl.color);
            hlPaint.setAlpha(HL_ALPHA);
            c.drawRect(hl.l * w, hl.t * h, hl.r * w, hl.b * h, hlPaint);
        }
        if (dragging) {
            hlPaint.setColor(color);
            hlPaint.setAlpha(HL_ALPHA);
            c.drawRect(Math.min(sx, ex), Math.min(sy, ey), Math.max(sx, ex), Math.max(sy, ey), hlPaint);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode == MODE_VIEW || getWidth() == 0 || getHeight() == 0) return false;
        float x = e.getX();
        float y = e.getY();
        int action = e.getActionMasked();

        if (mode == MODE_HIGHLIGHT) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    sx = x;
                    sy = y;
                    ex = x;
                    ey = y;
                    dragging = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        ex = x;
                        ey = y;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        ex = x;
                        ey = y;
                        float w = getWidth();
                        float h = getHeight();
                        float l = clamp01(Math.min(sx, ex) / w);
                        float r = clamp01(Math.max(sx, ex) / w);
                        float t = clamp01(Math.min(sy, ey) / h);
                        float b = clamp01(Math.max(sy, ey) / h);
                        if (r - l > 0.01f && b - t > 0.004f && listener != null) {
                            listener.onAdd(this, page, l, t, r, b);
                        }
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        // حالت پاک‌کن: لمس کوتاه روی یک هایلایت آن را حذف می‌کند (اسکرول عادی کار می‌کند)
        if (action == MotionEvent.ACTION_UP) {
            float nx = x / getWidth();
            float ny = y / getHeight();
            for (int i = highlights.size() - 1; i >= 0; i--) {
                HighlightStore.H hl = highlights.get(i);
                if (nx >= hl.l - 0.01f && nx <= hl.r + 0.01f && ny >= hl.t - 0.01f && ny <= hl.b + 0.01f) {
                    if (listener != null) listener.onRemove(this, page, hl);
                    break;
                }
            }
        }
        return true;
    }
}
