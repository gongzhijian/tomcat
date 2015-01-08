/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.http11;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.RequestDispatcher;

import org.apache.coyote.Response;
import org.apache.tomcat.util.buf.ByteBufferHolder;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.Nio2Channel;
import org.apache.tomcat.util.net.Nio2Endpoint;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Output buffer implementation for NIO2.
 */
public class InternalNio2OutputBuffer extends AbstractOutputBuffer<Nio2Channel> {

    // ----------------------------------------------------------- Constructors

    /**
     * Default constructor.
     */
    public InternalNio2OutputBuffer(Response response, int headerBufferSize) {
        super(response, headerBufferSize);
    }

    private static final ByteBuffer[] EMPTY_BUF_ARRAY = new ByteBuffer[0];

    /**
     * Track write interest
     */
    protected volatile boolean interest = false;

    /**
     * The completion handler used for asynchronous write operations
     */
    protected CompletionHandler<Integer, ByteBuffer> completionHandler;

    /**
     * The completion handler used for asynchronous write operations
     */
    protected CompletionHandler<Long, ByteBuffer[]> gatherCompletionHandler;

    /**
     * Write pending flag.
     */
    protected Semaphore writePending = new Semaphore(1);

    /**
     * The associated endpoint.
     */
    protected AbstractEndpoint<Nio2Channel> endpoint = null;

    /**
     * Exception that occurred during writing.
     */
    protected IOException e = null;

    // --------------------------------------------------------- Public Methods

