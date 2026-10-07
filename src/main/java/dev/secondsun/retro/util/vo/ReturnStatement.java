package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.util.Optional;

public record ReturnStatement(Token returnToken, Optional<Token> returnVariable, boolean hasErrors) {
    public ReturnStatement {
        returnVariable = returnVariable != null ? returnVariable : Optional.empty();
    }
}
