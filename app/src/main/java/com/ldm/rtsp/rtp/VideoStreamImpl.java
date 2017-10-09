package com.ldm.rtsp.rtp;

import android.util.Log;

import com.ldm.rtsp.utils.Constant;

import org.apache.mina.core.buffer.IoBuffer;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoStreamImpl implements Runnable, VideoStreamInterface {
    private IoBuffer buffer;
    private ConcurrentLinkedQueue<byte[]> streams;
    private IoBuffer frameBuffer;
    private AtomicBoolean status = new AtomicBoolean(false);
    private H264StreamInterface rawStream;

    public VideoStreamImpl(H264StreamInterface rawStream) {
        this.rawStream = rawStream;
        this.buffer = IoBuffer.allocate(8192).setAutoExpand(true).setAutoShrink(true);
        this.frameBuffer = IoBuffer.allocate(8192).setAutoExpand(true).setAutoShrink(true);
        this.streams = new ConcurrentLinkedQueue();
        this.status.set(true);
        (new Thread(this)).start();
    }

    public void run() {
        while(this.status.get()) {
            try {
                synchronized(this) {
                    this.wait(100L);
                }

                this.unpackRtp();
            } catch (Exception var4) {
                var4.printStackTrace();
            }
        }

    }

    private void unpackRtp() {
        if(!this.streams.isEmpty()) {
            while(!this.streams.isEmpty()) {
                this.buffer.put((byte[])this.streams.poll());
            }
        }

        this.buffer.flip();
        if(this.buffer.remaining() > 4) {
            boolean next;
            do {
                next = false;
                int p = this.buffer.position();
                byte hflag = this.buffer.get();
                if(hflag == 36) {
                    this.buffer.get();
                    short len = this.buffer.getShort();
                    next = len <= this.buffer.remaining();
                    if(next) {
                        byte[] cc = new byte[len];
                        this.buffer.get(cc);
                        IoBuffer frame = IoBuffer.wrap(cc);
                        byte rpth2 = frame.get(1);
                        short seq = frame.getShort(2);
                        boolean m = (rpth2 & 128) == 128;
                        Log.e(Constant.LOG_TAG,"seq:[" + seq + "],end:[" + m + "]");
                        frame.position(12);
                        byte h1 = frame.get();
                        byte h2 = frame.get();
                        byte nal = (byte)(h1 & 31);
                        int flag = h2 & 224;
                        byte nal_fua = (byte)(h1 & 224 | h2 & 31);
                        if(nal == 28) {
                            frame.position(14);
                            if(flag == 128) {
                                this.frameBuffer.putInt(1);
                                this.frameBuffer.put(nal_fua);
                                this.frameBuffer.put(frame);
                            } else if(flag == 64) {
                                this.frameBuffer.put(frame);
                            } else {
                                this.frameBuffer.put(frame);
                            }
                        } else {
                            frame.position(12);
                            this.frameBuffer.putInt(1);
                            this.frameBuffer.put(frame);
                        }

                        if(m) {
                            this.frameBuffer.flip();
                            byte[] newFrame = new byte[this.frameBuffer.remaining()];
                            this.frameBuffer.get(newFrame);
                            this.rawStream.process(newFrame);
                            this.frameBuffer.clear();
                        }
                    } else {
                        this.buffer.position(p);
                    }
                }
            } while(next && this.buffer.remaining() > 4);

            this.buffer.compact();
        }

    }

    public void onVideoStream(byte[] stream) {
        this.streams.add(stream);
        this.waiteUp();
    }

    private synchronized void waiteUp() {
        this.notifyAll();
    }

    public void releaseResource() {
        if(this.status.compareAndSet(true, false)) {
            this.streams.clear();
            this.waiteUp();
        }

    }
}
