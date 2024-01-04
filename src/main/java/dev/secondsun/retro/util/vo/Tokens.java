package dev.secondsun.retro.util.vo;

import java.util.ArrayList;
import java.util.List;

import dev.secondsun.retro.util.Token;

public record Tokens(String line, List<Token> tokens) {
    public Tokens add(Token token) {
        tokens.add(token);
        return new Tokens(line, new ArrayList<>(tokens));
    }
}
