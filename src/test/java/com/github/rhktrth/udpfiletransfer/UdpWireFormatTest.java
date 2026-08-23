package com.github.rhktrth.udpfiletransfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UdpWireFormatTest {
    @Test
    void headerEncodingUsesExactBytes() {
        byte[] sessionId = sequenceBytes(UdpWireFormat.SESSION_ID_SIZE, 0x10);
        ByteBuffer packet = ByteBuffer.allocate(UdpWireFormat.DATA_HEADER_SIZE);
        UdpWireFormat.writeHeader(packet, 0x0102030405060708L, sessionId);

        byte[] expected = new byte[UdpWireFormat.DATA_HEADER_SIZE];
        ByteBuffer expectedBuffer = ByteBuffer.wrap(expected);
        expectedBuffer.put(new byte[] {0x55, 0x46, 0x54, 0x31});
        expectedBuffer.put(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});
        expectedBuffer.put(sessionId);
        assertArrayEquals(expected, packet.array());
    }

    @Test
    void headerDecoderReturnsSequenceAndSession() {
        byte[] sessionId = sequenceBytes(UdpWireFormat.SESSION_ID_SIZE, 0x20);
        ByteBuffer packet = ByteBuffer.allocate(UdpWireFormat.DATA_HEADER_SIZE);
        UdpWireFormat.writeHeader(packet, -1L, sessionId);
        packet.flip();

        UdpWireFormat.Header header = UdpWireFormat.readHeader(packet);

        assertNotNull(header);
        assertEquals(-1L, header.sequenceNumber);
        assertArrayEquals(sessionId, header.sessionId);
        assertEquals(0, packet.remaining());
    }

    @Test
    void headerDecoderRejectsShortAndWrongMagic() {
        assertNull(UdpWireFormat.readHeader(ByteBuffer.wrap(new byte[UdpWireFormat.DATA_HEADER_SIZE - 1])));

        byte[] wrong = new byte[UdpWireFormat.DATA_HEADER_SIZE];
        ByteBuffer.wrap(wrong).put(new byte[] {0x55, 0x46, 0x54, 0x32});
        assertNull(UdpWireFormat.readHeader(ByteBuffer.wrap(wrong)));
    }

    @Test
    void metadataCodecUsesExactBytesAndFields() {
        byte[] sessionId = sequenceBytes(UdpWireFormat.SESSION_ID_SIZE, 0x30);
        byte[] sha256 = sequenceBytes(UdpWireFormat.SHA256_SIZE, 0x40);
        UdpWireFormat.Metadata metadata = UdpWireFormat.createMetadata(
                3, 5L, "a", sessionId, sha256);

        ByteBuffer packet = UdpWireFormat.writeMetadata(metadata);
        byte[] expected = new byte[UdpWireFormat.METADATA_HEADER_SIZE + 1];
        ByteBuffer expectedBuffer = ByteBuffer.wrap(expected);
        expectedBuffer.put("UFT1".getBytes(StandardCharsets.US_ASCII));
        expectedBuffer.putLong(-1L);
        expectedBuffer.put(sessionId);
        expectedBuffer.putInt(3);
        expectedBuffer.putLong(2L);
        expectedBuffer.putLong(5L);
        expectedBuffer.putInt(1);
        expectedBuffer.put(sha256);
        expectedBuffer.put((byte) 'a');
        assertArrayEquals(expected, packet.array());

        UdpWireFormat.Header header = UdpWireFormat.readHeader(packet);
        UdpWireFormat.Metadata decoded = UdpWireFormat.readMetadata(packet, header);
        assertNotNull(decoded);
        assertEquals(3, decoded.chunkSizeBytes);
        assertEquals(2L, decoded.chunkCount);
        assertEquals(5L, decoded.fileSizeBytes);
        assertEquals("a", decoded.sourceFileName);
        assertArrayEquals(sessionId, decoded.sessionId);
        assertArrayEquals(sha256, decoded.sha256);
        assertEquals(3, UdpWireFormat.expectedPayloadLength(decoded, 0));
        assertEquals(2, UdpWireFormat.expectedPayloadLength(decoded, 1));
    }

    @Test
    void metadataDecoderRejectsInconsistentAndInvalidFields() {
        byte[] sessionId = new byte[UdpWireFormat.SESSION_ID_SIZE];
        byte[] sha256 = new byte[UdpWireFormat.SHA256_SIZE];

        assertNull(decodeRawMetadata(0, 1, 1, new byte[] {'a'}, sessionId, sha256));
        assertNull(decodeRawMetadata(3, 3, 5, new byte[] {'a'}, sessionId, sha256));
        assertNull(decodeRawMetadata(3, 2, -1, new byte[] {'a'}, sessionId, sha256));
        assertNull(decodeRawMetadata(3, 2, 5, new byte[] {(byte) 0xc3, 0x28}, sessionId, sha256));
    }

    @Test
    void metadataCreationRejectsInvalidFields() {
        byte[] sessionId = new byte[UdpWireFormat.SESSION_ID_SIZE];
        byte[] sha256 = new byte[UdpWireFormat.SHA256_SIZE];

        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(0, 1, "a", sessionId, sha256));
        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(3, -1, "a", sessionId, sha256));
        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(3, 1, "", sessionId, sha256));
        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(
                        3, 1, repeat('a', UdpWireFormat.MAX_METADATA_FILE_NAME_SIZE + 1), sessionId, sha256));
        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(3, 1, "a", new byte[15], sha256));
        assertThrows(IllegalArgumentException.class,
                () -> UdpWireFormat.createMetadata(3, 1, "a", sessionId, new byte[31]));
    }

    @Test
    void packetAndMetadataLimitsAreBounded() {
        assertEquals(28, UdpWireFormat.DATA_HEADER_SIZE);
        assertEquals(9972, UdpWireFormat.MAX_DATA_PAYLOAD_SIZE);
        assertEquals(84, UdpWireFormat.METADATA_HEADER_SIZE);
        assertEquals(1024, UdpWireFormat.MAX_METADATA_FILE_NAME_SIZE);
        assertTrue(
                UdpWireFormat.METADATA_HEADER_SIZE + UdpWireFormat.MAX_METADATA_FILE_NAME_SIZE
                        <= UdpWireFormat.MAX_PACKET_SIZE);
        assertFalse(UdpWireFormat.sameSession(new byte[16], sequenceBytes(16, 1)));
    }

    private static UdpWireFormat.Metadata decodeRawMetadata(
            int chunkSize,
            long chunkCount,
            long fileSize,
            byte[] fileName,
            byte[] sessionId,
            byte[] sha256) {
        ByteBuffer packet = ByteBuffer.allocate(
                UdpWireFormat.METADATA_HEADER_SIZE + fileName.length);
        UdpWireFormat.writeHeader(packet, -1L, sessionId);
        packet.putInt(chunkSize)
                .putLong(chunkCount)
                .putLong(fileSize)
                .putInt(fileName.length)
                .put(sha256)
                .put(fileName)
                .flip();
        UdpWireFormat.Header header = UdpWireFormat.readHeader(packet);
        return UdpWireFormat.readMetadata(packet, header);
    }

    private static byte[] sequenceBytes(int length, int start) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (start + i);
        }
        return result;
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
