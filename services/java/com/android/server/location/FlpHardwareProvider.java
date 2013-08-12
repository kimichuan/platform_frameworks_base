/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.location;

import android.hardware.location.GeofenceHardwareImpl;
import android.hardware.location.IFusedLocationHardware;
import android.hardware.location.IFusedLocationHardwareSink;
import android.location.IFusedGeofenceHardware;
import android.location.FusedBatchOptions;
import android.location.Geofence;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * This class is an interop layer for JVM types and the JNI code that interacts
 * with the FLP HAL implementation.
 *
 * {@hide}
 */
public class FlpHardwareProvider {
    private GeofenceHardwareImpl mGeofenceHardwareSink = null;
    private IFusedLocationHardwareSink mLocationSink = null;

    private static FlpHardwareProvider sSingletonInstance = null;

    private final static String TAG = "FlpHardwareProvider";
    private final Context mContext;
    private final Object mLocationSinkLock = new Object();

    public static FlpHardwareProvider getInstance(Context context) {
        if (sSingletonInstance == null) {
            sSingletonInstance = new FlpHardwareProvider(context);
        }

        return sSingletonInstance;
    }

    private FlpHardwareProvider(Context context) {
        mContext = context;

        // register for listening for passive provider data
        LocationManager manager = (LocationManager) mContext.getSystemService(
                Context.LOCATION_SERVICE);
        manager.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                0 /* minTime */,
                0 /* minDistance */,
                new NetworkLocationListener(),
                Looper.myLooper());
    }

    public static boolean isSupported() {
        return nativeIsSupported();
    }

    /**
     * Private callback functions used by FLP HAL.
     */
    // FlpCallbacks members
    private void onLocationReport(Location[] locations) {
        for (Location location : locations) {
            location.setProvider(LocationManager.FUSED_PROVIDER);
            // set the elapsed time-stamp just as GPS provider does
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }

        IFusedLocationHardwareSink sink;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
        }
        try {
            if (sink != null) {
                sink.onLocationAvailable(locations);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onLocationAvailable");
        }
    }

    // FlpDiagnosticCallbacks members
    private void onDataReport(String data) {
        IFusedLocationHardwareSink sink;
        synchronized (mLocationSinkLock) {
            sink = mLocationSink;
        }
        try {
            if (mLocationSink != null) {
                sink.onDiagnosticDataAvailable(data);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException calling onDiagnosticDataAvailable");
        }
    }

    // FlpGeofenceCallbacks members
    private void onGeofenceTransition(
            int geofenceId,
            Location location,
            int transition,
            long timestamp,
            int sourcesUsed
            ) {
        // TODO: [GeofenceIntegration] change GeofenceHardwareImpl to accept a location object
    }

    private void onGeofenceMonitorStatus(int status, int source, Location location) {
        // TODO: [GeofenceIntegration]
    }

    private void onGeofenceAdd(int geofenceId, int result) {
        // TODO: [GeofenceIntegration] map between GPS and FLP results to pass a consistent status
    }

    private void onGeofenceRemove(int geofenceId, int result) {
        // TODO: [GeofenceIntegration] map between GPS and FLP results to pass a consistent status
    }

    private void onGeofencePause(int geofenceId, int result) {
        // TODO; [GeofenceIntegration] map between GPS and FLP results
    }

    private void onGeofenceResume(int geofenceId, int result) {
        // TODO: [GeofenceIntegration] map between GPS and FLP results
    }

    /**
     * Private native methods accessing FLP HAL.
     */
    static { nativeClassInit(); }

    // Core members
    private static native void nativeClassInit();
    private static native boolean nativeIsSupported();

    // FlpLocationInterface members
    private native void nativeInit();
    private native int nativeGetBatchSize();
    private native void nativeStartBatching(int requestId, FusedBatchOptions options);
    private native void nativeUpdateBatchingOptions(int requestId, FusedBatchOptions optionsObject);
    private native void nativeStopBatching(int id);
    private native void nativeRequestBatchedLocation(int lastNLocations);
    private native void nativeInjectLocation(Location location);
    // TODO [Fix] sort out the lifetime of the instance
    private native void nativeCleanup();

    // FlpDiagnosticsInterface members
    private native boolean nativeIsDiagnosticSupported();
    private native void nativeInjectDiagnosticData(String data);

    // FlpDeviceContextInterface members
    private native boolean nativeIsDeviceContextSupported();
    private native void nativeInjectDeviceContext(int deviceEnabledContext);

    // FlpGeofencingInterface members
    private native boolean nativeIsGeofencingSupported();
    private native void nativeAddGeofences(int[] geofenceIdsArray, Geofence[] geofencesArray);
    private native void nativePauseGeofence(int geofenceId);
    private native void  nativeResumeGeofence(int geofenceId, int monitorTransitions);
    private native void nativeModifyGeofenceOption(
        int geofenceId,
        int lastTransition,
        int monitorTransitions,
        int notificationResponsiveness,
        int unknownTimer,
        int sourcesToUse);
    private native void nativeRemoveGeofences(int[] geofenceIdsArray);

    /**
     * Interface implementations for services built on top of this functionality.
     */
    public static final String LOCATION = "Location";
    public static final String GEOFENCING = "Geofencing";

    public IFusedLocationHardware getLocationHardware() {
        nativeInit();
        return mLocationHardware;
    }

    public IFusedGeofenceHardware getGeofenceHardware() {
        nativeInit();
        return mGeofenceHardwareService;
    }

    private final IFusedLocationHardware mLocationHardware = new IFusedLocationHardware.Stub() {
        @Override
        public void registerSink(IFusedLocationHardwareSink eventSink) {
            synchronized (mLocationSinkLock) {
                // only one sink is allowed at the moment
                if (mLocationSink != null) {
                    throw new RuntimeException(
                            "IFusedLocationHardware does not support multiple sinks");
                }

                mLocationSink = eventSink;
            }
        }

        @Override
        public void unregisterSink(IFusedLocationHardwareSink eventSink) {
            synchronized (mLocationSinkLock) {
                // don't throw if the sink is not registered, simply make it a no-op
                if (mLocationSink == eventSink) {
                    mLocationSink = null;
                }
            }
        }

        @Override
        public int getSupportedBatchSize() {
            return nativeGetBatchSize();
        }

        @Override
        public void startBatching(int requestId, FusedBatchOptions options) {
            nativeStartBatching(requestId, options);
        }

        @Override
        public void stopBatching(int requestId) {
            nativeStopBatching(requestId);
        }

        @Override
        public void updateBatchingOptions(int requestId, FusedBatchOptions options) {
            nativeUpdateBatchingOptions(requestId, options);
        }

        @Override
        public void requestBatchOfLocations(int batchSizeRequested) {
            nativeRequestBatchedLocation(batchSizeRequested);
        }

        @Override
        public boolean supportsDiagnosticDataInjection() {
            return nativeIsDiagnosticSupported();
        }

        @Override
        public void injectDiagnosticData(String data) {
            nativeInjectDiagnosticData(data);
        }

        @Override
        public boolean supportsDeviceContextInjection() {
            return nativeIsDeviceContextSupported();
        }

        @Override
        public void injectDeviceContext(int deviceEnabledContext) {
            nativeInjectDeviceContext(deviceEnabledContext);
        }
    };

    private final IFusedGeofenceHardware mGeofenceHardwareService =
            new IFusedGeofenceHardware.Stub() {
        @Override
        public boolean isSupported() {
            return nativeIsGeofencingSupported();
        }

        @Override
        public void addGeofences(int[] geofenceIdsArray, Geofence[] geofencesArray) {
            nativeAddGeofences(geofenceIdsArray, geofencesArray);
        }

        @Override
        public void removeGeofences(int[] geofenceIds) {
            nativeRemoveGeofences(geofenceIds);
        }

        @Override
        public void pauseMonitoringGeofence(int geofenceId) {
            nativePauseGeofence(geofenceId);
        }

        @Override
        public void resumeMonitoringGeofence(int geofenceId, int monitorTransitions) {
            nativeResumeGeofence(geofenceId, monitorTransitions);
        }

        @Override
        public void modifyGeofenceOptions(int geofenceId,
                int lastTransition,
                int monitorTransitions,
                int notificationResponsiveness,
                int unknownTimer
                ) {
            // TODO: [GeofenceIntegration] set sourcesToUse to the right value
            // TODO: expose sourcesToUse externally when needed
            nativeModifyGeofenceOption(
                    geofenceId,
                    lastTransition,
                    monitorTransitions,
                    notificationResponsiveness,
                    unknownTimer,
                    /* sourcesToUse */ 0xFFFF);
        }
    };

    /**
     * Internal classes and functions used by the provider.
     */
    private final class NetworkLocationListener implements LocationListener {
        @Override
        public void onLocationChanged(Location location) {
            if (
                !LocationManager.NETWORK_PROVIDER.equals(location.getProvider()) ||
                !location.hasAccuracy()
                ) {
                return;
            }

            nativeInjectLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) { }

        @Override
        public void onProviderDisabled(String provider) { }
    }

    private GeofenceHardwareImpl getGeofenceHardwareSink() {
        if (mGeofenceHardwareSink == null) {
            // TODO: [GeofenceIntegration] we need to register ourselves with GeofenceHardwareImpl
            mGeofenceHardwareSink = GeofenceHardwareImpl.getInstance(mContext);
        }

        return mGeofenceHardwareSink;
    }
}