package com.phlox.simpleserver.screenshare;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class ScreenStreamInitQueueTest {

    @Test
    public void aReinitKeepsTheControlFrameThatWasAlreadySent() {
        List<String> texts = textFramesAfterInit("{\"type\":\"control\"}");

        assertEquals(List.of("{\"type\":\"init\"}", "{\"type\":\"control\"}"), texts);
    }

    @Test
    public void aReinitWithoutAControlFrameDoesNotInventOne() {
        List<String> texts = textFramesAfterInit(null);

        assertEquals(List.of("{\"type\":\"init\"}"), texts);
    }

    private static List<String> textFramesAfterInit(String lastControlStatus) {
        ArrayDeque<ScreenStreamWebSocketHandler.Message> queue = new ArrayDeque<>();
        queue.add(ScreenStreamWebSocketHandler.Message.text("stale"));
        ScreenStreamWebSocketHandler.queueInit(queue, "{\"type\":\"init\"}", new byte[]{1}, lastControlStatus);
        List<String> texts = new ArrayList<>();
        for (ScreenStreamWebSocketHandler.Message message : queue) {
            if (message.text != null) texts.add(message.text);
        }
        return texts;
    }
}
