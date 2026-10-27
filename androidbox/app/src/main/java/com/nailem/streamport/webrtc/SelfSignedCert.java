package com.nailem.streamport.webrtc;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The share page's TLS certificate: self-signed by the box (no setup needed), so browsers warn once per device.
 * Browsers only allow screen sharing on HTTPS pages, which is the whole reason for it.
 * <p>
 * EC P-256, valid 397 days (Apple rejects longer-lived TLS certificates even when the user accepts them), with the
 * box's current IPv4 addresses as subject alternative names. Made again when it nears expiry or the box's address
 * changes. Written by hand in DER so no certificate library is needed; the key never leaves the app's private files.
 */
final class SelfSignedCert {

    private static final String TAG = "StreamPort";
    private static final long VALID_MS = 397L * 24 * 3600 * 1000;
    private static final long RENEW_BEFORE_MS = 30L * 24 * 3600 * 1000;
    private static final char[] STORE_PASSWORD = "streamport".toCharArray(); // in-memory store only

    private SelfSignedCert() {
    }

    /** A TLS context for the share server, making or renewing the certificate if needed. */
    static SSLContext context(Context c) throws GeneralSecurityException, IOException {
        File dir = new File(c.getFilesDir(), "share-tls");
        File keyFile = new File(dir, "key.pk8");
        File certFile = new File(dir, "cert.der");
        List<InetAddress> addresses = localIPv4();
        X509Certificate cert = null;
        PrivateKey key = null;
        java.security.PublicKey publicKey = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                cert = parse(Files.readAllBytes(certFile.toPath()));
                key = KeyFactory.getInstance("EC").generatePrivate(
                        new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                publicKey = cert.getPublicKey();
                if (!stillGood(cert, addresses)) cert = null;
            } catch (GeneralSecurityException | IOException e) {
                cert = null;
                key = null;
            }
        }
        if (cert == null) {
            // A renewal keeps the key, so a browser that remembers the key's fingerprint isn't surprised more
            // often than it must be; a new key only when there is none.
            KeyPair pair;
            if (key != null && publicKey != null) {
                pair = new KeyPair(publicKey, key);
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
                pair = g.generateKeyPair();
            }
            byte[] der = make(pair, addresses);
            cert = parse(der);
            key = pair.getPrivate();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
            write(keyFile, key.getEncoded());
            write(certFile, der);
            Log.i(TAG, "share page: new certificate for " + addresses);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("share", key, STORE_PASSWORD, new java.security.cert.Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static boolean stillGood(X509Certificate cert, List<InetAddress> addresses) {
        if (cert.getNotAfter().getTime() - System.currentTimeMillis() < RENEW_BEFORE_MS) return false;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            List<String> ips = new ArrayList<>();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (Integer.valueOf(7).equals(san.get(0))) ips.add(String.valueOf(san.get(1)));
                }
            }
            for (InetAddress a : addresses) {
                if (!ips.contains(a.getHostAddress())) return false;
            }
            return true;
        } catch (java.security.cert.CertificateParsingException e) {
            return false;
        }
    }

    static List<InetAddress> localIPv4() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a);
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    // ---- DER ----

    private static final byte[] ECDSA_SHA256 = oid(1, 2, 840, 10045, 4, 3, 2);

    static byte[] make(KeyPair pair, List<InetAddress> addresses) throws GeneralSecurityException {
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F; // positive
        long now = System.currentTimeMillis();
        byte[] name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0C, "StreamPort share".getBytes(StandardCharsets.UTF_8)))));

        List<byte[]> sans = new ArrayList<>();
        for (InetAddress a : addresses) sans.add(tlv(0x87, a.getAddress()));
        byte[] extensions = seq(
                seq(oid(2, 5, 29, 17), tlv(0x04, seq(sans.toArray(new byte[0][])))),
                seq(oid(2, 5, 29, 19), tlv(0x04, seq())),
                seq(oid(2, 5, 29, 15), tlv(0x01, new byte[]{(byte) 0xFF}), tlv(0x04, tlv(0x03, new byte[]{7, (byte) 0x80}))),
                seq(oid(2, 5, 29, 37), tlv(0x04, seq(oid(1, 3, 6, 1, 5, 5, 7, 3, 1)))));

        byte[] tbs = seq(
                tlv(0xA0, tlv(0x02, new byte[]{2})),
                tlv(0x02, serial),
                seq(ECDSA_SHA256),
                name,
                seq(utc(now - 3600_000L), utc(now + VALID_MS)),
                name,
                pair.getPublic().getEncoded(),
                tlv(0xA3, extensions));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, seq(ECDSA_SHA256), tlv(0x03, bits));
    }

    private static byte[] utc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(int... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(40 * parts[0] + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            int n = 0;
            for (int t = v; t > 0x7F; t >>>= 7) n++;
            for (int k = n; k >= 0; k--) {
                int chunk = (v >>> (7 * k)) & 0x7F;
                b.write(k > 0 ? chunk | 0x80 : chunk);
            }
        }
        return tlv(0x06, b.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int len = value.length;
        if (len < 0x80) {
            b.write(len);
        } else if (len < 0x100) {
            b.write(0x81);
            b.write(len);
        } else {
            b.write(0x82);
            b.write(len >>> 8);
            b.write(len & 0xFF);
        }
        b.write(value, 0, len);
        return b.toByteArray();
    }
}
