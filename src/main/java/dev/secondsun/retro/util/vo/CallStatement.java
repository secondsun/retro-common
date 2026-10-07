package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.List;
import java.util.Optional;

public record CallStatement(
        Token callToken,
        Token targetToken,
        List<Token> arguments,
        Optional<Token> destinationVariable,
        boolean hasErrors) {
    public CallStatement {
        arguments = arguments != null ? List.copyOf(arguments) : List.of();
        destinationVariable = destinationVariable != null ? destinationVariable : Optional.empty();
    }

    public String targetName() {
        return targetToken != null ? targetToken.text() : "";
    }
}
