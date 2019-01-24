/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class UdpSendFile extends Thread {
	private final static int METADATA_SEQUENCE_NUMBER = -1;
	private final static int SEND_BUFFER_SIZE = 10000;
	private final static byte[] HEADER_KEYWORD = "UFT1".getBytes(StandardCharsets.US_ASCII);

	private final Path inputFile;
	private final int splitSize;
	private final InetSocketAddress remoteAddress;

	private int interval;
	private long splitCount;
	private FileChannel fileChannel;
	private DatagramChannel sendChannel;

	public UdpSendFile(Path path, int dsize, String ip, int port, int it) {
		this.inputFile = path;
		this.splitSize = dsize;
		this.remoteAddress = new InetSocketAddress(ip, port);

		this.interval = it;
	}

	public void run() {
		try {
			this.splitCount = (Files.size(inputFile) + splitSize - 1) / splitSize;
			this.fileChannel = FileChannel.open(this.inputFile, StandardOpenOption.READ);
			this.sendChannel = DatagramChannel.open();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
	}

	public void setInterval(int in) {
		this.interval = in;
	}

	public long getSplitCount() {
		return this.splitCount;
	}

	public void close() {
		try {
			if (this.fileChannel != null) {
				this.fileChannel.close();
			}
			if (this.sendChannel != null) {
				this.sendChannel.close();
			}
		} catch (IOException e) {
		}
	}

	public void sendMetaInfo() {
		ByteBuffer merged = ByteBuffer.allocate(SEND_BUFFER_SIZE);
		merged.put(HEADER_KEYWORD);
		merged.putLong(METADATA_SEQUENCE_NUMBER);
		merged.putInt(splitSize);
		merged.putLong(splitCount);
		merged.put(inputFile.getFileName().toString().getBytes());
		merged.flip();

		sendUdpPacket(merged);
	}

	public void sendSpecificData(long n) {
		if (n < 0 || splitCount - 1 < n) {
			throw new IllegalArgumentException("no such a split number");
		}

		ByteBuffer data = ByteBuffer.allocate(splitSize);
		int readSize;
		try {
			this.fileChannel.position(splitSize * n);
			do {
				readSize = this.fileChannel.read(data);
			} while (readSize != -1 && data.hasRemaining());

		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		if (data.position() == 0) {
			throw new IllegalArgumentException("can't read " + n);
		}
		data.flip();

		ByteBuffer merged = ByteBuffer.allocate(SEND_BUFFER_SIZE);
		merged.put(HEADER_KEYWORD);
		merged.putLong(n);
		merged.put(data);
		merged.flip();

		sendUdpPacket(merged);
	}

	private void sendUdpPacket(ByteBuffer targetBuffer) {
		try {
			sendChannel.send(targetBuffer, remoteAddress);
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
}
