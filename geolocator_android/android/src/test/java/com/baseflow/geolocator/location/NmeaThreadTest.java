package com.baseflow.geolocator.location;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.os.Handler;

import org.junit.After;
import org.junit.Test;

public class NmeaThreadTest {
  @After
  public void tearDown() {
    // Leave no users behind for other tests.
    for (int i = 0; i < 4; i++) {
      NmeaThread.release();
    }
  }

  @Test
  public void oneEngineDetachingKeepsTheThreadForAnotherEngine() {
    NmeaThread.retain(); // engine A
    NmeaThread.retain(); // engine B
    Handler before = NmeaThread.handler();

    NmeaThread.release(); // engine A detaches

    assertSame(before, NmeaThread.handler());
  }

  @Test
  public void theLastEngineDetachingStopsTheThread() {
    NmeaThread.retain();
    Handler before = NmeaThread.handler();

    NmeaThread.release();

    assertNotSame(before, NmeaThread.handler());
  }
}
