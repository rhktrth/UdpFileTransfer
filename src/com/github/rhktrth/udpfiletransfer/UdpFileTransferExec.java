/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

public class UdpFileTransferExec {
	final static String DEFAULT_MODE = "send";
	final static String DEFAULT_FILENAME = "file.txt";
	final static int DEFAULT_PORT = 30070;
	final static int DEFAULT_PAYLOAD_LENGTH = 700;
	final static String DEFAULT_IPADDRESS = "127.0.0.1";
	final static int DEFAULT_INTERVAL = 0;

	public static void main(String[] args) {
		System.out.println("UdpFileTransfer 0.4");

		String mode = DEFAULT_MODE;
		String fileName = DEFAULT_FILENAME;
		int portNumber = DEFAULT_PORT;
		int payloeadLength = DEFAULT_PAYLOAD_LENGTH;
		String ipAddress = DEFAULT_IPADDRESS;
		int interval = DEFAULT_INTERVAL;

		boolean argsError = false;
		if ((args.length % 2) == 0) {
			A: for (int i = 0; i < args.length; i++) {
				if ("-m".equals(args[i])) {
					String mstr = args[++i];
					if ("send".equals(mstr)) {
						mode = mstr;
					} else if ("recv".equals(mstr)) {
						mode = mstr;
					} else {
						argsError = true;
						break A;
					}
				} else if ("-f".equals(args[i])) {
					fileName = args[++i];
				} else if ("-h".equals(args[i])) {
					ipAddress = args[++i];
				} else if ("-p".equals(args[i])) {
					portNumber = Integer.parseInt(args[++i]);
				} else if ("-s".equals(args[i])) {
					payloeadLength = Integer.parseInt(args[++i]);
				} else if ("-i".equals(args[i])) {
					interval = Integer.parseInt(args[++i]);
				} else {
					argsError = true;
					break A;
				}
			}
		} else {
			argsError = true;
		}
		if (argsError == true) {
			System.err.println("Error: illegal argument(s)");
			System.err.println(" -m send -f [filename] -h [host] -p [portnum] -s [splitsize] -i [interval]");
			System.err.println(" -m recv -f [filename] -p [portnum]");
			System.exit(8);
		}

		/* main process */
		if (mode.equals("send")) {
			send(fileName, ipAddress, portNumber, payloeadLength, interval);
		} else { // recv
			recv(fileName, portNumber);
		}

		System.out.println("bye");
	}

	private static void send(String fileName, String ipAddress, int portNumber, int payloadLength, int interval) {
		System.out.println("  file: " + fileName);
		System.out.println("  host: " + ipAddress);
		System.out.println("  port: " + portNumber);
		System.out.println("  size: " + payloadLength);
		System.out.println("  interval: " + interval);

		UdpSendFile udpSendFile = new UdpSendFile(Paths.get(fileName), payloadLength, ipAddress, portNumber, interval);
		udpSendFile.start();

		try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));) {
			while (true) {
				try {
					System.out.println("input command (all<default>, meta, [0-9]*, #[0-9]*, quit)");
					String inputString = in.readLine();

					if (inputString.equals("all") || inputString.equals("")) {
						udpSendFile.sendMetaInfo();
						System.out.println("sent metainfo");
						udpSendFile.sendMetaInfo(); // send two times, just in case
						System.out.println("sent metainfo again");
						for (long i = 0; i < udpSendFile.getSplitCount(); i++) {
							udpSendFile.sendSpecificData(i);
							System.out.print(i + ", ");
						}
						System.out.println("EOF");
					} else if (inputString.equals("meta")) {
						udpSendFile.sendMetaInfo();
						System.out.println("sent metainfo");
					} else if (inputString.matches("^[0-9]*$")) {
						long n = Long.parseLong(inputString);
						udpSendFile.sendSpecificData(n);
						System.out.println("sent " + n);
					} else if (inputString.matches("^#[0-9]*$")) {
						int n = Integer.parseInt(inputString.substring(1));
						udpSendFile.setInterval(n);
						System.out.println("set interval " + n);
					} else if (inputString.equals("quit")) {
						break;
					} else {
						System.out.println("input error");
					}
				} catch (IllegalArgumentException e) {
					System.out.println(e.getMessage());
					continue;
				}
			}
		} catch (IOException e) {
			System.out.println("input was invalid");
		}

		udpSendFile.close();
		try {
			udpSendFile.join();
		} catch (InterruptedException e) {
		}
	}

	private static void recv(String fileName, int portNumber) {
		System.out.println("  file: " + fileName);
		Enumeration<NetworkInterface> enuIfs = null;
		try {
			enuIfs = NetworkInterface.getNetworkInterfaces();
		} catch (SocketException e) {
			e.printStackTrace();
			return;
		}
		if (enuIfs != null) {
			for (NetworkInterface ni : Collections.list(enuIfs)) {
				Enumeration<InetAddress> enuAddrs = ni.getInetAddresses();
				for (InetAddress in4 : Collections.list(enuAddrs)) {
					System.out.println("  ip address: " + in4.getHostAddress());
				}
			}
		}
		System.out.println("  port: " + portNumber);

		UdpReceiveFile udpRecvFile = new UdpReceiveFile(portNumber, Paths.get(fileName));
		udpRecvFile.start();

		try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in));) {
			while (true) {
				System.out.println("input command (missing<default>, file [filepath], quit)");
				String inputString = in.readLine();

				if (inputString.equals("missing") || inputString.equals("")) {
					Set<Long> notYet = udpRecvFile.getNotYetArrivedNumbers();
					StringBuilder sb = new StringBuilder();
					if (notYet.isEmpty()) {
						sb.append("no meta infomation");
					} else {
						for (Long i : notYet) {
							sb.append(i);
							sb.append(", ");
						}
						sb.append("EOF");
					}
					System.out.println(sb);
				} else if (inputString.matches("^file .*")) {
					Path newPath = Paths.get(inputString.replaceAll("^file ", ""));
					boolean ok = udpRecvFile.setOutputFile(newPath);
					if (!ok) {
						System.out.println("writing was started, file can not be changed");
					}
				} else if (inputString.equals("quit")) {
					break;
				} else {
					System.out.println("input error");
				}
			}
		} catch (IOException e) {
			System.out.println("input was invalid");
		}

		udpRecvFile.close();
		try {
			udpRecvFile.join();
		} catch (InterruptedException e) {
		}
	}
}
