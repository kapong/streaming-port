package com.nailem.streamport.airplay;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stream decryption for mirroring (AES-CTR, keystream continues across packets) and realtime audio (AES-CBC,
 * restarted per packet, trailing partial block left in the clear).
 * <p>
 * Key schedule from java-airplay's FairPlayVideoCipher / FairPlayAudioDecryptor (MIT). The video cipher is
 * rewritten to keep its spare keystream explicitly, so a packet shorter than the leftover keystream no longer
 * reads past the end of the array.
 */
final class StreamCiphers {

    private StreamCiphers() {
    }

    /** {@code aesKey} for a sender that paired is SHA-512(aesKey ‖ secret)[:16]; unpaired senders use it as is. */
    static byte[] masterKey(byte[] aesKey, byte[] sharedSecret) throws GeneralSecurityException {
        if (sharedSecret.length == 0) {
            return Arrays.copyOf(aesKey, 16);
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update(aesKey);
        sha.update(sharedSecret);
        return Arrays.copyOf(sha.digest(), 16);
    }

    static final class Video {
        private final Cipher ctr;
        private final byte[] keystream = new byte[16];
        private int used = 16;

        Video(byte[] masterKey, String streamConnectionId) throws GeneralSecurityException {
            MessageDigest sha = MessageDigest.getInstance("SHA-512");
            sha.update(("AirPlayStreamKey" + streamConnectionId).getBytes(StandardCharsets.UTF_8));
            sha.update(masterKey, 0, 16);
            byte[] key = Arrays.copyOf(sha.digest(), 16);
            sha.update(("AirPlayStreamIV" + streamConnectionId).getBytes(StandardCharsets.UTF_8));
            sha.update(masterKey, 0, 16);
            byte[] iv = Arrays.copyOf(sha.digest(), 16);
            ctr = Cipher.getInstance("AES/CTR/NoPadding");
            ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        }

        /** Decrypts {@code len} bytes of {@code buf} in place. */
        void decrypt(byte[] buf, int off, int len) throws GeneralSecurityException {
            int end = off + len;
            int p = off;
            while (used < 16 && p < end) {
                buf[p++] ^= keystream[used++];
            }
            int whole = (end - p) / 16 * 16;
            if (whole > 0) {
                ctr.update(buf, p, whole, buf, p);
                p += whole;
            }
            if (p < end) {
                Arrays.fill(keystream, (byte) 0);
                ctr.update(keystream, 0, 16, keystream, 0);
                used = 0;
                while (p < end) {
                    buf[p++] ^= keystream[used++];
                }
            }
        }
    }

    static final class Audio {
        private final Cipher cbc;
        private final SecretKeySpec key;
        private final IvParameterSpec iv;

        Audio(byte[] masterKey, byte[] eiv) throws GeneralSecurityException {
            key = new SecretKeySpec(masterKey, "AES");
            iv = new IvParameterSpec(eiv);
            cbc = Cipher.getInstance("AES/CBC/NoPadding");
        }

        void decrypt(byte[] buf, int off, int len) throws GeneralSecurityException {
            int whole = len / 16 * 16;
            if (whole == 0) return;
            cbc.init(Cipher.DECRYPT_MODE, key, iv);
            cbc.update(buf, off, whole, buf, off);
        }
    }
}
