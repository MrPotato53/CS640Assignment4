// import TCPPacket;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class TCPSender {
    private static final int MAX_RETRIES = 16;
    private static final double ALPHA = 0.875;
    private static final double BETA = 0.75;
    private static final long INITIAL_TIMEOUT = 5000000000L; // 5 seconds in nanoseconds

    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;
    private String filename;
    private int mtu;
    private int windowSize;
    private int baseSequenceNumber;
    private int nextSequenceNumber;
    private int expectedAck;
    private long timeout;
    private long estimatedRTT;
    private long deviationRTT;
    private boolean connectionEstablished;
    private boolean connectionClosed;
    private Map<Integer, byte[]> unackedPackets;
    private Map<Integer, Long> packetTimestamps;
    private Map<Integer, Integer> retryCount;  // Track retries per sequence number
    private ScheduledExecutorService scheduler;
    private int duplicateAcks;
    private int lastAckNumber;
    private int lastFinSequence = -1;
    private long totalBytesSent;
    private long totalPacketsSent;
    private long totalRetransmissions;
    private long totalDuplicateAcks;

    public TCPSender(String remoteIP, int remotePort, String filename, int mtu, int windowSize) 
            throws UnknownHostException, SocketException {
        this.socket = new DatagramSocket();
        this.remoteAddress = InetAddress.getByName(remoteIP);
        this.remotePort = remotePort;
        this.filename = filename;
        this.mtu = mtu;
        this.windowSize = windowSize;
        this.baseSequenceNumber = 0;
        this.nextSequenceNumber = 0;
        this.expectedAck = 0;
        this.timeout = INITIAL_TIMEOUT;
        this.estimatedRTT = 0;
        this.deviationRTT = 0;
        this.connectionEstablished = false;
        this.connectionClosed = false;
        this.unackedPackets = new ConcurrentHashMap<>();
        this.packetTimestamps = new ConcurrentHashMap<>();
        this.retryCount = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.duplicateAcks = 0;
        this.lastAckNumber = -1;
        this.lastFinSequence = -1;
        this.totalBytesSent = 0;
        this.totalPacketsSent = 0;
        this.totalRetransmissions = 0;
        this.totalDuplicateAcks = 0;
    }

    public void start() throws IOException {
        // Start connection
        establishConnection();
        
        // Start receiving thread
        new Thread(this::receiveLoop).start();
        
        // Send file
        sendFile();
        
        // Close connection
        closeConnection();
        
        // Print statistics
        printStatistics();
    }

    private void establishConnection() throws IOException {
        // Send SYN
        TCPPacket synPacket = new TCPPacket(null, baseSequenceNumber, 0, true, false, false);
        sendPacket(synPacket);
        
        // Wait for SYN-ACK
        while (!connectionEstablished) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection establishment interrupted");
            }
        }

        // Send ACK
        TCPPacket ackPacket = new TCPPacket(
            /*data=*/null,
            /*seq=*/nextSequenceNumber,
            /*ack=*/lastAckNumber,
            /*syn=*/false,
            /*fin=*/false,
            /*ack=*/true
        );
        sendPacket(ackPacket);
    }

    private void sendFile() throws IOException {
        File file = new File(filename);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[mtu - 16]; // Subtract header size
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] data = Arrays.copyOf(buffer, bytesRead);
                sendData(data);
            }
        }
    }

    private void sendData(byte[] data) throws IOException {
        while (nextSequenceNumber - baseSequenceNumber >= windowSize) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Send interrupted");
            }
        }

        // use the most recent ACK from the receiver, not 0
        TCPPacket packet = new TCPPacket(data, nextSequenceNumber, lastAckNumber, false, false, true);
        sendPacket(packet);
        nextSequenceNumber += data.length;
    }

    private void sendPacket(TCPPacket packet) throws IOException {
        byte[] serialized = packet.serialize();
        DatagramPacket datagram = new DatagramPacket(serialized, serialized.length, 
                                                    remoteAddress, remotePort);
        
        // Store packet for potential retransmission
        if (packet.getData() != null) {
            int seqNum = packet.getSequenceNumber();
            unackedPackets.put(seqNum, packet.getData());
            packetTimestamps.put(seqNum, System.nanoTime());
            retryCount.putIfAbsent(seqNum, 0);  // Initialize retry count
        }
        
        socket.send(datagram);
        totalPacketsSent++;
        totalBytesSent += packet.getLength();
        
        // Print packet info
        printPacketInfo("snd", packet);
        
        // Schedule timeout
        scheduleTimeout(packet.getSequenceNumber());
    }

    private void scheduleTimeout(int sequenceNumber) {
        scheduler.schedule(() -> {
            if (unackedPackets.containsKey(sequenceNumber)) {
                handleTimeout(sequenceNumber);
            }
        }, timeout, TimeUnit.NANOSECONDS);
    }

    private void handleTimeout(int sequenceNumber) {
        try {
            byte[] data = unackedPackets.get(sequenceNumber);
            if (data != null) {
                int retries = retryCount.get(sequenceNumber);
                if (retries >= MAX_RETRIES) {
                    // Maximum retries reached, report error and close connection
                    System.err.println("Error: Maximum retransmission attempts (" + MAX_RETRIES + 
                                    ") reached for sequence number " + sequenceNumber);
                    connectionClosed = true;
                    close();
                    throw new IOException("Maximum retransmission attempts reached");
                }
                
                // Increment retry count
                retryCount.put(sequenceNumber, retries + 1);
                
                TCPPacket packet = new TCPPacket(data, sequenceNumber, lastAckNumber, false, false, true);
                sendPacket(packet);
                totalRetransmissions++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[65535];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        
        while (!connectionClosed) {
            try {
                socket.receive(datagram);
                byte[] received = Arrays.copyOf(datagram.getData(), datagram.getLength());
                TCPPacket packet = TCPPacket.deserialize(received);
                
                if (packet != null) {
                    handleReceivedPacket(packet);
                }
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleReceivedPacket(TCPPacket packet) {
        printPacketInfo("rcv", packet);
        
        if (packet.isAckFlag()) {
            int ackNumber = packet.getAcknowledgment();
            
            if (ackNumber == lastAckNumber) {
                duplicateAcks++;
                totalDuplicateAcks++;
                if (duplicateAcks >= 3) {
                    // Fast retransmit
                    handleFastRetransmit(ackNumber);
                    duplicateAcks = 0;
                }
            } else {
                duplicateAcks = 0;
                lastAckNumber = ackNumber;
                
                // Update RTT estimates
                long currentTime = System.nanoTime();
                long sampleRTT = currentTime - packet.getTimestamp();
                
                // Check if this is the first acknowledgment (S = 0)
                if (ackNumber == 1) {
                    // First acknowledgment
                    estimatedRTT = sampleRTT;
                    deviationRTT = 0;
                    timeout = 2 * estimatedRTT;
                } else {
                    // Subsequent acknowledgments
                    long sampleDeviation = Math.abs(sampleRTT - estimatedRTT);
                    deviationRTT = (long)(BETA * deviationRTT + (1 - BETA) * sampleDeviation);
                    estimatedRTT = (long)(ALPHA * estimatedRTT + (1 - ALPHA) * sampleRTT);
                    timeout = estimatedRTT + 4 * deviationRTT;
                }
                
                // Remove acknowledged packets and their retry counts
                while (baseSequenceNumber < ackNumber) {
                    unackedPackets.remove(baseSequenceNumber);
                    packetTimestamps.remove(baseSequenceNumber);
                    retryCount.remove(baseSequenceNumber);  // Remove retry count
                    baseSequenceNumber += unackedPackets.get(baseSequenceNumber) != null ? 
                                       unackedPackets.get(baseSequenceNumber).length : 0;
                }
            }
        }
        
        if (packet.isSynFlag() && packet.isAckFlag()) {
            connectionEstablished = true;
        }
        
        if (packet.isFinFlag() && packet.isAckFlag()) {
            // record the peer’s FIN seq so we can ACK it
            lastFinSequence = packet.getSequenceNumber();
            connectionClosed = true;
        }
    }

    private void handleFastRetransmit(int ackNumber) {
        try {
            byte[] data = unackedPackets.get(ackNumber);
            if (data != null) {
                // include the current ACK from the receiver
                TCPPacket packet = new TCPPacket(data, ackNumber, lastAckNumber, false, false, true);
                sendPacket(packet);
                totalRetransmissions++;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void closeConnection() throws IOException {
        // Send FIN+ACK using the current ACK
        TCPPacket finPacket = new TCPPacket(null, nextSequenceNumber, lastAckNumber, false, true, true);
        sendPacket(finPacket);
        
        // Wait for FIN-ACK
        while (!connectionClosed) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Connection closure interrupted");
            }
        }

        TCPPacket finalAck = new TCPPacket(null, nextSequenceNumber, lastFinSequence + 1, false, false, true);
        sendPacket(finalAck);
    }

    private void printPacketInfo(String type, TCPPacket packet) {
        StringBuilder flags = new StringBuilder();
        if (packet.isSynFlag()) flags.append("S");
        if (packet.isFinFlag()) flags.append("F");
        if (packet.isAckFlag()) flags.append("A");
        if (packet.getData() != null) flags.append("D");
        
        System.out.printf("%s %.3f %s %d %d %d%n",
            type,
            System.nanoTime() / 1e9,
            flags.toString(),
            packet.getSequenceNumber(),
            packet.getLength(),
            packet.getAcknowledgment());
    }

    private void printStatistics() {
        System.out.println("\nTransfer Statistics:");
        System.out.println("Total bytes transferred: " + totalBytesSent);
        System.out.println("Total packets sent: " + totalPacketsSent);
        System.out.println("Total retransmissions: " + totalRetransmissions);
        System.out.println("Total duplicate acknowledgments: " + totalDuplicateAcks);
    }

    public void close() {
        socket.close();
        scheduler.shutdown();
    }
} 