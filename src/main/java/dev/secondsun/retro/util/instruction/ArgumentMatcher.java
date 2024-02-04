package dev.secondsun.retro.util.instruction;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;

import java.util.Arrays;
import java.util.List;

public sealed interface ArgumentMatcher {
    boolean match(List<Token> in);

    final class BrokenMatcher implements ArgumentMatcher {

        @Override
        public boolean match(List<Token> in) {
            throw new IllegalStateException("Not Implemented");
        }
    }

    final class RegisterMatcher implements ArgumentMatcher {
        final int specificRegister;

        public RegisterMatcher(String registerPattern) {
            if (registerPattern.length() == 2 || registerPattern.length() == 3) {
                if (registerPattern.charAt(1) == 'n') {
                    specificRegister = -1;
                } else {
                    specificRegister = Integer.parseInt(registerPattern.substring(1));
                }
                return;
            }
            throw new IllegalArgumentException(registerPattern + " is not a valid register pattern. Valid patterns are [rR][n\\d{1,2}]");

        }

        @Override
        public boolean match(List<Token> in) {
            if (in.size() < 1) {
                return false;
            } else {
                var token = in.remove(0);
                var tokenText = token.text();
                if (tokenText.startsWith("r") || tokenText.startsWith("R")) {
                    var actualRegister = Integer.parseInt(tokenText.substring(1));
                    if (specificRegister > -1) {
                        return specificRegister == actualRegister;
                    } else {
                        return actualRegister > -1 && actualRegister < 16;
                    }
                }
                return false;
            }
        }
    }

    /**
     * Matches #nn (TOK_HAS, TOK_INTCON)
     */
    final class NumberMatcher implements ArgumentMatcher {
        public NumberMatcher() {
        }

        @Override
        public boolean match(List<Token> in) {
            if (in.size() < 2) {
                return false;
            } else {
                var matches = true;
                matches = matches && in.remove(0).type == TokenType.TOK_HASH;
                matches = matches && in.remove(0).type == TokenType.TOK_INTCON;
                return matches;
            }
        }
    }

    final class AddressMatcher implements ArgumentMatcher {
        public AddressMatcher(String pattern) {
        }

        @Override
        public boolean match(List<Token> in) {
            if (in.isEmpty()) {
                return false;
            } else {
                var matches = true;
                matches = matches && in.remove(0).type == TokenType.TOK_LPAREN;
                matches = matches && Arrays.asList(TokenType.TOK_INTCON, TokenType.TOK_IDENT, TokenType.TOK_REGISTER).contains(in.remove(0).type);
                matches = matches && in.remove(0).type == TokenType.TOK_RPAREN;
                return matches;
            }

        }
    }

    final class LabelMatcher implements ArgumentMatcher {
        public LabelMatcher() {
        }

        @Override
        public boolean match(List<Token> in) {
            var token = in.remove(0);
            return token.type == TokenType.TOK_IDENT;
        }
    }

    static ArgumentMatcher create(String pattern) {
        if (pattern.startsWith("R") || pattern.startsWith("r")) {
            return new RegisterMatcher(pattern);
        } else if (pattern.startsWith("#")) {
            return new NumberMatcher();
        } else if (pattern.startsWith("(")) {
            return new AddressMatcher(pattern);
        } else if (pattern.equalsIgnoreCase("e")) {
            return new LabelMatcher();
        }

        throw new IllegalStateException("Illegal argument definition:" + pattern) ;

    }

}
