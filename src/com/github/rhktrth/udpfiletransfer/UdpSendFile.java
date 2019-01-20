/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

public class UdpSendFile extends Thread {
	private final static int SEQ_NUM_META = -1;
	private final static byte[] HEADER_WORD_BYTES = "UFT1".getBytes(StandardCharsets.US_ASCII);

	private File inputFile;
	private int splitSize;
	private InetSocketAddress remoteAddress;
	private int interval;

	private int splitCount;
	private RandomAccessFile raf;
	private DatagramSocket sendSocket;

	public UdpSendFile(File fileobj, int dsize, String ip, int port, int it) {
		this.inputFile = fileobj;
		this.splitSize = dsize;
		this.remoteAddress = new InetSocketAddress(ip, port);
		this.interval = it;
	}

	public void run() {
		this.splitCount = (int) (inputFile.length() / splitSize) + 1;

		try {
			this.raf = new RandomAccessFile(this.inputFile, "r");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}

		try {
			this.sendSocket = new DatagramSocket();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	public void setInterval(int in) {
		interval = in;
	}

	public void close() {
		try {
			raf.close();
		} catch (IOException e) {
		}
		sendSocket.close();
	}

	public void sendMetaInfo() {
		byte[] fn = inputFile.getName().getBytes();

		byte[] bs = new byte[HEADER_WORD_BYTES.length + 8 + 4 + 8 + fn.length];
		System.arraycopy(HEADER_WORD_BYTES, 0, bs, 0, HEADER_WORD_BYTES.length);
		System.arraycopy(LongAsBytes(SEQ_NUM_META), 0, bs, HEADER_WORD_BYTES.length, 8);
		System.arraycopy(IntAsBytes(splitSize), 0, bs, HEADER_WORD_BYTES.length + 8, 4);
		System.arraycopy(LongAsBytes(splitCount), 0, bs, HEADER_WORD_BYTES.length + 8 + 4, 8);
		System.arraycopy(fn, 0, bs, HEADER_WORD_BYTES.length + 8 + 4 + 8, fn.length);

		System.out.println("send metainfo");
		sendUdpPacket(bs);
	}

	public void sendSpecificData(long n) {
		if (n < 0 || splitCount - 1 < n) {
			System.out.println("no such a split number");
			return;
		}

		byte[] data = new byte[splitSize];
		int readLength;
		try {
			this.raf.seek(splitSize * n);
			readLength = this.raf.read(data);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		if (readLength < 0) {
			System.out.println("can't read " + n);
			return;
		}

		byte[] point = LongAsBytes(n);
		byte[] mergedArray = new byte[HEADER_WORD_BYTES.length + point.length + readLength];
		System.arraycopy(HEADER_WORD_BYTES, 0, mergedArray, 0, HEADER_WORD_BYTES.length);
		System.arraycopy(point, 0, mergedArray, HEADER_WORD_BYTES.length, point.length);
		System.arraycopy(data, 0, mergedArray, HEADER_WORD_BYTES.length + point.length, readLength);

		sendUdpPacket(mergedArray);
		System.out.println("sent " + n);
	}

	public void sendMetaInfoAndAllData() {
		sendMetaInfo();
		sendMetaInfo(); // send two times, just in case

		for (long i = 0; i < splitCount; i++) {
			sendSpecificData(i);
		}
	}

	private void sendUdpPacket(byte[] sendBuffer) {
		DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, remoteAddress);
		try {
			sendSocket.send(sendPacket);
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		try {
			Thread.sleep(interval);
		} catch (InterruptedException e) {
			e.printStackTrace();
			return;
		}
	}

	private static final byte[] IntAsBytes(int n) {
		byte[] bs = new byte[4];
		bs[0] = (byte) ((n & 0xff000000) >>> 24);
		bs[1] = (byte) ((n & 0x00ff0000) >>> 16);
		bs[2] = (byte) ((n & 0x0000ff00) >>> 8);
		bs[3] = (byte) ((n & 0x000000ff));

		return bs;
	}

	private static final byte[] LongAsBytes(long n) {
		byte[] bs = new byte[8];
		bs[0] = (byte) ((n & 0xff00000000000000L) >>> 56);
		bs[1] = (byte) ((n & 0x00ff000000000000L) >>> 48);
		bs[2] = (byte) ((n & 0x0000ff0000000000L) >>> 40);
		bs[3] = (byte) ((n & 0x000000ff00000000L) >>> 32);
		bs[4] = (byte) ((n & 0x00000000ff000000L) >>> 24);
		bs[5] = (byte) ((n & 0x0000000000ff0000L) >>> 16);
		bs[6] = (byte) ((n & 0x000000000000ff00L) >>> 8);
		bs[7] = (byte) ((n & 0x00000000000000ffL));

		return bs;
	}
}
