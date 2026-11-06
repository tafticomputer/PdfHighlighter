package com.example.pdfhighlighter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.util.LruCache;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * نمایشگر PDF: همه‌ی صفحه‌ها زیر هم، با
 *  - جابه‌جایی و پرتاب با یک انگشت (حالت «دست»)
 *  - زوم و جابه‌جایی با دو انگشت (در همه‌ی حالت‌ها) و دو بار لمس سریع برای زوم
 *  - هایلایت و قلم با کشیدن یک انگشت، و پاک‌کن
 * صفحه‌ها با PdfRenderer (جزو خود اندروید) رسم می‌شوند. هنگام زوم، فقط بخش دیده‌شده با کیفیت بالا رسم می‌شود.
 */
public class PdfView extends View {

    public static final int TOOL_HAND = 0;
    public static final int TOOL_HIGHLIGHT = 1;
    public static final int TOOL_PEN = 2;
    public static final int TOOL_ERASE = 3;

    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 5f;
    private static final int HL_ALPHA = 0x78;
    private static final int MAX_TEX = 2048;

    public interface Listener {
        void onPageChanged(int page, int count);
    }

    private static class Detail {
        final Bitmap bmp;
        final float zoom;
        final int x0, y0;

        Detail(Bitmap b, float z, int x, int y) {
            bmp = b;
            zoom = z;
            x0 = x;
            y0 = y;
        }
    }

    // ---- سند
    private final Object lock = new Object();
    private PdfRenderer renderer;
    private float[] pw = new float[0];
    private float[] ph = new float[0];
    private int count;
    private HighlightStore store;
    private volatile int docGen;

    // ---- چیدمان (در زوم ۱)
    private float[] top1 = new float[1];
    private float[] h1 = new float[0];
    private float total1;
    private int viewW, viewH, layoutW, baseW;
    private int pendingPage = -1;

    // ---- وضعیت نمایش
    private float zoom = 1f;
    private float offX, offY;
    private int curPage = -1;
    private Listener listener;
    private volatile int visFirst, visLast;

    // ---- ابزار
    private int tool = TOOL_HAND;
    private int brushColor = 0xFFFFEB3B;
    private float brushWidth = 0.034f;
    private boolean straighten = true;

    // ---- رندر
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> baseCache;
    private final Set<String> pending = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final SparseArray<Detail> details = new SparseArray<>();
    private final ConcurrentHashMap<Integer, Integer> detailLatest = new ConcurrentHashMap<>();
    private int detailSeq;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint whitePaint = new Paint();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF rect2 = new RectF();

    // ---- لمس
    private final OverScroller scroller;
    private final GestureDetector gestureDetector;
    private boolean multi;
    private int pid1 = -1, pid2 = -1;
    private float lastSpan, lastMx, lastMy;

    // ---- خط در حال کشیده‌شدن
    private boolean drawing;
    private int strokePage;
    private float[] curPts = new float[256];
    private int curN;
    private int curArgb;
    private float curWidth;
    private int curTool;

    private final Runnable detailRunnable = new Runnable() {
        @Override
        public void run() {
            updateDetails();
        }
    };

