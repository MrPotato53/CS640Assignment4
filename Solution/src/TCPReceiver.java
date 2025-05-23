// import TCPPacket;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TCPReceiver {
    private static final int HEADER_SIZE = 24;

    private DatagramSocket socket;
    private long startTime;
    private String filename;
    private int mtu;
    private int windowSize;
    private int expectedSequenceNumber;
    private int sendSequenceNumber;
    private boolean connectionEstablished;
    private boolean connectionClosed;
    private Map<Integer, byte[]> outOfOrderPackets;
    private FileOutputStream fileOutputStream;
    private long totalBytesReceived;
    private long totalPacketsReceived;
    private long totalOutOfOrderPackets;
    private long totalChecksumErrors;

    public TCPReceiver(int port, String filename, int mtu, int windowSize) 
            throws SocketException, IOException {
        this.startTime = 0;
        this.socket = new DatagramSocket(port);
        this.filename = filename;
        this.mtu = mtu;
        this.windowSize = windowSize;
        this.expectedSequenceNumber = 0;
        this.sendSequenceNumber = 0;
        this.connectionEstablished = false;
        this.connectionClosed = false;
        this.outOfOrderPackets = new ConcurrentHashMap<>();
        this.fileOutputStream = new FileOutputStream(this.filename);
        this.totalBytesReceived = 0;
        this.totalPacketsReceived = 0;
        this.totalOutOfOrderPackets = 0;
        this.totalChecksumErrors = 0;
    }

    public void start() throws IOException {
        // Use MTU for the receive buffer
        byte[] buffer = new byte[mtu + HEADER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);

        
        while (!connectionClosed) {
            try {
                socket.receive(datagram);
                byte[] received = Arrays.copyOf(datagram.getData(), datagram.getLength());
                TCPPacket packet = TCPPacket.deserialize(received);
                
                if (packet != null) {
                    handleReceivedPacket(packet, datagram.getAddress(), datagram.getPort());
                } else {
                    totalChecksumErrors++;
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    e.printStackTrace();
                }
            }
        }
        
        // Print statistics
        printStatistics();
    }

    private void handleReceivedPacket(TCPPacket packet, InetAddress senderAddress, int senderPort) 
            throws IOException {

        long sentTs = packet.getTimestamp();
        printPacketInfo("rcv", packet);
        totalPacketsReceived++;
        
        // Only process SYN before connection established
        if (!connectionEstablished) {
            if (packet.isSynFlag() && !packet.isAckFlag()) {
                // Received SYN, send SYN-ACK
                TCPPacket synAck = new TCPPacket(null, sendSequenceNumber, packet.getSequenceNumber() + 1, 
                                               true, false, true);
                synAck.setTimestamp(sentTs);
                sendSequenceNumber++;
                sendPacket(synAck, senderAddress, senderPort);
                connectionEstablished = true;
                expectedSequenceNumber = 1;
            }
            // ignore all other packets until handshake completes
            return;
        }

        // Reject packets exceeding advertised MTU payload
        int payloadLimit = mtu;
        if (packet.getLength() > payloadLimit) {
            // drop
            return;
        }

        // Handle FIN after handshake
        if (packet.isFinFlag()) {
            // Send FIN+ACK using the current ACK
            TCPPacket finAck = new TCPPacket(null, sendSequenceNumber, packet.getSequenceNumber() + 1,
                                             false, true, true);
            finAck.setTimestamp(sentTs);
            sendPacket(finAck, senderAddress, senderPort);
            sendSequenceNumber++;
            // 2) Close file and mark closed
            connectionClosed = true;
            fileOutputStream.close();
            return;
        }

        // Handle data packets
        if (packet.getData() != null) {
            int seqNum = packet.getSequenceNumber();
            
            // Enforce receive window
            if (seqNum < expectedSequenceNumber || seqNum >= expectedSequenceNumber + windowSize) {
                // drop and ACK current expected
                TCPPacket ack = new TCPPacket(null, sendSequenceNumber, expectedSequenceNumber,
                                             false, false, true);
                sendPacket(ack, senderAddress, senderPort);
                return;
            }

            if (seqNum == expectedSequenceNumber) {
                // In-order packet
                fileOutputStream.write(packet.getData());
                totalBytesReceived += packet.getLength();
                expectedSequenceNumber += packet.getLength();
                
                // Process any buffered out-of-order packets
                processBufferedPackets();
                
                // Send cumulative ACK
                TCPPacket ack = new TCPPacket(null, sendSequenceNumber, expectedSequenceNumber, 
                                             false, false, true);
                ack.setTimestamp(sentTs);
                sendPacket(ack, senderAddress, senderPort);
            } else {
                // Out-of-order packet within window
                outOfOrderPackets.put(seqNum, packet.getData());
                totalOutOfOrderPackets++;
                
                // Send duplicate ACK for expected
                TCPPacket dupAck = new TCPPacket(null, sendSequenceNumber, expectedSequenceNumber, 
                                                false, false, true);
                dupAck.setTimestamp(sentTs);
                sendPacket(dupAck, senderAddress, senderPort);
            }
        }
    }

    private void processBufferedPackets() throws IOException {
        while (outOfOrderPackets.containsKey(expectedSequenceNumber)) {
            byte[] data = outOfOrderPackets.remove(expectedSequenceNumber);
            fileOutputStream.write(data);
            totalBytesReceived += data.length;
            expectedSequenceNumber += data.length;
        }
    }

    private void sendPacket(TCPPacket packet, InetAddress address, int port) throws IOException {
        byte[] serialized = packet.serialize();
        DatagramPacket datagram = new DatagramPacket(serialized, serialized.length, address, port);
        socket.send(datagram);
        printPacketInfo("snd", packet);
    }

    private void printPacketInfo(String type, TCPPacket packet) {
        if(startTime == 0) {
            startTime = System.nanoTime();
        }
        
        System.out.printf("%s %.3f %s %s %s %s %d %d %d%n",
            type,
            (System.nanoTime() - startTime) / 1e9,
            packet.isSynFlag() ? "S" : "-",
            packet.isFinFlag() ? "F" : "-",
            packet.isAckFlag() ? "A" : "-",
            packet.getData() != null ? "D" : "-",
            packet.getSequenceNumber(),
            packet.getLength(),
            packet.getAcknowledgment());
    }

    private void printStatistics() {
        System.out.println("\nTransfer Statistics:");
        System.out.println("Total bytes received: " + totalBytesReceived);
        System.out.println("Total packets received: " + totalPacketsReceived);
        System.out.println("Total out-of-order packets: " + totalOutOfOrderPackets);
        System.out.println("Total checksum errors: " + totalChecksumErrors);
    }

    public void close() {
        socket.close();
        try {
            fileOutputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
