package dev.secondsun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import dev.secondsun.retro.util.Util;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import dev.secondsun.tm4e.core.registry.Registry;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Location;
/**
 * This is a suite of tests that test 
 *   1) symbol identification 
 *   2) symbol lookup/referencing   
 *   3) lexical scoping and 
 *   4) handling symbols becoming dirty
 */
public class SymbolsTest {
    
    private static final URI SYMBOLS_URI =URI.create("file:/C:/Users/secon/Projects/retro-lsp/target/test-classes/symbolTest/symbol.s");

    /**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canIdentifySymbolDefinition() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new FileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());


        var lines = fileService.readLines(URI.create("./symbol.s"));
        
        symbolService.extractDefinitions(SYMBOLS_URI, lines);

        var location = symbolService.getLocation("labelDef");
        assertEquals(new Location(SYMBOLS_URI, 0,0,9), location);
        
        var bobLocation = symbolService.getLocation("bob");
        assertEquals(new Location(SYMBOLS_URI, 14,0,5), bobLocation);

    }

/**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    @Test
    public void canIdentifyStructDefinition() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new FileService();

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());


        var lines = fileService.readLines(URI.create("./symbol.s"));
        symbolService.extractDefinitions(SYMBOLS_URI, lines);

        var location = symbolService.getLocation("camera");
        assertEquals(new Location(SYMBOLS_URI, 5,0,14), location);
        
    }

@Test
/**
 * Keep disabled for debugging
 * @throws IOException
 */
public void printLines() throws Exception {
    var fileService = new FileService();
    var registry = new Registry();
    var grammar = registry.loadGrammarFromPathSync("snes.json",
    Util.class.getClassLoader().getResourceAsStream("snes.json"));

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());

        fileService.readLines(URI.create("./symbol.s")).forEach((line)->{
        System.out.println(line);
        Arrays.stream(grammar.tokenizeLine(line).getTokens()).map(Object::toString).forEach(System.out::println);
     });
}

@Test
@Disabled
public void includeDocumentation() {
    fail("Scan in docs for hovering on labels");
}

@Test
@Disabled
public void ifDefAndEnvVariablesSupport() {
    fail("Respect ca65 ifdefs and have variables with scopes");
}


private URI getTestFile(String string) {
    try {
        return new File(getClass().getClassLoader().getResource("includeTest/" + string).getFile()).getCanonicalFile().toURI();
    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}

@Test
@Disabled("This seems to work in prod so consider it extra credit")
public void libsfxTracer() {
    fail("This method should schedule sfxRoot files to be scanned.");
    fail("Scans should be refreshed if libsfxroot changes.");
}

/**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    private URI getTestDirURI() {
        File file = new File(getClass().getClassLoader().getResource("includeTest/test.sgs").getFile());
        try {
            return file.getParentFile().getCanonicalFile().toURI();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        
    }

}

