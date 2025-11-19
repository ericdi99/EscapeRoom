package com.escaperoom.utils;

public class StringUtil {

    public static boolean isNEmptyString(String str) {
        return str == null || str.trim().isEmpty();
    }
}
