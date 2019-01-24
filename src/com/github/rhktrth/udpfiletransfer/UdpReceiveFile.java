/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class UdpReceiveFile extends Thread {
	private final static long METADATA_SEQUENCE_NUMBER = -1;
	private final static int RECV_BUFFER_SIZE = 10000;
	private final static byte[] HEADER_KEYWORD = "UFT1".getBytes(StandardCharsets.US_ASCII);

	private final InetSocketAddress localAddress;

	private Path outputFile;
	private Set<Long> notYetArrivedNumbers;
	private boolean writingFile;
	private DatagramChannel receiveChannel;

	public UdpReceiveFile(int portnum, Path path) {
		this.localAddress = new InetSocketAddress(portnum);

		this.outputFile = path;
		this.notYetArrivedNumbers = new HashSet<Long>();
		this.writingFile = false;
	}

	public void run() {
		int splitSize;
		long splitCount;
		ByteBuffer receiveBuffer = ByteBuffer.allocate(RECV_BUFFER_SIZE);

		try {
			this.receiveChannel = DatagramChannel.open();
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			this.receiveChannel.socket().bind(localAddress);
		} catch (SocketException e) {
			e.printStackTrace();
		}

		/* receive meta info */
		A: while (true) {
			receiveBuffer.clear();
			try {
				this.receiveChannel.receive(receiveBuffer);
			} catch (IOException e) {
				e.printStackTrace();
			}
			receiveBuffer.flip();

			byte[] headerBytes = new byte[HEADER_KEYWORD.length];
			if (receiveBuffer.remaining() < headerBytes.length) {
				System.out.println("too short data was received");
				continue;
			}
			receiveBuffer.get(headerBytes);
			if (!Arrays.equals(HEADER_KEYWORD, headerBytes)) {
				System.out.println("illegal data was received");
				continue;
			}

			long sequenceNumber = receiveBuffer.getLong();
			if (sequenceNumber == METADATA_SEQUENCE_NUMBER) {
				splitSize = receiveBuffer.getInt();
				splitCount = receiveBuffer.getLong();
				for (long i = 0; i < splitCount; i++) {
					this.notYetArrivedNumbers.add(i);
				}
				byte[] byteArray = new byte[receiveBuffer.remaining()];
				receiveBuffer.get(byteArray);
				String orgFileName = new String(byteArray);
				System.out.println("meta: " + orgFileName + " " + splitSize + " " + splitCount);
				break A;
			}
		}
		System.out.println("meta info was received");

		/* receive data and write file */
		this.writingFile = true;
		try {
			Files.deleteIfExists(this.outputFile);
			Files.createFile(this.outputFile);
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		System.out.println("receive file created: " + this.outputFile);

		try (FileChannel fileChannel = FileChannel.open(this.outputFile, StandardOpenOption.WRITE);) {
			while (!this.notYetArrivedNumbers.isEmpty()) {
				receiveBuffer.clear();
				try {
					this.receiveChannel.receive(receiveBuffer);
				} catch (IOException e) {
					e.printStackTrace();
				}
				receiveBuffer.flip();

				if (receiveBuffer.remaining() < HEADER_KEYWORD.length) {
					System.out.println("too short data was received");
					continue;
				}
				byte[] headerBytes = new byte[HEADER_KEYWORD.length];
				receiveBuffer.get(headerBytes);
				if (!Arrays.equals(HEADER_KEYWORD, headerBytes)) {
					System.out.println("illegal data was received");
					continue;
				}

				long sequenceNumber = receiveBuffer.getLong();
				if (sequenceNumber >= 0 && this.notYetArrivedNumbers.contains(sequenceNumber)) {
					byte[] data = new byte[receiveBuffer.remaining()];
					receiveBuffer.get(data);
					ByteBuffer databuf = ByteBuffer.wrap(data);
					int writtenSize;
					fileChannel.position(splitSize * sequenceNumber);
					do {
						writtenSize = fileChannel.write(databuf);
					} while (writtenSize != -1 && databuf.hasRemaining());
					this.notYetArrivedNumbers.remove(sequenceNumber);
				}
			}
		} catch (IOException e) {
			return;
		}

		System.out.println("all splited data were received and written");
		System.exit(0);
	}

	public boolean setOutputFile(Path path) {
		if (this.writingFile) {
			return false;
		} else {
			this.outputFile = path;
			return true;
		}
	}

	public void close() {
		if (this.receiveChannel != null) {
			try {
				this.receiveChannel.close();
			} catch (IOException e) {
			}
		}
	}

	public Set<Long> getNotYetArrivedNumbers() {
		return new HashSet<Long>(notYetArrivedNumbers);
	}
}
