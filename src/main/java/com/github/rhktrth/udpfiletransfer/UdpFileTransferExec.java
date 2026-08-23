package com.github.rhktrth.udpfiletransfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

public final class UdpFileTransferExec {
    private static final int DEFAULT_PORT = 30070;
    private static final int DEFAULT_CHUNK_SIZE_BYTES = 700;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_SEND_INTERVAL_MILLIS = 0;
    private static final long DEFAULT_MAX_CHUNK_COUNT = 300_000L;

    private UdpFileTransferExec() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
        output.println("UdpFileTransfer");

        final Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            error.println("error: " + e.getMessage());
            printUsage(error);
            return 2;
        }

        switch (options.mode) {
            case SEND:
                try {
                    send(options, input, output);
                    return 0;
                } catch (IOException e) {
                    error.println("send error: " + e.getMessage());
                    return 1;
                }
            case RECV:
                return receive(options, input, output, error);
            default:
                throw new AssertionError(options.mode);
        }
    }

    private static void send(Options options, InputStream input, PrintStream output) throws IOException {
        output.println("file: " + options.filePath);
        output.println("host: " + options.host);
        output.println("port: " + options.port);
        output.println("chunk size: " + options.chunkSizeBytes);
        output.println("interval: " + options.sendIntervalMillis);

        try (UdpFileSender sender = new UdpFileSender(
                        options.filePath,
                        options.chunkSizeBytes,
                        options.host,
                        options.port,
                        options.sendIntervalMillis);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (true) {
                output.println("command: all<default>, meta, <sequence>, quit");
                String command = reader.readLine();
                if (command == null || "quit".equals(command)) {
                    return;
                }
                handleSendCommand(sender, command, output);
            }
        }
    }

    private static void handleSendCommand(
            UdpFileSender sender, String command, PrintStream output) throws IOException {
        try {
            if (command.isEmpty() || "all".equals(command)) {
                sender.sendAll();
                output.println("sent all data");
            } else if ("meta".equals(command)) {
                sender.sendMetadata();
                output.println("sent metadata");
            } else {
                long sequenceNumber = Long.parseLong(command);
                sender.sendData(sequenceNumber);
                output.println("sent " + sequenceNumber);
            }
        } catch (NumberFormatException e) {
            output.println("input error");
        } catch (IllegalArgumentException e) {
            output.println(e.getMessage());
        }
    }

    private static int receive(
            Options options, InputStream input, PrintStream output, PrintStream error) {
        output.println("file: " + options.filePath);
        output.println("port: " + options.port);
        output.println("max chunks: " + options.maxChunkCount);

        UdpFileReceiver receiver = new UdpFileReceiver(
                options.port, options.filePath, options.maxChunkCount, output);
        AtomicReference<IOException> inputFailure = new AtomicReference<>();
        Thread commandThread = new Thread(
                () -> receiveCommands(receiver, input, output, inputFailure),
                "udp-receive-command");
        commandThread.setDaemon(true);
        commandThread.start();

        try {
            receiver.receive();
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                error.println("receive error: interrupted");
            } else {
                error.println("receive error: " + e.getMessage());
            }
            return 1;
        }

        IOException commandFailure = inputFailure.get();
        if (commandFailure != null) {
            error.println("input error: " + commandFailure.getMessage());
            return 1;
        }
        return 0;
    }

    private static void receiveCommands(
            UdpFileReceiver receiver,
            InputStream input,
            PrintStream output,
            AtomicReference<IOException> inputFailure) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            while (true) {
                output.println("command: missing [start]<default>, quit");
                String command = reader.readLine();
                if (command == null || !handleReceiveCommand(receiver, command, output)) {
                    receiver.close();
                    return;
                }
            }
        } catch (IOException e) {
            inputFailure.set(e);
            receiver.close();
        }
    }

    private static boolean handleReceiveCommand(
            UdpFileReceiver receiver, String command, PrintStream output) {
        if ("quit".equals(command)) {
            return false;
        }
        if (command.isEmpty() || "missing".equals(command)) {
            receiver.printMissingSequences();
            return true;
        }
        if (command.startsWith("missing ")) {
            try {
                receiver.printMissingSequences(Long.parseLong(command.substring(8)));
            } catch (NumberFormatException e) {
                output.println("input error");
            } catch (IllegalArgumentException e) {
                output.println(e.getMessage());
            }
            return true;
        }
        output.println("input error");
        return true;
    }

    private static void printUsage(PrintStream error) {
        error.println("Usage:");
        error.println("  java -jar UdpFileTransfer.jar recv <output-file> [-p <port>] [-n <max-chunks>]");
        error.println("  java -jar UdpFileTransfer.jar send <input-file> [-h <host>] [-p <port>] [-c <chunk-size>] [-i <interval-ms>]");
    }

    private enum Mode {
        SEND,
        RECV;

        static Mode parse(String value) {
            switch (value) {
                case "send":
                    return SEND;
                case "recv":
                    return RECV;
                default:
                    throw new IllegalArgumentException("mode must be send or recv");
            }
        }
    }

    private static final class Options {
        final Mode mode;
        final Path filePath;
        final String host;
        final int port;
        final int chunkSizeBytes;
        final int sendIntervalMillis;
        final long maxChunkCount;

        private Options(Mode mode, Path filePath, String host, int port,
                int chunkSizeBytes, int sendIntervalMillis, long maxChunkCount) {
            this.mode = mode;
            this.filePath = filePath;
            this.host = host;
            this.port = port;
            this.chunkSizeBytes = chunkSizeBytes;
            this.sendIntervalMillis = sendIntervalMillis;
            this.maxChunkCount = maxChunkCount;
        }

        static Options parse(String[] args) {
            if (args == null || args.length < 2) {
                throw new IllegalArgumentException("mode and file are required");
            }
            if (((args.length - 2) % 2) != 0) {
                throw new IllegalArgumentException("option value is missing");
            }

            Mode mode = Mode.parse(args[0]);
            if (args[1].isEmpty()) {
                throw new IllegalArgumentException("file must not be empty");
            }
            Path filePath = Paths.get(args[1]);

            String host = DEFAULT_HOST;
            int port = DEFAULT_PORT;
            int chunkSizeBytes = DEFAULT_CHUNK_SIZE_BYTES;
            int sendIntervalMillis = DEFAULT_SEND_INTERVAL_MILLIS;
            long maxChunkCount = DEFAULT_MAX_CHUNK_COUNT;

            for (int i = 2; i < args.length; i += 2) {
                String option = args[i];
                String value = args[i + 1];
                switch (option) {
                    case "-p":
                        port = parseInt(option, value);
                        break;
                    case "-h":
                        requireSend(mode, option);
                        host = value;
                        break;
                    case "-c":
                        requireSend(mode, option);
                        chunkSizeBytes = parseInt(option, value);
                        break;
                    case "-i":
                        requireSend(mode, option);
                        sendIntervalMillis = parseInt(option, value);
                        break;
                    case "-n":
                        requireReceive(mode, option);
                        maxChunkCount = parseLong(option, value);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option: " + option);
                }
            }

            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            if (mode == Mode.SEND) {
                if (host.isEmpty()) {
                    throw new IllegalArgumentException("host must not be empty");
                }
                if (chunkSizeBytes < 1 || chunkSizeBytes > UdpWireFormat.MAX_DATA_PAYLOAD_SIZE) {
                    throw new IllegalArgumentException(
                            "chunk size must be between 1 and " + UdpWireFormat.MAX_DATA_PAYLOAD_SIZE);
                }
                if (sendIntervalMillis < 0) {
                    throw new IllegalArgumentException("interval must not be negative");
                }
            } else if (maxChunkCount < 1) {
                throw new IllegalArgumentException("max chunks must be at least 1");
            }

            return new Options(
                    mode, filePath, host, port, chunkSizeBytes, sendIntervalMillis, maxChunkCount);
        }

        private static int parseInt(String option, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid integer for " + option);
            }
        }

        private static long parseLong(String option, String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid integer for " + option);
            }
        }

        private static void requireSend(Mode mode, String option) {
            if (mode != Mode.SEND) {
                throw new IllegalArgumentException(option + " is available only in send mode");
            }
        }

        private static void requireReceive(Mode mode, String option) {
            if (mode != Mode.RECV) {
                throw new IllegalArgumentException(option + " is available only in recv mode");
            }
        }
    }
}
