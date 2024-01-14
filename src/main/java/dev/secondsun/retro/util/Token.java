package dev.secondsun.retro.util;

import java.util.Set;
import java.util.HashSet;

public class Token {

    public TokenType type;
    public int startIndex;
    public int endIndex;
    public String message;
    public int intVal;
    private StringBuilder stringBuffer = new StringBuilder();
    private Set<TokenAttribute> attributes = new HashSet<TokenAttribute>();  

    public String getScopes() {
        return null;
    }

    public int getStartIndex() {
        return startIndex;
    }

    public int getEndIndex() {
        return endIndex;
    }

    public void appendChar(char c) {
        this.stringBuffer.append(c);
    }

    public String text() {
        return stringBuffer.toString();
    }

    public TokenType getType() {
        return type;
    }

    public boolean hasAttribute(TokenAttribute gsuInstruction) {
        return attributes.contains(gsuInstruction);
    }


}
