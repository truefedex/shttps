package com.phlox.server.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

public class Base64Test {

    @Test
    public void roundTrip() {
        byte[] data = "user:pässword".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.encodeToString(data);
        assertEquals(java.util.Base64.getEncoder().encodeToString(data), encoded);
        assertArrayEquals(data, Base64.decode(encoded));
    }

    @Test
    public void urlSafeEncoding() {
        byte[] data = {(byte) 0xfb, (byte) 0xff, (byte) 0xfe};
        assertEquals("+//+", Base64.encodeToString(data, false));
        assertEquals("-__-", Base64.encodeToString(data, true));
    }

    @Test
    public void invalidInputIsAnIllegalArgument() {
        //used to come out as RuntimeException(ClassNotFoundException: android.util.Base64)
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Base64.decode("not base64!"));
        assertEquals(IllegalArgumentException.class, e.getClass());
    }
}
