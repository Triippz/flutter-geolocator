package com.baseflow.geolocator.location;

import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;

/**
 * One background thread for NMEA and GNSS status listener registration.
 *
 * <p>Registering and unregistering these listeners are binder calls into the system location
 * service. On the Android main thread they block the UI while that service is slow, which
 * shows as "isn't responding" (ANR). The listener callbacks are delivered on this thread too.
 */
final class NmeaThread {
  private static HandlerThread thread;
  private static Handler handler;
  // Attached plugin instances (one per Flutter engine) that may use the thread.
  private static int users = 0;

  private NmeaThread() {}

  /** Returns the handler of the running thread, starting the thread when needed. */
  @NonNull
  static synchronized Handler handler() {
    if (handler == null) {
      thread = new HandlerThread("geolocator-nmea");
      thread.start();
      handler = new Handler(thread.getLooper());
    }
    return handler;
  }

  /** Runs {@code task} on the thread, in submission order; never races {@link #quit()}. */
  static synchronized void post(@NonNull Runnable task) {
    handler().post(task);
  }

  /** Marks one more attached plugin instance as a user of the thread. */
  static synchronized void retain() {
    users++;
  }

  /**
   * Drops one user. When the last attached plugin instance detaches, the thread stops after its
   * queued work; the next use starts a new one. Other engines keep the thread while attached.
   */
  static synchronized void release() {
    if (users > 0) {
      users--;
    }
    if (users == 0 && thread != null) {
      thread.quitSafely();
      thread = null;
      handler = null;
    }
  }
}
