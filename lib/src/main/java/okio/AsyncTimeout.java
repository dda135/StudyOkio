/*
 * Copyright (C) 2014 Square, Inc.
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
package okio;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

import static okio.Util.checkOffsetAndCount;

/**
 * This timeout uses a background thread to take action exactly when the timeout occurs. Use this to
 * implement timeouts where they aren't supported natively, such as to sockets that are blocked on
 * writing.
 *
 * <p>Subclasses should override {@link #timedOut} to take action when a timeout occurs. This method
 * will be invoked by the shared watchdog thread so it should not do any long-running operations.
 * Otherwise we risk starving other timeouts from being triggered.
 *
 * <p>Use {@link #sink} and {@link #source} to apply this timeout to a stream. The returned value
 * will apply the timeout to each operation on the wrapped stream.
 *
 * <p>Callers should call {@link #enter} before doing work that is subject to timeouts, and {@link
 * #exit} afterwards. The return value of {@link #exit} indicates whether a timeout was triggered.
 * Note that the call to {@link #timedOut} is asynchronous, and may be called after {@link #exit}.
 */
public class AsyncTimeout extends Timeout {
  /**
   * Don't write more than 64 KiB of data at a time, give or take a segment. Otherwise slow
   * connections may suffer timeouts even when they're making (slow) progress. Without this, writing
   * a single 1 MiB buffer may never succeed on a sufficiently slow connection.
   */
  private static final int TIMEOUT_WRITE_SIZE = 64 * 1024;

