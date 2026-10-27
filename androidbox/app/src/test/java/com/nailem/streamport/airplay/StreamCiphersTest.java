package com.nailem.streamport.airplay;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class StreamCiphersTest {

    /** Packets of any size, split anywhere, decrypt the same as one continuous AES-CTR stream. */
    @Test
    public void videoKeystreamContinuesAcrossPackets() throws Exception {
        byte[] master = new byte[16];
        new Random(3).nextBytes(master);
        Random r = new Random(4);
        byte[] plain = new byte[10_000];
        r.nextBytes(plain);

        // Reference: what the sender does, one continuous CTR stream.
        StreamCiphers.Video ref = new StreamCiphers.Video(master, "12345");
        byte[] whole = plain.clone();
        ref.decrypt(whole, 0, whole.length);

        StreamCiphers.Video v = new StreamCiphers.Video(master, "12345");
        byte[] pieces = plain.clone();
        int p = 0;
        while (p < pieces.length) {
            int n = Math.min(pieces.length - p, r.nextInt(40));
            v.decrypt(pieces, p, n);
            p += n;
        }
        assertArrayEquals(whole, pieces);
    }

    @Test
    public void videoMatchesPlainCtr() throws Exception {
        byte[] master = new byte[16];
        StreamCiphers.Video v = new StreamCiphers.Video(master, "1");
        byte[] data = new byte[100];
        v.decrypt(data, 0, 7);
        v.decrypt(data, 7, 93);
        // Same key derivation, done by hand.
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-512");
        sha.update("AirPlayStreamKey1".getBytes());
        sha.update(master);
        byte[] key = java.util.Arrays.copyOf(sha.digest(), 16);
        sha.update("AirPlayStreamIV1".getBytes());
        sha.update(master);
        byte[] iv = java.util.Arrays.copyOf(sha.digest(), 16);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        assertArrayEquals(c.doFinal(new byte[100]), data);
    }
}
