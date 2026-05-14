package com.axiom.webradar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.location.Location;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.Nullable;

public final class RadarMapView extends View {
    private static final float MIN_USER_SCALE = 1.0f;
    private static final float MAX_USER_SCALE = 4.0f;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint gpsFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gpsStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accuracyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    private Bitmap baseBitmap;
    private Bitmap contourBitmap;
    private Bitmap radarBitmap;
    private RadarPreset preset;
    private Location deviceLocation;

    private float userScale = 1.0f;
    private float panX = 0.0f;
    private float panY = 0.0f;
    private float lastTouchX;
    private float lastTouchY;
    private boolean dragging;

    public RadarMapView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gpsFillPaint.setColor(Color.parseColor("#1B64A0"));
        gpsFillPaint.setStyle(Paint.Style.FILL);

        gpsStrokePaint.setColor(Color.WHITE);
        gpsStrokePaint.setStyle(Paint.Style.STROKE);
        gpsStrokePaint.setStrokeWidth(dpToPx(2.5f));

        accuracyPaint.setColor(Color.parseColor("#551B64A0"));
        accuracyPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        accuracyPaint.setStrokeWidth(dpToPx(1.5f));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor());
                return true;
            }
        });
    }

    public void setPreset(@Nullable RadarPreset preset, boolean resetTransform) {
        this.preset = preset;
        if (resetTransform) {
            resetTransform();
        }
        invalidate();
    }

    public void setBaseBitmap(@Nullable Bitmap bitmap) {
        baseBitmap = bitmap;
        invalidate();
    }

    public void setContourBitmap(@Nullable Bitmap bitmap) {
        contourBitmap = bitmap;
        invalidate();
    }

    public void setRadarBitmap(@Nullable Bitmap bitmap) {
        radarBitmap = bitmap;
        invalidate();
    }

    public void setDeviceLocation(@Nullable Location location) {
        deviceLocation = location;
        invalidate();
    }

    public void resetTransform() {
        userScale = 1.0f;
        panX = 0.0f;
        panY = 0.0f;
        invalidate();
    }

    public void zoomBy(float factor) {
        float nextScale = clamp(userScale * factor, MIN_USER_SCALE, MAX_USER_SCALE);
        if (Math.abs(nextScale - userScale) < 0.001f) {
            return;
        }
        userScale = nextScale;
        clampPan();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampPan();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.parseColor("#8BB9D3"));

        RectF drawRect = currentDrawRect();
        if (drawRect == null) {
            return;
        }

        if (baseBitmap != null) {
            canvas.drawBitmap(baseBitmap, null, drawRect, bitmapPaint);
        }
        if (radarBitmap != null) {
            canvas.drawBitmap(radarBitmap, null, drawRect, bitmapPaint);
        }
        if (contourBitmap != null) {
            canvas.drawBitmap(contourBitmap, null, drawRect, bitmapPaint);
        }
        drawGps(canvas, drawRect);
    }

    private void drawGps(Canvas canvas, RectF drawRect) {
        if (preset == null || deviceLocation == null) {
            return;
        }

        double mercatorX = RadarPreset.lonToMercatorX(deviceLocation.getLongitude());
        double mercatorY = RadarPreset.latToMercatorY(deviceLocation.getLatitude());

        float xNorm = (float) ((mercatorX - preset.minX) / (preset.maxX - preset.minX));
        float yNorm = (float) ((preset.maxY - mercatorY) / (preset.maxY - preset.minY));

        if (xNorm < -0.25f || xNorm > 1.25f || yNorm < -0.25f || yNorm > 1.25f) {
            return;
        }

        float viewX = drawRect.left + drawRect.width() * xNorm;
        float viewY = drawRect.top + drawRect.height() * yNorm;

        float accuracyRadius = 0.0f;
        if (deviceLocation.hasAccuracy()) {
            float metersPerPixel = (float) ((preset.maxX - preset.minX) / drawRect.width());
            accuracyRadius = Math.max(deviceLocation.getAccuracy() / metersPerPixel, dpToPx(6.0f));
        }

        if (accuracyRadius > 0.0f) {
            canvas.drawCircle(viewX, viewY, accuracyRadius, accuracyPaint);
        }
        canvas.drawCircle(viewX, viewY, dpToPx(5.0f), gpsFillPaint);
        canvas.drawCircle(viewX, viewY, dpToPx(5.0f), gpsStrokePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);

        if (event.getPointerCount() > 1) {
            dragging = false;
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging || scaleDetector.isInProgress()) {
                    return true;
                }
                panX += event.getX() - lastTouchX;
                panY += event.getY() - lastTouchY;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                clampPan();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Nullable
    private RectF currentDrawRect() {
        Bitmap reference = referenceBitmap();
        if (reference == null || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }

        float fitScale = Math.min(
                (float) getWidth() / (float) reference.getWidth(),
                (float) getHeight() / (float) reference.getHeight()
        );
        float scale = fitScale * userScale;
        float drawWidth = reference.getWidth() * scale;
        float drawHeight = reference.getHeight() * scale;
        float left = (getWidth() - drawWidth) * 0.5f + panX;
        float top = (getHeight() - drawHeight) * 0.5f + panY;
        return new RectF(left, top, left + drawWidth, top + drawHeight);
    }

    @Nullable
    private Bitmap referenceBitmap() {
        if (baseBitmap != null) {
            return baseBitmap;
        }
        if (radarBitmap != null) {
            return radarBitmap;
        }
        return contourBitmap;
    }

    private void clampPan() {
        Bitmap reference = referenceBitmap();
        if (reference == null || getWidth() <= 0 || getHeight() <= 0) {
            panX = 0.0f;
            panY = 0.0f;
            return;
        }

        float fitScale = Math.min(
                (float) getWidth() / (float) reference.getWidth(),
                (float) getHeight() / (float) reference.getHeight()
        );
        float drawWidth = reference.getWidth() * fitScale * userScale;
        float drawHeight = reference.getHeight() * fitScale * userScale;

        float maxPanX = Math.max(0.0f, (drawWidth - getWidth()) * 0.5f);
        float maxPanY = Math.max(0.0f, (drawHeight - getHeight()) * 0.5f);

        panX = clamp(panX, -maxPanX, maxPanX);
        panY = clamp(panY, -maxPanY, maxPanY);
    }

    private float clamp(float value, float minValue, float maxValue) {
        return Math.max(minValue, Math.min(maxValue, value));
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
