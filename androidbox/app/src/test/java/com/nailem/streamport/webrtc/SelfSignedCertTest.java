package com.nailem.streamport.webrtc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class SelfSignedCertTest {

    /** The hand-written DER must be a certificate a real X.509 parser accepts, signed by its own key. */
    @Test
    public void certificateParsesVerifiesAndNamesTheAddress() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = g.generateKeyPair();
        InetAddress ip = InetAddress.getByName("192.168.1.23");
        byte[] der = SelfSignedCert.make(pair, Collections.singletonList(ip));

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        cert.verify(pair.getPublic());
        cert.checkValidity();
        assertEquals(3, cert.getVersion());
        assertEquals("SHA256withECDSA", cert.getSigAlgName());
        assertTrue(cert.getSubjectX500Principal().getName().contains("StreamPort share"));
        boolean hasIp = false;
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        for (List<?> san : sans) {
            if (Integer.valueOf(7).equals(san.get(0)) && "192.168.1.23".equals(san.get(1))) hasIp = true;
        }
        assertTrue(hasIp);
        assertTrue(cert.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        assertEquals(-1, cert.getBasicConstraints());
        long days = (cert.getNotAfter().getTime() - cert.getNotBefore().getTime()) / 86_400_000L;
        assertTrue("validity " + days, days <= 398);
    }

    @Test
    public void oidEncoding() {
        // 1.2.840.10045.4.3.2 (ecdsa-with-SHA256)
        byte[] expected = {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
        org.junit.Assert.assertArrayEquals(expected, SelfSignedCert.oid(1, 2, 840, 10045, 4, 3, 2));
    }

    @Test
    public void browserNames() {
        assertEquals("Chrome on Windows", ShareServer.describe(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"));
        assertEquals("Edge on Windows", ShareServer.describe(
                "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/140.0 Safari/537.36 Edg/140.0"));
        assertEquals("Safari on macOS", ShareServer.describe(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15"));
        assertEquals("Safari on iOS", ShareServer.describe(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 27_0 like Mac OS X) AppleWebKit/605.1.15 Version/27.0 Mobile Safari/604.1"));
        assertEquals("Firefox on Linux", ShareServer.describe("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"));
        assertEquals("OBS", ShareServer.describe("OBS-Studio/31.0"));
        assertEquals("Browser", ShareServer.describe(null));
    }
}
