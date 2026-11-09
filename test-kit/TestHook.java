package com.remotemate;
/** Test helper: makes the phone key and writes its certificate so the fake TV can read it. */
public class TestHook { public static java.security.KeyStore make(java.io.File f) throws Exception {
  java.security.KeyStore ks=Certs.loadOrCreate(f); java.security.cert.X509Certificate c=Certs.clientCert(ks);
  System.out.println("cert: "+c.getSubjectX500Principal()+" v"+c.getVersion()+" basic="+c.getBasicConstraints()+" san="+c.getSubjectAlternativeNames());
  c.verify(c.getPublicKey());
  java.nio.file.Files.write(java.nio.file.Paths.get("client.pem"),("-----BEGIN CERTIFICATE-----\n"+java.util.Base64.getMimeEncoder(64,"\n".getBytes()).encodeToString(c.getEncoded())+"\n-----END CERTIFICATE-----\n").getBytes());
  return ks; } }
