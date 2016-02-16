/*
 * Copyright (C) 2014 The Android Open Source Project
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

package org.allseen.lsf.sampleapp.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v17.leanback.app.BackgroundManager;
import android.support.v17.leanback.app.BrowseFragment;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ImageCardView;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.ListRowPresenter;
import android.support.v17.leanback.widget.OnItemViewClickedListener;
import android.support.v17.leanback.widget.OnItemViewSelectedListener;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowPresenter;
import android.support.v4.app.ActivityOptionsCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;

import org.allseen.lsf.sampleapp.R;
import org.allseen.lsf.sampleapp.SampleAppControllerConfiguration;
import org.allseen.lsf.sdk.AllLightingItemListener;
import org.allseen.lsf.sdk.Controller;
import org.allseen.lsf.sdk.Group;
import org.allseen.lsf.sdk.Lamp;
import org.allseen.lsf.sdk.LightingController;
import org.allseen.lsf.sdk.LightingDirector;
import org.allseen.lsf.sdk.LightingItemErrorEvent;
import org.allseen.lsf.sdk.LightingSystemQueue;
import org.allseen.lsf.sdk.MasterScene;
import org.allseen.lsf.sdk.Preset;
import org.allseen.lsf.sdk.PulseEffect;
import org.allseen.lsf.sdk.Scene;
import org.allseen.lsf.sdk.SceneElement;
import org.allseen.lsf.sdk.TrackingID;
import org.allseen.lsf.sdk.TransitionEffect;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

public class MainFragment extends BrowseFragment implements AllLightingItemListener{
    private static final String TAG = "MainFragment";

    private static final int BACKGROUND_UPDATE_DELAY = 300;
    private static final int GRID_ITEM_WIDTH = 200;
    private static final int GRID_ITEM_HEIGHT = 200;
    private static final int NUM_ROWS = 6;
    private static final int NUM_COLS = 15;

    Lamp publamp;
    private final Handler mHandler = new Handler();
    private ArrayObjectAdapter mRowsAdapter;
    private Drawable mDefaultBackground;
    private DisplayMetrics mMetrics;
    private Timer mBackgroundTimer;
    private URI mBackgroundURI;
    private BackgroundManager mBackgroundManager;
    Handler handler;
    public volatile Queue<Runnable> runInForeground;

    private LightingController controllerService;
    private boolean controllerClientConnected;
    private boolean controllerServiceEnabled;
    private volatile boolean controllerServiceStarted;

    private static final String CONTROLLER_ENABLED = "CONTROLLER_ENABLED_KEY";
    private AlertDialog wifiDisconnectAlertDialog;


    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        Log.i(TAG, "onCreate");
        super.onActivityCreated(savedInstanceState);

        prepareBackgroundManager();

        setupUIElements();

        loadRows();

        setupEventListeners();

        handler = new Handler(Looper.getMainLooper());
        runInForeground = new LinkedList<Runnable>();
        LightingDirector.get().addListener(this);
        LightingDirector.get().start(
                "SampleApp",
                new LightingSystemQueue() {
                    @Override
                    public void post(Runnable r) {

                        handler.post(r);
                    }

                    @Override
                    public void postDelayed(Runnable r, int delay) {
                        handler.postDelayed(r, delay);
                    }

                    @Override
                    public void stop() {
                        // Currently nothing to do
                    }
                });

        initWifiMonitoring();


        Toast.makeText(getActivity(), "yo", Toast.LENGTH_SHORT).show();
        controllerServiceEnabled = getActivity().getSharedPreferences("PREFS_READ", Context.MODE_PRIVATE).getBoolean(CONTROLLER_ENABLED, true);
        controllerService = LightingController.get();
        controllerService.init(new SampleAppControllerConfiguration(
                getActivity().getApplicationContext().getFileStreamPath("").getAbsolutePath(), getActivity().getApplicationContext()));
    }

    private void initWifiMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            //initWifiMonitoringApi14();
            Toast.makeText(getActivity(), "Min lollipop required", Toast.LENGTH_SHORT).show();

        } else {
            initWifiMonitoringApi21();
        }
    }

    protected boolean isWifiConnected() {
        NetworkInfo wifiNetworkInfo = ((ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE)).getNetworkInfo(ConnectivityManager.TYPE_WIFI);

        // determine if wifi AP mode is on
        boolean isWifiApEnabled = false;
        WifiManager wifiManager = (WifiManager) getActivity().getSystemService(Context.WIFI_SERVICE);
        // need reflection because wifi ap is not in the public API
        try {
            Method isWifiApEnabledMethod = wifiManager.getClass().getDeclaredMethod("isWifiApEnabled");
            isWifiApEnabled = (Boolean) isWifiApEnabledMethod.invoke(wifiManager);
        } catch (Exception e) {
            e.printStackTrace();
        }

        //Log.d(SampleAppActivity.TAG, "Connectivity state " + wifiNetworkInfo.getState().name() + " - connected:" + wifiNetworkInfo.isConnected() + " hotspot:" + isWifiApEnabled);

        return wifiNetworkInfo.isConnected() || isWifiApEnabled;
    }

    @SuppressLint("NewApi")
    private void initWifiMonitoringApi21() {
        // Set the initial wifi state
        wifiConnectionStateUpdate(isWifiConnected());

        // Listen for wifi state changes
        NetworkRequest networkRequest = (new NetworkRequest.Builder()).addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        ConnectivityManager connectivityManager = (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.registerNetworkCallback(networkRequest, new ConnectivityManager.NetworkCallback() {

            @Override
            public void onAvailable(Network network) {
                wifiConnectionStateUpdate(true);
            }

            @Override
            public void onLost(Network network) {
                wifiConnectionStateUpdate(false);
            }
        });
    }

    public boolean isControllerServiceEnabled() {
        return controllerServiceEnabled;
    }
    private void setControllerServiceStarted(final boolean startControllerService) {
        if (controllerService != null) {
            if (startControllerService) {
                if (!controllerServiceStarted) {
                    controllerServiceStarted = true;
                    controllerService.start();
                }
            } else {
                controllerService.stop();
                controllerServiceStarted = false;
            }
        }
    }

    public void setControllerServiceEnabled(final boolean enableControllerService) {

        if (enableControllerService != controllerServiceStarted) {
            SharedPreferences prefs = getActivity().getSharedPreferences("PREFS_READ", Context.MODE_PRIVATE);
            SharedPreferences.Editor e = prefs.edit();
            e.putBoolean(CONTROLLER_ENABLED, enableControllerService);
            e.commit();

            setControllerServiceStarted(enableControllerService);
        }

        controllerServiceEnabled = enableControllerService;
    }

    public void postInForeground(final Runnable r) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                boolean isForeground=true;
                if (isForeground) {
                   // Log.d(SampleAppActivity.TAG, "Foreground runnable running now");
                    handler.post(r);
                } else {
                   // Log.d(SampleAppActivity.TAG, "Foreground runnable running later");
                    runInForeground.add(r);
                }
            }
        });
    }
    private void wifiConnectionStateUpdate(boolean connected) {
        final Activity activity = getActivity();

        postUpdateControllerDisplay();

        if (connected) {
            handler.post(new Runnable() {
                @Override
                public void run() {
 //                   Log.d(SampleAppActivity.TAG, "wifi connected");

                    postInForeground(new Runnable() {
                        @Override
                        public void run() {
   //                         Log.d(SampleAppActivity.TAG_TRACE, "Starting system");

                            LightingDirector.get().setNetworkConnectionStatus(true);

                            if (isControllerServiceEnabled()) {
     //                           Log.d(SampleAppActivity.TAG_TRACE, "Starting bundled controller service");
                                setControllerServiceStarted(true);
                            }

                            if (wifiDisconnectAlertDialog != null) {
                                wifiDisconnectAlertDialog.dismiss();
                                wifiDisconnectAlertDialog = null;
                            }
                        }
                    });
                }
            });
        } else {
            handler.post(new Runnable() {
                @Override
                public void run() {
       //             Log.d(SampleAppActivity.TAG, "wifi disconnected");

                    postInForeground(new Runnable() {
                        @Override
                        public void run() {
                            if (wifiDisconnectAlertDialog == null) {
         //                       Log.d(SampleAppActivity.TAG, "Stopping system");

                                LightingDirector.get().setNetworkConnectionStatus(false);

                                setControllerServiceStarted(false);

                                View view = activity.getLayoutInflater().inflate(R.layout.view_loading, null);
                                ((TextView) view.findViewById(R.id.loadingText1)).setText(activity.getText(R.string.no_wifi_message));
                                ((TextView) view.findViewById(R.id.loadingText2)).setText(activity.getText(R.string.searching_wifi));

                                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity);
                                alertDialogBuilder.setView(view);
                                alertDialogBuilder.setTitle(R.string.no_wifi);
                                alertDialogBuilder.setCancelable(false);
                                wifiDisconnectAlertDialog = alertDialogBuilder.create();
                                wifiDisconnectAlertDialog.show();
                            }
                        }
                    });
                }
            });
        }
    }

    private void postUpdateControllerDisplay() {

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (null != mBackgroundTimer) {
            Log.d(TAG, "onDestroy: " + mBackgroundTimer.toString());
            mBackgroundTimer.cancel();
        }
    }

    private void loadRows() {
        List<Movie> list = MovieList.setupMovies();

        mRowsAdapter = new ArrayObjectAdapter(new ListRowPresenter());
        CardPresenter cardPresenter = new CardPresenter();

        int i=0;
//        for (i = 0; i < NUM_ROWS; i++) {
//            if (i != 0) {
//                Collections.shuffle(list);
//            }
//            ArrayObjectAdapter listRowAdapter = new ArrayObjectAdapter(cardPresenter);
//            for (int j = 0; j < NUM_COLS; j++) {
//                listRowAdapter.add(list.get(j % 5));
//            }
//            HeaderItem header = new HeaderItem(i, MovieList.MOVIE_CATEGORY[i]);
//            mRowsAdapter.add(new ListRow(header, listRowAdapter));
//        }

        HeaderItem gridHeader = new HeaderItem(i, "Activity");

        GridItemPresenter mGridPresenter = new GridItemPresenter();
        ArrayObjectAdapter gridRowAdapter = new ArrayObjectAdapter(mGridPresenter);
//        gridRowAdapter.add(getResources().getString(R.string.grid_view));
//        gridRowAdapter.add(getString(R.string.error_fragment));
//        gridRowAdapter.add(getResources().getString(R.string.personal_settings));
        gridRowAdapter.add("Movie Night");
        gridRowAdapter.add("Morning");
        mRowsAdapter.add(new ListRow(gridHeader, gridRowAdapter));

        gridHeader = new HeaderItem(i+1, "Lights");

        mGridPresenter = new GridItemPresenter();
        gridRowAdapter = new ArrayObjectAdapter(mGridPresenter);
        gridRowAdapter.add("Toggle");
        gridRowAdapter.add("Turn On");
        gridRowAdapter.add("Turn Off");
        mRowsAdapter.add(new ListRow(gridHeader, gridRowAdapter));

        gridHeader = new HeaderItem(i+1, "Settings");

        mGridPresenter = new GridItemPresenter();
        gridRowAdapter = new ArrayObjectAdapter(mGridPresenter);
        gridRowAdapter.add("setting..");
        mRowsAdapter.add(new ListRow(gridHeader, gridRowAdapter));


        setAdapter(mRowsAdapter);

    }

    private void prepareBackgroundManager() {

        mBackgroundManager = BackgroundManager.getInstance(getActivity());
        mBackgroundManager.attach(getActivity().getWindow());
        mDefaultBackground = getResources().getDrawable(R.drawable.default_background);
        mMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(mMetrics);
    }

    private void setupUIElements() {
        // setBadgeDrawable(getActivity().getResources().getDrawable(
        // R.drawable.videos_by_google_banner));
        setTitle(getString(R.string.browse_title)); // Badge, when set, takes precedent
        // over title
        setHeadersState(HEADERS_ENABLED);
        setHeadersTransitionOnBackEnabled(true);

        // set fastLane (or headers) background color
        setBrandColor(getResources().getColor(R.color.fastlane_background));
        // set search icon color
        setSearchAffordanceColor(getResources().getColor(R.color.search_opaque));
    }

    private void setupEventListeners() {
        setOnSearchClickedListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                Toast.makeText(getActivity(), "Implement your own in-app search", Toast.LENGTH_LONG)
                        .show();
            }
        });

        setOnItemViewClickedListener(new ItemViewClickedListener());
        setOnItemViewSelectedListener(new ItemViewSelectedListener());
    }

    protected void updateBackground(String uri) {
        int width = mMetrics.widthPixels;
        int height = mMetrics.heightPixels;
        Glide.with(getActivity())
                .load(uri)
                .centerCrop()
                .error(mDefaultBackground)
                .into(new SimpleTarget<GlideDrawable>(width, height) {
                    @Override
                    public void onResourceReady(GlideDrawable resource,
                                                GlideAnimation<? super GlideDrawable>
                                                        glideAnimation) {
                        mBackgroundManager.setDrawable(resource);
                    }
                });
        mBackgroundTimer.cancel();
    }

    private void startBackgroundTimer() {
        if (null != mBackgroundTimer) {
            mBackgroundTimer.cancel();
        }
        mBackgroundTimer = new Timer();
        mBackgroundTimer.schedule(new UpdateBackgroundTask(), BACKGROUND_UPDATE_DELAY);
    }

    @Override
    public void onLeaderChange(Controller controller) {
        Toast.makeText(getActivity(), "on leaderchange", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onControllerErrors(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on controller errors", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onGroupInitialized(TrackingID trackingID, Group group) {
        Toast.makeText(getActivity(), "on group initialized", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onGroupChanged(Group group) {
        Toast.makeText(getActivity(), "on group changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onGroupRemoved(Group group) {
        Toast.makeText(getActivity(), "on group removed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onGroupError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on group error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onLampInitialized(Lamp lamp) {
        Toast.makeText(getActivity(), "on lamp initialized", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onLampChanged(Lamp lamp) {
        Toast.makeText(getActivity(), "on lamp changed"+lamp.getName(), Toast.LENGTH_SHORT).show();
        try{
            //lamp.turnOn();
            publamp=lamp;
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void onLampRemoved(Lamp lamp) {
        Toast.makeText(getActivity(), "on lamp removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onLampError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on lamp error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onMasterSceneInitialized(TrackingID trackingID, MasterScene masterScene) {
        Toast.makeText(getActivity(), "on master scene initialized", Toast.LENGTH_SHORT).show();

    }
    @Override
    public void onMasterSceneChanged(MasterScene masterScene) {
        Toast.makeText(getActivity(), "on master scene changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onMasterSceneRemoved(MasterScene masterScene) {

        Toast.makeText(getActivity(), "on master scene removed", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onMasterSceneError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on master scene error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPresetInitialized(TrackingID trackingID, Preset preset) {
        Toast.makeText(getActivity(), "on preinitialized", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPresetChanged(Preset preset) {
        Toast.makeText(getActivity(), "on preset changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPresetRemoved(Preset preset) {
        Toast.makeText(getActivity(), "on preset removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPresetError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on preset error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPulseEffectInitialized(TrackingID trackingID, PulseEffect pulseEffect) {
        Toast.makeText(getActivity(), "onpulse effect init", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPulseEffectChanged(PulseEffect pulseEffect) {
        Toast.makeText(getActivity(), "onpulseeffect changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPulseEffectRemoved(PulseEffect pulseEffect) {
        Toast.makeText(getActivity(), "onpulse effect removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onPulseEffectError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "onpulse effect error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneElementInitialized(TrackingID trackingID, SceneElement sceneElement) {
        Toast.makeText(getActivity(), "onscene element init", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneElementChanged(SceneElement sceneElement) {
        Toast.makeText(getActivity(), "onscene element changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneElementRemoved(SceneElement sceneElement) {
        Toast.makeText(getActivity(), "onscene element removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneElementError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "onsene element error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneInitialized(TrackingID trackingID, Scene scene) {
        Toast.makeText(getActivity(), "on scene initialized", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneChanged(Scene scene) {
        Toast.makeText(getActivity(), "on scene changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneRemoved(Scene scene) {
        Toast.makeText(getActivity(), "on scener removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onSceneError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "on scene error", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onTransitionEffectInitialized(TrackingID trackingID, TransitionEffect transitionEffect) {
        Toast.makeText(getActivity(), "trans effect init", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onTransitionEffectChanged(TransitionEffect transitionEffect) {
        Toast.makeText(getActivity(), "trans changed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onTransitionEffectRemoved(TransitionEffect transitionEffect) {
        Toast.makeText(getActivity(), "transition removed", Toast.LENGTH_SHORT).show();

    }

    @Override
    public void onTransitionEffectError(LightingItemErrorEvent lightingItemErrorEvent) {
        Toast.makeText(getActivity(), "transition effect error", Toast.LENGTH_SHORT).show();

    }

    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                  RowPresenter.ViewHolder rowViewHolder, Row row) {

            if (item instanceof Movie) {
                Movie movie = (Movie) item;
                Log.d(TAG, "Item: " + item.toString());
                Intent intent = new Intent(getActivity(), DetailsActivity.class);
                intent.putExtra(DetailsActivity.MOVIE, movie);

                Bundle bundle = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        getActivity(),
                        ((ImageCardView) itemViewHolder.view).getMainImageView(),
                        DetailsActivity.SHARED_ELEMENT_NAME).toBundle();
                getActivity().startActivity(intent, bundle);
            } else if (item instanceof String) {
                if (((String) item).indexOf(getString(R.string.error_fragment)) >= 0) {
                    Intent intent = new Intent(getActivity(), BrowseErrorActivity.class);
                    startActivity(intent);
                } else if( ((String) item).equals("Toggle")){
                    publamp.togglePower();
                }
                else{
                    Toast.makeText(getActivity(), ((String) item), Toast.LENGTH_SHORT)
                            .show();
                }
            }
        }
    }

    private final class ItemViewSelectedListener implements OnItemViewSelectedListener {
        @Override
        public void onItemSelected(Presenter.ViewHolder itemViewHolder, Object item,
                                   RowPresenter.ViewHolder rowViewHolder, Row row) {
            if (item instanceof Movie) {
                mBackgroundURI = ((Movie) item).getBackgroundImageURI();
                startBackgroundTimer();
            }

        }
    }

    private class UpdateBackgroundTask extends TimerTask {

        @Override
        public void run() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mBackgroundURI != null) {
                        updateBackground(mBackgroundURI.toString());
                    }
                }
            });

        }
    }

    private class GridItemPresenter extends Presenter {
        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent) {
            TextView view = new TextView(parent.getContext());
            view.setLayoutParams(new ViewGroup.LayoutParams(GRID_ITEM_WIDTH, GRID_ITEM_HEIGHT));
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setBackgroundColor(getResources().getColor(R.color.default_background));
            view.setTextColor(Color.WHITE);
            view.setGravity(Gravity.CENTER);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ViewHolder viewHolder, Object item) {
            ((TextView) viewHolder.view).setText((String) item);
        }

        @Override
        public void onUnbindViewHolder(ViewHolder viewHolder) {
        }
    }

}
