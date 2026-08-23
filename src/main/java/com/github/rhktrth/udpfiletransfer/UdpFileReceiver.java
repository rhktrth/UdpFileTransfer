package com.github.rhktrth.udpfiletransfer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class UdpFileReceiver {
    private static final int MISSING_OUTPUT_LINE_LENGTH = 4096;
    private static final long MISSING_SEQUENCE_PAGE_SIZE = 1000L;

    private final InetSocketAddress localAddress;
    private final Path outputFile;
    private final Path partFile;
    private final long maxChunkCount;
    private final PrintStream output;
    private final Set<Long> receivedSequences = ConcurrentHashMap.newKeySet();

    private volatile boolean closed;
    private volatile long chunkCount = -1;
    private volatile long receivedSequenceCount;
    private DatagramChannel receiveChannel;

    UdpFileReceiver(int port, Path outputFile, long maxChunkCount, PrintStream output) {
        this.localAddress = new InetSocketAddress(port);
        this.outputFile = outputFile;
        this.partFile = Paths.get(outputFile.toString() + ".part");
        this.maxChunkCount = maxChunkCount;
        this.output = output;
    }

    void receive() throws IOException {
        try (DatagramChannel channel = DatagramChannel.open()) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                receiveChannel = channel;
            }
            try {
                channel.bind(localAddress);
                receiveFile(channel);
            } catch (IOException e) {
                if (!closed) {
                    throw e;
                }
            } finally {
                synchronized (this) {
                    receiveChannel = null;
                }
            }
        }
    }

    private void receiveFile(DatagramChannel channel) throws IOException {
        ByteBuffer packet = ByteBuffer.allocate(UdpWireFormat.MAX_PACKET_SIZE + 1);
        UdpWireFormat.Metadata metadata = receiveMetadata(channel, packet);
        chunkCount = metadata.chunkCount;

        try (FileChannel outputFileChannel = FileChannel.open(
                partFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            output.println("receiving: " + partFile);
            receiveData(channel, packet, outputFileChannel, metadata);
        }

        if (Files.size(partFile) != metadata.fileSizeBytes) {
            throw new IOException("received file size mismatch");
        }
        if (!FileHash.sha256Equals(partFile, metadata.sha256)) {
            throw new IOException("SHA-256 mismatch");
        }
        Files.move(partFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
        output.println("receive completed");
    }

    private UdpWireFormat.Metadata receiveMetadata(
            DatagramChannel channel, ByteBuffer packet) throws IOException {
        while (true) {
            if (!receivePacket(channel, packet)) {
                continue;
            }
            UdpWireFormat.Header header = readHeader(packet);
            if (header == null || header.sequenceNumber != UdpWireFormat.METADATA_SEQUENCE_NUMBER) {
                continue;
            }
            if (packet.remaining() < UdpWireFormat.METADATA_FIELDS_SIZE) {
                output.println("too short metadata was received");
                continue;
            }

            UdpWireFormat.Metadata metadata = UdpWireFormat.readMetadata(packet, header);
            if (metadata == null) {
                output.println("illegal metadata was received");
                continue;
            }
            if (metadata.chunkCount > maxChunkCount) {
                output.println("metadata exceeds max chunks");
                continue;
            }

            output.println("source file: " + sanitizeForDisplay(metadata.sourceFileName));
            return metadata;
        }
    }

    private void receiveData(
            DatagramChannel channel,
            ByteBuffer packet,
            FileChannel outputFileChannel,
            UdpWireFormat.Metadata metadata) throws IOException {
        while (receivedSequenceCount < metadata.chunkCount) {
            if (!receivePacket(channel, packet)) {
                continue;
            }
            UdpWireFormat.Header header = readHeader(packet);
            if (header == null) {
                continue;
            }

            long sequenceNumber = header.sequenceNumber;
            if (!UdpWireFormat.sameSession(header.sessionId, metadata.sessionId)
                    || sequenceNumber < 0
                    || sequenceNumber >= metadata.chunkCount
                    || receivedSequences.contains(sequenceNumber)) {
                continue;
            }

            int expectedPayloadLength = UdpWireFormat.expectedPayloadLength(metadata, sequenceNumber);
            if (packet.remaining() != expectedPayloadLength) {
                output.println("illegal data was received");
                continue;
            }

            outputFileChannel.position((long) metadata.chunkSizeBytes * sequenceNumber);
            while (packet.hasRemaining()) {
                outputFileChannel.write(packet);
            }
            receivedSequences.add(sequenceNumber);
            receivedSequenceCount++;
        }
    }

    private boolean receivePacket(DatagramChannel channel, ByteBuffer packet) throws IOException {
        packet.clear();
        channel.receive(packet);
        if (packet.position() > UdpWireFormat.MAX_PACKET_SIZE) {
            output.println("oversized datagram was received");
            packet.clear();
            return false;
        }
        packet.flip();
        return true;
    }

    private UdpWireFormat.Header readHeader(ByteBuffer packet) {
        if (packet.remaining() < UdpWireFormat.DATA_HEADER_SIZE) {
            output.println("too short data was received");
            return null;
        }
        UdpWireFormat.Header header = UdpWireFormat.readHeader(packet);
        if (header == null) {
            output.println("illegal data was received");
        }
        return header;
    }

    void printMissingSequences() {
        printMissingSequences(0);
    }

    void printMissingSequences(long startSequence) {
        long expectedChunkCount = chunkCount;
        if (expectedChunkCount < 0) {
            output.println("no metadata");
            return;
        }
        if (receivedSequenceCount >= expectedChunkCount) {
            output.println("no missing data");
            return;
        }
        if (startSequence < 0 || startSequence >= expectedChunkCount) {
            throw new IllegalArgumentException(
                    "missing start must be between 0 and " + (expectedChunkCount - 1));
        }

        long remaining = expectedChunkCount - startSequence;
        long endSequence = remaining > MISSING_SEQUENCE_PAGE_SIZE
                ? startSequence + MISSING_SEQUENCE_PAGE_SIZE
                : expectedChunkCount;

        boolean foundMissing = false;
        StringBuilder line = new StringBuilder();
        for (long sequenceNumber = startSequence; sequenceNumber < endSequence; sequenceNumber++) {
            if (receivedSequences.contains(sequenceNumber)) {
                continue;
            }
            foundMissing = true;
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(sequenceNumber);
            if (line.length() >= MISSING_OUTPUT_LINE_LENGTH) {
                output.println(line);
                line.setLength(0);
            }
        }
        if (line.length() > 0) {
            output.println(line);
        }
        if (!foundMissing) {
            output.println("no missing data in " + startSequence + ".." + (endSequence - 1));
        }
        if (endSequence < expectedChunkCount) {
            output.println("next: missing " + endSequence);
        }
    }

    void close() {
        closed = true;
        DatagramChannel channel;
        synchronized (this) {
            channel = receiveChannel;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // The receiver is already stopping.
            }
        }
    }

    private static String sanitizeForDisplay(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT) {
                result.append("\\u");
                appendHex4(result, ch);
            } else {
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static void appendHex4(StringBuilder result, char value) {
        final char[] hex = "0123456789abcdef".toCharArray();
        result.append(hex[(value >>> 12) & 0x0f]);
        result.append(hex[(value >>> 8) & 0x0f]);
        result.append(hex[(value >>> 4) & 0x0f]);
        result.append(hex[value & 0x0f]);
    }
}
