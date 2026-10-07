package dev.secondsun.retro.util.vo;

import java.net.URI;

public record Location(URI filename, int line, int startIndex, int endIndex) {
    public Location(URI filename, int line) {
        this(filename, line, 0, 0);
    }
}
