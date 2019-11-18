/*
 * Copyright (C) 2011 University of Washington
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

package org.odk.collect.android.activities;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.odk.collect.android.R;
import org.odk.collect.android.application.Collect;
import org.odk.collect.android.geo.MapFragment;
import org.odk.collect.android.geo.MapPoint;
import org.odk.collect.android.geo.MapProvider;
import org.odk.collect.android.preferences.MapsPreferences;
import org.odk.collect.android.provider.FormsProviderAPI.FormsColumns;
import org.odk.collect.android.provider.InstanceProviderAPI;
import org.odk.collect.android.provider.InstanceProviderAPI.InstanceColumns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

/** Show a map with points representing saved instances of the selected form. */
public class MapActivity extends BaseGeoMapActivity {
    public static final String MAP_CENTER_KEY = "map_center";
    public static final String MAP_ZOOM_KEY = "map_zoom";

    private MapFragment map;
    private boolean viewportInitialized;
    private String jrFormId;
    private String formTitle;
    private final Map<Integer, Long> instanceIdsByFeatureId = new HashMap<>();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.instance_map_layout);

        Uri contentUri = getIntent().getData();
        try (Cursor c = Collect.getInstance().getContentResolver().query(
            contentUri, new String[] {FormsColumns.JR_FORM_ID, FormsColumns.DISPLAY_NAME},
            null, null, null)) {
            if (c.moveToFirst()) {
                jrFormId = c.getString(0);
                formTitle = c.getString(1);
                Timber.i("Starting MapActivity for form \"%s\" (jrFormId = \"%s\")", formTitle, jrFormId);
            }
        }

        TextView titleView = findViewById(R.id.form_title);
        titleView.setText(formTitle);

        Context context = getApplicationContext();
        MapProvider.createMapFragment(context)
            .addTo(this, R.id.map_container, this::initMap, this::finish);
    }

    @Override public void onResume() {
        super.onResume();
        updateInstanceGeometry();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (map == null) {
            // initMap() is called asynchronously, so map can be null if the activity
            // is stopped (e.g. by screen rotation) before initMap() gets to run.
            // In this case, preserve any provided instance state.
            if (previousState != null) {
                state.putAll(previousState);
            }
            return;
        }
        state.putParcelable(MAP_CENTER_KEY, map.getCenter());
        state.putDouble(MAP_ZOOM_KEY, map.getZoom());
    }

    @SuppressLint("MissingPermission") // Permission handled in Constructor
    public void initMap(MapFragment newMapFragment) {
        map = newMapFragment;

        findViewById(R.id.zoom).setOnClickListener(v ->
            map.zoomToPoint(map.getGpsLocation(), true));

        findViewById(R.id.layer_menu).setOnClickListener(v -> {
            MapsPreferences.showReferenceLayerDialog(this);
        });

        findViewById(R.id.new_instance).setOnClickListener(v -> {
            startActivity(new Intent(Intent.ACTION_EDIT, getIntent().getData()));
        });

        map.setGpsLocationEnabled(true);
        map.setGpsLocationListener(this::onLocationChanged);

        if (previousState != null) {
            restoreFromInstanceState(previousState);
        }

        map.setFeatureClickListener(this::onFeatureClicked);
        updateInstanceGeometry();
    }

    protected void updateInstanceGeometry() {
        if (map == null) {
            return;
        }

        List<MapPoint> points = new ArrayList<>();
        map.clearFeatures();

        try (Cursor c = Collect.getInstance().getContentResolver().query(
            InstanceColumns.CONTENT_URI,
            new String[] {InstanceColumns._ID, InstanceColumns.STATUS,
                InstanceColumns.GEOMETRY_TYPE, InstanceColumns.GEOMETRY},
            InstanceColumns.JR_FORM_ID + " = ?",
            new String[] {jrFormId},
            null)) {

            while (c.moveToNext()) {
                long id = c.getLong(0);
                String status = c.getString(1);
                String type = c.getString(2);
                String json = c.getString(3);
                if (json != null) {
                    try {
                        JSONObject geometry = new JSONObject(json);
                        switch (type) {
                            case "Point":
                                JSONArray coordinates = geometry.getJSONArray("coordinates");
                                // In GeoJSON, longitude comes before latitude.
                                double lon = coordinates.getDouble(0);
                                double lat = coordinates.getDouble(1);
                                MapPoint point = new MapPoint(lat, lon);
                                int featureId = map.addMarker(point, false);
                                int drawableId = getDrawableIdForStatus(status);
                                map.setMarkerIcon(featureId, drawableId);
                                instanceIdsByFeatureId.put(featureId, id);
                                points.add(point);
                        }
                    } catch (JSONException e) {
                        Timber.w("Invalid JSON in instances table: %s", json);
                    }
                }
            }

            TextView statusView = findViewById(R.id.geometry_status);
            statusView.setText(getString(
                R.string.geometry_status, c.getCount(), points.size()
            ));
        }

        if (!viewportInitialized && !points.isEmpty()) {
            map.zoomToBoundingBox(points, 0.8, false);
            viewportInitialized = true;
        }
    }

    public void onLocationChanged(MapPoint point) {
        if (!viewportInitialized) {
            map.zoomToPoint(point, true);
            viewportInitialized = true;
        }
    }

    public void onFeatureClicked(int featureId) {
        Long instanceId = instanceIdsByFeatureId.get(featureId);
        if (instanceId != null) {
            Uri uri = ContentUris.withAppendedId(InstanceColumns.CONTENT_URI, instanceId);
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    protected void restoreFromInstanceState(Bundle state) {
        MapPoint mapCenter = state.getParcelable(MAP_CENTER_KEY);
        double mapZoom = state.getDouble(MAP_ZOOM_KEY);
        if (mapCenter != null) {
            map.zoomToPoint(mapCenter, mapZoom, false);
        }
    }

    private int getDrawableIdForStatus(String status) {
        switch (status) {
            case InstanceProviderAPI.STATUS_INCOMPLETE:
                return R.drawable.ic_instance_marker_saved;
            case InstanceProviderAPI.STATUS_COMPLETE:
                return R.drawable.ic_instance_marker_finalized;
            case InstanceProviderAPI.STATUS_SUBMITTED:
                return R.drawable.ic_instance_marker_submitted;
            case InstanceProviderAPI.STATUS_SUBMISSION_FAILED:
                return R.drawable.ic_instance_marker_submission_failed;
        }
        return R.drawable.ic_map_point;
    }
}
