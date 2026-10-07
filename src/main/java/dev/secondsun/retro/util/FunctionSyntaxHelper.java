package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.CallStatement;
import dev.secondsun.retro.util.vo.FunctionDeclaration;
import dev.secondsun.retro.util.vo.ReturnStatement;
import dev.secondsun.retro.util.vo.Tokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class FunctionSyntaxHelper {

    private FunctionSyntaxHelper() {}

    public static boolean isFunction(Tokens tokens) {
        return isFunction(tokens != null ? tokens.tokens() : null);
    }

    public static boolean isFunction(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return false;
        }
        Token first = tokenList.get(0);
        return first.type == TokenType.TOK_FUNCTION || "function".equalsIgnoreCase(first.text());
    }

    public static boolean isEndFunction(Tokens tokens) {
        return isEndFunction(tokens != null ? tokens.tokens() : null);
    }

    public static boolean isEndFunction(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return false;
        }
        Token first = tokenList.get(0);
        if (first.type == TokenType.TOK_ENDFUNCTION || "endfunction".equalsIgnoreCase(first.text())) {
            for (int i = 1; i < tokenList.size(); i++) {
                Token extra = tokenList.get(i);
                extra.addAttribute(TokenAttribute.ERROR);
                extra.message = "Unexpected token after endfunction: " + extra.text();
            }
            return true;
        }
        return false;
    }

    public static boolean validateEndFunction(Tokens tokens) {
        return validateEndFunction(tokens != null ? tokens.tokens() : null);
    }

    public static boolean validateEndFunction(List<Token> tokenList) {
        if (!isEndFunction(tokenList)) {
            return false;
        }
        return tokenList.size() == 1;
    }

    public static boolean isCall(Tokens tokens) {
        return isCall(tokens != null ? tokens.tokens() : null);
    }

    public static boolean isCall(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return false;
        }
        Token first = tokenList.get(0);
        return first.type == TokenType.TOK_CALL || "call".equalsIgnoreCase(first.text());
    }

    public static boolean isReturn(Tokens tokens) {
        return isReturn(tokens != null ? tokens.tokens() : null);
    }

    public static boolean isReturn(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return false;
        }
        Token first = tokenList.get(0);
        return first.type == TokenType.TOK_RETURN || "return".equalsIgnoreCase(first.text());
    }

    public static FunctionDeclaration parseFunction(Tokens tokens) {
        return parseFunction(tokens != null ? tokens.tokens() : null);
    }

    public static FunctionDeclaration parseFunction(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return new FunctionDeclaration(null, null, List.of(), Optional.empty(), true);
        }

        boolean hasErrors = false;
        Token functionToken = tokenList.get(0);
        if (functionToken.type != TokenType.TOK_FUNCTION && !"function".equalsIgnoreCase(functionToken.text())) {
            functionToken.addAttribute(TokenAttribute.ERROR);
            functionToken.message = "Expected 'function' keyword";
            hasErrors = true;
        }

        if (tokenList.size() == 1) {
            functionToken.addAttribute(TokenAttribute.ERROR);
            functionToken.message = "Missing function name";
            return new FunctionDeclaration(functionToken, null, List.of(), Optional.empty(), true);
        }

        Token nameToken = tokenList.get(1);
        if (!isIdentifier(nameToken)) {
            nameToken.addAttribute(TokenAttribute.ERROR);
            nameToken.message = "Expected function name identifier, found: " + nameToken.text();
            hasErrors = true;
        }

        List<Token> parameters = new ArrayList<>();
        Set<String> seenParamNames = new HashSet<>();
        Optional<Token> returnVariable = Optional.empty();

        int i = 2;
        boolean afterComma = false;
        Token lastComma = null;

        while (i < tokenList.size()) {
            Token token = tokenList.get(i);
            if (token.type == TokenType.TOK_COLON) {
                break;
            }

            if (token.type == TokenType.TOK_COMMA) {
                if (parameters.isEmpty() || afterComma) {
                    token.addAttribute(TokenAttribute.ERROR);
                    token.message = "Unexpected comma";
                    hasErrors = true;
                }
                afterComma = true;
                lastComma = token;
            } else if (isIdentifier(token)) {
                if (!seenParamNames.add(token.text())) {
                    token.addAttribute(TokenAttribute.ERROR);
                    token.message = "Duplicate parameter name: " + token.text();
                    hasErrors = true;
                }
                parameters.add(token);
                afterComma = false;
                lastComma = null;
            } else {
                token.addAttribute(TokenAttribute.ERROR);
                token.message = "Expected parameter identifier, found: " + token.text();
                hasErrors = true;
                parameters.add(token);
                afterComma = false;
                lastComma = null;
            }
            i++;
        }

        if (afterComma && lastComma != null) {
            lastComma.addAttribute(TokenAttribute.ERROR);
            lastComma.message = (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON)
                    ? "Unexpected comma before ':'"
                    : "Dangling comma in parameter list";
            hasErrors = true;
        }

        if (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON) {
            Token colonToken = tokenList.get(i);
            i++;
            if (i >= tokenList.size()) {
                colonToken.addAttribute(TokenAttribute.ERROR);
                colonToken.message = "Missing return variable after ':'";
                hasErrors = true;
            } else {
                Token retToken = tokenList.get(i);
                if (isValidVariableOrRegister(retToken)) {
                    returnVariable = Optional.of(retToken);
                } else {
                    retToken.addAttribute(TokenAttribute.ERROR);
                    retToken.message = "Expected identifier or register for return variable, found: " + retToken.text();
                    hasErrors = true;
                    returnVariable = Optional.of(retToken);
                }
                i++;

                while (i < tokenList.size()) {
                    Token extra = tokenList.get(i);
                    extra.addAttribute(TokenAttribute.ERROR);
                    extra.message = "Unexpected token after return variable: " + extra.text();
                    hasErrors = true;
                    i++;
                }
            }
        }

        return new FunctionDeclaration(functionToken, nameToken, parameters, returnVariable, hasErrors);
    }

    public static CallStatement parseCall(Tokens tokens) {
        return parseCall(tokens != null ? tokens.tokens() : null);
    }

    public static CallStatement parseCall(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return new CallStatement(null, null, List.of(), Optional.empty(), true);
        }

        boolean hasErrors = false;
        Token callToken = tokenList.get(0);
        if (callToken.type != TokenType.TOK_CALL && !"call".equalsIgnoreCase(callToken.text())) {
            callToken.addAttribute(TokenAttribute.ERROR);
            callToken.message = "Expected 'call' keyword";
            hasErrors = true;
        }

        if (tokenList.size() == 1) {
            callToken.addAttribute(TokenAttribute.ERROR);
            callToken.message = "Missing function target name";
            return new CallStatement(callToken, null, List.of(), Optional.empty(), true);
        }

        Token targetToken = tokenList.get(1);
        if (!isIdentifier(targetToken)) {
            targetToken.addAttribute(TokenAttribute.ERROR);
            targetToken.message = "Expected function target identifier, found: " + targetToken.text();
            hasErrors = true;
        }

        List<Token> arguments = new ArrayList<>();
        Optional<Token> destinationVariable = Optional.empty();

        int i = 2;
        boolean afterComma = false;
        Token lastComma = null;

        while (i < tokenList.size()) {
            Token token = tokenList.get(i);
            if (token.type == TokenType.TOK_COLON) {
                break;
            }

            if (token.type == TokenType.TOK_COMMA) {
                if (arguments.isEmpty() || afterComma) {
                    token.addAttribute(TokenAttribute.ERROR);
                    token.message = "Unexpected comma";
                    hasErrors = true;
                }
                afterComma = true;
                lastComma = token;
            } else if (isValidArgumentToken(token)) {
                arguments.add(token);
                afterComma = false;
                lastComma = null;
            } else {
                token.addAttribute(TokenAttribute.ERROR);
                token.message = "Unexpected argument token: " + token.text();
                hasErrors = true;
                arguments.add(token);
                afterComma = false;
                lastComma = null;
            }
            i++;
        }

        if (afterComma && lastComma != null) {
            lastComma.addAttribute(TokenAttribute.ERROR);
            lastComma.message = (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON)
                    ? "Unexpected comma before ':'"
                    : "Dangling comma in argument list";
            hasErrors = true;
        }

        if (i < tokenList.size() && tokenList.get(i).type == TokenType.TOK_COLON) {
            Token colonToken = tokenList.get(i);
            i++;
            if (i >= tokenList.size()) {
                colonToken.addAttribute(TokenAttribute.ERROR);
                colonToken.message = "Missing destination variable after ':'";
                hasErrors = true;
            } else {
                Token destToken = tokenList.get(i);
                if (isValidVariableOrRegister(destToken)) {
                    destinationVariable = Optional.of(destToken);
                } else {
                    destToken.addAttribute(TokenAttribute.ERROR);
                    destToken.message =
                            "Expected identifier or register for destination variable, found: " + destToken.text();
                    hasErrors = true;
                    destinationVariable = Optional.of(destToken);
                }
                i++;

                while (i < tokenList.size()) {
                    Token extra = tokenList.get(i);
                    extra.addAttribute(TokenAttribute.ERROR);
                    extra.message = "Unexpected token after destination variable: " + extra.text();
                    hasErrors = true;
                    i++;
                }
            }
        }

        return new CallStatement(callToken, targetToken, arguments, destinationVariable, hasErrors);
    }

    public static ReturnStatement parseReturn(Tokens tokens) {
        return parseReturn(tokens != null ? tokens.tokens() : null);
    }

    public static ReturnStatement parseReturn(List<Token> tokenList) {
        if (tokenList == null || tokenList.isEmpty()) {
            return new ReturnStatement(null, Optional.empty(), true);
        }

        boolean hasErrors = false;
        Token returnToken = tokenList.get(0);
        if (returnToken.type != TokenType.TOK_RETURN && !"return".equalsIgnoreCase(returnToken.text())) {
            returnToken.addAttribute(TokenAttribute.ERROR);
            returnToken.message = "Expected 'return' keyword";
            hasErrors = true;
        }

        Optional<Token> returnVariable = Optional.empty();

        if (tokenList.size() > 1) {
            Token firstArg = tokenList.get(1);
            if (isValidArgumentToken(firstArg)) {
                returnVariable = Optional.of(firstArg);
            } else {
                firstArg.addAttribute(TokenAttribute.ERROR);
                firstArg.message = "Invalid return value: " + firstArg.text();
                hasErrors = true;
                returnVariable = Optional.of(firstArg);
            }

            for (int i = 2; i < tokenList.size(); i++) {
                Token extra = tokenList.get(i);
                extra.addAttribute(TokenAttribute.ERROR);
                extra.message = "Unexpected token after return value: " + extra.text();
                hasErrors = true;
            }
        }

        return new ReturnStatement(returnToken, returnVariable, hasErrors);
    }

    private static boolean isIdentifier(Token token) {
        return token != null
                && (token.type == TokenType.TOK_IDENT
                        || token.type == TokenType.TOK_A
                        || token.type == TokenType.TOK_X
                        || token.type == TokenType.TOK_Y
                        || token.type == TokenType.TOK_S);
    }

    private static boolean isValidVariableOrRegister(Token token) {
        return isIdentifier(token) || (token != null && token.type == TokenType.TOK_REGISTER);
    }

    private static boolean isValidArgumentToken(Token token) {
        return isIdentifier(token)
                || (token != null
                        && (token.type == TokenType.TOK_REGISTER
                                || token.type == TokenType.TOK_INTCON
                                || token.type == TokenType.TOK_LOCAL_IDENT
                                || token.type == TokenType.TOK_CHARCON
                                || token.type == TokenType.TOK_STRCON));
    }
}
