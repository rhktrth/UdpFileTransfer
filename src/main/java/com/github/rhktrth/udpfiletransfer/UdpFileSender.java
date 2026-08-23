package com.github.rhktrth.udpfiletransfer;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;

final class UdpFileSender implements Closeable {
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();

    private final Path inputFile;
    private final UdpWireFormat.Metadata metadata;
    private final int sendIntervalMillis;
    private final InetSocketAddress remoteAddress;
    private final FileChannel inputFileChannel;
    private final DatagramChannel sendChannel;
    private final ByteBuffer dataPacket;

    UdpFileSender(Path inputFile, int chunkSizeBytes, String host, int port,
            int sendIntervalMillis) throws IOException {
        long fileSize = Files.size(inputFile);
        Path fileName = inputFile.getFileName();
        if (fileName == null) {
            throw new IOException("input file must have a file name");
        }

        byte[] sha256 = FileHash.sha256(inputFile);
        if (Files.size(inputFile) != fileSize) {
            throw new IOException("input file size changed during initialization");
        }

        byte[] sessionId = new byte[UdpWireFormat.SESSION_ID_SIZE];
        SESSION_RANDOM.nextBytes(sessionId);

        this.inputFile = inputFile;
        try {
            this.metadata = UdpWireFormat.createMetadata(
                    chunkSizeBytes, fileSize, fileName.toString(), sessionId, sha256);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        this.sendIntervalMillis = sendIntervalMillis;
        this.remoteAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        this.dataPacket = ByteBuffer.allocate(UdpWireFormat.DATA_HEADER_SIZE + chunkSizeBytes);

        FileChannel openedFile = FileChannel.open(inputFile, StandardOpenOption.READ);
        DatagramChannel openedChannel;
        try {
            openedChannel = DatagramChannel.open();
        } catch (IOException e) {
            try {
                openedFile.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        this.inputFileChannel = openedFile;
        this.sendChannel = openedChannel;
    }

    void sendAll() throws IOException {
        sendMetadata();
        sendMetadata();
        for (long sequenceNumber = 0; sequenceNumber < metadata.chunkCount; sequenceNumber++) {
            sendData(sequenceNumber);
        }
    }

    void sendMetadata() throws IOException {
        ensureInputFileSizeUnchanged();
        send(UdpWireFormat.writeMetadata(metadata));
    }

    void sendData(long sequenceNumber) throws IOException {
        int expectedPayloadLength = UdpWireFormat.expectedPayloadLength(metadata, sequenceNumber);
        if (expectedPayloadLength < 0) {
            throw new IllegalArgumentException("no such chunk number");
        }
        ensureInputFileSizeUnchanged();

        ByteBuffer packet = dataPacket;
        packet.clear();
        UdpWireFormat.writeHeader(packet, sequenceNumber, metadata.sessionId);
        packet.limit(UdpWireFormat.DATA_HEADER_SIZE + expectedPayloadLength);
        long filePosition = (long) metadata.chunkSizeBytes * sequenceNumber;

        while (packet.hasRemaining()) {
            int read = inputFileChannel.read(packet, filePosition);
            if (read == -1) {
                throw new IOException("failed to read chunk " + sequenceNumber);
            }
            filePosition += read;
        }
        ensureInputFileSizeUnchanged();
        packet.flip();
        send(packet);
    }

    private void ensureInputFileSizeUnchanged() throws IOException {
        if (Files.size(inputFile) != metadata.fileSizeBytes) {
            throw new IOException("input file size changed during transfer");
        }
    }

    private void send(ByteBuffer packet) throws IOException {
        sendChannel.send(packet, remoteAddress);
        if (sendIntervalMillis > 0) {
            try {
                Thread.sleep(sendIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("send interrupted", e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try (FileChannel input = inputFileChannel; DatagramChannel channel = sendChannel) {
            // try-with-resources preserves both close failures through suppressed exceptions.
        }
    }
}
