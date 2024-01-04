package dev.secondsun.retro.util;


import java.io.InputStream;
import java.text.StringCharacterIterator;
import java.util.*;
import java.util.stream.Collectors;

public final class  Util {
    public static List<String> readLines(InputStream stream){
        Scanner scanner = new Scanner(stream);
        var list = new ArrayList<String>();

        while(scanner.hasNextLine()) {
            list.add(scanner.nextLine());
        }
        return list;
        
    }

	public static String toString(InputStream resourceAsStream) {
		return readLines(resourceAsStream).stream().collect(Collectors.joining("\n"));
    }
    


    public static String getTokenText(String line, Token token) {
        var start = token.getStartIndex();
        var end = token.getEndIndex();
        if (start < 0) {
            start = 0;
        }
        if (end > line.length()) {
            end = line.length();
        }
        return line.substring(start, end);
    }

    public static Optional<Token> getTokenAt(List<Token> lineTokens, int position) {
        
        return lineTokens.stream().filter(token -> token.getStartIndex() < position && token.getEndIndex() >= position)
                .findFirst();

    }

    public static boolean isIncludeDirective(String line) {
        return line.toUpperCase().startsWith(".INCLUDE");
    }

        /**
     * This should consume replacement while it matches string left of cursor.
     * 
     * For example consider the two strings
     * 
     * stringLeftOfCursor = .InclUdE          "h
     * replacement = .include "himem.i"
     * 
     * the this method should return the string: imem.i having consumed .include "h from replacement 
     * 
     * @param stringLeftOfCursor
     * @param replacement
     * @return
     */
    public static String trimCompletion(String stringLeftOfCursor, String replacement) {
        var charIterator = new StringCharacterIterator(stringLeftOfCursor);
        while (charIterator.current() != StringCharacterIterator.DONE) {
            var character = charIterator.current();
            var replacementChar = replacement.charAt(0);

            while(Character.isWhitespace(character)) {
                character = charIterator.next();
                if (character == StringCharacterIterator.DONE) {
                    break;
                }
            }

            while(Character.isWhitespace(replacementChar)) {
                if (replacement.isEmpty()) {
                    return "";
                }
                replacement = replacement.substring(1);//pop
                replacementChar = replacement.charAt(0);
            }

            if ((character+"").equalsIgnoreCase(replacementChar+"")) {
                replacement = replacement.substring(1);
            } else {
                break;
            }

            
            charIterator.next();
        }
        return replacement;
    }

}
