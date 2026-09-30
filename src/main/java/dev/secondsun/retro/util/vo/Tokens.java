package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.ArrayList;
import java.util.List;

public record Tokens(String line, List<Token> tokens) {
    public Tokens add(Token token) {
        tokens.add(token);
        return new Tokens(line, new ArrayList<>(tokens));
    }
}
