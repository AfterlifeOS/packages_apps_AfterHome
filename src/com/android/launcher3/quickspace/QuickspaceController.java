/*
 * Copyright (C) 2021-2025 crDroid Android Project
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

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.crdroid.OmniJawsClient;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.util.MediaSessionManagerHelper;
import com.android.launcher3.util.MSMHProxy;

import io.chaldeaprjkt.seraphixgoogle.SeraphixDataProvider;
import io.chaldeaprjkt.seraphixgoogle.DataProviderListener;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class QuickspaceController implements OmniJawsClient.OmniJawsObserver,
        MediaSessionManagerHelper.MediaMetadataListener {

    private static final String TAG = "Launcher3:QuickspaceController";

    private final List<WeakReference<OnDataListener>> mListeners = 
        Collections.synchronizedList(new ArrayList<>());
    
    private final Context mAppContext;
    private final Map<String, Integer> mConditionMap;
    private QuickEventsController mEventsController;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private Drawable mConditionImage;
    private boolean mOmniRegistered = false;
    private boolean mMediaRegistered = false;
    private boolean mDestroyed = false;

    private static final long PSA_UPDATE_DELAY_MS = 3 * 60 * 1000;

    private final Handler mHandler = MAIN_EXECUTOR.getHandler();

    private enum WeatherProvider { OMNIJAWS, SERAPHIX }
    private WeatherProvider mProvider;

    private SeraphixDataProvider mSeraphix;
    private String mSeraphixText;
    private Icon mSeraphixIcon;
    private String mLastText;
    private int mLastBmpHash;

    private final WeakReference<QuickspaceController> mSelfRef = new WeakReference<>(this);

    private final Runnable mOnDataUpdatedRunnable = new Runnable() {
        @Override
        public void run() {
            QuickspaceController controller = mSelfRef.get();
            if (controller == null || controller.mDestroyed) return;
            
            synchronized (controller.mListeners) {
                Iterator<WeakReference<OnDataListener>> it = controller.mListeners.iterator();
                while (it.hasNext()) {
                    WeakReference<OnDataListener> ref = it.next();
                    OnDataListener listener = ref.get();
                    if (listener == null) {
                        it.remove();
                    } else {
                        try {
                            listener.onDataUpdated();
                        } catch (Exception e) {
                            Log.e(TAG, "Error notifying listener", e);
                        }
                    }
                }
            }
        }
    };

    private final Runnable mWeatherRunnable = new Runnable() {
        @Override
        public void run() {
            QuickspaceController controller = mSelfRef.get();
            if (controller == null || controller.mDestroyed) return;
            
            try {
                if (controller.mWeatherClient == null) return;
                controller.mWeatherClient.queryWeather(controller.mAppContext);
                controller.mWeatherInfo = controller.mWeatherClient.getWeatherInfo();
                if (controller.mWeatherInfo != null) {
                    controller.mConditionImage = controller.mWeatherClient.getWeatherConditionImage(
                        controller.mAppContext, controller.mWeatherInfo.conditionCode);
                }
                controller.notifyListeners();
            } catch(Exception e) {
                Log.e(TAG, "Weather update error", e);
            }
        }
    };

    private final Runnable mPsaRunnable = new Runnable() {
        @Override
        public void run() {
            QuickspaceController controller = mSelfRef.get();
            if (controller == null || controller.mDestroyed) return;
            
            controller.mHandler.removeCallbacks(this);
            if (controller.mEventsController == null) return;
            
            controller.mEventsController.updatePsonality();
            controller.mHandler.postDelayed(this, PSA_UPDATE_DELAY_MS);
            controller.notifyListeners();
        }
    };

    public interface OnDataListener {
        void onDataUpdated();
    }

    public QuickspaceController(Context context) {
        mAppContext = context.getApplicationContext();
        mConditionMap = initializeConditionMap();
        mEventsController = new QuickEventsController(mAppContext);
    }

    private void decideWeatherProvider() {
        if (mDestroyed) return;
        
        String pref = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_PROVIDER.get(mAppContext);
        WeatherProvider target = WeatherProvider.SERAPHIX;
        if ("seraphix".equals(pref)) {
            target = WeatherProvider.SERAPHIX;
        } else if ("auto".equals(pref)) {
            // Try seraphix first; if bind fails, fall back to OmniJaws
            if (tryBindSeraphix(true)) {
                target = WeatherProvider.SERAPHIX;
            } else {
                target = WeatherProvider.OMNIJAWS;
            }
        } else if ("omnijaws".equals(pref)) {
            target = WeatherProvider.OMNIJAWS;
        }
        switchProvider(target);
    }

    private void switchProvider(WeatherProvider target) {
        if (mDestroyed) return;
        
        if (mProvider == target) {
            // Ensure the chosen provider is actually set up
            if (target == WeatherProvider.SERAPHIX) {
                tryBindSeraphix(false);
            } else {
                addOmniJawsIfEnabled();
            }
            return;
        }

        // Tear down old
        if (mProvider == WeatherProvider.SERAPHIX) {
            unbindSeraphix();
        } else if (mProvider == WeatherProvider.OMNIJAWS) {
            removeOmniIfRegistered();
        }

        mProvider = target;

        // Bring up new
        if (mProvider == WeatherProvider.SERAPHIX) {
            if (!tryBindSeraphix(false)) {
                // fallback if bind fails at runtime
                mProvider = WeatherProvider.OMNIJAWS;
                addOmniJawsIfEnabled();
            }
        } else {
            addOmniJawsIfEnabled();
        }

        notifyListeners();
    }

    private void addOmniJawsIfEnabled() {
        if (mDestroyed) return;
        if (!LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(mAppContext)) return;
        
        try {
            if (mWeatherClient == null) mWeatherClient = OmniJawsClient.get();
            if (!mOmniRegistered && mWeatherClient != null) {
                mWeatherClient.addObserver(mAppContext, this);
                mOmniRegistered = true;
            }
            queryAndUpdateWeather();
        } catch (Exception e) {
            Log.e(TAG, "Error adding OmniJaws", e);
        }
    }

    private boolean tryBindSeraphix(boolean silent) {
        if (mDestroyed) return false;
        
        try {
            if (mSeraphix == null) {
                mSeraphix = new SeraphixDataProvider(mAppContext, 1022,
                    LauncherPrefs.SERAPHIX_HOLDER_ID.get(mAppContext));
                mSeraphix.setOnDataUpdated(mSeraphixListener);
            }
            mSeraphix.bind(id -> {
                if (!mDestroyed) {
                    LauncherPrefs.get(mAppContext).put(LauncherPrefs.SERAPHIX_HOLDER_ID, id);
                }
            });
            return true;
        } catch (Throwable t) {
            if (!silent) Log.w(TAG, "Seraphix bind failed, falling back", t);
            unbindSeraphix();
            return false;
        }
    }

    private void unbindSeraphix() {
        try {
            if (mSeraphix != null) {
                mSeraphix.setOnDataUpdated(null);
                mSeraphix.unbind();
            }
        } catch (Throwable ignored) {}
        mSeraphix = null;
        mSeraphixText = null;
        mSeraphixIcon = null;
    }

    private final DataProviderListener mSeraphixListener = card -> {
        if (mDestroyed) return;
        
        try {
            updateWeatherData(card.getText(), card.getImage());
        } catch (Exception e) {
            Log.e(TAG, "Seraphix update error", e);
        }
    };

    private void updateWeatherData(String text, Bitmap image) {
        if (mDestroyed) return;
        
        int hash = (image == null) ? 0 : image.getGenerationId();
        if (TextUtils.equals(text, mSeraphixText) && hash == mLastBmpHash) {
            return;
        }
        mLastBmpHash = hash;
        mSeraphixText = text;
        mSeraphixIcon = image == null ? null : Icon.createWithBitmap(image);
        notifyListeners();
    }

    public void addListener(OnDataListener listener) {
        if (listener == null || mDestroyed) return;
        
        synchronized (mListeners) {
            mListeners.removeIf(ref -> ref.get() == null);
            boolean alreadyRegistered = false;
            for (WeakReference<OnDataListener> ref : mListeners) {
                if (ref.get() == listener) {
                    alreadyRegistered = true;
                    break;
                }
            }
            
            boolean wasEmpty = mListeners.isEmpty();
            if (!alreadyRegistered) {
                mListeners.add(new WeakReference<>(listener));
            }
            
            if (wasEmpty) {
                decideWeatherProvider();
                registerMediaController();
                if (mEventsController != null) {
                    mEventsController.initQuickEvents();
                }
                updatePSAevent();
            }
        }
        
        // Notify the new listener immediately
        try {
            listener.onDataUpdated();
        } catch (Exception e) {
            Log.e(TAG, "Error in initial listener notification", e);
        }
    }

    private void removeOmniIfRegistered() {
        try {
            if (mOmniRegistered && mWeatherClient != null) {
                mWeatherClient.removeObserver(mAppContext, this);
                mOmniRegistered = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing OmniJaws observer", e);
        }
        mWeatherClient = null;
        mWeatherInfo = null;
        mConditionImage = null;
    }

    public void removeListener(OnDataListener listener) {
        if (listener == null) return;
        
        synchronized (mListeners) {
            mListeners.removeIf(ref -> {
                OnDataListener l = ref.get();
                return l == null || l == listener;
            });
            
            if (mListeners.isEmpty() && !mDestroyed) {
                cleanupWhenEmpty();
            }
        }
    }

    private void cleanupWhenEmpty() {
        if (mProvider == WeatherProvider.OMNIJAWS) {
            removeOmniIfRegistered();
        } else {
            unbindSeraphix();
        }
        unregisterMediaController();
        
        if (mHandler != null) {
            mHandler.removeCallbacks(mPsaRunnable);
            mHandler.removeCallbacks(mWeatherRunnable);
            mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        }
    }

    public boolean isQuickEvent() {
        return !mDestroyed && mEventsController != null && mEventsController.isQuickEvent();
    }

    public QuickEventsController getEventController() {
        return mDestroyed ? null : mEventsController;
    }

    public boolean isWeatherAvailable() {
        if (mDestroyed || !LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(mAppContext)) return false;
        
        if (mProvider == WeatherProvider.SERAPHIX) {
            return !TextUtils.isEmpty(mSeraphixText) || mSeraphixIcon != null;
        } else {
            return mWeatherClient != null && mWeatherClient.isOmniJawsEnabled(mAppContext);
        }
    }

    public Drawable getWeatherIcon() {
        if (mDestroyed) return null;
        
        if (mProvider == WeatherProvider.SERAPHIX) {
            return mSeraphixIcon != null ? mSeraphixIcon.loadDrawable(mAppContext) : null;
        } else {
            return mConditionImage;
        }
    }

    public String getWeatherTemp() {
        if (mDestroyed) return null;
        
        if (mProvider == WeatherProvider.SERAPHIX) {
            return mSeraphixText;
        } else {
            if (mWeatherInfo == null) return null;

            boolean shouldShowCity = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_CITY.get(mAppContext);
            boolean showWeatherText = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.get(mAppContext);

            StringBuilder weatherTemp = new StringBuilder();
            if (shouldShowCity) {
                weatherTemp.append(mWeatherInfo.city).append(" ");
            }
            weatherTemp.append(mWeatherInfo.temp)
                       .append(mWeatherInfo.tempUnits);

            if (showWeatherText) {
                weatherTemp.append(" • ").append(getConditionText(mWeatherInfo.condition));
            }

            return weatherTemp.toString();
        }
    }

    private String getConditionText(String input) {
        if (input == null || input.isEmpty()) return "";

        Locale locale = mAppContext.getResources().getConfiguration().getLocales().get(0);
        boolean isEnglish = locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("en");
        String lowerCaseInput = input.toLowerCase();

        if (!isEnglish) {
            for (Map.Entry<String, Integer> entry : mConditionMap.entrySet()) {
                if (lowerCaseInput.contains(entry.getKey())) {
                    return mAppContext.getResources().getString(entry.getValue());
                }
            }
        }
        return capitalizeWords(lowerCaseInput);
    }

    private Map<String, Integer> initializeConditionMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("clouds", R.string.quick_event_weather_clouds);
        map.put("rain", R.string.quick_event_weather_rain);
        map.put("clear", R.string.quick_event_weather_clear);
        map.put("storm", R.string.quick_event_weather_storm);
        map.put("snow", R.string.quick_event_weather_snow);
        map.put("wind", R.string.quick_event_weather_wind);
        map.put("mist", R.string.quick_event_weather_mist);
        return map;
    }

    private String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) return input;

        String[] words = input.split("\\s+");
        StringBuilder capitalized = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                capitalized.append(Character.toUpperCase(word.charAt(0)))
                           .append(word.substring(1).toLowerCase())
                           .append(" ");
            }
        }
        return capitalized.toString().trim();
    }

    public void onPause() {
        if (mDestroyed) return;
        
        unregisterMediaController();
        
        if (mHandler != null) {
            mHandler.removeCallbacks(mPsaRunnable);
            mHandler.removeCallbacks(mWeatherRunnable);
            mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        }
        
        if (mProvider == WeatherProvider.SERAPHIX && mSeraphix != null) {
            try {
                mSeraphix.pauseListening();
            } catch (Exception e) {
                Log.e(TAG, "Error pausing Seraphix", e);
            }
        }
    }

    public void onResume() {
        if (mDestroyed) return;
        
        registerMediaController();
        updateMediaController();
        decideWeatherProvider();
        
        if (mProvider == WeatherProvider.SERAPHIX && mSeraphix != null) {
            try {
                mSeraphix.resumeListening();
            } catch (Exception e) {
                Log.e(TAG, "Error resuming Seraphix", e);
            }
        }
        
        updatePSAevent();
        notifyListeners();
    }

    public void onDestroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        
        unregisterMediaController();
        
        if (mHandler != null) {
            mHandler.removeCallbacks(mPsaRunnable);
            mHandler.removeCallbacks(mWeatherRunnable);
            mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        }
        
        if (mProvider == WeatherProvider.SERAPHIX) {
            unbindSeraphix();
        } else {
            removeOmniIfRegistered();
        }
        
        if (mEventsController != null) {
            mEventsController.destroy();
            mEventsController = null;
        }
        
        synchronized (mListeners) {
            mListeners.clear();
        }
        
        mWeatherInfo = null;
        mConditionImage = null;
        mSeraphixText = null;
        mSeraphixIcon = null;
    }

    @Override
    public void weatherUpdated() {
        if (mDestroyed) return;
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (mDestroyed) return;
        
        Log.d(TAG, "weatherError " + errorReason);
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            notifyListeners();
        }
    }

    @Override
    public void updateSettings() {
        if (mDestroyed) return;
        
        Log.i(TAG, "updateSettings");
        queryAndUpdateWeather();
    }

    private void updatePSAevent() {
        if (mDestroyed || mHandler == null) return;
        
        mHandler.removeCallbacks(mPsaRunnable);
        mHandler.post(mPsaRunnable);
    }

    private void queryAndUpdateWeather() {
        if (mDestroyed || mHandler == null) return;
        
        mHandler.removeCallbacks(mWeatherRunnable);
        mHandler.post(mWeatherRunnable);
    }

    public void notifyListeners() {
        if (mDestroyed || mHandler == null) return;
        
        mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        mHandler.post(mOnDataUpdatedRunnable);
    }

    private void registerMediaController() {
        if (mDestroyed || mMediaRegistered) return;
        
        try {
            MSMHProxy.INSTANCE(mAppContext).addMediaMetadataListener(this);
            mMediaRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Error registering media controller", e);
        }
    }

    private void unregisterMediaController() {
        if (!mMediaRegistered) return;
        
        try {
            MSMHProxy.INSTANCE(mAppContext).removeMediaMetadataListener(this);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering media controller", e);
        }
        mMediaRegistered = false;
    }

    private boolean updateMediaController() {
        if (mDestroyed || !LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(mAppContext)) {
            return false;
        }
        
        try {
            MediaMetadata mediaMetadata = MSMHProxy.INSTANCE(mAppContext).getCurrentMediaMetadata();
            boolean isPlaying = MSMHProxy.INSTANCE(mAppContext).isMediaPlaying();
            String trackArtist = isPlaying && mediaMetadata != null ? 
                mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "";
            String trackTitle = isPlaying && mediaMetadata != null ? 
                mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
            
            if (mEventsController != null) {
                mEventsController.setMediaInfo(trackTitle, trackArtist, isPlaying);
                mEventsController.updateQuickEvents();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error updating media controller", e);
            return false;
        }
    }

    @Override
    public void onMediaMetadataChanged() {
        if (mDestroyed) return;
        if (updateMediaController()) notifyListeners();
    }

    @Override
    public void onPlaybackStateChanged() {
        if (mDestroyed) return;
        if (updateMediaController()) notifyListeners();
    }
}