    @Override
    public void init(SocketWrapperBase<Nio2Channel> socketWrapper) {
        super.init(socketWrapper);
        this.endpoint = socketWrapper.getEndpoint();

        this.completionHandler = new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer nBytes, ByteBuffer attachment) {
                boolean notify = false;
                synchronized (completionHandler) {
                    if (nBytes.intValue() < 0) {
                        failed(new EOFException(sm.getString("iob.failedwrite")), attachment);
                    } else if (socketWrapper.bufferedWrites.size() > 0) {
                        // Continue writing data using a gathering write
                        ArrayList<ByteBuffer> arrayList = new ArrayList<>();
                        if (attachment.hasRemaining()) {
                            arrayList.add(attachment);
                        }
                        for (ByteBufferHolder buffer : socketWrapper.bufferedWrites) {
                            buffer.flip();
                            arrayList.add(buffer.getBuf());
                        }
                        socketWrapper.bufferedWrites.clear();
                        ByteBuffer[] array = arrayList.toArray(EMPTY_BUF_ARRAY);
                        socketWrapper.getSocket().write(array, 0, array.length,
                                socketWrapper.getTimeout(), TimeUnit.MILLISECONDS,
                                array, gatherCompletionHandler);
                    } else if (attachment.hasRemaining()) {
                        // Regular write
                        socketWrapper.getSocket().write(attachment, socketWrapper.getTimeout(),
                                TimeUnit.MILLISECONDS, attachment, completionHandler);
                    } else {
                        // All data has been written
                        if (interest && !Nio2Endpoint.isInline()) {
                            interest = false;
                            notify = true;
                        }
                        writePending.release();
                    }
                }
                if (notify) {
                    endpoint.processSocket(socketWrapper, SocketStatus.OPEN_WRITE, false);
                }
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                socketWrapper.setError(true);
                if (exc instanceof IOException) {
                    e = (IOException) exc;
                } else {
                    e = new IOException(exc);
                }
                response.getRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION, e);
                writePending.release();
                endpoint.processSocket(socketWrapper, SocketStatus.OPEN_WRITE, true);
            }
        };
        this.gatherCompletionHandler = new CompletionHandler<Long, ByteBuffer[]>() {
            @Override
            public void completed(Long nBytes, ByteBuffer[] attachment) {
                boolean notify = false;
                synchronized (completionHandler) {
                    if (nBytes.longValue() < 0) {
                        failed(new EOFException(sm.getString("iob.failedwrite")), attachment);
                    } else if (socketWrapper.bufferedWrites.size() > 0 || arrayHasData(attachment)) {
                        // Continue writing data
                        ArrayList<ByteBuffer> arrayList = new ArrayList<>();
                        for (ByteBuffer buffer : attachment) {
                            if (buffer.hasRemaining()) {
                                arrayList.add(buffer);
                            }
                        }
                        for (ByteBufferHolder buffer : socketWrapper.bufferedWrites) {
                            buffer.flip();
                            arrayList.add(buffer.getBuf());
                        }
                        socketWrapper.bufferedWrites.clear();
                        ByteBuffer[] array = arrayList.toArray(EMPTY_BUF_ARRAY);
                        socketWrapper.getSocket().write(array, 0, array.length,
                                socketWrapper.getTimeout(), TimeUnit.MILLISECONDS,
                                array, gatherCompletionHandler);
                    } else {
                        // All data has been written
                        if (interest && !Nio2Endpoint.isInline()) {
                            interest = false;
                            notify = true;
                        }
                        writePending.release();
                    }
                }
                if (notify) {
                    endpoint.processSocket(socketWrapper, SocketStatus.OPEN_WRITE, false);
                }
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment) {
                socketWrapper.setError(true);
                if (exc instanceof IOException) {
                    e = (IOException) exc;
                } else {
                    e = new IOException(exc);
                }
                response.getRequest().setAttribute(RequestDispatcher.ERROR_EXCEPTION, e);
                writePending.release();
                endpoint.processSocket(socketWrapper, SocketStatus.OPEN_WRITE, true);
           }
        };
    }


    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    @Override
    public void recycle() {
        super.recycle();
        e = null;
        interest = false;
        if (writePending.availablePermits() != 1) {
            writePending.drainPermits();
            writePending.release();
        }
    }


    @Override
    public void nextRequest() {
        super.nextRequest();
        interest = false;
    }


    // ------------------------------------------------------ Protected Methods

    private static boolean arrayHasData(ByteBuffer[] byteBuffers) {
        for (ByteBuffer byteBuffer : byteBuffers) {
            if (byteBuffer.hasRemaining()) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void addToBB(byte[] buf, int offset, int length)
            throws IOException {

        if (length == 0)
            return;
        if (socketWrapper == null || socketWrapper.getSocket() == null)
            return;

        if (isBlocking()) {
            while (length > 0) {
                int thisTime = transfer(buf, offset, length, socketWrapper.socketWriteBuffer);
                length = length - thisTime;
                offset = offset + thisTime;
                if (socketWrapper.socketWriteBuffer.remaining() == 0) {
                    flushBuffer(true);
                }
            }
        } else {
            // FIXME: Possible new behavior:
            // If there's non blocking abuse (like a test writing 1MB in a single
            // "non blocking" write), then block until the previous write is
            // done rather than continue buffering
            // Also allows doing autoblocking
            // Could be "smart" with coordination with the main CoyoteOutputStream to
            // indicate the end of a write
            // Uses: if (writePending.tryAcquire(socketWrapper.getTimeout(), TimeUnit.MILLISECONDS))
            if (writePending.tryAcquire()) {
                synchronized (completionHandler) {
                    // No pending completion handler, so writing to the main buffer
                    // is possible
                    int thisTime = transfer(buf, offset, length, socketWrapper.socketWriteBuffer);
                    length = length - thisTime;
                    offset = offset + thisTime;
                    if (length > 0) {
                        // Remaining data must be buffered
                        addToBuffers(buf, offset, length);
                    }
                    flushBufferInternal(false, true);
                }
            } else {
                synchronized (completionHandler) {
                    addToBuffers(buf, offset, length);
                }
            }
        }
    }


    private void addToBuffers(byte[] buf, int offset, int length) {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put(buf, offset, length);
        socketWrapper.bufferedWrites.add(new ByteBufferHolder(buffer, false));
    }


    /**
     * Callback to write data from the buffer.
     */
    @Override
    protected boolean flushBuffer(boolean block) throws IOException {
        if (e != null) {
            throw e;
        }
        return flushBufferInternal(block, false);
    }

    private boolean flushBufferInternal(boolean block, boolean hasPermit) throws IOException {
        if (socketWrapper == null || socketWrapper.getSocket() == null)
            return false;

        if (block) {
            if (!isBlocking()) {
                // The final flush is blocking, but the processing was using
                // non blocking so wait until an async write is done
                try {
                    if (writePending.tryAcquire(socketWrapper.getTimeout(), TimeUnit.MILLISECONDS)) {
                        writePending.release();
                    }
                } catch (InterruptedException e) {
                    // Ignore timeout
                }
            }
            try {
                if (socketWrapper.bufferedWrites.size() > 0) {
                    for (ByteBufferHolder holder : socketWrapper.bufferedWrites) {
                        holder.flip();
                        ByteBuffer buffer = holder.getBuf();
                        while (buffer.hasRemaining()) {
                            if (socketWrapper.getSocket().write(buffer).get(socketWrapper.getTimeout(), TimeUnit.MILLISECONDS).intValue() < 0) {
                                throw new EOFException(sm.getString("iob.failedwrite"));
                            }
                        }
                    }
                    socketWrapper.bufferedWrites.clear();
                }
                if (!socketWrapper.writeBufferFlipped) {
                    socketWrapper.socketWriteBuffer.flip();
                    socketWrapper.writeBufferFlipped = true;
                }
                while (socketWrapper.socketWriteBuffer.hasRemaining()) {
                    if (socketWrapper.getSocket().write(socketWrapper.socketWriteBuffer).get(socketWrapper.getTimeout(), TimeUnit.MILLISECONDS).intValue() < 0) {
                        throw new EOFException(sm.getString("iob.failedwrite"));
                    }
                }
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                } else {
                    throw new IOException(e);
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (TimeoutException e) {
                throw new SocketTimeoutException();
            }
            socketWrapper.socketWriteBuffer.clear();
            socketWrapper.writeBufferFlipped = false;
            return false;
        } else {
            synchronized (completionHandler) {
                if (hasPermit || writePending.tryAcquire()) {
                    if (!socketWrapper.writeBufferFlipped) {
                        socketWrapper.socketWriteBuffer.flip();
                        socketWrapper.writeBufferFlipped = true;
                    }
                    Nio2Endpoint.startInline();
                    if (socketWrapper.bufferedWrites.size() > 0) {
                        // Gathering write of the main buffer plus all leftovers
                        ArrayList<ByteBuffer> arrayList = new ArrayList<>();
                        if (socketWrapper.socketWriteBuffer.hasRemaining()) {
                            arrayList.add(socketWrapper.socketWriteBuffer);
                        }
                        for (ByteBufferHolder buffer : socketWrapper.bufferedWrites) {
                            buffer.flip();
                            arrayList.add(buffer.getBuf());
                        }
                        socketWrapper.bufferedWrites.clear();
                        ByteBuffer[] array = arrayList.toArray(EMPTY_BUF_ARRAY);
                        socketWrapper.getSocket().write(array, 0, array.length, socketWrapper.getTimeout(),
                                TimeUnit.MILLISECONDS, array, gatherCompletionHandler);
                    } else if (socketWrapper.socketWriteBuffer.hasRemaining()) {
                        // Regular write
                        socketWrapper.getSocket().write(socketWrapper.socketWriteBuffer, socketWrapper.getTimeout(),
                                TimeUnit.MILLISECONDS, socketWrapper.socketWriteBuffer, completionHandler);
                    } else {
                        // Nothing was written
                        writePending.release();
                    }
                    Nio2Endpoint.endInline();
                    if (writePending.availablePermits() > 0) {
                        if (socketWrapper.socketWriteBuffer.remaining() == 0) {
                            socketWrapper.socketWriteBuffer.clear();
                            socketWrapper.writeBufferFlipped = false;
                        }
                    }
                }
                return socketWrapper.hasMoreDataToFlush() || hasBufferedData() || e != null;
            }
        }
    }


    @Override
    public boolean hasDataToWrite() {
        synchronized (completionHandler) {
            return socketWrapper.hasMoreDataToFlush() || hasBufferedData() || e != null;
        }
    }

    protected boolean hasBufferedData() {
        return socketWrapper.bufferedWrites.size() > 0;
    }

    @Override
    protected void registerWriteInterest() {
        synchronized (completionHandler) {
            if (writePending.availablePermits() == 0) {
                interest = true;
            } else {
                // If no write is pending, notify
                endpoint.processSocket(socketWrapper, SocketStatus.OPEN_WRITE, true);
            }
        }
    }
}
