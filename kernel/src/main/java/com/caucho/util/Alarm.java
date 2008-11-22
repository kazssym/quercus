/*
 * Copyright (c) 1998-2008 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.util;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The alarm class provides a lightweight event scheduler.  This allows
 * an objects to schedule a timeout without creating a new thread.
 *
 * <p>A separate thread periodically tests the queue for alarms ready.
 */
public class Alarm implements ThreadTask {
  static private final Logger log
    = Logger.getLogger(Alarm.class.getName());
  
  static private final Integer timeLock = new Integer(0);

  static private final ClassLoader _systemLoader
    = ClassLoader.getSystemClassLoader();

  static private volatile long _currentTime = System.currentTimeMillis();

  static private int _concurrentAlarmThrottle = 5;
  
  static private Object _queueLock = new Object();
  
  static private AlarmThread _alarmThread;
  static private CoordinatorThread _coordinatorThread;

  static private Alarm []_heap = new Alarm[256];
  static private int _heapTop;
  
  static private volatile int _runningAlarmCount;
  
  static private long _testTime;
  static private int _testCount;

  static private final Method _nanoTimeMethod;

  private long _wakeTime;
  private AlarmListener _listener;
  private ClassLoader _contextLoader;
  private String _name;

  private boolean _isPriority = true;
  
  private int _heapIndex = 0;

  private volatile boolean _isRunning;
  
    
  /**
   * Create a new wakeup alarm with a designated listener as a callback.
   * The alarm is not scheduled.
   */
  protected Alarm()
  {
    _name = "alarm";

    init();
  }
    
  /**
   * Create a new wakeup alarm with a designated listener as a callback.
   * The alarm is not scheduled.
   */
  public Alarm(AlarmListener listener) 
  {
    this("alarm[" + listener + "]", listener);
  }
    
  /**
   * Create a new wakeup alarm with a designated listener as a callback.
   * The alarm is not scheduled.
   */
  public Alarm(String name, AlarmListener listener) 
  {
    this(name, listener, Thread.currentThread().getContextClassLoader());
  }
    
  /**
   * Create a new wakeup alarm with a designated listener as a callback.
   * The alarm is not scheduled.
   */
  public Alarm(String name, AlarmListener listener, ClassLoader loader) 
  {
    _name = name;
    
    setListener(listener);
    setContextLoader(loader);
    
    init();
  }
    
  /**
   * Create a new wakeup alarm with a designated listener as a callback.
   * The alarm is not scheduled.
   */
  public Alarm(String name,
	       AlarmListener listener,
	       long delta,
	       ClassLoader loader) 
  {
    _name = name;
    
    setListener(listener);
    setContextLoader(loader);

    queue(delta);
    
    init();
  }

  /**
   * Creates a named alarm and schedules its wakeup.
   *
   * @param name the object prepared to receive the callback
   * @param listener the object prepared to receive the callback
   * @param delta the time in milliseconds to wake up
   */
  public Alarm(String name, AlarmListener listener, long delta) 
  {
    this(listener);

    _name = name;
    queue(delta);
    
    init();
  }
  /**
   * Creates a new alarm and schedules its wakeup.
   *
   * @param listener the object prepared to receive the callback
   * @param delta the time in milliseconds to wake up
   */
  public Alarm(AlarmListener listener, long delta) 
  {
    this(listener);

    queue(delta);
    
    init();
  }

  private void init()
  {
    synchronized (Alarm.class) {
      if (_alarmThread == null) {
	_currentTime = System.currentTimeMillis();
	_alarmThread = new AlarmThread();
	_alarmThread.start();
      }
      
      if (_coordinatorThread == null && ! isTest()) {
	_coordinatorThread = new CoordinatorThread();
	_coordinatorThread.start();
      }
    }
  }

  /**
   * Returns the alarm name.
   */
  public String getName()
  {
    return _name;
  }

  /**
   * Sets the alarm name.
   */
  protected void setName(String name)
  {
    _name = name;
  }

  /**
   * Returns the approximate current time in milliseconds.
   * Convenient for minimizing system calls.
   */
  public static long getCurrentTime()
  {
    if (_testTime > 0)
      return _testTime;
    else if (_alarmThread != null)
      return _currentTime;
    else
      return System.currentTimeMillis();
  }

  /**
   * Returns the exact current time in milliseconds.
   */
  public static long getExactTime()
  {
    if (_testTime > 0)
      return _testTime;
    else
      return System.currentTimeMillis();
  }

  /**
   * Returns the exact current time in nanoseconds.
   */
  public static long getExactTimeNanoseconds()
  {
    if (_testTime > 0)
      return _testTime * 1000000L;

    return System.nanoTime();
  }

  /**
   * Returns true for testing.
   */
  public static boolean isTest()
  {
    return _testTime > 0;
  }
  
  /**
   * Returns the wake time of this alarm.
   */
  public long getWakeTime()
  {
    return _wakeTime;
  }
  
