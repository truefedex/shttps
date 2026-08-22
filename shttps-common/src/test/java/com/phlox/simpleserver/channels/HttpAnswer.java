package com.phlox.simpleserver.channels;

/** One plain HTTP answer, as much of it as the channel tests look at. */
class HttpAnswer {
    final String statusLine;
    final String body;

    HttpAnswer(String statusLine, String body) {
        this.statusLine = statusLine;
        this.body = body;
    }
}
