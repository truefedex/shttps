package com.phlox.simpleserver.utils;

public class Holder<T> {
    private volatile T value;

    public Holder(T value) {
        this.value = value;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }
}