  /**
   * Return the alarm's listener.
   */
  public AlarmListener getListener()
  {
    return _listener;
  }
  
  /**
   * Sets the alarm's listener.
   */
  public void setListener(AlarmListener listener)
  {
    _listener = listener;
  }
  
  /**
   * Sets the alarm's context loader
   */
  public void setContextLoader(ClassLoader loader)
  {
    _contextLoader = loader;
  }
  
  /**
   * Sets the alarm's context loader
   */
  public ClassLoader getContextLoader()
  {
    return _contextLoader;
  }

  /**
   * Returns true if the alarm is currently queued.
   */
  public boolean isQueued()
  {
    return _heapIndex != 0;
  }

  /**
   * Returns true if the alarm is currently running
   */
  boolean isRunning()
  {
    return _isRunning;
  }

  /**
   * True for a priority alarm (default)
   */
  public void setPriority(boolean isPriority)
  {
    _isPriority = isPriority;
  }

  /**
   * True for a priority alarm (default)
   */
  public boolean isPriority()
  {
    return _isPriority;
  }

  /**
   * Queue the alarm for wakeup.
   *
   * @param delta time in milliseconds to wake
   */
  public void queue(long delta)
  {
    boolean isNotify = false;
    
    synchronized (_queueLock) {
      if (_heapIndex > 0)
	dequeueImpl(this);

      long wakeTime;

      if (delta >= 2000)
	wakeTime = delta + getCurrentTime();
      else
	wakeTime = delta + getExactTime();
      
      _wakeTime = wakeTime;

      isNotify = insertImpl(this);
    }

    if (isNotify) {
      synchronized (_coordinatorThread) {
	_coordinatorThread.notifyAll();
      }
    }
  }

  /**
   * Queue the alarm for wakeup.
   *
   * @param delta time in milliseconds to wake
   */
  public void queueAt(long wakeTime)
  {
    boolean isNotify = false;
    
    synchronized (_queueLock) {
      if (_heapIndex > 0)
	dequeueImpl(this);
      
      _wakeTime = wakeTime;

      isNotify = insertImpl(this);
    }
    
    if (isNotify) {
      synchronized (_coordinatorThread) {
	_coordinatorThread.notifyAll();
      }
    }
  }

  /**
   * Remove the alarm from the wakeup queue.
   */
  public void dequeue()
  {
    synchronized (_queueLock) {
      if (_heapIndex > 0)
	dequeueImpl(this);
    }
  }

  /**
   * Runs the alarm.  This is only called from the worker thread.
   */
  public void run()
  {
    try {
      handleAlarm();
    } catch (Throwable e) {
      log.log(Level.WARNING, e.toString(), e);
    } finally {
      synchronized (_queueLock) {
	_isRunning = false;
	_runningAlarmCount--;
      }
    }
  }

  /**
   * Handles the alarm.
   */
  private void handleAlarm()
  {
    AlarmListener listener = getListener();
    
    if (listener == null)
      return;
    
    Thread thread = Thread.currentThread();
    ClassLoader loader = getContextLoader();
    
    if (loader != null)
      thread.setContextClassLoader(loader);
    else
      thread.setContextClassLoader(_systemLoader);

    try {
      listener.handleAlarm(this);
    } finally {
      thread.setContextClassLoader(_systemLoader);
    }
  }

  /**
   * Closes the alarm instance
   */
  public void close()
  {
    dequeue();
    _listener = null;
    _contextLoader = null;
  }

  /**
   * Returns the next alarm ready to run
   */
  static Alarm extractAlarm()
  {
    synchronized (_queueLock) {
      long now = getExactTime();

      Alarm []heap = _heap;

      Alarm alarm = heap[1];

      if (alarm == null)
	return null;
      else if (now < alarm._wakeTime)
	return null;

      dequeueImpl(alarm);

      return alarm;
    }
  }

  /**
   * Returns the next alarm ready to run
   */
  static long nextAlarmTime()
  {
    synchronized (_queueLock) {
      Alarm []heap = _heap;

      Alarm alarm = heap[1];

      if (alarm == null) {
	return getCurrentTime() + 12000;
      }
      else {
	return alarm._wakeTime;
      }
    }
  }

  /**
   * Removes the alarm item.  Must be called from within the heap lock.
   */
  private static boolean insertImpl(Alarm item)
  {
    if (item._heapIndex != 0)
      throw new IllegalStateException();
    
    // resize if necessary
    if (_heap.length <= _heapTop + 2) {
      Alarm []newHeap = new Alarm[2 * _heap.length];
      System.arraycopy(_heap, 0, newHeap, 0, _heap.length);
      _heap = newHeap;
    }

    Alarm []heap = _heap;

    int i = ++_heapTop;
    int parent = 0;
    Alarm alarm = null;
    long wakeTime = item._wakeTime;

    long now = getExactTime();
    while (i > 1 && wakeTime < (alarm = heap[parent = (i >> 1)])._wakeTime) {
      heap[i] = alarm;
      alarm._heapIndex = i;
      i = parent;
    }

    heap[i] = item;
    item._heapIndex = i;
    
    if (_heapTop < i)
      throw new IllegalStateException();

    return (i == 1 && _coordinatorThread != null);
  }

