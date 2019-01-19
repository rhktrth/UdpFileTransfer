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

public class UdpSendFile extends Thread {
	private static int SEQ_NUM_META = -1;

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

		byte[] bs = new byte[12 + fn.length];
		System.arraycopy(IntAsBytes(SEQ_NUM_META), 0, bs, 0, 4);
		System.arraycopy(IntAsBytes(splitSize), 0, bs, 4, 4);
		System.arraycopy(IntAsBytes(splitCount), 0, bs, 8, 4);
		System.arraycopy(fn, 0, bs, 12, fn.length);

		System.out.println("send metainfo");
		sendUdpPacket(bs);
	}

	public void sendSpecificData(int n) {
		if (n < 0 || splitCount - 1 < n) {
			System.out.println("no such a split number");
			return;
		}

		byte[] ln = IntAsBytes(n);

		byte[] data = new byte[splitSize];
		try {
			this.raf.seek(splitSize * n);
			this.raf.read(data);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		byte[] mergedArray = new byte[ln.length + data.length];
		System.arraycopy(ln, 0, mergedArray, 0, ln.length);
		System.arraycopy(data, 0, mergedArray, ln.length, data.length);

		sendUdpPacket(mergedArray);
		System.out.println("sent " + n);
	}

	public void sendMetaInfoAndAllData() {
		sendMetaInfo();
		sendMetaInfo(); // send two times, just in case

		for (int i = 0; i < splitCount; i++) {
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
		bs[3] = (byte) (n & 0x000000ff);

		return bs;
	}
}
