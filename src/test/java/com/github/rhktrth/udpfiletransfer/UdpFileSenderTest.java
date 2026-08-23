package com.github.rhktrth.udpfiletransfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UdpFileSenderTest {
    @TempDir
    Path tempDir;

    @Test
    void sendAllUsesOneSessionAndCurrentWireFormat() throws Exception {
        Path input = tempDir.resolve("日本語.bin");
        Files.write(input, "abcde".getBytes(StandardCharsets.US_ASCII));

        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                UdpFileSender sender = new UdpFileSender(
                        input, 3, "127.0.0.1", receiver.getLocalPort(), 0)) {
            receiver.setSoTimeout(2000);
            sender.sendAll();

            ByteBuffer firstMetadataPacket = receive(receiver);
            UdpWireFormat.Header firstHeader = UdpWireFormat.readHeader(firstMetadataPacket);
            UdpWireFormat.Metadata firstMetadata = UdpWireFormat.readMetadata(
                    firstMetadataPacket, firstHeader);
            assertEquals(-1L, firstHeader.sequenceNumber);
            assertEquals(3, firstMetadata.chunkSizeBytes);
            assertEquals(2L, firstMetadata.chunkCount);
            assertEquals(5L, firstMetadata.fileSizeBytes);
            assertEquals("日本語.bin", firstMetadata.sourceFileName);
            assertArrayEquals(FileHash.sha256(input), firstMetadata.sha256);

            ByteBuffer secondMetadataPacket = receive(receiver);
            UdpWireFormat.Header secondHeader = UdpWireFormat.readHeader(secondMetadataPacket);
            UdpWireFormat.Metadata secondMetadata = UdpWireFormat.readMetadata(
                    secondMetadataPacket, secondHeader);
            assertArrayEquals(firstHeader.sessionId, secondHeader.sessionId);
            assertArrayEquals(firstMetadata.sha256, secondMetadata.sha256);

            ByteBuffer firstData = receive(receiver);
            UdpWireFormat.Header firstDataHeader = UdpWireFormat.readHeader(firstData);
            assertEquals(0L, firstDataHeader.sequenceNumber);
            assertArrayEquals(firstHeader.sessionId, firstDataHeader.sessionId);
            assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), readRemaining(firstData));

            ByteBuffer secondData = receive(receiver);
            UdpWireFormat.Header secondDataHeader = UdpWireFormat.readHeader(secondData);
            assertEquals(1L, secondDataHeader.sequenceNumber);
            assertArrayEquals(firstHeader.sessionId, secondDataHeader.sessionId);
            assertArrayEquals("de".getBytes(StandardCharsets.US_ASCII), readRemaining(secondData));

            assertThrows(IllegalArgumentException.class, () -> sender.sendData(-1));
            assertThrows(IllegalArgumentException.class, () -> sender.sendData(2));
        }
    }

    @Test
    void rejectsSourceSizeChangeDuringTransfer() throws Exception {
        Path input = tempDir.resolve("changing.bin");
        Files.write(input, "abc".getBytes(StandardCharsets.US_ASCII));

        try (UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", 30070, 0)) {
            Files.write(input, new byte[] {'d'}, StandardOpenOption.APPEND);
            assertThrows(IOException.class, sender::sendMetadata);
            assertThrows(IOException.class, () -> sender.sendData(0));
        }
    }

    @Test
    void constructionRejectsMissingFile() {
        assertThrows(
                IOException.class,
                () -> new UdpFileSender(
                        tempDir.resolve("missing.bin"), 3, "127.0.0.1", 30070, 0));
    }

    private static ByteBuffer receive(DatagramSocket socket) throws Exception {
        byte[] bytes = new byte[UdpWireFormat.MAX_PACKET_SIZE + 1];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        socket.receive(packet);
        return ByteBuffer.wrap(Arrays.copyOf(packet.getData(), packet.getLength()));
    }

    private static byte[] readRemaining(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}
