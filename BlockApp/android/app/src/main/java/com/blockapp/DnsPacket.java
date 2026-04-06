package com.blockapp;

import java.nio.ByteBuffer;

/**
 * Minimal DNS packet parser and builder.
 *
 * We only handle A-record queries (type 1, class 1).
 * Enough to intercept Chrome DNS lookups.
 */
public class DnsPacket {

    public static final int TYPE_A = 1;
    public static final int CLASS_IN = 1;

    public short transactionId;
    public String queryName;   // e.g. "www.youtube.com"
    public int    queryType;
    public int    queryClass;
    public byte[] rawPayload;  // the original full DNS payload (UDP data only)

    // ── Parse ────────────────────────────────────────────────────────────────

    public static DnsPacket parse(byte[] data, int offset, int length) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
            DnsPacket pkt  = new DnsPacket();
            pkt.rawPayload = new byte[length];
            System.arraycopy(data, offset, pkt.rawPayload, 0, length);

            pkt.transactionId = buf.getShort();
            short flags   = buf.getShort();
            int   qdcount = buf.getShort() & 0xFFFF;
            buf.getShort(); // ANCOUNT
            buf.getShort(); // NSCOUNT
            buf.getShort(); // ARCOUNT

            // Only handle queries (QR bit = 0) with exactly 1 question
            if ((flags & 0x8000) != 0 || qdcount != 1) return null;

            // Parse QNAME
            StringBuilder name = new StringBuilder();
            while (buf.hasRemaining()) {
                int labelLen = buf.get() & 0xFF;
                if (labelLen == 0) break;
                if ((labelLen & 0xC0) == 0xC0) { buf.get(); break; } // pointer, skip
                byte[] label = new byte[labelLen];
                buf.get(label);
                if (name.length() > 0) name.append('.');
                name.append(new String(label));
            }
            pkt.queryName  = name.toString().toLowerCase();
            pkt.queryType  = buf.getShort() & 0xFFFF;
            pkt.queryClass = buf.getShort() & 0xFFFF;

            return pkt;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Build NXDOMAIN response ───────────────────────────────────────────────

    public byte[] buildNxDomainResponse() {
        byte[] response = rawPayload.clone();
        // Flags: QR=1, AA=1, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        response[2] = (byte) 0x85;
        response[3] = (byte) 0x83;
        return response;
    }

    // ── Build A-record response pointing to a given IPv4 address ─────────────

    public byte[] buildARecordResponse(byte[] ipv4) {
        // Original question section length
        int questionLen = rawPayload.length - 12; // everything after the 12-byte header
        int totalLen    = rawPayload.length + 16; // +16 for one A record answer
        byte[] response = new byte[totalLen];

        // Copy header + question section
        System.arraycopy(rawPayload, 0, response, 0, rawPayload.length);

        // Patch flags: QR=1, AA=1, RD=1, RA=1, RCODE=0
        response[2] = (byte) 0x85;
        response[3] = (byte) 0x80;

        // ANCOUNT = 1
        response[6] = 0x00;
        response[7] = 0x01;

        // Answer section (pointer to question name, A record, TTL=30, RDATA)
        int pos = rawPayload.length;
        response[pos++] = (byte) 0xC0;  // pointer
        response[pos++] = 0x0C;          // -> offset 12 (start of question)
        response[pos++] = 0x00; response[pos++] = 0x01; // TYPE A
        response[pos++] = 0x00; response[pos++] = 0x01; // CLASS IN
        response[pos++] = 0x00; response[pos++] = 0x00; response[pos++] = 0x00; response[pos++] = 0x1E; // TTL 30
        response[pos++] = 0x00; response[pos++] = 0x04; // RDLENGTH 4
        response[pos++] = ipv4[0]; response[pos++] = ipv4[1];
        response[pos++] = ipv4[2]; response[pos++] = ipv4[3];

        return response;
    }

    // ── Check if domain matches a blocked pattern ─────────────────────────────

    /**
     * Returns true if `domain` equals or is a sub-domain of `pattern`.
     * e.g. pattern="youtube.com" matches "youtube.com", "www.youtube.com", "m.youtube.com"
     */
    public static boolean matches(String domain, String pattern) {
        if (domain == null || pattern == null) return false;
        String d = domain.toLowerCase();
        String p = pattern.toLowerCase();
        return d.equals(p) || d.endsWith("." + p);
    }
}
