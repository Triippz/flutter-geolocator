package com.baseflow.geolocator.location;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.location.LocationListenerCompat;
import androidx.core.location.LocationManagerCompat;
import androidx.core.location.LocationRequestCompat;

import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;

import java.util.List;
import java.util.concurrent.Executor;

class LocationManagerClient implements LocationClient, LocationListenerCompat {

  /** Supplies the looper that location updates are delivered on. */
  interface LooperProvider {
    @Nullable
    Looper get();
  }

  private static final long TWO_MINUTES = 120000;
  private final LocationManager locationManager;
  private final NmeaClient nmeaClient;
  @Nullable private final LocationOptions locationOptions;
  public Context context;
  private boolean isListening = false;

  @Nullable private Location currentBestLocation;
  @Nullable private String currentLocationProvider;
  @Nullable private PositionChangedCallback positionChangedCallback;
  @Nullable private ErrorCallback errorCallback;

  // LocationManager calls are binder transactions that block while the system location service
  // is slow; they run on [worker], off the Android main thread, in call order. Location updates
  // arrive on the worker's looper, and results go back to the main thread through [mainThread].
  private final Executor worker;
  private final LooperProvider workerLooper;
  private final Executor mainThread;
  // What the caller asked for; queued start work checks it, so a stop that comes first wins.
  private volatile boolean active = false;

  public LocationManagerClient(
      @NonNull Context context, @Nullable LocationOptions locationOptions) {
    this(
        context,
        locationOptions,
        new NmeaClient(context, locationOptions),
        NmeaThread::post,
        () -> NmeaThread.handler().getLooper(),
        new Handler(Looper.getMainLooper())::post);
  }

  LocationManagerClient(
      @NonNull Context context,
      @Nullable LocationOptions locationOptions,
      @NonNull NmeaClient nmeaClient,
      @NonNull Executor worker,
      @NonNull LooperProvider workerLooper,
      @NonNull Executor mainThread) {
    this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    this.locationOptions = locationOptions;
    this.context = context;
    this.nmeaClient = nmeaClient;
    this.worker = worker;
    this.workerLooper = workerLooper;
    this.mainThread = mainThread;
  }

  static boolean isBetterLocation(Location location, Location bestLocation) {
    if (bestLocation == null) return true;

    long timeDelta = location.getTime() - bestLocation.getTime();
    boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
    boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
    boolean isNewer = timeDelta > 0;

    if (isSignificantlyNewer) return true;

    if (isSignificantlyOlder) return false;

    float accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
    boolean isLessAccurate = accuracyDelta > 0;
    boolean isMoreAccurate = accuracyDelta < 0;
    boolean isSignificantlyLessAccurate = accuracyDelta > 200;

    boolean isFromSameProvider = false;
    if (location.getProvider() != null) {
      isFromSameProvider = location.getProvider().equals(bestLocation.getProvider());
    }

    if (isMoreAccurate) return true;

    if (isNewer && !isLessAccurate) return true;

    //noinspection RedundantIfStatement
    if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true;

    return false;
  }

  private static @Nullable String determineProvider(
      @NonNull LocationManager locationManager,
      @NonNull LocationAccuracy accuracy) {

      final List<String> enabledProviders = locationManager.getProviders(true);

      if (accuracy == LocationAccuracy.lowest) {
          return LocationManager.PASSIVE_PROVIDER;
      } else if (enabledProviders.contains(LocationManager.FUSED_PROVIDER) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          return LocationManager.FUSED_PROVIDER;
      } else if (enabledProviders.contains(LocationManager.GPS_PROVIDER)) {
          return LocationManager.GPS_PROVIDER;
      } else if (enabledProviders.contains(LocationManager.NETWORK_PROVIDER)) {
          return LocationManager.NETWORK_PROVIDER;
      } else if (!enabledProviders.isEmpty()){
          return enabledProviders.get(0);
      } else {
          return null;
      }
  }

  private static @LocationRequestCompat.Quality int accuracyToQuality(@NonNull LocationAccuracy accuracy) {
      switch (accuracy) {
          case lowest:
          case low:
              return LocationRequestCompat.QUALITY_LOW_POWER;
          case high:
          case best:
          case bestForNavigation:
              return LocationRequestCompat.QUALITY_HIGH_ACCURACY;
          case medium:
          default:
              return LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY;
      }
  }

  @Override
  public void isLocationServiceEnabled(LocationServiceListener listener) {
    if (locationManager == null) {
      listener.onLocationServiceResult(false);
      return;
    }

    listener.onLocationServiceResult(checkLocationService(context));
  }

