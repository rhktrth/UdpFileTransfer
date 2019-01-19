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
	private int splitCount;
	private InetSocketAddress remoteAddress;
	private DatagramSocket sendSocket;
	private int interval;
	private RandomAccessFile raf;

	public UdpSendFile(File fileobj, int dsize, String ip, int port, int it) {
		inputFile = fileobj;
		splitSize = dsize;
		remoteAddress = new InetSocketAddress(ip, port);
		interval = it;
	}

	public void run() {
		splitCount = (int) (inputFile.length() / splitSize) + 1;

		try {
			raf = new RandomAccessFile(this.inputFile, "r");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}

		try {
			sendSocket = new DatagramSocket();
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
		byte[] bs = new byte[12];
		bs[3] = (byte) (0xff & (SEQ_NUM_META));
		bs[2] = (byte) (0xff & (SEQ_NUM_META >>> 8));
		bs[1] = (byte) (0xff & (SEQ_NUM_META >>> 16));
		bs[0] = (byte) (0xff & (SEQ_NUM_META >>> 24));
		bs[7] = (byte) (0xff & (splitSize));
		bs[6] = (byte) (0xff & (splitSize >>> 8));
		bs[5] = (byte) (0xff & (splitSize >>> 16));
		bs[4] = (byte) (0xff & (splitSize >>> 24));
		bs[11] = (byte) (0xff & (splitCount));
		bs[10] = (byte) (0xff & (splitCount >>> 8));
		bs[9] = (byte) (0xff & (splitCount >>> 16));
		bs[8] = (byte) (0xff & (splitCount >>> 24));

		System.out.println("send metainfo");
		sendUdpPacket(mergeByteArray(bs, inputFile.getName().getBytes()));
	}

	public void sendSpecificData(int n) {
		if (n < 0 || splitCount - 1 < n) {
			System.out.println("no such a split number");
			return;
		}

		byte[] bs = new byte[4];
		bs[3] = (byte) (0xff & (n));
		bs[2] = (byte) (0xff & (n >>> 8));
		bs[1] = (byte) (0xff & (n >>> 16));
		bs[0] = (byte) (0xff & (n >>> 24));

		byte[] buf = new byte[splitSize];
		try {
			raf.seek(splitSize * n);
			raf.read(buf);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		System.out.println("send " + n);
		sendUdpPacket(mergeByteArray(bs, buf));
	}

	public void sendMetaInfoAndAllData() {
		sendMetaInfo();
		sendMetaInfo(); // send two times, just in case

		for (int i = 0; i < splitCount; i++) {
			sendSpecificData(i);
		}
	}

	private void sendUdpPacket(byte[] sendBuffer) {
		DatagramPacket sendPacket;
		try {
			sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, remoteAddress);
			sendSocket.send(sendPacket);
			Thread.sleep(interval);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private static byte[] mergeByteArray(byte[] a, byte[] b) {
		byte[] mergedArray = new byte[a.length + b.length];
		System.arraycopy(a, 0, mergedArray, 0, a.length);
		System.arraycopy(b, 0, mergedArray, a.length, b.length);
		return mergedArray;
	}
}
