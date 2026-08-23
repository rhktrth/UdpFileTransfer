package com.github.rhktrth.udpfiletransfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UdpFileReceiverTest {
    private static final long DEFAULT_MAX_CHUNKS = 300000L;

    @TempDir
    Path tempDir;

    @Test
    void reconstructsOutOfOrderFileAndVerifiesHash() throws Exception {
        byte[] expected = ascii("abcdefghij");
        Path input = tempDir.resolve("source-name.bin");
        Path output = tempDir.resolve("output.bin");
        Files.write(input, expected);
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", port, 0)) {
            waitUntilPartExists(sender, output);
            sender.sendData(2);
            sender.sendData(0);
            sender.sendData(3);
            sender.sendData(1);

            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
            assertFalse(Files.exists(partPath(output)));
            assertTrue(stdout.toString("UTF-8").contains("source file: source-name.bin"));
            assertTrue(stdout.toString("UTF-8").contains("receive completed"));
        }
    }

    @Test
    void transfersFileSizeBoundaries() throws Exception {
        assertTransfer(new byte[0], 3);
        assertTransfer(ascii("ab"), 3);
        assertTransfer(ascii("abc"), 3);
        assertTransfer(ascii("abcd"), 3);
        assertTransfer(ascii("abcdefgh"), 3);
    }

    @Test
    void replacesExistingOutputOnlyAfterValidation() throws Exception {
        byte[] expected = ascii("abc");
        byte[] original = ascii("keep-until-validation");
        Path input = tempDir.resolve("input.bin");
        Path output = tempDir.resolve("existing.bin");
        Files.write(input, expected);
        Files.write(output, original);
        int port = freePort();

        try (ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, System.out);
                UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", port, 0)) {
            waitUntilPartExists(sender, output);
            assertArrayEquals(original, Files.readAllBytes(output));
            sender.sendData(0);

            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
            assertFalse(Files.exists(partPath(output)));
        }
    }

    @Test
    void ignoresPacketsFromAnotherSession() throws Exception {
        Path output = tempDir.resolve("session.bin");
        int port = freePort();
        byte[] sessionA = bytes(16, 1);
        byte[] sessionB = bytes(16, 33);
        byte[] expected = ascii("abcdef");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port,
                    metadataPacket(3, expected.length, "x", sessionA, sha256(expected)), output);
            stdout.reset();
            sendRaw(sender, port, dataPacket(0, sessionB, ascii("BAD")));
            sendRaw(sender, port, dataPacket(1, sessionA, ascii("def")));
            waitUntilMissingOutputContains(harness.receiver, stdout, "0");
            assertTrue(harness.thread.isAlive());

            sendRaw(sender, port, dataPacket(0, sessionA, ascii("abc")));
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
        }
    }

    @Test
    void keepsFirstAcceptedMetadataForSession() throws Exception {
        Path output = tempDir.resolve("metadata-lock.bin");
        int port = freePort();
        byte[] sessionA = bytes(16, 10);
        byte[] sessionB = bytes(16, 50);
        byte[] expected = ascii("abc");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port,
                    metadataPacket(3, 3, "first.bin", sessionA, sha256(expected)), output);
            stdout.reset();
            sendRaw(sender, port,
                    metadataPacket(3, 3, "second.bin", sessionB, sha256(ascii("XYZ"))));
            sendRaw(sender, port, dataPacket(0, sessionB, ascii("XYZ")));
            waitUntilMissingOutputContains(harness.receiver, stdout, "0");
            assertTrue(harness.thread.isAlive());

            sendRaw(sender, port, dataPacket(0, sessionA, expected));
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
            assertFalse(stdout.toString("UTF-8").contains("second.bin"));
        }
    }

    @Test
    void keepsFirstSuccessfulChunkForDuplicateSequence() throws Exception {
        Path output = tempDir.resolve("duplicate.bin");
        int port = freePort();
        byte[] session = bytes(16, 70);
        byte[] expected = ascii("abcdef");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port,
                    metadataPacket(3, 6, "x", session, sha256(expected)), output);
            sendRaw(sender, port, dataPacket(0, session, ascii("abc")));
            stdout.reset();
            waitUntilMissingOutputContains(harness.receiver, stdout, "1");

            sendRaw(sender, port, dataPacket(0, session, ascii("BAD")));
            sendRaw(sender, port, dataPacket(1, session, ascii("def")));
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
        }
    }

    @Test
    void rejectsWrongPayloadLengthThenAcceptsCorrectPacket() throws Exception {
        Path output = tempDir.resolve("length.bin");
        int port = freePort();
        byte[] session = bytes(16, 5);
        byte[] expected = ascii("abcde");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port,
                    metadataPacket(3, 5, "x", session, sha256(expected)), output);
            sendRaw(sender, port, dataPacket(0, session, ascii("abc")));
            sendRaw(sender, port, dataPacket(1, session, ascii("d")));
            waitUntilOutputContains(stdout, "illegal data was received");
            assertTrue(harness.thread.isAlive());

            sendRaw(sender, port, dataPacket(1, session, ascii("de")));
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
        }
    }

    @Test
    void rejectsMalformedAndOversizedPacketsThenContinues() throws Exception {
        Path input = tempDir.resolve("valid.bin");
        Path output = tempDir.resolve("valid-output.bin");
        Files.write(input, ascii("abc"));
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket rawSender = new DatagramSocket();
                UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", port, 0)) {
            sendUntilOutputContains(rawSender, port, new byte[] {0x55}, stdout,
                    "too short data was received");
            sendUntilOutputContains(rawSender, port,
                    new byte[UdpWireFormat.MAX_PACKET_SIZE + 1], stdout,
                    "oversized datagram was received");
            byte[] invalidUtf8 = metadataPacketRaw(
                    3, 1, 1, new byte[] {(byte) 0xc3, 0x28}, bytes(16, 7), new byte[32]);
            sendUntilOutputContains(rawSender, port, invalidUtf8, stdout,
                    "illegal metadata was received");

            waitUntilPartExists(sender, output);
            sender.sendData(0);
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(ascii("abc"), Files.readAllBytes(output));
        }
    }

    @Test
    void rejectsChunkCountAboveConfiguredLimitWithoutTouchingFiles() throws Exception {
        Path output = tempDir.resolve("protected.bin");
        Path part = partPath(output);
        byte[] finalOriginal = ascii("final-keep");
        byte[] partOriginal = ascii("part-keep");
        Files.write(output, finalOriginal);
        Files.write(part, partOriginal);
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, 3, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            byte[] metadata = metadataPacketRaw(
                    1, 4, 4, ascii("x"), bytes(16, 9), sha256(ascii("abcd")));
            sendUntilOutputContains(sender, port, metadata, stdout, "metadata exceeds max chunks");

            assertArrayEquals(finalOriginal, Files.readAllBytes(output));
            assertArrayEquals(partOriginal, Files.readAllBytes(part));
            assertTrue(harness.thread.isAlive());
        }
    }

    @Test
    void failsWhenCompletedFileHashDoesNotMatchMetadata() throws Exception {
        Path output = tempDir.resolve("hash.bin");
        Path part = partPath(output);
        byte[] original = ascii("keep-final");
        byte[] received = ascii("abc");
        Files.write(output, original);
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port,
                    metadataPacket(3, 3, "x", bytes(16, 11), new byte[32]), output);
            sendRaw(sender, port, dataPacket(0, bytes(16, 11), received));

            harness.await();
            assertFalse(harness.thread.isAlive());
            assertNotNull(harness.failure.get());
            assertTrue(harness.failure.get().getMessage().contains("SHA-256 mismatch"));
            assertArrayEquals(original, Files.readAllBytes(output));
            assertArrayEquals(received, Files.readAllBytes(part));
            assertFalse(stdout.toString("UTF-8").contains("receive completed"));
        }
    }

    @Test
    void escapesControlCharactersInSourceFileName() throws Exception {
        Path output = tempDir.resolve("empty.bin");
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        byte[] metadata = metadataPacket(
                3, 0, "evil\nname", bytes(16, 13), sha256(new byte[0]));

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilReceiverStops(sender, port, metadata, harness);
            harness.assertStoppedWithoutFailure();
            String text = stdout.toString("UTF-8");
            assertTrue(text.contains("source file: evil\\u000aname"));
            assertFalse(text.contains("source file: evil\nname"));
        }
    }

    @Test
    void missingCommandIsPagedAndValidatesStart() throws Exception {
        Path output = tempDir.resolve("many.bin");
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        byte[] metadata = metadataPacket(1, 5000, "x", bytes(16, 15), new byte[32]);

        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(port, output, 10000, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilPartExists(sender, port, metadata, output);
            harness.receiver.printMissingSequences();
            assertTrue(stdout.toString("UTF-8").contains("next: missing 1000"));
            assertThrows(IllegalArgumentException.class,
                    () -> harness.receiver.printMissingSequences(5000));
        }
    }

    @Test
    void propagatesFailureWhenPartCannotBeOpened() throws Exception {
        Path output = tempDir.resolve("missing-parent").resolve("output.bin");
        int port = freePort();
        byte[] metadata = metadataPacket(3, 0, "x", bytes(16, 17), sha256(new byte[0]));

        try (ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, System.out);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilReceiverStops(sender, port, metadata, harness);
            assertNotNull(harness.failure.get());
        }
    }

    @Test
    void closeStopsReceiverWaitingForMetadata() throws Exception {
        int port = freePort();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (PrintStream receiverOutput = new PrintStream(stdout, true, "UTF-8");
                ReceiverHarness harness = new ReceiverHarness(
                        port, tempDir.resolve("unused.bin"), DEFAULT_MAX_CHUNKS, receiverOutput);
                DatagramSocket sender = new DatagramSocket()) {
            sendUntilOutputContains(sender, port, new byte[] {0x55}, stdout,
                    "too short data was received");
            harness.receiver.close();
            harness.await();
            harness.assertStoppedWithoutFailure();
        }
    }

    private void assertTransfer(byte[] expected, int chunkSize) throws Exception {
        Path input = tempDir.resolve("input-" + expected.length + ".bin");
        Path output = tempDir.resolve("output-" + expected.length + ".bin");
        Files.write(input, expected);
        int port = freePort();

        try (ReceiverHarness harness = new ReceiverHarness(port, output, DEFAULT_MAX_CHUNKS, System.out);
                UdpFileSender sender = new UdpFileSender(input, chunkSize, "127.0.0.1", port, 0)) {
            waitUntilTransferStarted(sender, output);
            long count = expected.length == 0 ? 0 : 1 + (expected.length - 1) / chunkSize;
            for (long sequence = 0; sequence < count; sequence++) {
                sender.sendData(sequence);
            }
            harness.await();
            harness.assertStoppedWithoutFailure();
            assertArrayEquals(expected, Files.readAllBytes(output));
            assertFalse(Files.exists(partPath(output)));
        }
    }

    private static void waitUntilPartExists(UdpFileSender sender, Path output) throws Exception {
        Path part = partPath(output);
        long deadline = deadline();
        while (!Files.exists(part) && System.nanoTime() < deadline) {
            sender.sendMetadata();
            Thread.sleep(10);
        }
        assertTrue(Files.exists(part), "metadata was not accepted before timeout");
    }

    private static void waitUntilTransferStarted(UdpFileSender sender, Path output) throws Exception {
        Path part = partPath(output);
        long deadline = deadline();
        while (!Files.exists(part) && !Files.exists(output) && System.nanoTime() < deadline) {
            sender.sendMetadata();
            Thread.sleep(10);
        }
        assertTrue(Files.exists(part) || Files.exists(output),
                "metadata was not accepted before timeout");
    }

    private static void sendUntilPartExists(
            DatagramSocket socket, int port, byte[] packet, Path output) throws Exception {
        Path part = partPath(output);
        long deadline = deadline();
        while (!Files.exists(part) && System.nanoTime() < deadline) {
            sendRaw(socket, port, packet);
            Thread.sleep(10);
        }
        assertTrue(Files.exists(part), "metadata was not accepted before timeout");
    }

    private static void sendUntilOutputContains(
            DatagramSocket socket, int port, byte[] packet,
            ByteArrayOutputStream stdout, String expected) throws Exception {
        long deadline = deadline();
        while (!stdout.toString("UTF-8").contains(expected) && System.nanoTime() < deadline) {
            sendRaw(socket, port, packet);
            Thread.sleep(10);
        }
        assertTrue(stdout.toString("UTF-8").contains(expected));
    }

    private static void waitUntilOutputContains(ByteArrayOutputStream stdout, String expected)
            throws Exception {
        long deadline = deadline();
        while (!stdout.toString("UTF-8").contains(expected) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(stdout.toString("UTF-8").contains(expected));
    }

    private static void waitUntilMissingOutputContains(
            UdpFileReceiver receiver, ByteArrayOutputStream stdout, String expected) throws Exception {
        long deadline = deadline();
        while (!stdout.toString("UTF-8").contains(expected) && System.nanoTime() < deadline) {
            receiver.printMissingSequences();
            Thread.sleep(10);
        }
        assertTrue(stdout.toString("UTF-8").contains(expected));
    }

    private static void sendUntilReceiverStops(
            DatagramSocket sender, int port, byte[] packet, ReceiverHarness harness) throws Exception {
        long deadline = deadline();
        while (harness.thread.isAlive() && System.nanoTime() < deadline) {
            sendRaw(sender, port, packet);
            Thread.sleep(10);
        }
        harness.await();
        assertFalse(harness.thread.isAlive());
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    }

    private static Path partPath(Path output) {
        return Paths.get(output.toString() + ".part");
    }

    private static byte[] metadataPacket(
            int chunkSize, long fileSize, String fileName, byte[] sessionId, byte[] sha256) {
        return metadataPacketRaw(
                chunkSize,
                fileSize == 0 ? 0 : 1 + (fileSize - 1) / chunkSize,
                fileSize,
                fileName.getBytes(StandardCharsets.UTF_8),
                sessionId,
                sha256);
    }

    private static byte[] metadataPacketRaw(
            int chunkSize, long chunkCount, long fileSize, byte[] fileName,
            byte[] sessionId, byte[] sha256) {
        ByteBuffer buffer = ByteBuffer.allocate(UdpWireFormat.METADATA_HEADER_SIZE + fileName.length);
        buffer.put("UFT1".getBytes(StandardCharsets.US_ASCII));
        buffer.putLong(-1L);
        buffer.put(sessionId);
        buffer.putInt(chunkSize);
        buffer.putLong(chunkCount);
        buffer.putLong(fileSize);
        buffer.putInt(fileName.length);
        buffer.put(sha256);
        buffer.put(fileName);
        return buffer.array();
    }

    private static byte[] dataPacket(long sequence, byte[] sessionId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(UdpWireFormat.DATA_HEADER_SIZE + payload.length);
        buffer.put("UFT1".getBytes(StandardCharsets.US_ASCII));
        buffer.putLong(sequence);
        buffer.put(sessionId);
        buffer.put(payload);
        return buffer.array();
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(int length, int start) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = (byte) (start + i);
        }
        return result;
    }

    private static void sendRaw(DatagramSocket socket, int port, byte[] bytes) throws Exception {
        socket.send(new DatagramPacket(
                bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static final class ReceiverHarness implements AutoCloseable {
        final UdpFileReceiver receiver;
        final Thread thread;
        final AtomicReference<IOException> failure = new AtomicReference<>();

        ReceiverHarness(int port, Path output, long maxChunkCount, PrintStream receiverOutput) {
            receiver = new UdpFileReceiver(port, output, maxChunkCount, receiverOutput);
            thread = new Thread(() -> {
                try {
                    receiver.receive();
                } catch (IOException e) {
                    failure.set(e);
                }
            }, "udp-receive-test");
            thread.start();
        }

        void await() throws InterruptedException {
            thread.join(2000);
        }

        void assertStoppedWithoutFailure() {
            assertFalse(thread.isAlive());
            assertNull(failure.get());
        }

        @Override
        public void close() throws InterruptedException {
            receiver.close();
            thread.join(2000);
        }
    }
}
