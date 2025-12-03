/*
 * Copyright (C) 2025 HinohArata
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.quickstep.util;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;

public class IosRecentsMath {

    private static final String TAG = "IosRecentsMath";

    private static final String DEFAULT_SPLINE_DATA = "-20,-20,-15,0,50,125,17,17,17,17,17,17,5,80,110,120,130,140,112,114,116,118,120,122,0,60,100,100,100,100,0,0,0,100,0,0,80,85,90,90,90,95,100,100,100,100,100,100,-10,-10,-10,-10,-10,-10,0,0,0,0,0,0,2,2,2,2,2,2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,100,100,100,100,100,100";

    public static final int SPLINE_X_COORD = 0;
    public static final int SPLINE_Y_COORD = 1;
    public static final int SPLINE_ALPHA = 2;
    public static final int SPLINE_SCALE = 3;
    public static final int SPLINE_ICON_ALPHA = 4;
    public static final int SPLINE_TEXT_ALPHA = 5;
    public static final int SPLINE_HEADER_SCALE = 6;
    public static final int SPLINE_TEXT_SCALE = 7;
    public static final int SPLINE_HEADER_X = 8;
    public static final int SPLINE_TEXT_X = 9;
    public static final int SPLINE_HEADER_Y = 10;
    public static final int SPLINE_TEXT_Y = 11;
    public static final int SPLINE_ROTATION_X = 12;
    public static final int SPLINE_ROTATION_Y = 13;
    public static final int SPLINE_ROTATION_Z = 14;
    public static final int SPLINE_CAMERA_DISTANCE = 15;

    private final ArrayList<SplineHelper> mSplines;
    
    private static IosRecentsMath sInstance;

    public static synchronized IosRecentsMath getInstance() {
        if (sInstance == null) {
            sInstance = new IosRecentsMath();
        }
        return sInstance;
    }

    private IosRecentsMath() {
        mSplines = new ArrayList<>();
        initSplines();
    }

    private void initSplines() {
        String[] values = DEFAULT_SPLINE_DATA.split(",");
        int splineCount = 16;
        int pointsPerSpline = 6;
        
        if (values.length < splineCount * pointsPerSpline) {
            Log.e(TAG, "Invalid spline data length");
            return;
        }

        for (int i = 0; i < splineCount; i++) {
            double[] yValues = new double[pointsPerSpline];
            for (int j = 0; j < pointsPerSpline; j++) {
                try {
                    // Data in string is scaled by 100 (e.g., 125 -> 1.25)
                    yValues[j] = Integer.parseInt(values[i * pointsPerSpline + j]) / 100.0;
                } catch (NumberFormatException e) {
                    yValues[j] = 0;
                }
            }
            SplineHelper spline = new SplineHelper();
            spline.initSplineBySampleData(yValues);
            mSplines.add(spline);
        }
    }

    public double getValue(int splineIndex, double x) {
        if (splineIndex >= 0 && splineIndex < mSplines.size()) {
            return mSplines.get(splineIndex).getValue(x);
        }
        return 0;
    }

    // Port of SplineHelperByGPT4o
    private static class SplineHelper {
        private double[] a;
        private double[] b;
        private double[] c;
        private double[] d;
        private final double[] x = {0.0d, 1.0d, 2.0d, 3.0d, 4.0d, 5.0d};

        public void initSplineBySampleData(double[] y) {
            if (y.length != 6) {
                throw new IllegalArgumentException("Exactly 6 Y values are required.");
            }
            int n = this.x.length - 1;
            this.a = Arrays.copyOf(y, y.length);
            this.b = new double[n];
            this.c = new double[n + 1];
            this.d = new double[n];
            double[] h = new double[n];
            for (int i = 0; i < n; i++) {
                h[i] = this.x[i + 1] - this.x[i];
            }
            double[] alpha = new double[n];
            for (int i2 = 1; i2 < n; i2++) {
                double d = 3.0 / h[i2];
                double d3 = d * (this.a[i2 + 1] - this.a[i2]);
                double d4 = 3.0 / h[i2 - 1];
                alpha[i2] = d3 - ((this.a[i2] - this.a[i2 - 1]) * d4);
            }
            double[] l = new double[n + 1];
            double[] mu = new double[n + 1];
            double[] z = new double[n + 1];
            l[0] = 1.0d;
            mu[0] = 0.0d;
            z[0] = 0.0d;
            for (int i3 = 1; i3 < n; i3++) {
                l[i3] = (2 * (this.x[i3 + 1] - this.x[i3 - 1])) - (h[i3 - 1] * mu[i3 - 1]);
                mu[i3] = h[i3] / l[i3];
                z[i3] = (alpha[i3] - (h[i3 - 1] * z[i3 - 1])) / l[i3];
            }
            l[n] = 1.0d;
            z[n] = 0.0d;
            this.c[n] = 0.0d;
            for (int j = n - 1; j >= 0; j--) {
                this.c[j] = z[j] - (mu[j] * this.c[j + 1]);
                this.b[j] = (this.a[j + 1] - this.a[j]) / h[j] - (h[j] * (this.c[j + 1] + 2 * this.c[j])) / 3.0;
                this.d[j] = (this.c[j + 1] - this.c[j]) / (3 * h[j]);
            }
        }

        public double getValue(double xValue) {
            int n = this.x.length - 1;
            int idx = -1;
            
            // Find the interval containing xValue
            for(int i=0; i<n; i++) {
                 if (xValue < this.x[i] || xValue > this.x[i+1]) {
                     continue;
                 } else {
                     idx = i;
                     break;
                 }
            }

            // If not found (out of bounds), perform extrapolation
            if (idx == -1) {
                int i2 = xValue < this.x[0] ? 0 : 4; // 4 is last interval start index (n-1)
                double xi = this.x[i2];
                double diff = xValue - xi;
                // This extrapolation logic mimics the original Kotlin code
                return this.a[i2] + ((this.b[i2] + this.c[i2] + this.d[i2]) * diff);
            }

            // Perform Spline Interpolation
            double xi = this.x[idx];
            double diff = xValue - xi;
            return this.a[idx] + (this.b[idx] * diff) + (this.c[idx] * diff * diff) + (this.d[idx] * diff * diff * diff);
        }
    }
}
