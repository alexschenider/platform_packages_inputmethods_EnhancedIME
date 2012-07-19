/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.keyboard.internal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.FloatMath;

import com.android.inputmethod.latin.Constants;
import com.android.inputmethod.latin.InputPointers;

public class GestureStroke {
    public static final int DEFAULT_CAPACITY = 128;

    private final int mPointerId;
    // TODO: Replace this {@link InputPointers} with a set of {@link ScalableIntArray}s.
    private final InputPointers mInputPointers = new InputPointers(DEFAULT_CAPACITY);
    private float mLength;
    private float mAngle;
    private int mIncrementalRecognitionSize;
    private int mLastIncrementalBatchSize;
    private long mLastPointTime;
    private int mLastPointX;
    private int mLastPointY;

    private int mMinGestureLength;
    private int mMinGestureLengthWhileInGesture;
    private int mMinGestureSampleLength;
    private final Rect mDrawingRect = new Rect();

    // TODO: Move some of these to resource.
    private static final float MIN_GESTURE_LENGTH_RATIO_TO_KEY_WIDTH = 1.0f;
    private static final float MIN_GESTURE_LENGTH_RATIO_TO_KEY_WIDTH_WHILE_IN_GESTURE = 0.5f;
    private static final int MIN_GESTURE_DURATION = 150; // msec
    private static final int MIN_GESTURE_DURATION_WHILE_IN_GESTURE = 75; // msec
    private static final float MIN_GESTURE_SAMPLING_RATIO_TO_KEY_HEIGHT = 1.0f / 6.0f;
    private static final float GESTURE_RECOG_SPEED_THRESHOLD = 0.4f; // dip/msec
    private static final float GESTURE_RECOG_CURVATURE_THRESHOLD = (float)(Math.PI / 4.0f);

    private static final float DOUBLE_PI = (float)(2 * Math.PI);

    // Fade based on number of gesture samples, see MIN_GESTURE_SAMPLING_RATIO_TO_KEY_HEIGHT
    private static final int DRAWING_GESTURE_FADE_START = 10;
    private static final int DRAWING_GESTURE_FADE_RATE = 6;

    public GestureStroke(int pointerId) {
        mPointerId = pointerId;
        reset();
    }

    public void setGestureSampleLength(final int keyWidth, final int keyHeight) {
        // TODO: Find an appropriate base metric for these length. Maybe diagonal length of the key?
        mMinGestureLength = (int)(keyWidth * MIN_GESTURE_LENGTH_RATIO_TO_KEY_WIDTH);
        mMinGestureLengthWhileInGesture = (int)(
                keyWidth * MIN_GESTURE_LENGTH_RATIO_TO_KEY_WIDTH_WHILE_IN_GESTURE);
        mMinGestureSampleLength = (int)(keyHeight * MIN_GESTURE_SAMPLING_RATIO_TO_KEY_HEIGHT);
    }

    public boolean isStartOfAGesture(final int downDuration, final boolean wasInGesture) {
        // The tolerance of the time duration and the stroke length to detect the start of a
        // gesture stroke should be eased when the previous input was a gesture input.
        if (wasInGesture) {
            return downDuration > MIN_GESTURE_DURATION_WHILE_IN_GESTURE
                    && mLength > mMinGestureLengthWhileInGesture;
        }
        return downDuration > MIN_GESTURE_DURATION && mLength > mMinGestureLength;
    }

    public void reset() {
        mLength = 0;
        mAngle = 0;
        mIncrementalRecognitionSize = 0;
        mLastIncrementalBatchSize = 0;
        mLastPointTime = 0;
        mInputPointers.reset();
        mDrawingRect.setEmpty();
    }

    private void updateLastPoint(final int x, final int y, final int time) {
        mLastPointTime = time;
        mLastPointX = x;
        mLastPointY = y;
    }

