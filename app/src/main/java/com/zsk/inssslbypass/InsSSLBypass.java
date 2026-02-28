package com.zsk.inssslbypass;

import android.util.Log;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class InsSSLBypass implements IXposedHookLoadPackage {

    private static final String TAG = "InstagramSSLBypass";
    private static final String TARGET_PACKAGE = "com.instagram.android";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        log("Module loaded in Instagram");

        hookTigonMNS(lpparam);
        hookCheckTrustedRecursive(lpparam);
        hookSSLContextInit(lpparam);
    }

    /**
     * Hook TigonMNSServiceHolder.initHybrid - Instagram自有的证书校验
     * 通过MergedSoMapping延迟触发，动态匹配所有overload
     */
    private void hookTigonMNS(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                "com.facebook.soloader.MergedSoMapping$Invoke_JNI_OnLoad",
                lpparam.classLoader,
                "libappstatelogger2_so",
                new XC_MethodHook() {
                    private boolean hooked = false;

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (hooked) return;
                        hooked = true;
                        doHookTigonMNS(lpparam.classLoader);
                    }
                }
        );
    }

    private void doHookTigonMNS(ClassLoader cl) {
        try {
            Class<?> holder = XposedHelpers.findClass(
                    "com.facebook.tigon.tigonmns.TigonMNSServiceHolder", cl);
            Class<?> config = XposedHelpers.findClass(
                    "com.facebook.tigon.tigonmns.TigonMNSConfig", cl);

            Method target = null;
            for (Method m : holder.getDeclaredMethods()) {
                if (!m.getName().equals("initHybrid")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 4 && params[0] == config) {
                    target = m;
                    log("Matched initHybrid: " + getMethodSignature(m));
                }
            }

            if (target == null) {
                log("No initHybrid overload found");
                return;
            }

            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object cfg = param.args[0];
                    invokeIfExists(cfg, "setEnableCertificateVerificationWithProofOfPossession", boolean.class, false);
                    invokeIfExists(cfg, "setTrustSandboxCertificates", boolean.class, true);
                    invokeIfExists(cfg, "setForceHttp2", boolean.class, true);
                    invokeIfExists(cfg, "setEnableCertificateVerification", boolean.class, false);
                    invokeIfExists(cfg, "setCertificatePinningEnabled", boolean.class, false);
                    log("TigonMNSConfig modified");
                }
            });
        } catch (Throwable e) {
            log("TigonMNS hook failed: " + e.getMessage());
        }
    }

    private void hookCheckTrustedRecursive(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.org.conscrypt.TrustManagerImpl", lpparam.classLoader);

            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals("checkTrustedRecursive")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(new ArrayList<>());
                        }
                    });
                    log("Hooked checkTrustedRecursive");
                    return;
                }
            }
        } catch (Throwable e) {
            log("checkTrustedRecursive hook failed: " + e.getMessage());
        }
    }

    private void hookSSLContextInit(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            final TrustManager[] emptyTrustManagers = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };

            XposedHelpers.findAndHookMethod(
                    "javax.net.ssl.SSLContext", lpparam.classLoader, "init",
                    javax.net.ssl.KeyManager[].class, javax.net.ssl.TrustManager[].class, SecureRandom.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[1] = emptyTrustManagers;
                        }
                    }
            );
            log("Hooked SSLContext.init");
        } catch (Throwable e) {
            log("SSLContext.init hook failed: " + e.getMessage());
        }
    }

    private static void invokeIfExists(Object obj, String methodName, Class<?> paramType, Object value) {
        try {
            obj.getClass().getMethod(methodName, paramType).invoke(obj, value);
        } catch (Throwable ignored) {}
    }

    private static String getMethodSignature(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(p.getName());
        }
        return sb.append(")").toString();
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
