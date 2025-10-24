/*
 * Copyright (C) 2020-2025 crDroid Android Project
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
package com.android.launcher3.quickspace;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.icu.text.DateFormat;
import android.icu.text.DisplayContext;
import android.view.View.OnClickListener;

import androidx.core.content.ContextCompat;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.util.Calendar;
import java.util.concurrent.ThreadLocalRandom;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.android.launcher3.quickspace.receivers.QuickSpaceActionReceiver;
import com.android.launcher3.util.MSMHProxy;

public class QuickEventsController {

    private final Context mAppContext;
    private final Resources mResources;

    private String mEventTitle;
    private String mEventTitleSub;
    private String mGreetings;
    private String mClockExt;
    private OnClickListener mEventTitleSubAction = null;
    private Drawable mEventSubIcon = null;

    private boolean mIsQuickEvent = false;
    private boolean mDestroyed = false;

    private final Map<Integer, String[]> mCachedPSAMap = new HashMap<>();

    // PSA + Personality
    private String[] mPSAStr;

    // NowPlaying
    private boolean mEventNowPlaying = false;
    private String mNowPlayingTitle;
    private String mNowPlayingArtist;
    private boolean mPlayingActive = false;

    public QuickEventsController(Context context) {
        mAppContext = context.getApplicationContext();
        mResources = mAppContext.getResources();
    }

    public void initQuickEvents() {
        if (mDestroyed) return;
        updateQuickEvents();
    }

    public void updateQuickEvents() {
        if (mDestroyed) return;
        nowPlayingEvent();
        initNowPlayingEvent();
        psonalityEvent();
    }

    public void updatePsonality() {
        if (mDestroyed) return;
        psonalityEvent();
    }

    private void nowPlayingEvent() {
        if (mDestroyed) return;
        if (mEventNowPlaying && !mPlayingActive) {
            mIsQuickEvent = false;
            mEventNowPlaying = false;
        }
    }

    private void initNowPlayingEvent() {
        if (mDestroyed) return;
        if (!LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(mAppContext)) return;

        if (!mPlayingActive) return;

        if (mNowPlayingTitle == null) return;

        mEventTitle = mNowPlayingTitle;
        mGreetings = mResources.getString(R.string.qe_now_playing_ext_one);
        mClockExt = "";
        if (mNowPlayingArtist == null ) {
            mEventTitleSub = mResources.getString(R.string.qe_now_playing_unknown_artist);
        } else {
            mEventTitleSub = mNowPlayingArtist;
        }
        mEventSubIcon = MSMHProxy.INSTANCE(mAppContext).getMediaAppIcon();
        mIsQuickEvent = true;
        mEventNowPlaying = true;

        mEventTitleSubAction = view -> {
            if (!mDestroyed) {
                MSMHProxy.INSTANCE(mAppContext).launchMediaApp();
            }
        };
    }

    private static String formatDateTime(Context context) {
        String styleText;
        DateFormat dateFormat;
        if (LauncherPrefs.SHOW_QUICKSPACE_ALT.get(context)) {
            styleText = context.getString(R.string.quickspace_date_format_minimalistic);
        } else {
            styleText = context.getString(R.string.quickspace_date_format);
        }
        dateFormat = DateFormat.getInstanceForSkeleton(styleText, Locale.getDefault());
        dateFormat.setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE);
        return dateFormat.format(System.currentTimeMillis());
    }

    private void psonalityEvent() {
        if (mDestroyed) return;
        if (mEventNowPlaying) return;

	    mEventTitle = formatDateTime(mAppContext);
        mEventTitleSubAction = QuickSpaceActionReceiver.getCalendarAction();

        int hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);

        if (hourOfDay >= 5 && hourOfDay <= 9) {
            mGreetings = mResources.getString(R.string.quickspace_grt_morning);
            mClockExt = mResources.getString(R.string.quickspace_ext_one);
        } else if (hourOfDay >= 12 && hourOfDay <= 15) {
            mGreetings = mResources.getString(R.string.quickspace_grt_afternoon);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 16 && hourOfDay <= 20) {
            mGreetings = mResources.getString(R.string.quickspace_grt_evening);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 21 && hourOfDay <= 23) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else if (hourOfDay >= 0 && hourOfDay <= 3) {
            mGreetings = mResources.getString(R.string.quickspace_grt_night);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        } else {
            mGreetings = mResources.getString(R.string.quickspace_grt_general);
            mClockExt = mResources.getString(R.string.quickspace_ext_two);
        }

        if (!LauncherPrefs.SHOW_QUICKSPACE_PSONALITY.get(mAppContext)) {
            mIsQuickEvent = false;
            return;
        }

        mEventSubIcon = null;

        int luckNumber = getLuckyNumber(13);
        if (luckNumber < 7) {
            mIsQuickEvent = false;
            return;
        } else if (luckNumber == 7) {
            mPSAStr = mResources.getStringArray(R.array.quickspace_psa_random);
            mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
            mEventSubIcon = ContextCompat.getDrawable(mAppContext, R.drawable.ic_quickspace_afterlife);
            mIsQuickEvent = true;
            return;
        }

        mPSAStr = getPSAStr(hourOfDay);

        if (mPSAStr != null) {
            mEventTitleSub = mPSAStr[getLuckyNumber(0, mPSAStr.length - 1)];
            mIsQuickEvent = true;
        } else {
            mIsQuickEvent = false;
        }
    }

    private String[] getPSAStr(int hour) {
        if (hour >= 0 && hour <= 3) {
            return getCachedArray(R.array.quickspace_psa_midnight);
        } else if (hour >= 5 && hour <= 9) {
            return getCachedArray(R.array.quickspace_psa_morning);
        } else if (hour >= 12 && hour <= 15) {
            return getCachedArray(R.array.quickspace_psa_noon);
        } else if (hour >= 16 && hour <= 18) {
            return getCachedArray(R.array.quickspace_psa_early_evening);
        } else if (hour >= 19 && hour <= 21) {
            return getCachedArray(R.array.quickspace_psa_evening);
        } else {
            return null;
        }
    }

    public boolean isQuickEvent() {
        return mIsQuickEvent && !mDestroyed;
    }

    public String getTitle() {
        return mDestroyed ? "" : mEventTitle;
    }

    public String getActionTitle() {
        return mDestroyed ? "" : mEventTitleSub;
    }

    public String getClockExt() {
        return mDestroyed ? "" : mClockExt;
    }

    public String getGreetings() {
        return mDestroyed ? "" : mGreetings;
    }

    public OnClickListener getAction() {
        return mDestroyed ? null : mEventTitleSubAction;
    }

    public Drawable getActionIcon() {
        return mDestroyed ? null : mEventSubIcon;
    }

    public int getLuckyNumber(int max) {
        return getLuckyNumber(0, max);
    }

    public int getLuckyNumber(int min, int max) {
        if (max < min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public void setMediaInfo(String title, String artist, boolean activePlayback) {
        if (mDestroyed) return;
        mNowPlayingTitle = title;
        mNowPlayingArtist = artist;
        mPlayingActive = activePlayback;
    }

    public boolean isNowPlaying() {
        return !mDestroyed && mPlayingActive;
    }

    private String[] getCachedArray(int resId) {
        if (mDestroyed) return null;
        if (!mCachedPSAMap.containsKey(resId)) {
            mCachedPSAMap.put(resId, mResources.getStringArray(resId));
        }
        return mCachedPSAMap.get(resId);
    }

    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        mEventTitleSubAction = null;
        mEventSubIcon = null;
        mEventTitle = null;
        mEventTitleSub = null;
        mGreetings = null;
        mClockExt = null;
        mPSAStr = null;
        mNowPlayingTitle = null;
        mNowPlayingArtist = null;
        mCachedPSAMap.clear();
    }
}