    public void addPoint(final int x, final int y, final int time, final boolean isHistorical) {
        final int size = mInputPointers.getPointerSize();
        if (size == 0) {
            mInputPointers.addPointer(x, y, mPointerId, time);
            if (!isHistorical) {
                updateLastPoint(x, y, time);
            }
            return;
        }
        final int[] xCoords = mInputPointers.getXCoordinates();
        final int[] yCoords = mInputPointers.getYCoordinates();
        final int lastX = xCoords[size - 1];
        final int lastY = yCoords[size - 1];
        final float dist = getDistance(lastX, lastY, x, y);
        if (dist > mMinGestureSampleLength) {
            mInputPointers.addPointer(x, y, mPointerId, time);
            if (mDrawingRect.isEmpty()) {
                mDrawingRect.set(x - 1, y - 1, x + 1, y + 1);
            } else {
                mDrawingRect.union(x, y);
            }
            mLength += dist;
            final float angle = getAngle(lastX, lastY, x, y);
            if (size > 1) {
                final float curvature = getAngleDiff(angle, mAngle);
                if (curvature > GESTURE_RECOG_CURVATURE_THRESHOLD) {
                    if (size > mIncrementalRecognitionSize) {
                        mIncrementalRecognitionSize = size;
                    }
                }
            }
            mAngle = angle;
        }

        if (!isHistorical) {
            final int duration = (int)(time - mLastPointTime);
            if (mLastPointTime != 0 && duration > 0) {
                final float speed = getDistance(mLastPointX, mLastPointY, x, y) / duration;
                if (speed < GESTURE_RECOG_SPEED_THRESHOLD) {
                    mIncrementalRecognitionSize = size;
                }
            }
            updateLastPoint(x, y, time);
        }
    }

    public void appendAllBatchPoints(final InputPointers out) {
        final int size = mInputPointers.getPointerSize();
        out.append(mInputPointers, mLastIncrementalBatchSize, size - mLastIncrementalBatchSize);
        mLastIncrementalBatchSize = size;
    }

    public void appendIncrementalBatchPoints(final InputPointers out) {
        out.append(mInputPointers, mLastIncrementalBatchSize,
                mIncrementalRecognitionSize - mLastIncrementalBatchSize);
        mLastIncrementalBatchSize = mIncrementalRecognitionSize;
    }

    private static float getDistance(final int p1x, final int p1y,
            final int p2x, final int p2y) {
        final float dx = p1x - p2x;
        final float dy = p1y - p2y;
        // TODO: Optimize out this {@link FloatMath#sqrt(float)} call.
        return FloatMath.sqrt(dx * dx + dy * dy);
    }

    private static float getAngle(final int p1x, final int p1y, final int p2x, final int p2y) {
        final int dx = p1x - p2x;
        final int dy = p1y - p2y;
        if (dx == 0 && dy == 0) return 0;
        return (float)Math.atan2(dy, dx);
    }

    private static float getAngleDiff(final float a1, final float a2) {
        final float diff = Math.abs(a1 - a2);
        if (diff > Math.PI) {
            return DOUBLE_PI - diff;
        }
        return diff;
    }

    public void drawGestureTrail(Canvas canvas, Paint paint, int lastX, int lastY) {
        // TODO: These paint parameter interpolation should be tunable, possibly introduce an object
        // that implements an interface such as Paint getPaint(int step, int strokePoints)
        final int size = mInputPointers.getPointerSize();
        int[] xCoords = mInputPointers.getXCoordinates();
        int[] yCoords = mInputPointers.getYCoordinates();
        int alpha = Constants.Color.ALPHA_OPAQUE;
        for (int i = size - 1; i > 0 && alpha > 0; i--) {
            paint.setAlpha(alpha);
            if (size - i > DRAWING_GESTURE_FADE_START) {
                alpha -= DRAWING_GESTURE_FADE_RATE;
            }
            canvas.drawLine(xCoords[i - 1], yCoords[i - 1], xCoords[i], yCoords[i], paint);
            if (i == size - 1) {
                canvas.drawLine(lastX, lastY, xCoords[i], yCoords[i], paint);
            }
        }
    }

    public Rect getDrawingRect() {
        return mDrawingRect;
    }
}
