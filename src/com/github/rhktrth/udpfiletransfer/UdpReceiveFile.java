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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class UdpReceiveFile extends Thread {
	private final static long SEQ_NUM_META = -1;
	private final static int RECV_BUFFER_SIZE = 10000;
	private final static String HEADER_WORD = "UFT1";

	private File outputFile;
	int portNumber;

	private Set<Long> notYetArrivedNumbers;
	private DatagramSocket receiveSocket;
	private boolean writingFile;

	public UdpReceiveFile(int portnum, File fileobj) {
		setOutputFile(fileobj);
		this.portNumber = portnum;
		this.notYetArrivedNumbers = new HashSet<Long>();
		this.writingFile = false;
	}

	public void run() {
		int splitSize;
		long splitCount;

		try {
			this.receiveSocket = new DatagramSocket(portNumber);
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		}
		DatagramPacket packet = new DatagramPacket(new byte[RECV_BUFFER_SIZE], RECV_BUFFER_SIZE);

		/* receive meta info */
		A: while (true) {
			try {
				this.receiveSocket.receive(packet);
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}

			byte[] packetData = packet.getData();
			int receivedSize = packet.getLength();

			String headerWord = new String(Arrays.copyOf(packetData, 4), StandardCharsets.US_ASCII);
			if (!HEADER_WORD.equals(headerWord)) {
				System.out.println("illegal data was received");
				continue;
			}
			long seqnum = bytesAsLong(packetData, 4, 8);
			if (seqnum == SEQ_NUM_META) {
				splitSize = bytesAsInt(packetData, 12, 4);
				splitCount = bytesAsLong(packetData, 16, 8);
				for (long i = 0; i < splitCount; i++) {
					this.notYetArrivedNumbers.add(i);
				}
				String orgFileName = new String(Arrays.copyOfRange(packetData, 24, receivedSize));
				System.out.println("meta: " + orgFileName + " " + splitSize + " " + splitCount);
				break A;
			}
		}
		System.out.println("meta info was received");

		/* receive data and write file */
		writingFile = true;
		try {
			Files.deleteIfExists(Paths.get(outputFile.getPath()));
			outputFile.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		System.out.println("receive file created: " + outputFile);

		try (RandomAccessFile raf = new RandomAccessFile(this.outputFile, "rw");) {
			while (!this.notYetArrivedNumbers.isEmpty()) {
				try {
					this.receiveSocket.receive(packet);
				} catch (IOException e) {
					return;
				}

				byte[] packetData = packet.getData();
				int receivedSize = packet.getLength();

				String headerWord = new String(Arrays.copyOf(packetData, 4), StandardCharsets.US_ASCII);
				if (!HEADER_WORD.equals(headerWord)) {
					System.out.println("illegal data was received");
					continue;
				}
				long seqnum = bytesAsLong(packetData, 4, 8);
				if (seqnum >= 0 && this.notYetArrivedNumbers.contains(seqnum)) {
					byte[] d = Arrays.copyOfRange(packetData, 12, receivedSize);
					raf.seek(splitSize * seqnum);
					raf.write(d);
					this.notYetArrivedNumbers.remove(seqnum);
				}
			}
		} catch (IOException e) {
			return;
		}

		System.out.println("all splited data were received and written");
		System.exit(0);
	}

	public boolean setOutputFile(File fileobj) {
		if (writingFile) {
			return false;
		} else {
			outputFile = fileobj;
			return true;
		}
	}

	public void close() {
		if (receiveSocket != null) {
			receiveSocket.close();
		}
	}

	public void printMissingNumbers() {
		StringBuilder sb = new StringBuilder();
		if (this.notYetArrivedNumbers.isEmpty()) {
			sb.append("no meta infomation");
		} else {
			for (Long i : this.notYetArrivedNumbers) {
				sb.append(i);
				sb.append(", ");
			}
			sb.append("EOF");
		}
		System.out.println(sb);
	}

	private static final int bytesAsInt(byte[] byteArr, int offset, int length) {
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

	private static final long bytesAsLong(byte[] byteArr, int offset, int length) {
		if (length > 8) {
			throw new IllegalArgumentException("Only 8 or fewer bytes can fit into an int");
		}
		long val = 0;
		int last = offset + length;
		for (int i = offset; i < last; i++) {
			val <<= 8;
			val |= byteArr[i] & 0xff;
		}
		return val;
	}
}