    public PdfView(Context context) {
        super(context);
        scroller = new OverScroller(context);
        whitePaint.setColor(Color.WHITE);
        whitePaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        int mem = (int) (Runtime.getRuntime().maxMemory() / 5);
        baseCache = new LruCache<String, Bitmap>(Math.max(mem, 8 * 1024 * 1024)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                offX += dx;
                offY += dy;
                clampOffsets();
                invalidate();
                scheduleDetail();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling((int) offX, (int) offY, (int) -vx, (int) -vy,
                        0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                setZoomAt(zoom > 1.05f ? 1f : 2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ API عمومی

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean hasDocument() {
        return count > 0;
    }

    public int getPageCount() {
        return count;
    }

    public int getCurrentPage() {
        return Math.max(0, curPage);
    }

    public void setTool(int t) {
        tool = t;
        cancelStroke();
    }

    public void setBrush(int opaqueColor, float widthFraction) {
        brushColor = opaqueColor;
        brushWidth = widthFraction;
    }

    public void setStraighten(boolean s) {
        straighten = s;
    }

    public void refreshHighlights() {
        invalidate();
    }

    public void setDocument(PdfRenderer r, float[] w, float[] h, HighlightStore s, int startPage) {
        closeDocument();
        docGen++;
        synchronized (lock) {
            renderer = r;
        }
        pw = w;
        ph = h;
        count = w.length;
        store = s;
        zoom = 1f;
        offX = 0;
        offY = 0;
        curPage = -1;
        total1 = 0;
        layoutW = 0;
        relayout(false);
        goToPage(startPage);
        invalidate();
    }

    public void closeDocument() {
        docGen++;
        removeCallbacks(detailRunnable);
        scroller.forceFinished(true);
        synchronized (lock) {
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Throwable ignored) {
                }
                renderer = null;
            }
        }
        baseCache.evictAll();
        details.clear();
        pending.clear();
        detailLatest.clear();
        count = 0;
        pw = new float[0];
        ph = new float[0];
        h1 = new float[0];
        top1 = new float[1];
        total1 = 0;
        store = null;
        drawing = false;
        curN = 0;
        curPage = -1;
        pendingPage = -1;
        zoom = 1f;
        offX = 0;
        offY = 0;
        invalidate();
    }

    public void release() {
        closeDocument();
        exec.shutdownNow();
    }

    public void goToPage(int p) {
        if (count == 0) return;
        p = Math.max(0, Math.min(count - 1, p));
        if (viewW <= 0) {
            pendingPage = p;
            return;
        }
        pendingPage = -1;
        offY = top1[p] * zoom;
        clampOffsets();
        invalidate();
        scheduleDetail();
    }

    public void resetZoom() {
        setZoomAt(1f, viewW / 2f, viewH / 2f);
    }

    public HighlightStore.H undo() {
        if (store == null) return null;
        HighlightStore.H h = store.removeLast();
        if (h != null) {
            invalidate();
            if (h.page < visFirst || h.page > visLast) goToPage(h.page);
        }
        return h;
    }

    // ------------------------------------------------------------------ چیدمان

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW = w;
        viewH = h;
        relayout(true);
        if (pendingPage >= 0) goToPage(pendingPage);
    }

    private void relayout(boolean keepPos) {
        if (viewW <= 0) return;
        baseW = Math.min(Math.round(viewW * 1.25f), 1500);
        if (count == 0) {
            layoutW = viewW;
            return;
        }
        float frac = 0f;
        if (keepPos && total1 > 0) frac = offY / (total1 * zoom);
        float xScale = (layoutW > 0) ? viewW / (float) layoutW : 1f;

        top1 = new float[count + 1];
        h1 = new float[count];
        float gap = dp(6);
        float y = 0;
        for (int i = 0; i < count; i++) {
            h1[i] = viewW * ph[i] / pw[i];
            top1[i] = y;
            y += h1[i] + gap;
        }
        top1[count] = y;
        total1 = Math.max(1f, y - gap);
        layoutW = viewW;
        if (keepPos) {
            offY = frac * total1 * zoom;
            offX = offX * xScale;
            baseCache.evictAll();
            pending.clear();
            details.clear();
            detailLatest.clear();
        }
        clampOffsets();
        scheduleDetail();
    }

    private float maxX() {
        return Math.max(0f, viewW * zoom - viewW);
    }

    private float maxY() {
        return Math.max(0f, total1 * zoom - viewH);
    }

    private void clampOffsets() {
        offX = Math.max(0f, Math.min(maxX(), offX));
        offY = Math.max(0f, Math.min(maxY(), offY));
    }

    private int pageAt(float y1) {
        int lo = 0;
        int hi = count - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (top1[mid] <= y1) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    private void setZoomAt(float nz, float fx, float fy) {
        if (count == 0) return;
        nz = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, nz));
        float cx = (offX + fx) / zoom;
        float cy = (offY + fy) / zoom;
        zoom = nz;
        offX = cx * zoom - fx;
        offY = cy * zoom - fy;
        clampOffsets();
        invalidate();
        scheduleDetail();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.getCurrX();
            offY = scroller.getCurrY();
            clampOffsets();
            scheduleDetail();
            postInvalidateOnAnimation();
        }
    }

    // ------------------------------------------------------------------ رسم

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF757575);
        if (count == 0 || viewW == 0) return;
        float z = zoom;
        int first = pageAt(offY / z);
        int last = pageAt((offY + viewH) / z);
        visFirst = first;
        visLast = last;
        float pwid = viewW * z;
        float left = -offX;

        for (int i = first; i <= last; i++) {
            float t = top1[i] * z - offY;
            float h = h1[i] * z;
            rect.set(left, t, left + pwid, t + h);
            c.drawRect(rect, whitePaint);

            Bitmap b = baseCache.get(baseKey(i));
            if (b != null) c.drawBitmap(b, null, rect, bmpPaint);
            else requestBase(i);

            Detail d = details.get(i);
            if (d != null) {
                float r = z / d.zoom;
                rect2.set(left + d.x0 * r, t + d.y0 * r,
                        left + (d.x0 + d.bmp.getWidth()) * r, t + (d.y0 + d.bmp.getHeight()) * r);
                c.drawBitmap(d.bmp, null, rect2, bmpPaint);
            }

            drawStrokes(c, i, left, t, pwid, h);
            if (drawing && strokePage == i && curN >= 2) {
                strokePaint.setColor(curArgb);
                strokePaint.setStrokeWidth(Math.max(1f, curWidth * pwid));
                buildPath(curPts, curN, left, t, pwid, h);
                c.drawPath(path, strokePaint);
            }
        }
        requestBase(first - 1);
        requestBase(last + 1);
        notifyPage();
    }

    private void drawStrokes(Canvas c, int page, float left, float t, float w, float h) {
        if (store == null) return;
        List<HighlightStore.H> list = store.forPage(page);
        for (int k = 0; k < list.size(); k++) {
            HighlightStore.H hl = list.get(k);
            if (hl.pts == null) {
                fillPaint.setColor((hl.color & 0x00FFFFFF) | 0x70000000);
                c.drawRect(left + hl.l * w, t + hl.t * h, left + hl.r * w, t + hl.b * h, fillPaint);
            } else {
                strokePaint.setColor(hl.color);
                strokePaint.setStrokeWidth(Math.max(1f, hl.width * w));
                buildPath(hl.pts, hl.pts.length / 2, left, t, w, h);
                c.drawPath(path, strokePaint);
            }
        }
    }

    private void buildPath(float[] pts, int n, float left, float t, float w, float h) {
        path.reset();
        if (n < 2) return;
        path.moveTo(left + pts[0] * w, t + pts[1] * h);
        if (n == 2) {
            path.lineTo(left + pts[2] * w, t + pts[3] * h);
            return;
        }
        for (int k = 1; k < n - 1; k++) {
            float cx = left + pts[2 * k] * w;
            float cy = t + pts[2 * k + 1] * h;
            float nx = left + pts[2 * k + 2] * w;
            float ny = t + pts[2 * k + 3] * h;
            path.quadTo(cx, cy, (cx + nx) / 2f, (cy + ny) / 2f);
        }
        path.lineTo(left + pts[2 * (n - 1)] * w, t + pts[2 * (n - 1) + 1] * h);
    }

    private void notifyPage() {
        if (count == 0) return;
        final int p = pageAt((offY + viewH / 2f) / zoom);
        if (p != curPage) {
            curPage = p;
            final int cnt = count;
            post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onPageChanged(p, cnt);
                }
            });
        }
    }

    // ------------------------------------------------------------------ رندر صفحه‌ها

    private String baseKey(int i) {
        return docGen + "_" + i;
    }

    private void requestBase(final int i) {
        if (i < 0 || i >= count || viewW <= 0) return;
        final String key = baseKey(i);
        if (baseCache.get(key) != null) return;
        if (!pending.add(key)) return;
        final int gen = docGen;
        final float ptsW = pw[i];
        final float ptsH = ph[i];
        final int bw0 = baseW;
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (gen != docGen || i < visFirst - 2 || i > visLast + 2) return;
                    Bitmap b = renderBase(i, ptsW, ptsH, bw0, gen);
                    if (b != null && gen == docGen) {
                        baseCache.put(key, b);
                        postInvalidate();
                    }
                } finally {
                    pending.remove(key);
                }
            }
        });
    }

    private Bitmap renderBase(int i, float ptsW, float ptsH, int bw, int gen) {
        int bh = Math.max(1, Math.round(bw * ptsH / ptsW));
        if (bh > MAX_TEX) {
            bw = Math.max(1, Math.round(bw * (float) MAX_TEX / bh));
            bh = MAX_TEX;
        }
        synchronized (lock) {
            if (renderer == null || gen != docGen) return null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(i);
                Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } catch (Throwable t) {
                return null;
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void scheduleDetail() {
        removeCallbacks(detailRunnable);
        postDelayed(detailRunnable, 130);
    }

    /** بعد از زوم، بخش دیده‌شده‌ی هر صفحه را با کیفیت کامل رسم می‌کند. */
    private void updateDetails() {
        if (count == 0 || viewW == 0) return;
        float z = zoom;
        int first = pageAt(offY / z);
        int last = pageAt((offY + viewH) / z);
        for (int k = details.size() - 1; k >= 0; k--) {
            int pg = details.keyAt(k);
            if (pg < first - 1 || pg > last + 1) details.removeAt(k);
        }
        if (z < 1.15f) {
            details.clear();
            detailLatest.clear();
            return;
        }
        float pwid = viewW * z;
        float mx = viewW * 0.1f;
        float my = viewH * 0.1f;
        for (int i = first; i <= last; i++) {
            float t = top1[i] * z;
            float h = h1[i] * z;
            float vx0 = Math.max(0f, offX);
            float vx1 = Math.min(pwid, offX + viewW);
            float vy0 = Math.max(0f, offY - t);
            float vy1 = Math.min(h, offY + viewH - t);
            if (vx1 <= vx0 || vy1 <= vy0) continue;
            Detail d = details.get(i);
            if (d != null && Math.abs(d.zoom - z) < 0.005f * z
                    && d.x0 <= vx0 + 1 && d.y0 <= vy0 + 1
                    && d.x0 + d.bmp.getWidth() >= vx1 - 1 && d.y0 + d.bmp.getHeight() >= vy1 - 1) {
                continue;
            }
            int x0 = (int) Math.max(0f, vx0 - mx);
            int y0 = (int) Math.max(0f, vy0 - my);
            int x1 = (int) Math.min(pwid, Math.ceil(vx1 + mx));
            int y1 = (int) Math.min(h, Math.ceil(vy1 + my));
            int w = Math.min(MAX_TEX, x1 - x0);
            int hh = Math.min(MAX_TEX, y1 - y0);
            if (w < 8 || hh < 8) continue;
            requestDetail(i, z, x0, y0, w, hh);
        }
    }

    private void requestDetail(final int i, final float z, final int x0, final int y0, final int w, final int h) {
        final int id = ++detailSeq;
        detailLatest.put(i, id);
        final int gen = docGen;
        final float scale = (viewW * z) / pw[i];
        exec.execute(new Runnable() {
            @Override
            public void run() {
                Integer cur = detailLatest.get(i);
                if (cur == null || cur.intValue() != id || gen != docGen) return;
                final Bitmap b = renderDetail(i, scale, x0, y0, w, h, gen);
                if (b == null) return;
                post(new Runnable() {
                    @Override
                    public void run() {
                        Integer c2 = detailLatest.get(i);
                        if (gen == docGen && c2 != null && c2.intValue() == id && zoom >= 1.15f) {
                            details.put(i, new Detail(b, z, x0, y0));
                            invalidate();
                        }
                    }
                });
            }
        });
    }

    private Bitmap renderDetail(int i, float scale, int x0, int y0, int w, int h, int gen) {
        synchronized (lock) {
            if (renderer == null || gen != docGen) return null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(i);
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.eraseColor(Color.WHITE);
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postTranslate(-x0, -y0);
                page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return b;
            } catch (Throwable t) {
                return null;
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ لمس

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (count == 0) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multi = false;
                scroller.forceFinished(true);
                if (tool == TOOL_HAND) gestureDetector.onTouchEvent(e);
                else if (tool == TOOL_ERASE) eraseAt(e.getX(), e.getY());
                else startStroke(e.getX(), e.getY());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (!multi) {
                    multi = true;
                    cancelStroke();
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    gestureDetector.onTouchEvent(cancel);
                    cancel.recycle();
                }
                anchorPinch(e, -1);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    doPinch(e);
                } else if (tool == TOOL_HAND) {
                    gestureDetector.onTouchEvent(e);
                } else if (tool == TOOL_ERASE) {
                    eraseAt(e.getX(), e.getY());
                } else if (drawing) {
                    for (int k = 0; k < e.getHistorySize(); k++) {
                        addPoint(e.getHistoricalX(k), e.getHistoricalY(k));
                    }
                    addPoint(e.getX(), e.getY());
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (multi) anchorPinch(e, e.getActionIndex());
                return true;

            case MotionEvent.ACTION_UP:
                if (multi) {
                    multi = false;
                    scheduleDetail();
                } else if (tool == TOOL_HAND) {
                    gestureDetector.onTouchEvent(e);
                    scheduleDetail();
                } else if (tool != TOOL_ERASE) {
                    endStroke(e.getX(), e.getY());
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                multi = false;
                cancelStroke();
                if (tool == TOOL_HAND) gestureDetector.onTouchEvent(e);
                return true;

            default:
                return true;
        }
    }

    private void anchorPinch(MotionEvent e, int skipIndex) {
        pid1 = -1;
        pid2 = -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skipIndex) continue;
            if (pid1 < 0) {
                pid1 = e.getPointerId(i);
            } else {
                pid2 = e.getPointerId(i);
                break;
            }
        }
        if (pid1 >= 0 && pid2 >= 0) {
            int i1 = e.findPointerIndex(pid1);
            int i2 = e.findPointerIndex(pid2);
            if (i1 >= 0 && i2 >= 0) {
                float x1 = e.getX(i1), y1 = e.getY(i1), x2 = e.getX(i2), y2 = e.getY(i2);
                lastMx = (x1 + x2) / 2f;
                lastMy = (y1 + y2) / 2f;
                lastSpan = (float) Math.hypot(x2 - x1, y2 - y1);
                return;
            }
        }
        pid1 = -1;
        pid2 = -1;
    }

    /** زوم و جابه‌جایی هم‌زمان با دو انگشت: نقطه‌ی زیر انگشت‌ها ثابت می‌ماند. */
    private void doPinch(MotionEvent e) {
        if (pid1 < 0 || pid2 < 0) return;
        int i1 = e.findPointerIndex(pid1);
        int i2 = e.findPointerIndex(pid2);
        if (i1 < 0 || i2 < 0) return;
        float x1 = e.getX(i1), y1 = e.getY(i1), x2 = e.getX(i2), y2 = e.getY(i2);
        float mx = (x1 + x2) / 2f;
        float my = (y1 + y2) / 2f;
        float span = (float) Math.hypot(x2 - x1, y2 - y1);
        if (lastSpan > dp(8)) {
            float nz = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * (span / lastSpan)));
            float cx = (offX + lastMx) / zoom;
            float cy = (offY + lastMy) / zoom;
            zoom = nz;
            offX = cx * zoom - mx;
            offY = cy * zoom - my;
            clampOffsets();
        }
        lastSpan = span;
        lastMx = mx;
        lastMy = my;
        invalidate();
    }

    // ------------------------------------------------------------------ هایلایت و قلم

    private void startStroke(float x, float y) {
        if (store == null) return;
        strokePage = pageAt((offY + y) / zoom);
        curTool = tool;
        curArgb = (tool == TOOL_HIGHLIGHT)
                ? ((brushColor & 0x00FFFFFF) | (HL_ALPHA << 24))
                : (brushColor | 0xFF000000);
        curWidth = brushWidth;
        curN = 0;
        drawing = true;
        addPoint(x, y);
    }

    private void addPoint(float x, float y) {
        if (!drawing) return;
        float W = viewW * zoom;
        float H = h1[strokePage] * zoom;
        float lx = offX + x;
        float ly = offY + y - top1[strokePage] * zoom;
        float nx = Math.max(0f, Math.min(1f, lx / W));
        float ny = Math.max(0f, Math.min(1f, ly / H));
        if (curN > 0) {
            float dx = (nx - curPts[2 * (curN - 1)]) * W;
            float dy = (ny - curPts[2 * (curN - 1) + 1]) * H;
            float md = dp(1.5f);
            if (dx * dx + dy * dy < md * md) return;
        }
        if (2 * curN + 2 > curPts.length) curPts = Arrays.copyOf(curPts, curPts.length * 2);
        curPts[2 * curN] = nx;
        curPts[2 * curN + 1] = ny;
        curN++;
    }

    private void endStroke(float x, float y) {
        if (!drawing) return;
        addPoint(x, y);
        drawing = false;
        if (curN >= 2 && store != null) {
            float[] pts = Arrays.copyOf(curPts, curN * 2);
            if (straighten && curTool == TOOL_HIGHLIGHT) pts = straightened(pts, curN);
            store.add(strokePage, curArgb, curWidth, pts);
        }
        curN = 0;
        invalidate();
    }

    /** اگر خط تقریباً افقی کشیده شده، آن را به یک خط صاف و تمیز تبدیل می‌کند (مثل انتخاب متن). */
    private float[] straightened(float[] pts, int n) {
        float W = viewW * zoom;
        float H = h1[strokePage] * zoom;
        float minY = 1f, maxY = 0f, sumY = 0f;
        for (int k = 0; k < n; k++) {
            float yy = pts[2 * k + 1];
            if (yy < minY) minY = yy;
            if (yy > maxY) maxY = yy;
            sumY += yy;
        }
        float wPx = curWidth * W;
        float dev = (maxY - minY) * H;
        float len = Math.abs(pts[2 * (n - 1)] - pts[0]) * W;
        if (dev <= wPx * 0.9f && len > wPx * 0.5f) {
            float y = sumY / n;
            return new float[]{pts[0], y, pts[2 * (n - 1)], y};
        }
        return pts;
    }

    private void cancelStroke() {
        if (drawing) {
            drawing = false;
            curN = 0;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ پاک‌کن

    private void eraseAt(float x, float y) {
        if (store == null) return;
        int p = pageAt((offY + y) / zoom);
        float W = viewW * zoom;
        float H = h1[p] * zoom;
        float lx = offX + x;
        float ly = offY + y - top1[p] * zoom;
        if (ly < 0 || ly > H || lx < 0 || lx > W) return;
        float nx = lx / W;
        float ny = ly / H;
        float rad = dp(10);
        List<HighlightStore.H> copy = new ArrayList<>(store.forPage(p));
        boolean removed = false;
        for (HighlightStore.H hl : copy) {
            if (hit(hl, nx, ny, W, H, rad)) {
                store.remove(hl);
                removed = true;
            }
        }
        if (removed) invalidate();
    }

    private boolean hit(HighlightStore.H hl, float nx, float ny, float W, float H, float rad) {
        if (hl.pts == null) {
            return nx >= hl.l && nx <= hl.r && ny >= hl.t && ny <= hl.b;
        }
        float px = nx * W;
        float py = ny * H;
        float limit = hl.width * W / 2f + rad;
        float limit2 = limit * limit;
        int n = hl.pts.length / 2;
        for (int k = 0; k < n - 1; k++) {
            float ax = hl.pts[2 * k] * W, ay = hl.pts[2 * k + 1] * H;
            float bx = hl.pts[2 * k + 2] * W, by = hl.pts[2 * k + 3] * H;
            float dx = bx - ax, dy = by - ay;
            float len2 = dx * dx + dy * dy;
            float tt = len2 == 0f ? 0f : ((px - ax) * dx + (py - ay) * dy) / len2;
            tt = Math.max(0f, Math.min(1f, tt));
            float cx = ax + tt * dx - px;
            float cy = ay + tt * dy - py;
            if (cx * cx + cy * cy <= limit2) return true;
        }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(detailRunnable);
    }
}
