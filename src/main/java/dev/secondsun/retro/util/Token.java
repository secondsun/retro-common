package dev.secondsun.retro.util;

import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

public class Token {

    public TokenType type;
    public int startIndex;
    public int endIndex;
    public String message;
    public int lineNumber;
    public int intVal;

    private final HashMap metadata = new HashMap<>();

    private StringBuilder stringBuffer = new StringBuilder();
    private Set<TokenAttribute> attributes = new HashSet<TokenAttribute>();  


    public Token addMetadata(Object key, Object value) {
        metadata.put(key,value);
        return this;
    }

    public <T> T getMetadata(Object key) {
        var toReturn = metadata.get(key);
        if (toReturn != null){
            return (T) metadata.get(key);
        } else {
            return null;
        }
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


    public void addAttribute(TokenAttribute tokenAttribute) {
        attributes.add(tokenAttribute);
    }
}
