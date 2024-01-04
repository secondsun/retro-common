package dev.secondsun.retro.util.vo;

import java.util.IdentityHashMap;
import java.util.Map;

public class TokenizedFile {
    private Map<Integer, Tokens> fileLines = new IdentityHashMap<>();

    public int lines() {
        return 0;
    }
}
