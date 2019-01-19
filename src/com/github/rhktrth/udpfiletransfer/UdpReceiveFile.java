/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class UdpReceiveFile extends Thread {
	private static int SEQ_NUM_META = -1;
	private static int BUFFUER_SIZE_RECV_META = 1000;

	private DatagramSocket receiveSocket;
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
		byte[] buf = new byte[BUFFUER_SIZE_RECV_META];
		int splitSize;
		int splitCount;

		A: while (true) {
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			try {
				this.receiveSocket.receive(packet);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}

			byte[] packetData = packet.getData();
			int effectiveLength = packet.getLength();

			int seqnum = bytesAsInt(packetData, 0, 4);
			if (seqnum == SEQ_NUM_META) {
				splitSize = bytesAsInt(packetData, 4, 4);
				splitCount = bytesAsInt(packetData, 8, 4);
				for (int i = 0; i < splitCount; i++) {
					this.missingNumbers.add(i);
				}
				String orgFileName = new String(Arrays.copyOfRange(packetData, 12, effectiveLength));
				System.out.println("meta: " + orgFileName + " " + splitSize + " " + splitCount);
				break A;
			}
		}
		System.out.println("meta info was received");

		try {
			Files.deleteIfExists(Paths.get(outputFile.getPath()));
			outputFile.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		System.out.println("receive file created: " + outputFile);

		try (RandomAccessFile raf = new RandomAccessFile(this.outputFile, "rw");) {
			while (!this.missingNumbers.isEmpty()) {
				byte[] buf2 = new byte[4 + splitSize];
				DatagramPacket packet = new DatagramPacket(buf2, buf2.length);
				try {
					this.receiveSocket.receive(packet);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}

				byte[] packetData = packet.getData();
				int effectiveLength = packet.getLength();

				int seqnum = bytesAsInt(packetData, 0, 4);
				if (seqnum >= 0 && this.missingNumbers.contains(seqnum)) {
					byte[] d = Arrays.copyOfRange(packetData, 4, effectiveLength);
					raf.seek(splitSize * seqnum);
					raf.write(d);
					this.missingNumbers.remove(seqnum);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		System.out.println("all splited data were received and written");
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
