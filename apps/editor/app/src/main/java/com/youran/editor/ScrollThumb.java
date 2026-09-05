package com.youran.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * UI 美化第三轮·B：右侧可拖拽的“椭圆细条”滚动条(哑控件)。
 *
 * 它自己不碰任何滚动 API：宿主(ListView / ScrollView 的滚动监听)只要把自己的
 * 「当前位置比例」报到 setProgress(f)，它就把细条画到对应竖直位置。用户按住并上
 * 下拖动时，它把「松手/实时位置比例」通过调用 jump(f) 交还宿主去跳转。这样不同
 * 容器都能接，且不依赖安卓里被保护的那几个 compute… 方法。
 *
 * 外观：平时细而半透明(约 5dp)，按住立刻变粗变大(约 12dp 近实)，四角故意取圆润
 * 做成长椭圆的手感；长列表时细条很短、短列表时细条较长，直观反映“还有多少能翻”。
 */
public final class ScrollThumb extends View {

    /** 宿主收到 f(0..1) 后执行真正跳转回调。 */
    public interface OnScrub { void jump(float f); }

    private OnScrub scrub;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    // 模型：可视比例 / 高度厚薄(0..1)
    private float frac = 0f;      // 当前顶部在整条里占的比例 0..1
    private float visRatio = 0.5f;// 可视段占总长比例(0..1，越小=越长越细)

    // 交互态
    private boolean pressed = false;
    private float dragY = 0f;     // 拖动时细条顶部的 y

    private static final int COLOR_IDLE = 0x55B6C4D6;
    private static final int COLOR_PRES = 0xD09BC3FF;

    public ScrollThumb(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        pill.setStyle(Paint.Style.FILL);
        pill.setColor(COLOR_IDLE);
    }

    /** 绑定宿主跳转回调。 */
    public void setScrub(OnScrub s) { scrub = s; }

    /** 报到当前可视位置比例(0=顶,1=底)。 */
    public void setProgress(float f) {
        frac = clamp01(f);
        invalidate();
    }

    /** 报到可视段占总内容的比例，用于调控细条长度(0..1)。 */
    public void setVisibleRatio(float r) { visRatio = clamp01(r); invalidate(); }

    private static float clamp01(float f) { return f < 0 ? 0 : (f > 1 ? 1 : f); }

    /** 细条(thumb)高度：随可见占比调整，短则粗、长则细，至少便于抓。 */
    private float slideH() {
        if (getHeight() <= 0) return dp(20);
        float h = getHeight();
        // 用 visRatio 决定相对长度：可见占 90%(短列表) 较粗；占1/20(长列表)则很短
        float rel = Math.max(0.06f, visRatio);      // 底线 6%
        return clamp01(rel) * h;
    }

    /* ---------------- 绘制 ---------------- */

    @Override
    protected void onDraw(@NonNull Canvas c) {
        super.onDraw(c);
        if (getHeight() <= 0) return;
        float sh = slideH();
        float travel = Math.max(1f, getHeight() - sh);
        // 顶部 y：平时按 frac 投影，拖拽时按手指 y
        float top = pressed ? dragY : (travel * frac);

        float width = pressed ? dp(12) : dp(5);
        float x = getWidth() - width - dp(1);
        // 收在 0..travel 内
        top = (top > travel ? travel : top);
        if (top < 0) top = 0;

        float radius = Math.min(width, sh) / 2f;
        pill.setColor(pressed ? COLOR_PRES : COLOR_IDLE);
        RectF r = new RectF(x, top, x + width, top + sh);
        c.drawRoundRect(r, Math.min(dp(10), radius), Math.min(dp(10), radius), pill);
    }

    /* ---------------- 触摸 ---------------- */

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                float half = slideH() / 2f;
                dragY = e.getY() - half;
                emit();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed) {
                    dragY = e.getY() - slideH() / 2f;
                    emit();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed) { pressed = false; invalidate(); }
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** 把当前 dragY 折算成 0..1 并让宿主跳转。 */
    private void emit() {
        if (scrub == null) return;
        float travel = Math.max(1f, getHeight() - slideH());
        float f = clamp01(dragY / travel);
        scrub.jump(f);
    }

    private float dp(float v) { return v * density; }
}
