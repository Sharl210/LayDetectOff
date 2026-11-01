import com.android.apksig.ApkSigner;
import com.android.apksig.ApkSigner.SignerConfig;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用 apksig 给 APK 签名（v1+v2+v3）。参数: keystore pass alias in.apk out.apk */
public class SignApk {
    public static void main(String[] args) throws Exception {
        String ksPath = args[0];
        String ksPass = args[1];
        String alias = args[2];
        File in = new File(args[3]);
        File out = new File(args[4]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksPath)) {
            ks.load(fis, ksPass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, ksPass.toCharArray());
        Certificate[] chain = ks.getCertificateChain(alias);
        if (key == null || chain == null) {
            throw new IllegalStateException("alias not found: " + alias);
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) certs.add((X509Certificate) c);

        SignerConfig sc = new SignerConfig.Builder(alias, key, certs).build();
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();
        signer.sign();
        System.out.println("signed -> " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }
}
