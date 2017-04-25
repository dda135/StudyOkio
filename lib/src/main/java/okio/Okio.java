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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

import static okio.Util.checkOffsetAndCount;

/** Essential APIs for working with Okio. */
public final class Okio {
  static final Logger logger = Logger.getLogger(Okio.class.getName());

  private Okio() {
  }

  /**
   * Returns a new source that buffers reads from {@code source}. The returned
   * source will perform bulk reads into its in-memory buffer. Use this wherever
   * you read a source to get an ergonomic and efficient access to data.
   */
  public static BufferedSource buffer(Source source) {
    return new RealBufferedSource(source);
  }
  public static BufferedSink buffer(Sink sink) {
    return new RealBufferedSink(sink);
  }
  /**
   * Returns a new sink that buffers writes to {@code sink}. The returned sink
   * will batch writes to {@code sink}. Use this wherever you write to a sink to
   * get an ergonomic and efficient access to data.
   */


  /** Returns a sink that writes to {@code out}. */
  public static Sink sink(OutputStream out) {
    return sink(out, new Timeout());
  }

  /**
   * Sink是OutputStream的封装，在Sink中写入数据的时候相当于写入OutputStream
   * @param out 实际写入数据的流
   * @param timeout 写入超时
     */
  private static Sink sink(final OutputStream out, final Timeout timeout) {
    if (out == null) throw new IllegalArgumentException("out == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        //校验数据合理性
        checkOffsetAndCount(source.size, 0, byteCount);
        //注意这里是读取所有的数据，其中byteCount是需要读取的数据总量
        while (byteCount > 0) {
          //检查当前是否读写超时，Okio的一个特点就在这里
          timeout.throwIfReached();
          //Segment本身是一个双向链表的实现
          //每一个节点对应8192的字节数组
          //可以理解为最小的缓冲单位
          //此处是获得Buffer的头结点
          Segment head = source.head;
          //计算Segment可以写入的大小
          int toCopy = (int) Math.min(byteCount, head.limit - head.pos);
          //将head.data中从head.pos位置开始的toCopy数据量的数据写入到OutputStream中
          out.write(head.data, head.pos, toCopy);
          //标记当前节点已经被读取的数据下标或者说下一个可以读取数据的起始下标
          head.pos += toCopy;
          //当前剩余的需要读取的字节数总量
          byteCount -= toCopy;
          //当前数据来源被读取了toCopy的数据量
          //总大小需要减少
          source.size -= toCopy;

          if (head.pos == head.limit) {//当前Segment节点的缓冲区是否已被读取
            //当前head已经被完全读取
            //从链表中剔除当前head，则head.next成为下一个读取目标
            source.head = head.pop();
            //放入循环池中用于复用
            SegmentPool.recycle(head);
          }
        }
      }

      @Override public void flush() throws IOException {
        out.flush();
      }

      @Override public void close() throws IOException {
        out.close();
      }

      @Override public Timeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "sink(" + out + ")";
      }
    };
  }

  /**
   * Returns a sink that writes to {@code socket}. Prefer this over {@link
   * #sink(OutputStream)} because this method honors timeouts. When the socket
   * write times out, the socket is asynchronously closed by a watchdog thread.
   * 简单就是关联Socket的输出流，常用场景就是往ServerSocket中写入Http报文数据
   * 内部有异步超时自动关闭socket机制
   */
  public static Sink sink(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    AsyncTimeout timeout = timeout(socket);
    Sink sink = sink(socket.getOutputStream(), timeout);
    return timeout.sink(sink);
  }

  /** Returns a source that reads from {@code in}.
   * 默认Timeout是没有超时时间的
   * */
  public static Source source(InputStream in) {
    return source(in, new Timeout());
  }

  /**
   * Source是InputStream的封装，实际作用就是往InputStream中输入数据
   * @param in 需要输入数据的数据流
   * @param timeout 超时时间
   * @note Source的读操作类似于byte[8192]的读取操作，一次最多读取8192个字节，如果读到尾部，返回-1
     */
  private static Source source(final InputStream in, final Timeout timeout) {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (timeout == null) throw new IllegalArgumentException("timeout == null");

    return new Source() {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        //校验信息输入量的合理性
        if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
        if (byteCount == 0) return 0;
        try {
          //校验当前操作是否超时
          timeout.throwIfReached();
          //保证当前Buffer可以写入新的数据
          //并且获得当前需要写入数据的最新的节点
          Segment tail = sink.writableSegment(1);
          //当前最多可以写入的数量
          //其中Segment.SIZE为一个节点可写入的最大值
          //limit表示的是当前节点第一个有效的数据的起始可写入的位置，一定小于等于SIZE
          //同时也表示了当前节点剩余的可写入数据量
          int maxToCopy = (int) Math.min(byteCount, Segment.SIZE - tail.limit);
          //从输入流中读取maxToCopy字节到tail中
          int bytesRead = in.read(tail.data, tail.limit, maxToCopy);
          //此时输入流已经读到结尾，返回-1
          if (bytesRead == -1) return -1;
          //当前数据成功读取
          //节点和Buffer的数据量增加
          tail.limit += bytesRead;
          sink.size += bytesRead;
          return bytesRead;
          //实际上就是从InputStream中读取数据到Buffer中
        } catch (AssertionError e) {
          if (isAndroidGetsocknameError(e)) throw new IOException(e);
          throw e;
        }
      }

      @Override public void close() throws IOException {
        in.close();
      }

      @Override public Timeout timeout() {
        return timeout;
      }

      @Override public String toString() {
        return "source(" + in + ")";
      }
    };
  }

  /** Returns a source that reads from {@code file}. */
  public static Source source(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return source(new FileInputStream(file));
  }

  /** Returns a source that reads from {@code path}. */
  @IgnoreJRERequirement // Should only be invoked on Java 7+.
  public static Source source(Path path, OpenOption... options) throws IOException {
    if (path == null) throw new IllegalArgumentException("path == null");
    return source(Files.newInputStream(path, options));
  }

  /** Returns a sink that writes to {@code file}. */
  public static Sink sink(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return sink(new FileOutputStream(file));
  }

  /** Returns a sink that appends to {@code file}. */
  public static Sink appendingSink(File file) throws FileNotFoundException {
    if (file == null) throw new IllegalArgumentException("file == null");
    return sink(new FileOutputStream(file, true));
  }

  /** Returns a sink that writes to {@code path}. */
  @IgnoreJRERequirement // Should only be invoked on Java 7+.
  public static Sink sink(Path path, OpenOption... options) throws IOException {
    if (path == null) throw new IllegalArgumentException("path == null");
    return sink(Files.newOutputStream(path, options));
  }

  /** Returns a sink that writes nowhere. */
  public static Sink blackhole() {
    return new Sink() {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        source.skip(byteCount);
      }

      @Override public void flush() throws IOException {}

      @Override public Timeout timeout() {
        return Timeout.NONE;
      }

      @Override public void close() throws IOException {}
    };
  }

  /**
   * Returns a source that reads from {@code socket}. Prefer this over {@link
   * #source(InputStream)} because this method honors timeouts. When the socket
   * read times out, the socket is asynchronously closed by a watchdog thread.
   * 建立一个Socket的Source，主要是关联socket的输入流，内部有异步超时关闭socket机制
   * 使用场景为建立套接字连接之后可以通过Source来读取ServerSocket的response
   */
  public static Source source(Socket socket) throws IOException {
    if (socket == null) throw new IllegalArgumentException("socket == null");
    AsyncTimeout timeout = timeout(socket);
    Source source = source(socket.getInputStream(), timeout);
    return timeout.source(source);
  }

  /**
   * 设置Socket的连接超时，这个的应用可以看OkHttp的ConnectTimeout的实现
   * 校验逻辑和普通的Timeout一致，只是最后超时的时候会自动关闭Socket连接
     */
  private static AsyncTimeout timeout(final Socket socket) {
    return new AsyncTimeout() {
      @Override protected IOException newTimeoutException(IOException cause) {
        InterruptedIOException ioe = new SocketTimeoutException("timeout");
        if (cause != null) {
          ioe.initCause(cause);
        }
        return ioe;
      }

      @Override protected void timedOut() {
        try {
          socket.close();
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
        } catch (AssertionError e) {
          if (isAndroidGetsocknameError(e)) {
            // Catch this exception due to a Firmware issue up to android 4.2.2
            // https://code.google.com/p/android/issues/detail?id=54072
            logger.log(Level.WARNING, "Failed to close timed out socket " + socket, e);
          } else {
            throw e;
          }
        }
      }
    };
  }

  /**
   * Returns true if {@code e} is due to a firmware bug fixed after Android 4.2.2.
   * https://code.google.com/p/android/issues/detail?id=54072
   */
  static boolean isAndroidGetsocknameError(AssertionError e) {
    return e.getCause() != null && e.getMessage() != null
        && e.getMessage().contains("getsockname failed");
  }
}
