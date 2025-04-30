package TCPImplementation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TCPPacket {
    private static final int HEADER_SIZE = 24; // 24 bytes for header
    
    // Header fields
    private int sequenceNumber;     // 4 bytes
    private int acknowledgment;     // 4 bytes
    private long timestamp;         // 8 bytes
    private int length;             // 29 bits
    private boolean synFlag;        // 1 bit
    private boolean finFlag;        // 1 bit
    private boolean ackFlag;        // 1 bit
    private short checksum;         // 16 bits
    private byte[] data;            // Variable length

    public TCPPacket(byte[] data, int sequenceNumber, int acknowledgment,
                     boolean synFlag, boolean finFlag, boolean ackFlag) {
        this.data = data;
        this.sequenceNumber = sequenceNumber;
        this.acknowledgment = acknowledgment;
        this.timestamp = System.nanoTime();
        this.length = data != null ? data.length : 0;
        this.synFlag = synFlag;
        this.finFlag = finFlag;
        this.ackFlag = ackFlag;
        this.checksum = 0; // Will be computed when serializing
    }

    public byte[] serialize() {
        // Calculate total length including header
        int totalLength = HEADER_SIZE + (data != null ? data.length : 0);
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Write header fields
        buffer.putInt(sequenceNumber);      // 4 bytes
        buffer.putInt(acknowledgment);      // 4 bytes
        buffer.putLong(timestamp);          // 8 bytes
        
        // Combine length and flags into one 32-bit word
        // Length is 29 bits, flags are 3 bits
        int lengthAndFlags = (length << 3) | 
                           ((synFlag ? 1 : 0) << 2) | 
                           ((finFlag ? 1 : 0) << 1) | 
                           (ackFlag ? 1 : 0);
        buffer.putInt(lengthAndFlags);     // 4 bytes
        
        // Write 16 bits of zeros followed by 16 bits for checksum
        buffer.putShort((short)0);         // 2 bytes of zeros
        buffer.putShort((short)0);         // 2 bytes for checksum placeholder
        
        // Write data if present
        if (data != null) {
            buffer.put(data);
        }

        // Calculate and set checksum
        byte[] packet = buffer.array();
        short calculatedChecksum = calculateChecksum(packet);
        
        // Put checksum in the correct position (after the 16 zero bits)
        buffer.putShort(22, calculatedChecksum); // Position 22 is where checksum starts

        return buffer.array();
    }

    public static TCPPacket deserialize(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Read header fields
        int seq = buffer.getInt();             // 8 bytes
        int ack = buffer.getInt();             // 8 bytes
        long ts = buffer.getLong();            // 8 bytes
        
        // Read length and flags (4 bytes)
        int lengthAndFlags = buffer.getInt();
        int length = lengthAndFlags >>> 3;     // Right shift to get length (29 bits)
        boolean syn = ((lengthAndFlags >> 2) & 1) == 1;
        boolean fin = ((lengthAndFlags >> 1) & 1) == 1;
        boolean ackFlag = (lengthAndFlags & 1) == 1;
        
        // Skip 16 zero bits and read checksum
        short zeros = buffer.getShort();       // 2 bytes of zeros
        short checksum = buffer.getShort();    // 2 bytes of checksum
        
        // Read data if present
        byte[] data = null;
        if (length > 0) {
            data = new byte[length];
            buffer.get(data);
        }

        // Create packet
        TCPPacket tcpPacket = new TCPPacket(data, seq, ack, syn, fin, ackFlag);
        tcpPacket.timestamp = ts;
        tcpPacket.checksum = checksum;

        // Verify checksum
        // First, zero out the checksum field in the original packet
        byte[] verifyPacket = packet.clone();
        ByteBuffer.wrap(verifyPacket).putShort(22, (short)0);
        
        short calculatedChecksum = calculateChecksum(verifyPacket);
        if (calculatedChecksum != checksum) {
            return null; // Invalid checksum
        }

        return tcpPacket;
    }

    private static short calculateChecksum(byte[] data) {
        int sum = 0;
        
        // Process 16-bit chunks
        for (int i = 0; i < data.length - 1; i += 2) {
            int chunk = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += chunk;
            
            // Handle carry
            if ((sum & 0xFFFF0000) > 0) {
                sum &= 0xFFFF;
                sum++;
            }
        }
        
        // Handle last byte if length is odd
        if (data.length % 2 != 0) {
            sum += ((data[data.length - 1] & 0xFF) << 8);
            if ((sum & 0xFFFF0000) > 0) {
                sum &= 0xFFFF;
                sum++;
            }
        }
        
        // Take one's complement
        return (short)(~sum & 0xFFFF);
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    // Getters
    public int getSequenceNumber() { return sequenceNumber; }
    public int getAcknowledgment() { return acknowledgment; }
    public long getTimestamp() { return timestamp; }
    public int getLength() { return length; }
    public boolean isSynFlag() { return synFlag; }
    public boolean isFinFlag() { return finFlag; }
    public boolean isAckFlag() { return ackFlag; }
    public byte[] getData() { return data; }
    public short getChecksum() { return checksum; }
} 