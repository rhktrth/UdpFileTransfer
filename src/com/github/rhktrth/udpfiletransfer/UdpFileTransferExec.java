/*
 * Copyright (C) 2019 rhktrth
 * This software is under the terms of MIT license.
 * For details, see the web site: https://github.com/rhktrth/UdpFileTransfer/
 */

package com.github.rhktrth.udpfiletransfer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;

public class UdpFileTransferExec {
	final static String DEFAULT_MODE = "send";
	final static String DEFAULT_FILENAME = "file.txt";
	final static int DEFAULT_PORT = 30070;
	final static int DEFAULT_PAYLOAD_LENGTH = 700;
	final static String DEFAULT_IPADDRESS = "127.0.0.1";
	final static int DEFAULT_INTERVAL = 0;

	public static void main(String[] args) {
		System.out.println("UdpFileTransfer 0.3");

		String mode = DEFAULT_MODE;
		String fileName = DEFAULT_FILENAME;
		int port = DEFAULT_PORT;
		int pl = DEFAULT_PAYLOAD_LENGTH;
		String ip = DEFAULT_IPADDRESS;
		int it = DEFAULT_INTERVAL;

		boolean argErr = false;
		if ((args.length % 2) == 0) {
			A: for (int i = 0; i < args.length; i++) {
				if ("-m".equals(args[i])) {
					String mstr = args[++i];
					if ("send".equals(mstr)) {
						mode = mstr;
					} else if ("recv".equals(mstr)) {
						mode = mstr;
					} else {
						argErr = true;
						break A;
					}
				} else if ("-f".equals(args[i])) {
					fileName = args[++i];
				} else if ("-h".equals(args[i])) {
					ip = args[++i];
				} else if ("-p".equals(args[i])) {
					port = Integer.parseInt(args[++i]);
				} else if ("-s".equals(args[i])) {
					pl = Integer.parseInt(args[++i]);
				} else if ("-i".equals(args[i])) {
					it = Integer.parseInt(args[++i]);
				} else {
					argErr = true;
					break A;
				}
			}
		} else {
			argErr = true;
		}
		if (argErr == true) {
			System.err.println("Error: illegal argument(s)");
			System.err.println(" -m send -f [filename] -h [host] -p [portnum] -s [splitsize] -i [interval]");
			System.err.println(" -m recv -f [filename] -p [portnum]");
			System.exit(8);
		}

		/* main process */
		if (mode.equals("send")) {
			send(fileName, ip, port, pl, it);
		} else { // recv
			recv(fileName, port);
		}
	}

	private static void send(String fileName, String ip, int port, int pl, int it) {
		System.out.println("  file: " + fileName);
		System.out.println("  host: " + ip);
		System.out.println("  port: " + port);
		System.out.println("  size: " + pl);
		System.out.println("  interval: " + it);

		UdpSendFile udpSendFile;
		udpSendFile = new UdpSendFile(new File(fileName), pl, ip, port, it);
		udpSendFile.start();

		String inputString;
		while (true) {
			System.out.println("input command (all<default>, meta, [0-9]*, #[0-9]*, quit)");

			try {
				BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
				inputString = new String(in.readLine());
			} catch (Exception e) {
				System.out.println("That is invalid input");
				inputString = "*NEVERMATCH*";
			}

			if (inputString.equals("all") || inputString.equals("")) {
				udpSendFile.sendMetaInfoAndAllData();
			} else if (inputString.equals("meta")) {
				udpSendFile.sendMetaInfo();
			} else if (inputString.matches("^[0-9]*$")) {
				udpSendFile.sendSpecificData(Integer.parseInt(inputString));
			} else if (inputString.matches("^#[0-9]*$")) {
				udpSendFile.setInterval(Integer.parseInt(inputString.substring(1)));
			} else if (inputString.equals("quit")) {
				break;
			} else {
				System.out.println("input error");
			}
		}

		udpSendFile.close();
		System.out.println("bye");
	}

	private static void recv(String fileName, int port) {
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
		System.out.println("  port: " + port);

		UdpReceiveFile udpRecvFile = new UdpReceiveFile(port, new File(fileName));
		udpRecvFile.start();

		String inputString;
		while (true) {
			System.out.println("input command (missing<default>, file [filepath], quit)");

			try {
				BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
				inputString = new String(in.readLine());
			} catch (Exception e) {
				System.out.println("That is invalid input");
				inputString = "*NEVERMATCH*";
			}

			if (inputString.equals("missing") || inputString.equals("")) {
				udpRecvFile.printMissingNumbers();
			} else if (inputString.matches("^file .*")) {
				udpRecvFile.setOutputFile(new File(inputString.replaceAll("^file ", "")));
			} else if (inputString.equals("quit")) {
				break;
			} else {
				System.out.println("input error");
			}
		}

		udpRecvFile.close();
		try {
			udpRecvFile.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		System.out.println("bye");
	}
}
