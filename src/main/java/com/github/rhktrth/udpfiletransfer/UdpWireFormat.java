package com.github.rhktrth.udpfiletransfer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class UdpWireFormat {
    static final int MAX_PACKET_SIZE = 10000;
    static final long METADATA_SEQUENCE_NUMBER = -1L;
    static final int SESSION_ID_SIZE = 16;
    static final int SHA256_SIZE = 32;
    static final int MAX_METADATA_FILE_NAME_SIZE = 1024;

    private static final byte[] MAGIC = "UFT1".getBytes(StandardCharsets.US_ASCII);
    static final int DATA_HEADER_SIZE = MAGIC.length + Long.BYTES + SESSION_ID_SIZE;
    static final int METADATA_FIELDS_SIZE = Integer.BYTES
            + Long.BYTES
            + Long.BYTES
            + Integer.BYTES
            + SHA256_SIZE;
    static final int METADATA_HEADER_SIZE = DATA_HEADER_SIZE + METADATA_FIELDS_SIZE;
    static final int MAX_DATA_PAYLOAD_SIZE = MAX_PACKET_SIZE - DATA_HEADER_SIZE;

    private UdpWireFormat() {
    }

    static void writeHeader(ByteBuffer packet, long sequenceNumber, byte[] sessionId) {
        requireLength(sessionId, SESSION_ID_SIZE, "session id");
        packet.put(MAGIC).putLong(sequenceNumber).put(sessionId);
    }

    static Header readHeader(ByteBuffer packet) {
        if (packet.remaining() < DATA_HEADER_SIZE) {
            return null;
        }
        for (byte expected : MAGIC) {
            if (packet.get() != expected) {
                return null;
            }
        }
        long sequenceNumber = packet.getLong();
        byte[] sessionId = new byte[SESSION_ID_SIZE];
        packet.get(sessionId);
        return new Header(sequenceNumber, sessionId);
    }

    static Metadata createMetadata(
            int chunkSizeBytes,
            long fileSizeBytes,
            String sourceFileName,
            byte[] sessionId,
            byte[] sha256) {
        requireLength(sessionId, SESSION_ID_SIZE, "session id");
        requireLength(sha256, SHA256_SIZE, "SHA-256");
        byte[] fileNameBytes = sourceFileName.getBytes(StandardCharsets.UTF_8);
        long chunkCount = calculateChunkCount(fileSizeBytes, chunkSizeBytes);
        if (!isValidMetadata(
                chunkSizeBytes,
                chunkCount,
                fileSizeBytes,
                fileNameBytes.length,
                fileNameBytes.length)) {
            throw new IllegalArgumentException("invalid metadata");
        }
        return new Metadata(
                chunkSizeBytes,
                chunkCount,
                fileSizeBytes,
                sourceFileName,
                sessionId,
                sha256,
                fileNameBytes);
    }

    static ByteBuffer writeMetadata(Metadata metadata) {
        ByteBuffer packet = ByteBuffer.allocate(METADATA_HEADER_SIZE + metadata.fileNameBytes.length);
        writeHeader(packet, METADATA_SEQUENCE_NUMBER, metadata.sessionId);
        packet.putInt(metadata.chunkSizeBytes)
                .putLong(metadata.chunkCount)
                .putLong(metadata.fileSizeBytes)
                .putInt(metadata.fileNameBytes.length)
                .put(metadata.sha256)
                .put(metadata.fileNameBytes)
                .flip();
        return packet;
    }

    static Metadata readMetadata(ByteBuffer packet, Header header) {
        if (header.sequenceNumber != METADATA_SEQUENCE_NUMBER
                || packet.remaining() < METADATA_FIELDS_SIZE) {
            return null;
        }

        int chunkSizeBytes = packet.getInt();
        long chunkCount = packet.getLong();
        long fileSizeBytes = packet.getLong();
        int fileNameLength = packet.getInt();
        byte[] sha256 = new byte[SHA256_SIZE];
        packet.get(sha256);

        if (!isValidMetadata(
                chunkSizeBytes,
                chunkCount,
                fileSizeBytes,
                fileNameLength,
                packet.remaining())) {
            return null;
        }

        byte[] fileNameBytes = new byte[fileNameLength];
        packet.get(fileNameBytes);
        String sourceFileName = decodeUtf8(fileNameBytes);
        if (sourceFileName == null) {
            return null;
        }

        return new Metadata(
                chunkSizeBytes,
                chunkCount,
                fileSizeBytes,
                sourceFileName,
                header.sessionId,
                sha256,
                fileNameBytes);
    }

    static int expectedPayloadLength(Metadata metadata, long sequenceNumber) {
        if (sequenceNumber < 0 || sequenceNumber >= metadata.chunkCount) {
            return -1;
        }
        long offset = (long) metadata.chunkSizeBytes * sequenceNumber;
        long remaining = metadata.fileSizeBytes - offset;
        return (int) Math.min((long) metadata.chunkSizeBytes, remaining);
    }

    static boolean sameSession(byte[] left, byte[] right) {
        return Arrays.equals(left, right);
    }

    private static boolean isValidMetadata(
            int chunkSizeBytes,
            long chunkCount,
            long fileSizeBytes,
            int fileNameLength,
            int remainingBytes) {
        if (chunkSizeBytes < 1 || chunkSizeBytes > MAX_DATA_PAYLOAD_SIZE || fileSizeBytes < 0) {
            return false;
        }
        if (fileNameLength < 1
                || fileNameLength > MAX_METADATA_FILE_NAME_SIZE
                || fileNameLength != remainingBytes) {
            return false;
        }
        return chunkCount == calculateChunkCount(fileSizeBytes, chunkSizeBytes);
    }

    private static long calculateChunkCount(long fileSizeBytes, int chunkSizeBytes) {
        if (chunkSizeBytes < 1 || fileSizeBytes < 0) {
            return -1;
        }
        return fileSizeBytes == 0 ? 0 : 1 + (fileSizeBytes - 1) / chunkSizeBytes;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static void requireLength(byte[] value, int expectedLength, String name) {
        if (value == null || value.length != expectedLength) {
            throw new IllegalArgumentException(name + " must be " + expectedLength + " bytes");
        }
    }

    static final class Header {
        final long sequenceNumber;
        final byte[] sessionId;

        private Header(long sequenceNumber, byte[] sessionId) {
            this.sequenceNumber = sequenceNumber;
            this.sessionId = sessionId;
        }
    }

    static final class Metadata {
        final int chunkSizeBytes;
        final long chunkCount;
        final long fileSizeBytes;
        final String sourceFileName;
        final byte[] sessionId;
        final byte[] sha256;
        private final byte[] fileNameBytes;

        private Metadata(
                int chunkSizeBytes,
                long chunkCount,
                long fileSizeBytes,
                String sourceFileName,
                byte[] sessionId,
                byte[] sha256,
                byte[] fileNameBytes) {
            this.chunkSizeBytes = chunkSizeBytes;
            this.chunkCount = chunkCount;
            this.fileSizeBytes = fileSizeBytes;
            this.sourceFileName = sourceFileName;
            this.sessionId = sessionId.clone();
            this.sha256 = sha256.clone();
            this.fileNameBytes = fileNameBytes.clone();
        }
    }
}
