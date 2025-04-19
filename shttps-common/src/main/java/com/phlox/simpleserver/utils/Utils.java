package com.phlox.simpleserver.utils;

import java.util.Locale;

public class Utils {
    public static String formatFileSize(long size) {
        if (size <= 0) {
            return "0 B";
        }

        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format(Locale.US,
                "%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static String getFileExtensionFromFilename(String filePath){
        int pos = filePath.lastIndexOf(".");
        if(pos > 0)
            return filePath.substring(pos + 1).toLowerCase();
        return null;
    }

    public static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] array = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : array) {
                sb.append(Integer.toHexString((b & 0xFF) | 0x100), 1, 3);
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static boolean contains(String[] array, String value) {
        for (String s : array) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
