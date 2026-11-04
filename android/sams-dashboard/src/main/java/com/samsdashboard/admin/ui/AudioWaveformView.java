package com.samsdashboard.admin.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * AudioWaveformView renders genuine measured PCM audio waveforms,
 * amplitude bars, scrub playhead, and high-volume highlight flags.
 */
public class AudioWaveformView extends View {

    public interface OnScrubListener {
        void onScrub(float progressRatio);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<Float> amplitudes = new ArrayList<>();
    private float progress = 0.0f; // 0.0 to 1.0
    private boolean isHighlight = false;
    private double peakDb = -96.0;
    private OnScrubListener scrubListener;

    public AudioWaveformView(Context context) {
        super(context);
        init();
    }

    public AudioWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setColor(Color.parseColor("#00F0FF"));
        highlightPaint.setColor(Color.parseColor("#FF0055"));
        playheadPaint.setColor(Color.parseColor("#FFFFFF"));
        playheadPaint.setStrokeWidth(4);

        textPaint.setColor(Color.parseColor("#94A3B8"));
        textPaint.setTextSize(24);
    }

    public void setAmplitudes(List<Float> amps, boolean highlight, double peakDb) {
        this.amplitudes = (amps != null) ? amps : new ArrayList<>();
        this.isHighlight = highlight;
        this.peakDb = peakDb;
        invalidate();
    }

    public void setProgress(float prog) {
        this.progress = Math.max(0.0f, Math.min(1.0f, prog));
        invalidate();
    }

    public void setOnScrubListener(OnScrubListener listener) {
        this.scrubListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        // Background
        canvas.drawColor(Color.parseColor("#0F172A"));

        if (amplitudes.isEmpty()) {
            canvas.drawText("No waveform data loaded", 40, height / 2 + 8, textPaint);
            return;
        }

        int count = amplitudes.size();
        float barWidth = (float) width / count;
        float centerY = height / 2.0f;

        for (int i = 0; i < count; i++) {
            float amp = amplitudes.get(i);
            float barHeight = Math.max(6, amp * (height * 0.85f));
            float left = i * barWidth + 1;
            float right = (i + 1) * barWidth - 1;
            float top = centerY - (barHeight / 2.0f);
            float bottom = centerY + (barHeight / 2.0f);

            // Highlight bar color if peak loudness > -10 dB
            if (isHighlight && amp > 0.65f) {
                canvas.drawRoundRect(new RectF(left, top, right, bottom), 4, 4, highlightPaint);
            } else {
                canvas.drawRoundRect(new RectF(left, top, right, bottom), 4, 4, barPaint);
            }
        }

        // Draw scrub playhead
        float playheadX = progress * width;
        canvas.drawLine(playheadX, 0, playheadX, height, playheadPaint);

        // Draw Peak dB badge
        String dbText = String.format(java.util.Locale.US, "Peak: %.1f dBFS", peakDb);
        canvas.drawText(dbText, width - 180, 36, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            float touchX = event.getX();
            float prog = Math.max(0.0f, Math.min(1.0f, touchX / getWidth()));
            setProgress(prog);
            if (scrubListener != null) {
                scrubListener.onScrub(prog);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }
}