  /** Duration for the watchdog thread to be idle before it shuts itself down. */
  private static final long IDLE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60);
  private static final long IDLE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS);

  /**
   * The watchdog thread processes a linked list of pending timeouts, sorted in the order to be
   * triggered. This class synchronizes on AsyncTimeout.class. This lock guards the queue.
   *
   * <p>Head's 'next' points to the first element of the linked list. The first element is the next
   * node to time out, or null if the queue is empty. The head is null until the watchdog thread is
   * started and also after being idle for {@link #IDLE_TIMEOUT_MILLIS}.
   */
  static AsyncTimeout head;

  /** True if this node is currently in the queue. */
  private boolean inQueue;

  /** The next node in the linked list. */
  private AsyncTimeout next;

  /** If scheduled, this is the time that the watchdog should time this out. */
  private long timeoutAt;

  /**
   * 开始计时
   */
  public final void enter() {
    //当前timeout不能在队列中，否则说明计时器运行中
    if (inQueue) throw new IllegalStateException("Unbalanced enter/exit");
    long timeoutNanos = timeoutNanos();
    boolean hasDeadline = hasDeadline();
    //0表示没有超时限制
    if (timeoutNanos == 0 && !hasDeadline) {
      return; // No timeout and no deadline? Don't bother with the queue.
    }
    inQueue = true;//当前AsyncTimeout进入队列
    //开启守护线程watchDog来观察是否超时，如果超时会回调timeout()
    scheduleTimeout(this, timeoutNanos, hasDeadline);
  }

  private static synchronized void scheduleTimeout(
      AsyncTimeout node, long timeoutNanos, boolean hasDeadline) {
    // Start the watchdog thread and create the head node when the first timeout is scheduled.
    // 当前守护进程没有开始，需要重新创建队列，并且开启守护进程
    // 队列中总会有一个节点，这个节点没有携带任何有效的数据
    if (head == null) {
      head = new AsyncTimeout();
      new Watchdog().start();
    }
    //设置当前AsyncTimeout节点的超时时间
    long now = System.nanoTime();
    if (timeoutNanos != 0 && hasDeadline) {
      // Compute the earliest event; either timeout or deadline. Because nanoTime can wrap around,
      // Math.min() is undefined for absolute values, but meaningful for relative ones.
      node.timeoutAt = now + Math.min(timeoutNanos, node.deadlineNanoTime() - now);
    } else if (timeoutNanos != 0) {
      node.timeoutAt = now + timeoutNanos;
    } else if (hasDeadline) {
      node.timeoutAt = node.deadlineNanoTime();
    } else {
      throw new AssertionError();
    }

    // Insert the node in sorted order.
    //计算当前AsyncTimeout节点的剩余时间
    long remainingNanos = node.remainingNanos(now);
    for (AsyncTimeout prev = head; true; prev = prev.next) {//遍历当前队列
      //如果守护进程开始，则队列中默认会有一个节点
      //如果此时只有一个节点，则直接将当前节点插入到队列尾部即可，并且唤醒AsyncTimeout.class.wait的某一个线程
      //否则要进行对比，将剩余时间少的节点放在链表的前面，如果当前是插入到head之后，要唤醒AsyncTimeout.class.wait的某一个线程
      //唤醒主要是做超时处理
      //这里可以看到可能会有频繁的插入操作，所以使用链表的数据结构最为合适
      if (prev.next == null || remainingNanos < prev.next.remainingNanos(now)) {
        node.next = prev.next;
        prev.next = node;
        if (prev == head) {
          AsyncTimeout.class.notify(); // Wake up the watchdog when inserting at the front.
        }
        break;
      }
    }
  }

  /** Returns true if the timeout occurred. */
  public final boolean exit() {
    if (!inQueue) return false;
    //标志当前AsyncTimeout不在队列中
    inQueue = false;
    //这里如果当前AsyncTimeout在队列中，说明此操作没有超时
    //否则超时
    return cancelScheduledTimeout(this);
    //不管超不超时都会将当前AsyncTimeout从队列中移除，不再进行超时相关的处理
  }

  /** Returns true if the timeout occurred. */
  private static synchronized boolean cancelScheduledTimeout(AsyncTimeout node) {
    // Remove the node from the linked list.
    for (AsyncTimeout prev = head; prev != null; prev = prev.next) {
      if (prev.next == node) {
        prev.next = node.next;
        node.next = null;
        return false;
      }
    }

    // The node wasn't found in the linked list: it must have timed out!
    return true;
  }

  /**
   * Returns the amount of time left until the time out. This will be negative if the timeout has
   * elapsed and the timeout should occur immediately.
   */
  private long remainingNanos(long now) {
    return timeoutAt - now;
  }

  /**
   * Invoked by the watchdog thread when the time between calls to {@link #enter()} and {@link
   * #exit()} has exceeded the timeout.
   */
  protected void timedOut() {
  }

  /**
   * Returns a new sink that delegates to {@code sink}, using this to implement timeouts. This works
   * best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   * 使用场景之一Socket向ServerSocket写入Http请求报文数据
   * 主要就是enter和exit的封装
   */
  public final Sink sink(final Sink sink) {
    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        checkOffsetAndCount(source.size, 0, byteCount);//校验数据合理性

        while (byteCount > 0L) {
          // Count how many bytes to write. This loop guarantees we split on a segment boundary.
          long toWrite = 0L;
          for (Segment s = source.head; toWrite < TIMEOUT_WRITE_SIZE; s = s.next) {
            int segmentSize = source.head.limit - source.head.pos;
            toWrite += segmentSize;
            if (toWrite >= byteCount) {
              toWrite = byteCount;
              break;
            }
          }
          //注意这里一次toWrite最大为TIMEOUT_WRITE_SIZE即65536的数据量
          //也就是一个enter()和exit()流程中最多写入65536字节的数据
          // Emit one write. Only this section is subject to the timeout.
          boolean throwOnTimeout = false;
          enter();//如果手动设置了超时时间，这里就开启守护线程来处理超时，否则默认没有超时时间
          try {
            sink.write(source, toWrite);
            byteCount -= toWrite;
            throwOnTimeout = true;
          } catch (IOException e) {
            throw exit(e);//此处虽然发生异常，但是也校验是否超时
          } finally {
            //手动判断是否超时，超时会直接抛出异常
            exit(throwOnTimeout);
          }
        }
      }

      @Override public void flush() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.flush();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          sink.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.sink(" + sink + ")";
      }
    };
  }

  /**
   * Returns a new source that delegates to {@code source}, using this to implement timeouts. This
   * works best if {@link #timedOut} is overridden to interrupt {@code sink}'s current operation.
   */
  public final Source source(final Source source) {
    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        boolean throwOnTimeout = false;
        enter();
        try {
          long result = source.read(sink, byteCount);
          throwOnTimeout = true;
          return result;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public void close() throws IOException {
        boolean throwOnTimeout = false;
        try {
          source.close();
          throwOnTimeout = true;
        } catch (IOException e) {
          throw exit(e);
        } finally {
          exit(throwOnTimeout);
        }
      }

      @Override public Timeout timeout() {
        return AsyncTimeout.this;
      }

      @Override public String toString() {
        return "AsyncTimeout.source(" + source + ")";
      }
    };
  }

  /**
   * Throws an IOException if {@code throwOnTimeout} is {@code true} and a timeout occurred. See
   * {@link #newTimeoutException(java.io.IOException)} for the type of exception thrown.
   */
  final void exit(boolean throwOnTimeout) throws IOException {
    boolean timedOut = exit();
    if (timedOut && throwOnTimeout) throw newTimeoutException(null);
  }

  /**
   * Returns either {@code cause} or an IOException that's caused by {@code cause} if a timeout
   * occurred. See {@link #newTimeoutException(java.io.IOException)} for the type of exception
   * returned.
   */
  final IOException exit(IOException cause) throws IOException {
    if (!exit()) return cause;
    return newTimeoutException(cause);
  }

  /**
   * Returns an {@link IOException} to represent a timeout. By default this method returns {@link
   * java.io.InterruptedIOException}. If {@code cause} is non-null it is set as the cause of the
   * returned exception.
   */
  protected IOException newTimeoutException(IOException cause) {
    InterruptedIOException e = new InterruptedIOException("timeout");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  private static final class Watchdog extends Thread {
    public Watchdog() {
      super("Okio Watchdog");
      setDaemon(true);//守护线程，即JVM结束之后当前线程也会跟着结束
    }

    public void run() {
      while (true) {
        try {
          AsyncTimeout timedOut;
          //在enter中有
          synchronized (AsyncTimeout.class) {
            //这里实际上就是获取队列head后面的AsyncTimeout节点，如果还没有超时则让当前线程沉睡超时时间，
            timedOut = awaitTimeout();

            // Didn't find a node to interrupt. Try again.
            // 1.当前队列为空，等待了空闲时间之后有新的节点进入，要重新尝试
            // 2.当前队列不为空，第一个有效节点还没超时，线程会沉睡这个超时剩余时间，之后出来重新尝试，
            // 一般会再次进入awaitTimeout中从队列中移除，且timeOut不为null
            if (timedOut == null) continue;

            // The queue is completely empty. Let this thread exit and let another watchdog thread
            // get created on the next call to scheduleTimeout().
            if (timedOut == head) {//等待了一段时间之后队列依然为空
              //等待下一次scheduleTimeout再启动线程，不再浪费多余资源
              head = null;
              return;
            }
          }

          // Close the timed out node.
          //当前节点超时，回调超时方法
          timedOut.timedOut();
        } catch (InterruptedException ignored) {
        }
      }
    }
  }

  /**
   * Removes and returns the node at the head of the list, waiting for it to time out if necessary.
   * This returns {@link #head} if there was no node at the head of the list when starting, and
   * there continues to be no node after waiting {@code IDLE_TIMEOUT_NANOS}. It returns null if a
   * new node was inserted while waiting. Otherwise this returns the node being waited on that has
   * been removed.
   */
  static AsyncTimeout awaitTimeout() throws InterruptedException {
    // Get the next eligible node.
    AsyncTimeout node = head.next;

    // The queue is empty. Wait until either something is enqueued or the idle timeout elapses.
    if (node == null) {//当前队列为空
      long startNanos = System.nanoTime();
      //当前线程沉睡一个空闲时间
      AsyncTimeout.class.wait(IDLE_TIMEOUT_MILLIS);
      //在这段时间内如果有新的节点进入，则返回null，继续WatchDog的执行
      //否则返回head，则会终止当前WatchDog线程的执行，等待下一次schedule
      return head.next == null && (System.nanoTime() - startNanos) >= IDLE_TIMEOUT_NANOS
          ? head  // The idle timeout elapsed.
          : null; // The situation has changed.
    }
    //计算当前第一个有效节点的剩余超时时间
    long waitNanos = node.remainingNanos(System.nanoTime());

    // The head of the queue hasn't timed out yet. Await that.
    if (waitNanos > 0) {//如果还没有超时
      // Waiting is made complicated by the fact that we work in nanoseconds,
      // but the API wants (millis, nanos) in two arguments.
      long waitMillis = waitNanos / 1000000L;
      waitNanos -= (waitMillis * 1000000L);
      //沉睡一段时间，然后唤醒进入watchdog中重新执行，一般来说会再次进入这个if判断，不过此时走的是else
      AsyncTimeout.class.wait(waitMillis, (int) waitNanos);
      return null;
    }

    // The head of the queue has timed out. Remove it.
    // 如果当前节点已经超时，从队列中移除当前节点
    head.next = node.next;
    node.next = null;
    return node;
    // 则在exit中会手动判断超时
  }
}
