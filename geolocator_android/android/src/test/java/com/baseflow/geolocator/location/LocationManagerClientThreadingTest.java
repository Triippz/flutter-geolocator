package com.baseflow.geolocator.location;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.Invocation;

public class LocationManagerClientThreadingTest {
  /** Queues work until the test runs it; stands in for a thread. */
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

  private LocationManager locationManager;
  private QueueExecutor worker;
  private QueueExecutor mainThread;
  private LocationManagerClient client;

  @Before
  public void setUp() {
    Context context = mock(Context.class);
    locationManager = mock(LocationManager.class);
    when(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager);
    when(context.checkSelfPermission(anyString())).thenReturn(PackageManager.PERMISSION_GRANTED);
    when(locationManager.isProviderEnabled(anyString())).thenReturn(true);
    when(locationManager.getProviders(anyBoolean()))
        .thenReturn(Collections.singletonList(LocationManager.GPS_PROVIDER));
    worker = new QueueExecutor();
    mainThread = new QueueExecutor();
    LocationOptions options = LocationOptions.parseArguments(null);
    NmeaClient nmea =
        new NmeaClient(context, options, worker, () -> null, /* nmeaSupported= */ false);
    client =
        new LocationManagerClient(context, options, nmea, worker, () -> null, mainThread);
  }

  private int locationManagerCalls() {
    int calls = 0;
    for (Invocation invocation : mockingDetails(locationManager).getInvocations()) {
      calls++;
    }
    return calls;
  }

  @Test
  public void startMakesNoLocationManagerCallOnTheCallingThread() {
    client.startPositionUpdates(null, location -> {}, error -> {});

    assertEquals(0, locationManagerCalls());

    worker.runAll();
    // isProviderEnabled, getProviders and requestLocationUpdates all ran on the worker.
    assertEquals(true, locationManagerCalls() >= 3);
  }

  @Test
  public void stopMakesNoLocationManagerCallOnTheCallingThread() {
    client.startPositionUpdates(null, location -> {}, error -> {});
    worker.runAll();
    int afterStart = locationManagerCalls();

    client.stopPositionUpdates();

    assertEquals(afterStart, locationManagerCalls());
    worker.runAll();
    assertEquals(true, locationManagerCalls() > afterStart);
  }

  @Test
  public void stopBeforeTheQueuedStartRunsMakesNoRequest() {
    client.startPositionUpdates(null, location -> {}, error -> {});
    client.stopPositionUpdates();
    worker.runAll();

    assertEquals(0, locationManagerCalls());
  }

  @Test
  public void positionsReachTheCallbackOnlyThroughTheMainThread() {
    AtomicReference<Location> received = new AtomicReference<>();
    client.startPositionUpdates(null, received::set, error -> {});
    worker.runAll();
    Location location = mock(Location.class);
    when(location.getExtras()).thenReturn(mock(Bundle.class));

    client.onLocationChanged(location);

    assertNull(received.get());
    mainThread.runAll();
    assertSame(location, received.get());
  }
}
