package com.baseflow.geolocator.location;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Handler;
import java.util.List;
import java.util.ArrayList;
import org.mockito.ArgumentCaptor;
import android.os.Bundle;
import android.location.Location;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

public class NmeaClientTest {
  /** Stands in for the NMEA thread: queues work until the test runs it. */
  private static final class QueueExecutor implements Executor {
    final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    void runAll() {
      while (!tasks.isEmpty()) {
        tasks.remove().run();
      }
    }
  }

  private Context context;
  private LocationManager locationManager;
  private QueueExecutor executor;
  private NmeaClient client;

  @Before
  public void setUp() {
    context = mock(Context.class);
    locationManager = mock(LocationManager.class);
    when(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager);
    when(context.checkSelfPermission(anyString())).thenReturn(PackageManager.PERMISSION_GRANTED);
    executor = new QueueExecutor();
    client =
        new NmeaClient(
            context,
            LocationOptions.parseArguments(null),
            executor,
            /* callbackHandler= */ () -> null,
            /* nmeaSupported= */ true);
  }

  @Test
  public void startMakesNoBinderCallOnTheCallingThread() {
    client.start();

    verifyNoInteractions(locationManager);
    assertEquals(1, executor.tasks.size());

    executor.runAll();
    verify(locationManager)
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
    verify(locationManager)
        .registerGnssStatusCallback(any(GnssStatus.Callback.class), (Handler) any());
  }

  @Test
  public void stopMakesNoBinderCallOnTheCallingThread() {
    client.start();
    executor.runAll();

    client.stop();

    verify(locationManager, never()).removeNmeaListener(any(OnNmeaMessageListener.class));
    executor.runAll();
    verify(locationManager).removeNmeaListener(any(OnNmeaMessageListener.class));
    verify(locationManager).unregisterGnssStatusCallback(any(GnssStatus.Callback.class));
  }

  @Test
  public void startStopStartRegistersOnceAndKeepsTheListener() {
    client.start();
    executor.runAll();
    client.stop();
    client.start();
    executor.runAll();

    // The last call was start(), so the listener stays registered, added only once.
    verify(locationManager, times(1))
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
    verify(locationManager, never()).removeNmeaListener(any(OnNmeaMessageListener.class));
  }

  @Test
  public void startThenStopRegistersThenRemovesInOrder() {
    client.start();
    client.stop();
    client.start();
    client.stop();
    executor.runAll();

    InOrder order = inOrder(locationManager);
    order.verify(locationManager, never())
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
    verifyNoInteractions(locationManager);

    client.start();
    executor.runAll();
    client.stop();
    executor.runAll();
    order = inOrder(locationManager);
    order.verify(locationManager)
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
    order.verify(locationManager)
        .registerGnssStatusCallback(any(GnssStatus.Callback.class), (Handler) any());
    order.verify(locationManager).removeNmeaListener(any(OnNmeaMessageListener.class));
    order.verify(locationManager)
        .unregisterGnssStatusCallback(any(GnssStatus.Callback.class));
  }

  @Test
  public void stopBeforeTheQueuedStartRunsRegistersNothing() {
    client.start();
    client.stop();
    executor.runAll();

    verify(locationManager, never())
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
    verify(locationManager, never()).removeNmeaListener(any(OnNmeaMessageListener.class));
  }

  @Test
  public void withoutFineLocationPermissionNothingIsRegistered() {
    when(context.checkSelfPermission(anyString())).thenReturn(PackageManager.PERMISSION_DENIED);

    client.start();
    executor.runAll();

    verify(locationManager, never())
        .addNmeaListener(any(OnNmeaMessageListener.class), (Handler) any());
  }

  private static GnssStatus status(int count, int usedInFix) {
    GnssStatus status = mock(GnssStatus.class);
    when(status.getSatelliteCount()).thenReturn(count);
    when(status.usedInFix(anyInt())).thenAnswer(call -> (int) call.getArgument(0) < usedInFix);
    return status;
  }

  @Test
  public void satelliteCountAndUsedInFixAlwaysComeFromTheSameUpdate() {
    client.start();
    executor.runAll();
    ArgumentCaptor<GnssStatus.Callback> callback =
        ArgumentCaptor.forClass(GnssStatus.Callback.class);
    verify(locationManager).registerGnssStatusCallback(callback.capture(), (Handler) any());
    callback.getValue().onSatelliteStatusChanged(status(10, 4));

    // A new status update lands while the extras are being written.
    Bundle extras = mock(Bundle.class);
    List<Double> written = new ArrayList<>();
    doAnswer(
            call -> {
              written.add(call.getArgument(1));
              if (written.size() == 1) {
                callback.getValue().onSatelliteStatusChanged(status(20, 15));
              }
              return null;
            })
        .when(extras)
        .putDouble(anyString(), anyDouble());
    Location location = mock(Location.class);
    when(location.getExtras()).thenReturn(extras);

    client.enrichExtrasWithNmea(location);

    verify(extras).putDouble(eq(NmeaClient.GNSS_SATELLITE_COUNT_EXTRA), anyDouble());
    // The first two writes are the count and used-in-fix pair from one update.
    assertEquals(10.0, written.get(0), 0.0);
    assertEquals(4.0, written.get(1), 0.0);
  }
}
