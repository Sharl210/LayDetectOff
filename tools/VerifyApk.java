import com.android.apksig.ApkVerifier;

import java.io.File;

/** 校验 APK 签名（v1/v2/v3）。参数: apk */
public class VerifyApk {
    public static void main(String[] args) throws Exception {
        File apk = new File(args[0]);
        ApkVerifier verifier = new ApkVerifier.Builder(apk).build();
        ApkVerifier.Result r = verifier.verify();
        System.out.println("verified        = " + r.isVerified());
        System.out.println("v1 (JAR)        = " + r.isVerifiedUsingV1Scheme());
        System.out.println("v2 (APK Sig)    = " + r.isVerifiedUsingV2Scheme());
        System.out.println("v3 (APK Sig 3)  = " + r.isVerifiedUsingV3Scheme());
        System.out.println("v4              = " + r.isVerifiedUsingV4Scheme());
        if (!r.getErrors().isEmpty()) {
            System.out.println("errors:");
            for (Object e : r.getErrors()) System.out.println("  - " + e);
        }
        if (!r.getWarnings().isEmpty()) {
            System.out.println("warnings:");
            for (Object w : r.getWarnings()) System.out.println("  - " + w);
        }
    }
}
