package com.phlox.server.platform;

public class Base64 {

    @SuppressWarnings("NewApi")
    static public byte[] decode(String s) {
        //check if java.util.Base64 is available. If not, use android.util.Base64 by reflection
        try {
            return java.util.Base64.getDecoder().decode(s);
        } catch (Throwable e) {
            try {
                return (byte[]) Class.forName("android.util.Base64").getMethod("decode", String.class, int.class).invoke(null, s, 0);
            } catch (Throwable e1) {
                throw new RuntimeException(e1);
            }
        }
    }

    @SuppressWarnings("NewApi")
    static public String encodeToString(byte[] input) {
        //check if java.util.Base64 is available. If not, use android.util.Base64 by reflection
        try {
            return java.util.Base64.getEncoder().encodeToString(input);
        } catch (Throwable e) {
            try {
                return (String) Class.forName("android.util.Base64").getMethod("encodeToString", byte[].class, int.class).invoke(null, input, 0);
            } catch (Throwable e1) {
                throw new RuntimeException(e1);
            }
        }
    }
}
