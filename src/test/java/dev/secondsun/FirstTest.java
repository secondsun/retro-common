package dev.secondsun;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.ProjectService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.tm4e.core.grammar.IGrammar;
import dev.secondsun.tm4e.core.grammar.IToken;
import dev.secondsun.tm4e.core.grammar.ITokenizeLineResult;
import dev.secondsun.tm4e.core.registry.Registry;
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
        var registry = new Registry();
        IGrammar grammar = registry.loadGrammarFromPathSync("snes.json",
                Util.class.getClassLoader().getResourceAsStream("snes.json"));
        
        var sgsProgram = Util.toString(Util.class.getClassLoader().getResourceAsStream("libSFX.i"));
        
        Arrays.asList(sgsProgram.split("\n")).forEach(line -> {
            ITokenizeLineResult lineTokens = grammar.tokenizeLine(line);
            System.out.println(line);
            for (int i = 0; i < lineTokens.getTokens().length; i++) {
                IToken token = lineTokens.getTokens()[i];
                System.out.println("Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes "
                        + token.getScopes());
            }
        });

    }

    @Test
    public void projectServiceinit() throws Exception {
        var rootUri = getTestDirURI();
        var fileService = new FileService();

        var registry = new Registry();

        var grammar = registry.loadGrammarFromPathSync("snes.json",
                Util.class.getClassLoader().getResourceAsStream("snes.json"));

        var symbolService = new SymbolService(registry, grammar);
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
