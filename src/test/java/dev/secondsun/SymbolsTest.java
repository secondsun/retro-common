package dev.secondsun;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;

import dev.secondsun.retro.util.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is a suite of tests that test 
 *   1) symbol identification 
 *   2) symbol lookup/referencing   
 *   3) lexical scoping and 
 *   4) handling symbols becoming dirty
 */
public class SymbolsTest {
    
    private static final URI SYMBOLS_URI =URI.create("file:/C:/Users/secon/Projects/retro-common/target/test-classes/symbolTest/./symbol.s");

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
        symbolService.extractDefinitions( lines);

        var location = symbolService.getLocation("labelDef");
        assertEquals(new Location(SYMBOLS_URI, 0,0,8), location);
        
        var bobLocation = symbolService.getLocation("bob");
        assertEquals(new Location(SYMBOLS_URI, 14,0,3), bobLocation);

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
        symbolService.extractDefinitions( lines);

        var location = symbolService.getLocation("camera");
        assertEquals(new Location(SYMBOLS_URI, 5,0,14), location);
        
    }

    /**
     * Does a label lookup work everywhere
     * @throws Exception
     */
    @Test
    public void fullSystemLabelLink() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new FileService();
        var projectService = new ProjectService(fileService, symbolService);

        projectService.includeDir(getHomebrewDirURI());


        var lines = projectService.getFileContents(getHomebrewDirURI().resolve("./tests/decode_rnc/Test.sgs"));
        symbolService.extractDefinitions( lines );

        var location = symbolService.getLocation("initialize_buffer");
        assertNotNull(location);

    }


    @Test
    public void semiColonInStringIsNotAComment() {
        var program = """
                ;This is a comment
                ";" This is not a comment
                ";" ; This is a comment
                """;

        var expected = """
                                 \s
                ";" This is not a comment
                ";"                   \s
                """;


        var stripped = Util.removeComments(program);
        assertEquals(expected, stripped);

    }

@Test
/**
 * Keep disabled for debugging
 * @throws IOException
 */
public void printLines() throws Exception {
    var fileService = new FileService();
    var grammar = new CA65Scanner();
    

        ClassLoader classLoader = getClass().getClassLoader();
        // src/test/resources/workspace1
        File file = new File(classLoader.getResource("symbolTest").getFile());
        fileService.addSearchPath(file.toURI());

        fileService.readLines(URI.create("./symbol.s")).forEach((index,line)->{
            System.out.println(line);
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

    private URI getHomebrewDirURI() {
        try {
            return new File(getClass().getClassLoader().getResource("homebrew/X-GSU/.").getFile()).getCanonicalFile().toURI();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

