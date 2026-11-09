package com.remotemate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/** The phone's own identity for talking to the TV: an RSA key and a self-signed certificate. */
final class Certs {
    private Certs() {}

    static final char[] PASS = "tvremote".toCharArray();
    static final String ALIAS = "client";

    static synchronized KeyStore loadOrCreate(File file) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                ks.load(in, PASS);
                if (ks.containsAlias(ALIAS)) return ks;
            } catch (Exception ignored) { }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = selfSigned(kp, "atvremote");
        ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), PASS, new Certificate[]{cert});
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { ks.store(out, PASS); }
        if (!tmp.renameTo(file)) throw new Exception("Couldn't save the remote's key");
        return ks;
    }

    static X509Certificate clientCert(KeyStore ks) throws Exception {
        return (X509Certificate) ks.getCertificate(ALIAS);
    }

    /** Remembers each TV's certificate (by host) once it's paired, so a different device on that address is refused. */
    interface Pins {
        String get(String host);
        void put(String host, String fingerprint);
    }

    static String fingerprint(Certificate c) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(c.getPublicKey().getEncoded());
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
        return b.toString();
    }

    /**
     * TLS for talking to TVs. TVs use their own self-signed certificates, so there's no CA to check against:
     * - pairing port: any certificate is allowed, because the code on the TV screen is mixed with the TV's key
     *   (see GoogleTvDriver.secretFor), which proves it's the right TV;
     * - remote port: the certificate must match the one pinned when pairing (or is pinned on first use).
     */
    static SSLContext context(KeyStore ks, int pairPort, Pins pins) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS);
        TrustManager trustTv = new X509ExtendedTrustManager() {
            void check(X509Certificate[] chain, java.net.Socket s) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("The TV sent no certificate.");
                if (s == null || s.getInetAddress() == null) throw new CertificateException("Unknown TV.");
                if (s.getPort() == pairPort) return; // protected by the pairing code
                String host = s.getInetAddress().getHostAddress(), fp;
                try { fp = fingerprint(chain[0]); } catch (Exception e) { throw new CertificateException(e); }
                String pinned = pins.get(host);
                if (pinned == null) pins.put(host, fp);
                else if (!pinned.equals(fp)) throw new CertificateException("This isn't the TV that was paired at " + host + ".");
            }
            @Override public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s) throws CertificateException { check(c, s); }
            @Override public void checkServerTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws CertificateException { throw new CertificateException("Not used"); }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("Not used"); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) throws CertificateException { throw new CertificateException("Not a server"); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a, javax.net.ssl.SSLEngine e) throws CertificateException { throw new CertificateException("Not a server"); }
            @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("Not a server"); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{trustTv}, new SecureRandom());
        return ctx;
    }

    /** Unsigned big-endian bytes, without a leading sign byte. */
    static byte[] magnitude(BigInteger x) {
        byte[] b = x.toByteArray();
        if (b.length > 1 && b[0] == 0) return Arrays.copyOfRange(b, 1, b.length);
        return b;
    }

    // ---------- tiny X.509 builder ----------
    private static final byte[] SHA256_RSA = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] NULL = {0x05, 0x00};
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    static X509Certificate selfSigned(KeyPair kp, String cn) throws Exception {
        byte[] alg = der(0x30, SHA256_RSA, NULL);
        byte[] name = der(0x30, der(0x31, der(0x30, OID_CN, der(0x0C, cn.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = der(0x30, utc(new Date(now - 86_400_000L)), utc(new Date(now + 10L * 365 * 86_400_000L)));
        byte[] tbs = der(0x30,
                der(0xA0, der(0x02, new byte[]{2})),
                der(0x02, BigInteger.valueOf(1000 + new SecureRandom().nextInt(1_000_000)).toByteArray()),
                alg, name, validity, name,
                kp.getPublic().getEncoded(),
                der(0xA3, der(0x30,
                        // basicConstraints: CA true, path length 0 (same as the Google TV remote library)
                        der(0x30, new byte[]{0x06, 0x03, 0x55, 0x1D, 0x13}, der(0x04, der(0x30, new byte[]{0x01, 0x01, (byte) 0xFF}, der(0x02, new byte[]{0})))),
                        // subjectAltName: DNS name
                        der(0x30, new byte[]{0x06, 0x03, 0x55, 0x1D, 0x11}, der(0x04, der(0x30, der(0x82, cn.getBytes(StandardCharsets.US_ASCII))))))));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = der(0x30, tbs, alg, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] utc(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        int n = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(n + 6);
        out.write(tag);
        if (n < 0x80) out.write(n);
        else if (n < 0x100) { out.write(0x81); out.write(n); }
        else if (n < 0x10000) { out.write(0x82); out.write(n >> 8); out.write(n & 0xff); }
        else { out.write(0x83); out.write(n >> 16); out.write((n >> 8) & 0xff); out.write(n & 0xff); }
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
