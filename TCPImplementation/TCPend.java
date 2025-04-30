package TCPImplementation;

import TCPImplementation.TCPSender;
import TCPImplementation.TCPReceiver;

import java.io.IOException;

public class TCPend {
    public static void main(String[] args) {
        try {
            if (args.length < 4) {
                printUsage();
                return;
            }

            // Parse command line arguments
            String mode = null;
            int port = -1;
            String remoteIP = null;
            int remotePort = -1;
            String filename = null;
            int mtu = -1;
            int windowSize = -1;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-p":
                        port = Integer.parseInt(args[++i]);
                        break;
                    case "-s":
                        mode = "sender";
                        remoteIP = args[++i];
                        break;
                    case "-a":
                        remotePort = Integer.parseInt(args[++i]);
                        break;
                    case "-f":
                        filename = args[++i];
                        break;
                    case "-m":
                        mtu = Integer.parseInt(args[++i]);
                        break;
                    case "-c":
                        windowSize = Integer.parseInt(args[++i]);
                        break;
                }
            }

            // Validate arguments
            if (port == -1 || mtu == -1 || windowSize == -1 || filename == null) {
                System.err.println("Error: Missing required arguments");
                printUsage();
                return;
            }

            if (mode != null) {
                // Sender mode
                if (remoteIP == null || remotePort == -1) {
                    System.err.println("Error: Missing remote IP or port for sender mode");
                    printUsage();
                    return;
                }
                TCPSender sender = new TCPSender(remoteIP, remotePort, filename, mtu, windowSize);
                sender.start();
                sender.close();
            } else {
                // Receiver mode
                TCPReceiver receiver = new TCPReceiver(port, filename, mtu, windowSize);
                receiver.start();
                receiver.close();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("Sender mode:");
        System.out.println("  java TCPend -p <port> -s <remote IP> -a <remote port> -f <file name> -m <mtu> -c <sws>");
        System.out.println("Receiver mode:");
        System.out.println("  java TCPend -p <port> -m <mtu> -c <sws> -f <file name>");
    }
} 