  /**
   * Removes the alarm item.  Must be called from within the heap lock.
   */
  private static void dequeueImpl(Alarm item)
  {
    int i = item._heapIndex;

    if (i < 1)
      return;
    
    if (_heapTop < i)
      throw new IllegalStateException("bad heap: " + _heapTop + " index:" + i);

    Alarm []heap = _heap;

    if (_heapTop < 1)
      throw new IllegalStateException();

    int size = _heapTop--;

    heap[i] = heap[size];
    heap[i]._heapIndex = i;
    heap[size] = null;
    
    item._heapIndex = 0;

    if (size == i)
      return;

    if (item._wakeTime < heap[i]._wakeTime) {
      while (i < size) {
	item = heap[i];

	int minIndex = i;
	long minWakeTime = item._wakeTime;
      
	int left = i << 1;
	if (left < size && heap[left]._wakeTime < minWakeTime) {
	  minIndex = left;
	  minWakeTime = heap[left]._wakeTime;
	}
      
	int right = left + 1;
	if (right < size && heap[right]._wakeTime < minWakeTime)
	  minIndex = right;

	if (i == minIndex)
	  return;

	heap[i] = heap[minIndex];
	heap[i]._heapIndex = i;
	heap[minIndex] = item;
	item._heapIndex = minIndex;
      
	i = minIndex;
      }
    }
    else {
      int parent;
      Alarm alarm;
      item = heap[i];
      long wakeTime = item._wakeTime;

      while (i > 1 && wakeTime < (alarm = heap[parent = (i >> 1)])._wakeTime) {
	heap[i] = alarm;
	alarm._heapIndex = i;
	i = parent;
      }

      heap[i] = item;
      item._heapIndex = i;
    }
  }

  // test

  static void testClear()
  {
    for (; _heapTop > 0; _heapTop--) {
      Alarm alarm = _heap[_heapTop];
      alarm._heapIndex = 0;
      _heap[_heapTop] = null;
    }
  }
  
  static void setTestTime(long time)
  {
    _testTime = time;
    
    if (_testTime > 0) {
      if (time < _currentTime) {
	testClear();
      }

      _currentTime = time;
    }
    else
      _currentTime = System.currentTimeMillis();

    Alarm alarm;

    Thread thread = Thread.currentThread();
    ClassLoader oldLoader = thread.getContextClassLoader();

    try {
      while ((alarm = Alarm.extractAlarm()) != null) {
	alarm.run();
      }
    } finally {
      thread.setContextClassLoader(oldLoader);
    }
    
    try {
      Thread.currentThread().sleep(10);
    } catch (Exception e) {
    }
  }

  public String toString()
  {
    return "Alarm[" + _name + "]";
  }

  static class AlarmThread extends Thread {
    AlarmThread()
    {
      super("resin-timer");
      setDaemon(true);
    }
    
    public void run()
    {
      while (true) {
	try {
	  if (_testTime > 0)
	    _currentTime = _testTime;
	  else
	    _currentTime = System.currentTimeMillis();
	
	  Thread.sleep(250);
	} catch (Throwable e) {
	}
      }
    }
  }

  static class CoordinatorThread extends Thread {
    CoordinatorThread()
    {
      super("resin-alarm");
      setDaemon(true);
    }
    
    /**
     * Runs the coordinator task.
     */
    public void run()
    {
      Thread thread = Thread.currentThread();
      thread.setName("alarm-coordinator");

      while (true) {
	try {
	  Alarm alarm;

	  if ((alarm = Alarm.extractAlarm()) != null) {
	    // throttle alarm invocations by 5ms so quick alarms don't need
	    // extra threads
	    if (_concurrentAlarmThrottle < _runningAlarmCount) {
	      try {
		Thread.sleep(5);
	      } catch (Throwable e) {
	      }
	    }

	    if (alarm.isPriority())
	      ThreadPool.getThreadPool().startPriority(alarm);
	    else
	      ThreadPool.getThreadPool().start(alarm);
	  }

	  synchronized (this) {
	    long next = nextAlarmTime();
	    long now = System.currentTimeMillis();

	    if (now < next) {
	      wait(next - now);
	    }
	    now = System.currentTimeMillis();
	  }
	} catch (Throwable e) {
	  log.log(Level.WARNING, e.toString(), e);
	}
      }
    }
  }

  static {
    _currentTime = System.currentTimeMillis();

    Method nanoTimeMethod;

    try
    {
      nanoTimeMethod = System.class.getMethod("nanoTime", null);
    }
    catch (NoSuchMethodException e)
    {
      nanoTimeMethod = null;
    }

    _nanoTimeMethod = nanoTimeMethod;
  }
}