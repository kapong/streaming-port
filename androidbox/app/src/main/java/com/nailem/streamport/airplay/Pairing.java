package com.nailem.streamport.airplay;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Legacy AirPlay pairing: {@code /pair-setup} hands out our Ed25519 key, {@code /pair-verify} runs an X25519
 * exchange whose secret salts the stream keys.
 * <p>
 * Based on java-airplay's PairingHandshake (MIT). Changes: request lengths are checked (the original read into
 * fixed buffers and ignored short reads), and the second verify step is refused unless the first one ran.
 * <p>
 * This is not authentication. The sender's Ed25519 key is self-asserted and nothing on the TV trusts it, so a
 * verified pairing proves only that the same sender did both steps. Access control is the on-screen approval
 * in {@link Hub}.
 */
final class Pairing {

    /** One Ed25519 identity per receiver process; the mDNS {@code pk} field advertises its public half. */
    static final class Identity {
        final KeyPair keyPair = new KeyPairGenerator().generateKeyPair();

        byte[] publicKey() {
            return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
        }
    }

    private final Identity identity;
    private byte[] ecdhOurs;
    private byte[] ecdhTheirs;
    private byte[] edTheirs;
    private byte[] sharedSecret;
    private boolean verified;

    Pairing(Identity identity) {
        this.identity = identity;
    }

    byte[] setup() {
        return identity.publicKey();
    }

    /** Returns the response body, or null when the request is malformed or out of order. */
    byte[] verify(byte[] body) throws GeneralSecurityException {
        if (body == null || body.length < 4 + 64) {
            return null;
        }
        if (body[0] != 0) {
            ecdhTheirs = Arrays.copyOfRange(body, 4, 36);
            edTheirs = Arrays.copyOfRange(body, 36, 68);
            Curve25519 curve = Curve25519.getInstance(Curve25519.BEST);
            Curve25519KeyPair ours = curve.generateKeyPair();
            ecdhOurs = ours.getPublicKey();
            sharedSecret = curve.calculateAgreement(ecdhTheirs, ours.getPrivateKey());
            verified = false;

            byte[] toSign = concat(ecdhOurs, ecdhTheirs);
            EdDSAEngine signer = new EdDSAEngine();
            signer.initSign(identity.keyPair.getPrivate());
            byte[] signature = signer.signOneShot(toSign);
            return concat(ecdhOurs, cipher().doFinal(signature));
        }
        if (sharedSecret == null) {
            return null;
        }
        Cipher c = cipher();
        // The sender's signature is encrypted with the keystream right after ours, so skip one signature's worth.
        c.update(new byte[64]);
        byte[] signature = c.doFinal(Arrays.copyOfRange(body, 4, 68));
        EdDSAEngine verifier = new EdDSAEngine();
        verifier.initVerify(new EdDSAPublicKey(new EdDSAPublicKeySpec(edTheirs,
                EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519))));
        verified = verifier.verifyOneShot(concat(ecdhTheirs, ecdhOurs), signature);
        return new byte[0];
    }

    boolean isVerified() {
        return verified;
    }

    /** X25519 secret from pair-verify, or an empty array when the sender skipped pairing. */
    byte[] sharedSecret() {
        return sharedSecret != null ? sharedSecret : new byte[0];
    }

    private Cipher cipher() throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update("Pair-Verify-AES-Key".getBytes(StandardCharsets.UTF_8));
        sha.update(sharedSecret);
        byte[] key = Arrays.copyOf(sha.digest(), 16);
        sha.update("Pair-Verify-AES-IV".getBytes(StandardCharsets.UTF_8));
        sha.update(sharedSecret);
        byte[] iv = Arrays.copyOf(sha.digest(), 16);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
