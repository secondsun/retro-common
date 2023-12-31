package dev.secondsun.retro.util;

public class Token {

    public TokenType type;
    public int startIndex;
    public int endIndex;
    public String message;
    public int intVal;
    private StringBuilder stringBuffer = new StringBuilder();  

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


}
