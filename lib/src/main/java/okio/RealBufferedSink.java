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

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * 具有缓冲机制的输出流
 * 本身Sink是对OutputStream的二次封装，这里在二次封装的基础上结合Buffer
 * 完成缓冲机制
 */
final class RealBufferedSink implements BufferedSink {
  //缓冲区
  public final Buffer buffer = new Buffer();
  //实际进行封装的Sink
  public final Sink sink;
  //标记是否关闭
  boolean closed;

  RealBufferedSink(Sink sink) {
    if (sink == null) throw new NullPointerException("sink == null");
    this.sink = sink;
  }

  @Override public Buffer buffer() {
    return buffer;
  }

  @Override public void write(Buffer source, long byteCount)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    //实际上就是将source中的节点移除，并且添加到buffer的尾部
    buffer.write(source, byteCount);
    emitCompleteSegments();
  }

  @Override public BufferedSink write(ByteString byteString) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(byteString);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8(String string) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8(String string, int beginIndex, int endIndex)
      throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8(string, beginIndex, endIndex);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeUtf8CodePoint(int codePoint) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeUtf8CodePoint(codePoint);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeString(String string, Charset charset) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeString(string, charset);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeString(String string, int beginIndex, int endIndex,
      Charset charset) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeString(string, beginIndex, endIndex, charset);
    return emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    //单纯将source数据写入buffer中
    buffer.write(source);
    //将buffer中的数据写入sink当中
    return emitCompleteSegments();
  }

  @Override public BufferedSink write(byte[] source, int offset, int byteCount) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.write(source, offset, byteCount);
    return emitCompleteSegments();
  }

  @Override public long writeAll(Source source) throws IOException {
    if (source == null) throw new IllegalArgumentException("source == null");
    long totalBytesRead = 0;
    for (long readCount; (readCount = source.read(buffer, Segment.SIZE)) != -1; ) {
      totalBytesRead += readCount;
      emitCompleteSegments();
    }
    return totalBytesRead;
  }

  @Override public BufferedSink write(Source source, long byteCount) throws IOException {
    while (byteCount > 0) {
      long read = source.read(buffer, byteCount);
      if (read == -1) throw new EOFException();
      byteCount -= read;
      emitCompleteSegments();
    }
    return this;
  }

  @Override public BufferedSink writeByte(int b) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeByte(b);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeShort(int s) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShort(s);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeShortLe(int s) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeShortLe(s);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeInt(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeInt(i);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeIntLe(int i) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeIntLe(i);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeLong(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeLongLe(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeLongLe(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeDecimalLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeDecimalLong(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink writeHexadecimalUnsignedLong(long v) throws IOException {
    if (closed) throw new IllegalStateException("closed");
    buffer.writeHexadecimalUnsignedLong(v);
    return emitCompleteSegments();
  }

  @Override public BufferedSink emitCompleteSegments() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.completeSegmentByteCount();
    //实际上就是将buffer中的数据写入到OutputStream中
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public BufferedSink emit() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    long byteCount = buffer.size();
    if (byteCount > 0) sink.write(buffer, byteCount);
    return this;
  }

  @Override public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) throws IOException {
        if (closed) throw new IOException("closed");
        buffer.writeByte((byte) b);
        emitCompleteSegments();
      }

      @Override public void write(byte[] data, int offset, int byteCount) throws IOException {
        if (closed) throw new IOException("closed");
        buffer.write(data, offset, byteCount);
        emitCompleteSegments();
      }

      @Override public void flush() throws IOException {
        // For backwards compatibility, a flush() on a closed stream is a no-op.
        if (!closed) {
          RealBufferedSink.this.flush();
        }
      }

      @Override public void close() throws IOException {
        RealBufferedSink.this.close();
      }

      @Override public String toString() {
        return RealBufferedSink.this + ".outputStream()";
      }
    };
  }

  @Override public void flush() throws IOException {
    if (closed) throw new IllegalStateException("closed");
    if (buffer.size > 0) {
      sink.write(buffer, buffer.size);
    }
    sink.flush();
  }

  /**
   * 稍微注意一下，如果是一次成功的close操作，会在close之前将buffer中的所有数据先写入到Sink中的OutputStream
   * 之后才关闭OutputStream，不允许再次写入数据
   * @throws IOException
     */
  @Override public void close() throws IOException {
    if (closed) return;

    // Emit buffered data to the underlying sink. If this fails, we still need
    // to close the sink; otherwise we risk leaking resources.
    Throwable thrown = null;
    try {
      if (buffer.size > 0) {//尝试将buffer中的数据全部写入OutputStream中
        sink.write(buffer, buffer.size);
      }
    } catch (Throwable e) {
      thrown = e;
    }

    try {
      sink.close();//这里实际上就是关闭OutputStream，不允许再次写入数据
    } catch (Throwable e) {
      if (thrown == null) thrown = e;
    }
    closed = true;//标记当前BufferedSink已经关闭

    if (thrown != null) Util.sneakyRethrow(thrown);
  }

  @Override public Timeout timeout() {
    return sink.timeout();
  }

  @Override public String toString() {
    return "buffer(" + sink + ")";
  }
}
