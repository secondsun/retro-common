package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.List;
import java.util.Optional;

public record FunctionDeclaration(
        Token functionToken,
        Token nameToken,
        List<Token> parameters,
        Optional<Token> returnVariable,
        boolean hasErrors) {
    public FunctionDeclaration {
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        returnVariable = returnVariable != null ? returnVariable : Optional.empty();
    }

    public String name() {
        return nameToken != null ? nameToken.text() : "";
    }
}
