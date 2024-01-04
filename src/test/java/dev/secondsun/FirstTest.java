package dev.secondsun;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.Util;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


public class FirstTest {
    /**
     * Confirm that tokens from a source file can be parsed
     * 
     * @throws Exception
     */
    @Test
    public void printTestSgs() throws Exception {
        CA65Scanner grammar = new CA65Scanner();
        
        var sgsProgram = Util.toString(Util.class.getClassLoader().getResourceAsStream("libSFX.i"));
        
        Arrays.asList(sgsProgram.split("\n")).forEach(line -> {
            var lineTokens = grammar.tokenizeLine(line);
            System.out.println(line);
            for (int i = 0; i < lineTokens.size(); i++) {
                Token token = lineTokens.get(i);
                System.out.println("Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes "
                        + token.getScopes());
            }
        });

    }

    @Test
    public void projectServiceinit() throws Exception {
        var rootUri = getTestDirURI();
        var fileService = new FileService();

        var symbolService = new SymbolService();
        var projectService = new ProjectService(fileService, symbolService);
        projectService.includeDir(rootUri);

        assertNotNull(projectService.getFileContents(getTestFile("test.sgs")));
    }

    private URI getTestFile(String string) {
        return new File(getClass().getClassLoader().getResource("includeTest/" + string).getFile()).toURI();
    }

    private URI getTestDirURI() throws IOException {
        File file = new File(getClass().getClassLoader().getResource("includeTest/test.sgs").getFile());
        return file.getParentFile().getCanonicalFile().toURI();
    }
}
