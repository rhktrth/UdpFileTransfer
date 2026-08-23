package com.github.rhktrth.udpfiletransfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UdpFileTransferExecTest {
    @TempDir
    Path tempDir;

    @Test
    void sendUsesDefaults() throws Exception {
        Path input = tempDir.resolve("input.bin");
        Files.write(input, new byte[] {1});
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = run(
                new String[] {"send", input.toString()},
                "quit\n",
                stdout,
                new ByteArrayOutputStream());

        String output = stdout.toString("UTF-8");
        assertEquals(0, exitCode);
        assertTrue(output.contains("host: 127.0.0.1"));
        assertTrue(output.contains("port: 30070"));
        assertTrue(output.contains("chunk size: 700"));
        assertTrue(output.contains("interval: 0"));
    }

    @Test
    void receiveUsesDefaultMaxChunkCount() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = run(
                new String[] {
                    "recv",
                    tempDir.resolve("unused.bin").toString(),
                    "-p",
                    Integer.toString(freePort())
                },
                "quit\n",
                stdout,
                new ByteArrayOutputStream());
        assertEquals(0, exitCode);
        assertTrue(stdout.toString("UTF-8").contains("max chunks: 300000"));
    }

    @Test
    void receiveAcceptsMaxChunkCountOption() throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exitCode = run(
                new String[] {
                    "recv",
                    tempDir.resolve("unused.bin").toString(),
                    "-p",
                    Integer.toString(freePort()),
                    "-n",
                    "500000"
                },
                "quit\n",
                stdout,
                new ByteArrayOutputStream());
        assertEquals(0, exitCode);
        assertTrue(stdout.toString("UTF-8").contains("max chunks: 500000"));
    }

    @Test
    void recvRejectsSendOnlyOptions() throws Exception {
        assertArgumentError(
                new String[] {"recv", "output.bin", "-c", "700"},
                "-c is available only in send mode");
    }

    @Test
    void sendRejectsReceiveOnlyOptions() throws Exception {
        assertArgumentError(
                new String[] {"send", "input.bin", "-n", "300000"},
                "-n is available only in recv mode");
    }

    @Test
    void oldTerminologyOptionsAreRejected() throws Exception {
        assertArgumentError(
                new String[] {"send", "input.bin", "-s", "700"},
                "unknown option: -s");
        assertArgumentError(
                new String[] {"recv", "output.bin", "-m", "200"},
                "unknown option: -m");
    }

    @Test
    void malformedArgumentsAreRejected() throws Exception {
        assertArgumentError(new String[0], "mode and file are required");
        assertArgumentError(new String[] {"other", "file.bin"}, "mode must be send or recv");
        assertArgumentError(new String[] {"send", "file.bin", "-p"}, "option value is missing");
        assertArgumentError(
                new String[] {"send", "file.bin", "-p", "not-a-number"},
                "invalid integer for -p");
        assertArgumentError(
                new String[] {"recv", "file.bin", "-n", "not-a-number"},
                "invalid integer for -n");
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        assertArgumentError(new String[] {"recv", "file.bin", "-p", "0"},
                "port must be between 1 and 65535");
        assertArgumentError(new String[] {"send", "file.bin", "-h", ""},
                "host must not be empty");
        assertArgumentError(new String[] {"send", "file.bin", "-c", "0"},
                "chunk size must be between 1 and 9972");
        assertArgumentError(new String[] {"send", "file.bin", "-c", "9973"},
                "chunk size must be between 1 and 9972");
        assertArgumentError(new String[] {"send", "file.bin", "-i", "-1"},
                "interval must not be negative");
        assertArgumentError(new String[] {"recv", "file.bin", "-n", "0"},
                "max chunks must be at least 1");
    }

    @Test
    void runReturnsSendFailureWithoutExitingJvm() throws Exception {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = run(
                new String[] {"send", tempDir.resolve("missing.bin").toString()},
                "quit\n",
                new ByteArrayOutputStream(),
                stderr);
        assertEquals(1, exitCode);
        assertTrue(stderr.toString("UTF-8").contains("send error:"));
    }

    @Test
    void invalidSendSequenceKeepsCommandLoopRunning() throws Exception {
        Path input = tempDir.resolve("input.bin");
        Files.write(input, new byte[] {1});
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = run(
                new String[] {"send", input.toString()},
                "1\nquit\n",
                stdout,
                new ByteArrayOutputStream());

        assertEquals(0, exitCode);
        assertTrue(stdout.toString("UTF-8").contains("no such chunk number"));
    }

    @Test
    void receiveOutputOpenFailureReturnsOne() throws Exception {
        Path input = tempDir.resolve("empty.bin");
        Files.write(input, new byte[0]);
        Path output = tempDir.resolve("missing-parent").resolve("output.bin");
        int port = freePort();

        PipedInputStream commandInput = new PipedInputStream();
        PipedOutputStream commandWriter = new PipedOutputStream(commandInput);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread cli = new Thread(() -> exitCode.set(UdpFileTransferExec.run(
                new String[] {"recv", output.toString(), "-p", Integer.toString(port)},
                commandInput,
                new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(stderr, true))));
        cli.start();

        try (UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", port, 0)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (cli.isAlive() && System.nanoTime() < deadline) {
                sender.sendMetadata();
                Thread.sleep(10);
            }
        }
        cli.join(2000);
        commandWriter.close();
        commandInput.close();

        assertFalse(cli.isAlive());
        assertEquals(1, exitCode.get());
        assertTrue(stderr.toString("UTF-8").contains("receive error:"));
    }

    @Test
    void receiveHashMismatchReturnsOne() throws Exception {
        Path output = tempDir.resolve("hash-output.bin");
        Path part = partPath(output);
        byte[] original = "old".getBytes(StandardCharsets.US_ASCII);
        Files.write(output, original);
        int port = freePort();
        byte[] session = bytes(16, 1);
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);

        PipedInputStream commandInput = new PipedInputStream();
        PipedOutputStream commandWriter = new PipedOutputStream(commandInput);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread cli = new Thread(() -> exitCode.set(UdpFileTransferExec.run(
                new String[] {"recv", output.toString(), "-p", Integer.toString(port)},
                commandInput,
                new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(stderr, true))));
        cli.start();

        try (DatagramSocket sender = new DatagramSocket()) {
            byte[] metadata = metadataPacket(3, data.length, "x", session, new byte[32]);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(part) && System.nanoTime() < deadline) {
                sendRaw(sender, port, metadata);
                Thread.sleep(10);
            }
            assertTrue(Files.exists(part));
            sendRaw(sender, port, dataPacket(0, session, data));
        }

        cli.join(2000);
        commandWriter.close();
        commandInput.close();

        assertFalse(cli.isAlive());
        assertEquals(1, exitCode.get());
        assertTrue(stderr.toString("UTF-8").contains("SHA-256 mismatch"));
        assertTrue(arraysEqual(original, Files.readAllBytes(output)));
        assertTrue(arraysEqual(data, Files.readAllBytes(part)));
    }

    @Test
    void receiveInputFailureReturnsOne() throws Exception {
        InputStream failingInput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken input");
            }
        };
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exitCode = UdpFileTransferExec.run(
                new String[] {
                    "recv",
                    tempDir.resolve("unused.bin").toString(),
                    "-p",
                    Integer.toString(freePort())
                },
                failingInput,
                new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(stderr, true));

        assertEquals(1, exitCode);
        assertTrue(stderr.toString("UTF-8").contains("input error: broken input"));
    }

    @Test
    void runReturnsZeroWhenReceiveCompletes() throws Exception {
        Path input = tempDir.resolve("source.bin");
        Path output = tempDir.resolve("received.bin");
        Path part = partPath(output);
        Files.write(input, "abc".getBytes(StandardCharsets.US_ASCII));
        int port = freePort();

        PipedInputStream commandInput = new PipedInputStream();
        PipedOutputStream commandWriter = new PipedOutputStream(commandInput);
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread cli = new Thread(() -> exitCode.set(UdpFileTransferExec.run(
                new String[] {"recv", output.toString(), "-p", Integer.toString(port)},
                commandInput,
                new PrintStream(new ByteArrayOutputStream(), true),
                new PrintStream(new ByteArrayOutputStream(), true))));
        cli.start();

        try (UdpFileSender sender = new UdpFileSender(input, 3, "127.0.0.1", port, 0)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(part) && System.nanoTime() < deadline) {
                sender.sendMetadata();
                Thread.sleep(10);
            }
            assertTrue(Files.exists(part));
            sender.sendData(0);
        }

        cli.join(2000);
        commandWriter.close();
        commandInput.close();
        assertFalse(cli.isAlive());
        assertEquals(0, exitCode.get());
        assertTrue(arraysEqual(Files.readAllBytes(input), Files.readAllBytes(output)));
        assertFalse(Files.exists(part));
    }

    private static void assertArgumentError(String[] args, String expected) throws Exception {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = run(args, "", new ByteArrayOutputStream(), stderr);
        assertEquals(2, exitCode);
        assertTrue(stderr.toString("UTF-8").contains(expected));
    }

    private static int run(
            String[] args,
            String input,
            ByteArrayOutputStream stdout,
            ByteArrayOutputStream stderr) throws Exception {
        return UdpFileTransferExec.run(
                args,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, "UTF-8"),
                new PrintStream(stderr, true, "UTF-8"));
    }

    private static Path partPath(Path output) {
        return Paths.get(output.toString() + ".part");
    }

    private static byte[] metadataPacket(
            int chunkSize, long fileSize, String fileName, byte[] sessionId, byte[] sha256) {
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        long chunkCount = fileSize == 0 ? 0 : 1 + (fileSize - 1) / chunkSize;
        ByteBuffer buffer = ByteBuffer.allocate(UdpWireFormat.METADATA_HEADER_SIZE + fileNameBytes.length);
        buffer.put("UFT1".getBytes(StandardCharsets.US_ASCII));
        buffer.putLong(-1L);
        buffer.put(sessionId);
        buffer.putInt(chunkSize);
        buffer.putLong(chunkCount);
        buffer.putLong(fileSize);
        buffer.putInt(fileNameBytes.length);
        buffer.put(sha256);
        buffer.put(fileNameBytes);
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

    private static byte[] bytes(int length, int start) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (start + i);
        }
        return result;
    }

    private static void sendRaw(DatagramSocket socket, int port, byte[] bytes) throws Exception {
        socket.send(new DatagramPacket(
                bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }

    private static boolean arraysEqual(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static int freePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