  @Override
  public void getLastKnownPosition(
      PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {
    Location bestLocation = null;

    for (String provider : locationManager.getProviders(true)) {
      @SuppressLint("MissingPermission")
      Location location = locationManager.getLastKnownLocation(provider);

      if (location != null && isBetterLocation(location, bestLocation)) {
        bestLocation = location;
      }
    }

    positionChangedCallback.onPositionChanged(bestLocation);
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode) {
    return false;
  }

  @Override
  public void startPositionUpdates(
      Activity activity,
      PositionChangedCallback positionChangedCallback,
      ErrorCallback errorCallback) {
    this.positionChangedCallback = positionChangedCallback;
    this.errorCallback = errorCallback;
    this.active = true;
    worker.execute(this::requestUpdates);
  }

  @SuppressLint("MissingPermission")
  private void requestUpdates() {
    if (!active) {
      return;
    }
    final ErrorCallback errorCallback = this.errorCallback;

    if (!checkLocationService(context)) {
      postError(errorCallback, ErrorCodes.locationServicesDisabled);
      return;
    }

    LocationAccuracy accuracy = LocationAccuracy.best;
    long timeInterval = 0;
    float distanceFilter = 0;
    @LocationRequestCompat.Quality int quality = LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY;

    if (this.locationOptions != null) {
      distanceFilter = locationOptions.getDistanceFilter();
      accuracy = locationOptions.getAccuracy();
      timeInterval = accuracy == LocationAccuracy.lowest
          ? LocationRequestCompat.PASSIVE_INTERVAL
          : locationOptions.getTimeInterval();
      quality = accuracyToQuality(accuracy);
    }

    this.currentLocationProvider = determineProvider(this.locationManager, accuracy);

    if (this.currentLocationProvider == null) {
      postError(errorCallback, ErrorCodes.locationServicesDisabled);
      return;
    }

    final LocationRequestCompat locationRequest = new LocationRequestCompat.Builder(timeInterval)
        .setMinUpdateDistanceMeters(distanceFilter)
        .setMinUpdateIntervalMillis(timeInterval)
        .setQuality(quality)
        .build();

    this.isListening = true;
    this.nmeaClient.start();

    LocationManagerCompat.requestLocationUpdates(
        this.locationManager,
        this.currentLocationProvider,
        locationRequest,
        this,
        workerLooper.get());
  }

  @Override
  public void stopPositionUpdates() {
    this.active = false;
    worker.execute(this::removeUpdates);
  }

  private void removeUpdates() {
    // A start() that came after this stop() wins; with nothing registered there is nothing to
    // remove, so no binder call is made.
    if (active || !isListening) {
      return;
    }
    this.isListening = false;
    this.nmeaClient.stop();
    this.locationManager.removeUpdates(this);
  }

  private void postError(@Nullable ErrorCallback callback, ErrorCodes code) {
    if (callback != null) {
      mainThread.execute(() -> callback.onError(code));
    }
  }

  @Override
  public synchronized void onLocationChanged(Location location) {
    if (isBetterLocation(location, currentBestLocation)) {
      this.currentBestLocation = location;

      final PositionChangedCallback callback = this.positionChangedCallback;
      if (callback != null) {
        nmeaClient.enrichExtrasWithNmea(location);
        final Location best = currentBestLocation;
        mainThread.execute(() -> callback.onPositionChanged(best));
      }
    }
  }

  /**
   * This callback will never be invoked on Android Q and above, and providers can be considered as
   * always in the {@link android.location.LocationProvider#AVAILABLE} state.
   * See the <a href=https://developer.android.com/reference/android/location/LocationListener#onStatusChanged(java.lang.String,%20int,%20android.os.Bundle)>Android documentation</a>
   * for more information.
   */
  @SuppressWarnings({"deprecation", "RedundantSuppression"})
  @TargetApi(28)
  @Override
  public void onStatusChanged(@NonNull String provider, int status, Bundle extras) {
    if (status == android.location.LocationProvider.AVAILABLE) {
      onProviderEnabled(provider);
    } else if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
      onProviderDisabled(provider);
    }
  }

  @Override
  public void onProviderEnabled(@NonNull String provider) {}

  @SuppressLint("MissingPermission")
  @Override
  public void onProviderDisabled(String provider) {
    if (provider.equals(this.currentLocationProvider)) {
      if (isListening) {
        this.locationManager.removeUpdates(this);
      }

      postError(this.errorCallback, ErrorCodes.locationServicesDisabled);

      this.currentLocationProvider = null;
    }
  }
}
