/*
 * Copyright (C) 2011-2013 GUIGUI Simon, fyhertz@gmail.com
 * 
 * This file is part of Spydroid (http://code.google.com/p/spydroid-ipcamera/)
 * 
 * Spydroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package tv.inhand.streaming.rtmp;

import java.io.IOException;
import java.nio.ByteOrder;

import android.util.Log;
import org.apache.mina.core.buffer.IoBuffer;
import org.red5.io.IoConstants;
import org.red5.io.flv.Tag;
import org.red5.server.messaging.IMessage;

/**
 * 
 *   RFC 3984.
 *   
 *   H.264 streaming over RTP.
 *   
 *   Must be fed with an InputStream containing H.264 NAL units preceded by their length (4 bytes).
 *   The stream must start with mpeg4 or 3gpp header, it will be skipped.
 *   
 */
public class H264Packetizer extends BasePacketizer implements Runnable {

	public final static String TAG = "H264Packetizer";
	private final static int MAX_VALID_NALU_LENGTH = 100000;
	private final static int MAXPACKETSIZE = 1400;
	private final static int flagSize = 5;


	private Thread t = null;
	private int naluLength = 0;
	private byte[] sps = null, pps = null;
	private boolean sentConfig = false;
	
	public H264Packetizer() throws IOException {
		super();
	}

	public void start() throws IOException {
		if (t == null) {
			t = new Thread(this);
			t.start();
		}
	}

	public void stop() {
		if (t != null) {
			t.interrupt();
			try {
				t.join(1000);
			} catch (InterruptedException e) {}
			t = null;
		}
	}

	public void setStreamParameters(byte[] pps, byte[] sps) {
		this.pps = pps;
		this.sps = sps;
		Log.i(TAG, "PPS:" + printBuffer(pps, 0, pps.length));
		Log.i(TAG, "SPS:" + printBuffer(sps, 0, sps.length));
	}

