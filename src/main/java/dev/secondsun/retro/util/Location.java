package dev.secondsun.retro.util;

import java.net.URI;

public record Location(URI filename, int line, int startIndex, int endIndex) {
    
}
