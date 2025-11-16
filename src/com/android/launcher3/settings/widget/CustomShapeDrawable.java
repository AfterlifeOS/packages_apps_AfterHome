/*
 * Copyright (C) 2025 AfterlifeOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package com.android.launcher3.settings.widget;

import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.util.AttributeSet;

import com.android.launcher3.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class CustomShapeDrawable extends ShapeDrawable {

    private float mRadius = 0f; // default 0dp

    public CustomShapeDrawable() {
        super();
    }

    public CustomShapeDrawable(Resources resources) {
        super();
        init(resources, null);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        init(r, attrs);
    }

    private void init(Resources resources, AttributeSet attrs) {
        if (attrs != null) {
            final TypedArray a = resources.obtainAttributes(
                    attrs, R.styleable.CustomShapeDrawable);
            mRadius = a.getDimension(R.styleable.CustomShapeDrawable_radius, 0f);
            a.recycle();
        }

        float[] radii = new float[]{
                mRadius, mRadius,
                mRadius, mRadius,
                mRadius, mRadius,
                mRadius, mRadius
        };

        setShape(new RoundRectShape(radii, null, null));
    }
}