	public void run() {
		long duration = 0, delta2 = 0;
		Log.i(TAG,"H264 packetizer started !");

		// This will skip the MPEG4 header if this step fails we can't stream anything :(
		try {
			byte buffer[] = new byte[4];
			// Skip all atoms preceding mdat atom
			while (!Thread.interrupted()) {
				while (is.read() != 'm');
				is.read(buffer,0,3);
				if (buffer[0] == 'd' && buffer[1] == 'a' && buffer[2] == 't') {
					Log.i(TAG, "Skip MP4 header");
					break;
				}
			}
		} catch (IOException e) {
			Log.e(TAG,"Couldn't skip MP4 header :/", e);
			return;
		}

		try {
			while (!Thread.interrupted()) {
				try {
					processNalu(fillNalu());
				} catch (IOException e) {
					Log.e(TAG, "run IOException", e);
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "run Exception", e);
		}

		Log.i(TAG, "H264 packetizer stopped !");

	}

	private byte[] fillNalu()  throws IOException{
		byte[] header = new byte[4];

		// Read NAL unit length (4 bytes) and NAL unit header (1 byte)
		fill(header, 0, 4);
		naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;

		if (naluLength>MAX_VALID_NALU_LENGTH || naluLength<0)
			resync();

		byte[] nalu = new byte[naluLength + header.length];
		System.arraycopy(header, 0, nalu, 0, header.length);

		fill(nalu, header.length, naluLength);	// We had fill the first byte
		return nalu;
	}

	private void processNalu(byte[] nalu) throws IOException {
		// TODO: 还需要处理分片的情况

		int nalType = nalu[4] & 0x1F;
		Log.i(TAG, "NAL type:" + nalType);
		if (nalType == 5) {
			if (sentConfig) {
				Log.i(TAG, "Already sent configuration");
			}
			else {
				Log.i(TAG, "Send configuration one time");
				byte[] conf = configurationFromSpsAndPps();
				writeVideoFrame(conf, true, true);
				sentConfig = true;
			}
		}
		writeVideoFrame(nalu, false, (nalType == 5));
	}

	private void writeVideoFrame(byte[] frameData, boolean configuration, boolean keyframe) throws IOException {
		byte flag = IoConstants.FLAG_CODEC_H264;
		if (keyframe) {
			flag |= (IoConstants.FLAG_FRAMETYPE_KEYFRAME << 4);
		}else {
			flag |= (IoConstants.FLAG_FRAMETYPE_INTERFRAME << 4);
		}

		long timestamp = System.currentTimeMillis();
		if (timeBase == 0) {
			timeBase = timestamp;
		}
		int currentTime = (int) (timestamp - timeBase);
		Tag tag = new Tag(IoConstants.TYPE_VIDEO, currentTime, frameData.length+flagSize, null, prevSize);
		prevSize = frameData.length;

		IoBuffer body = IoBuffer.allocate(tag.getBodySize());

		body.setAutoExpand(true);
		body.put(flag);
		body.put(configuration?(byte)0:(byte)1);

		int dts = 0; 	// TODO: 使用正确的DTS和PTS
		int pts = 0;	// TODO:
		int delay = dts - pts;
		// TODO: add 'delay' value. Use 0 for test only
//		body.put(delay, 24bit);
		body.put((byte)0);
		body.put((byte)0);
		body.put((byte)0);

		body.put(frameData);

		body.flip();
		body.limit(tag.getBodySize());
		tag.setBody(body);

		byte[] data = body.array();
		Log.i(TAG, "\nvideo body:" + tag.getBodySize() + ":\n" + printBuffer(data, 0, tag.getBodySize()<64? tag.getBodySize() : 64));

		IMessage msg = makeMessageFromTag(tag);
		send(msg);
	}

	private byte[] configurationFromSpsAndPps() {
		if (sps == null || pps == null) {
			Log.e(TAG, "Invalid sps or pps");
			throw new IllegalStateException("SPS|PPS missed");
		}

		IoBuffer conf = IoBuffer.allocate(9);
		conf.setAutoExpand(true);
		conf.put((byte)1);	// version
		conf.put(sps[1]);	// profile
		conf.put(sps[2]);	// compat
		conf.put(sps[3]);	// level
		conf.put((byte)0xff);	// 6 bits reserved + 2 bits nal size length - 1 (11)
		conf.put((byte)0xe1); 	// 3 bits reserved + 5 bits number of sps (00001)

		{
			IoBuffer beBuf = IoBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
			beBuf.putShort((short) sps.length);
			conf.put(beBuf.array());
		}
		conf.put(sps);

		conf.put((byte)1);
		{
			IoBuffer beBuf = IoBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
			beBuf.putShort((short) pps.length);
			conf.put(beBuf.array());
		}
		conf.put(pps);

		return conf.array();
	}

	private int fill(byte[] buffer, int offset,int length) throws IOException {
		int sum = 0, len;

		while (sum<length) {
			len = is.read(buffer, offset+sum, length-sum);
			if (len<0) {
				throw new IOException("End of stream");
			}
			else sum+=len;
		}
		return sum;
	}

	private void resync() throws IOException {
		byte[] header = new byte[5];
		int type;

		Log.e(TAG,"Packetizer out of sync ! Let's try to fix that...");
		
		while (true) {

			header[0] = header[1];
			header[1] = header[2];
			header[2] = header[3];
			header[3] = header[4];
			header[4] = (byte) is.read();

			type = header[4]&0x1F;

			if (type == 5 || type == 1) {
				naluLength = header[3]&0xFF | (header[2]&0xFF)<<8 | (header[1]&0xFF)<<16 | (header[0]&0xFF)<<24;
				if (naluLength>0 && naluLength<MAX_VALID_NALU_LENGTH) {
					Log.e(TAG,"A NAL unit may have been found in the bit stream !");
					break;
				}
			}
		}
	}
}