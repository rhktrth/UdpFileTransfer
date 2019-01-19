/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class UdpReceiveFile extends Thread {
	private static int SEQ_NUM_META = -1;
	private static int BUFFUER_SIZE_RECV_META = 1000;

	private byte[][] splitArray;
	private DatagramSocket receiveSocket;
	private int splitSize;
	private int splitCount;
	private File outputFile;
	private Set<Integer> missingNumbers;

	public UdpReceiveFile(int portnum, File fileobj) {
		setOutputFile(fileobj);
		try {
			receiveSocket = new DatagramSocket(portnum);
		} catch (SocketException e) {
			e.printStackTrace();
			System.exit(8);
		}
		missingNumbers = new HashSet<Integer>();
	}

	public void setOutputFile(File fileobj) {
		outputFile = fileobj;
	}

	public void run() {
		try {
			receiveMetaInfo();
		} catch (IOException e) {
			return;
		}
		System.out.println("meta info was received");

		try {
			receiveFile();
		} catch (IOException e) {
			return;
		}
		System.out.println("all splited data was received");

		try {
			writeFile();
		} catch (IOException e) {
			return;
		}
		System.out.println("file written");

		System.exit(0);
	}

	public void receiveMetaInfo() throws IOException {
		boolean waitingPacket = true;
		byte[] buf = new byte[BUFFUER_SIZE_RECV_META];

		while (waitingPacket) {
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			receiveSocket.receive(packet);

			byte[] packetData = packet.getData();
			int effectiveLength = packet.getLength();

			int seqnum = bytesAsInt(packetData, 0, 4);
			if (seqnum == SEQ_NUM_META) {
				splitSize = bytesAsInt(packetData, 4, 4);
				splitCount = bytesAsInt(packetData, 8, 4);
				splitArray = new byte[splitCount][];
				for (int i = 0; i < splitCount; i++) {
					missingNumbers.add(i);
				}
				waitingPacket = false;
				String orgFileName = new String(Arrays.copyOfRange(packetData, 12, effectiveLength));
				System.out.println("meta: " + orgFileName + " " + splitSize + " " + splitCount);
			}
		}
	}

	public void receiveFile() throws IOException {
		boolean waitingPacket = true;
		byte[] buf = new byte[4 + splitSize];

		while (waitingPacket && !missingNumbers.isEmpty()) {
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			receiveSocket.receive(packet);

			byte[] packetData = packet.getData();
			int effectiveLength = packet.getLength();

			int seqnum = bytesAsInt(packetData, 0, 4);
			if (seqnum >= 0) {
				splitArray[seqnum] = Arrays.copyOfRange(packetData, 4, effectiveLength);
				missingNumbers.remove(seqnum);
			}
		}
	}

	private void writeFile() throws IOException {
		FileOutputStream fos = new FileOutputStream(outputFile);
		BufferedOutputStream bos = new BufferedOutputStream(fos);
		for (byte[] b : splitArray) {
			bos.write(b, 0, b.length);
		}
		bos.flush();
		bos.close();
	}

	public void printMissingNumbers() {
		StringBuilder sb = new StringBuilder();
		if (missingNumbers.isEmpty()) {
			sb.append("no meta infomation");
		} else {
			for (Integer i : missingNumbers) {
				sb.append(i);
				sb.append(", ");
			}
			sb.append("EOF");
		}
		System.out.println(sb.toString());
	}

	public void close() {
		if (receiveSocket != null) {
			receiveSocket.close();
		}
	}

	public static final int bytesAsInt(byte[] byteArr, int offset, int length) {
		if (length > 4) {
			throw new IllegalArgumentException("Only 4 or fewer bytes can fit into an int");
		}
		int val = 0;
		int last = offset + length;
		for (int i = offset; i < last; i++) {
			val <<= 8;
			val |= byteArr[i] & 0xff;
		}
		return val;
	}
}
