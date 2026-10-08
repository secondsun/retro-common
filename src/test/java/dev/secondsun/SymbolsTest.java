package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.*;
import dev.secondsun.retro.util.vo.Location;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * This is a suite of tests that test
 *   1) symbol identification
 *   2) symbol lookup/referencing
 *   3) lexical scoping and
 *   4) handling symbols becoming dirty
 */
public class SymbolsTest {

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
        symbolService.extractDefinitions(lines);

        var location = symbolService.getLocation("labelDef");
        assertEquals(new Location(lines.uri, 0, 0, 8), location);

        var bobLocation = symbolService.getLocation("bob");
        assertEquals(new Location(lines.uri, 14, 0, 3), bobLocation);
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
        symbolService.extractDefinitions(lines);

        var location = symbolService.getLocation("camera");
        assertEquals(new Location(lines.uri, 5, 0, 14), location);
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
        symbolService.extractDefinitions(lines);

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

        fileService.readLines(URI.create("./symbol.s")).forEach((index, line) -> {
            System.out.println(line);
        });
    }

    @Test
    public void includeDocumentation() throws Exception {
        var symbolService = new SymbolService();
        var fileService = new FileService();

        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("includeTest").getFile());
        fileService.addSearchPath(file.toURI());

        var lines = fileService.readLines(URI.create("./macros.s"));
        symbolService.extractDefinitions(lines);

        assertTrue(symbolService.hasDocumentation("gsuRunning"));
        var expectedGsuRunningDoc = """
                Is the GSU running
                zflag = 1 -> Yes
                zflag = 0 -> No
                Clobbers the Accumulator""";
        assertEquals(expectedGsuRunningDoc, symbolService.getDocumentation("gsuRunning"));

        // Macro without doc comments
        assertNull(symbolService.getDocumentation("gsuOff"));

        // Also verify function documentation from an assembly snippet
        var functionSnippet = """
                ;Calculates LOOKAT_MATRIX from CAMERA
                ; This operates on Static values and has no input/output
                ; Clobbers : All
                function camera_lookAt
                    nop
                """;
        var snippetFile = new CA65Scanner().tokenize(functionSnippet);
        var snippetSymbolService = new SymbolService();
        snippetSymbolService.extractDefinitions(snippetFile);

        assertTrue(snippetSymbolService.hasDocumentation("camera_lookAt"));
        var expectedFunctionDoc = """
                Calculates LOOKAT_MATRIX from CAMERA
                This operates on Static values and has no input/output
                Clobbers : All""";
        assertEquals(expectedFunctionDoc, snippetSymbolService.getDocumentation("camera_lookAt"));
    }

    @Test
    public void twoFunctionsCanDefineSameLabelAndResolveByContext() {
        var program = """
                function foo
                loop:
                    bra loop
                endfunction

                function bar
                loop:
                    bra loop
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/funcs.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // foo starts at line 0, loop at line 1, bra at line 2, endfunction at line 3
        // bar starts at line 5, loop at line 6, bra at line 7, endfunction at line 8

        // Line 2 is inside foo: loop must resolve to foo's loop (line 1)
        var fooLoop = symbolService.getLocation("loop", file.uri, 2);
        assertNotNull(fooLoop);
        assertEquals(1, fooLoop.line());

        // Line 7 is inside bar: loop must resolve to bar's loop (line 6)
        var barLoop = symbolService.getLocation("loop", file.uri, 7);
        assertNotNull(barLoop);
        assertEquals(6, barLoop.line());

        // Qualified resolution:
        var qualFoo = symbolService.getLocation("foo::loop");
        assertNotNull(qualFoo);
        assertEquals(1, qualFoo.line());

        var qualBar = symbolService.getLocation("bar::loop");
        assertNotNull(qualBar);
        assertEquals(6, qualBar.line());

        // Multi-definition lookup:
        var allLoops = symbolService.getLocations("loop");
        assertEquals(2, allLoops.size());

        // Context-aware multi-definition lookup:
        var fooContextLoops = symbolService.getLocations("loop", file.uri, 2);
        assertEquals(1, fooContextLoops.size());
        assertEquals(1, fooContextLoops.get(0).line());

        var barContextLoops = symbolService.getLocations("loop", file.uri, 7);
        assertEquals(1, barContextLoops.size());
        assertEquals(6, barContextLoops.get(0).line());
    }

    @Test
    public void goToDefinitionUsageWithLocationAndToken() {
        var program = """
                function foo
                loop:
                    bra loop
                endfunction

                function bar
                loop:
                    bra loop
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/lsp_test.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // Retro-LSP passes cursor Location or (uri, line)
        var fooCursor = new Location(file.uri, 2, 8, 12);
        var resolvedForFoo = symbolService.getLocation("loop", fooCursor);
        assertNotNull(resolvedForFoo);
        assertEquals(1, resolvedForFoo.line());

        var barCursor = new Location(file.uri, 7, 8, 12);
        var resolvedForBar = symbolService.getLocation("loop", barCursor);
        assertNotNull(resolvedForBar);
        assertEquals(6, resolvedForBar.line());

        // Token-based lookup
        var fooToken = file.getLineTokens(2).get(1); // loop token
        assertEquals("loop", fooToken.text());
        var tokenResolvedFoo = symbolService.getLocation(fooToken, file);
        assertNotNull(tokenResolvedFoo);
        assertEquals(1, tokenResolvedFoo.line());

        var barToken = file.getLineTokens(7).get(1); // loop token
        assertEquals("loop", barToken.text());
        var tokenResolvedBar = symbolService.getLocation(barToken, file);
        assertNotNull(tokenResolvedBar);
        assertEquals(6, tokenResolvedBar.line());
    }

    @Test
    public void functionParametersAndReturnVariableAreScoped() {
        var program = """
                function add_vectors v1, v2 : result
                    lda v1
                    add v2
                    sta result
                    return result
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/params.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // Function itself is in global scope
        var fnLoc = symbolService.getLocation("add_vectors");
        assertNotNull(fnLoc);
        assertEquals(0, fnLoc.line());

        // Inside function (line 1), parameters and return var resolve to definition line (0)
        var v1Loc = symbolService.getLocation("v1", file.uri, 1);
        assertNotNull(v1Loc);
        assertEquals(0, v1Loc.line());

        var v2Loc = symbolService.getLocation("v2", file.uri, 2);
        assertNotNull(v2Loc);
        assertEquals(0, v2Loc.line());

        var resLoc = symbolService.getLocation("result", file.uri, 3);
        assertNotNull(resLoc);
        assertEquals(0, resLoc.line());

        // Outside function (line 10), parameters are not in scope
        assertNull(symbolService.getLocation("v1", file.uri, 10));
    }

    @Test
    public void scopeShadowingAndRootNamespaceResolution() {
        var program = """
                loop:
                    nop

                function foo
                loop:
                    bra loop
                    bra ::loop
                endfunction

                function bar
                    bra loop
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/shadow.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // Outside functions: loop is global (line 0)
        assertEquals(0, symbolService.getLocation("loop", file.uri, 0).line());

        // Inside foo (line 5): loop resolves to local loop (line 4)
        assertEquals(4, symbolService.getLocation("loop", file.uri, 5).line());

        // Inside foo (line 6): ::loop explicitly resolves to global loop (line 0)
        assertEquals(0, symbolService.getLocation("::loop", file.uri, 6).line());

        // Inside bar (line 10): does not define loop, falls back to global loop (line 0)
        assertEquals(0, symbolService.getLocation("loop", file.uri, 10).line());
    }

    @Test
    public void contextAwareDocumentationForScopedSymbols() {
        var program = """
                function foo
                ; Documentation for foo's loop
                loop:
                    bra loop
                endfunction

                function bar
                ; Documentation for bar's loop
                loop:
                    bra loop
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/docs.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        assertTrue(symbolService.hasDocumentation("loop", file.uri, 3));
        assertEquals("Documentation for foo's loop", symbolService.getDocumentation("loop", file.uri, 3));

        assertTrue(symbolService.hasDocumentation("loop", file.uri, 9));
        assertEquals("Documentation for bar's loop", symbolService.getDocumentation("loop", file.uri, 9));

        assertEquals("Documentation for foo's loop", symbolService.getDocumentation("foo::loop"));
        assertEquals("Documentation for bar's loop", symbolService.getDocumentation("bar::loop"));
    }

    @Test
    public void procAndScopeBlockSupport() {
        var program = """
                .proc my_proc
                local_sym:
                    .scope inner
                    inner_sym = 42
                    .endscope
                    rts
                .endproc
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/proc.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // Inside inner scope (line 3)
        assertEquals(3, symbolService.getLocation("inner_sym", file.uri, 3).line());
        assertEquals(1, symbolService.getLocation("local_sym", file.uri, 3).line());

        // Inside my_proc outside inner scope (line 5)
        assertEquals(1, symbolService.getLocation("local_sym", file.uri, 5).line());
        assertEquals(
                3, symbolService.getLocation("inner::inner_sym", file.uri, 5).line());

        // Outside my_proc
        assertEquals(1, symbolService.getLocation("my_proc::local_sym").line());
        assertEquals(3, symbolService.getLocation("my_proc::inner::inner_sym").line());
    }

    @Test
    public void refreshingFileRemovesStaleDefinitions() {
        var uri = URI.create("file:///test/refresh.s");
        var programV1 = """
                function foo
                old_label:
                    nop
                endfunction
                """;
        var file1 = new CA65Scanner().tokenize(programV1);
        file1.uri = uri;
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file1);

        assertNotNull(symbolService.getLocation("old_label", uri, 1));

        // Re-extracting with new code removes old_label
        var programV2 = """
                function foo
                new_label:
                    nop
                endfunction
                """;
        var file2 = new CA65Scanner().tokenize(programV2);
        file2.uri = uri;
        symbolService.extractDefinitions(file2);

        assertNull(symbolService.getLocation("old_label", uri, 1));
        assertNotNull(symbolService.getLocation("new_label", uri, 1));
    }

    @Test
    public void globalRegisterVariables() {
        var program = """
                register input
                register output = r6
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/register_global.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        var inputLoc = symbolService.getLocation("input");
        assertNotNull(inputLoc);
        assertEquals(new Location(file.uri, 0, 8, 14), inputLoc);

        var outputLoc = symbolService.getLocation("output");
        assertNotNull(outputLoc);
        assertEquals(new Location(file.uri, 1, 8, 15), outputLoc);
    }

    @Test
    public void multipleRegisterDeclarationsPerLine() {
        var program = """
                register varA = r1, varB = r2, varC
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/register_multiple.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        var varALoc = symbolService.getLocation("varA");
        assertNotNull(varALoc);
        assertEquals(new Location(file.uri, 0, 8, 13), varALoc);

        var varBLoc = symbolService.getLocation("varB");
        assertNotNull(varBLoc);
        assertEquals(new Location(file.uri, 0, 19, 24), varBLoc);

        var varCLoc = symbolService.getLocation("varC");
        assertNotNull(varCLoc);
        assertEquals(new Location(file.uri, 0, 30, 35), varCLoc);
    }

    @Test
    public void registerVariablesInLexicalScopeAndQualifiedNames() {
        var program = """
                function calculate
                    register temp = r3
                    nop
                endfunction

                function process
                    register temp = r4
                    nop
                endfunction
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/register_scopes.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        // Inside calculate (line 2)
        var calcTemp = symbolService.getLocation("temp", file.uri, 2);
        assertNotNull(calcTemp);
        assertEquals(1, calcTemp.line());

        // Inside process (line 7)
        var procTemp = symbolService.getLocation("temp", file.uri, 7);
        assertNotNull(procTemp);
        assertEquals(6, procTemp.line());

        // Qualified lookups
        var qualifiedCalc = symbolService.getLocation("calculate::temp");
        assertNotNull(qualifiedCalc);
        assertEquals(1, qualifiedCalc.line());

        var qualifiedProc = symbolService.getLocation("process::temp");
        assertNotNull(qualifiedProc);
        assertEquals(6, qualifiedProc.line());
    }

    @Test
    public void registerDocumentationComments() {
        var program = """
                ; Temporary counter
                register counter = r0
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/register_doc.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        assertTrue(symbolService.hasDocumentation("counter"));
        assertEquals("Temporary counter", symbolService.getDocumentation("counter"));
    }

    @Test
    public void registerCaseInsensitiveScanning() {
        var program = """
                REGISTER foo = r1
                Register bar = r2
                """;
        var file = new CA65Scanner().tokenize(program);
        file.uri = URI.create("file:///test/register_case.s");
        var symbolService = new SymbolService();
        symbolService.extractDefinitions(file);

        var fooLoc = symbolService.getLocation("foo");
        assertNotNull(fooLoc);
        assertEquals(0, fooLoc.line());

        var barLoc = symbolService.getLocation("bar");
        assertNotNull(barLoc);
        assertEquals(1, barLoc.line());
    }

    private URI getTestFile(String string) {
        try {
            return new File(getClass()
                            .getClassLoader()
                            .getResource("includeTest/" + string)
                            .getFile())
                    .getCanonicalFile()
                    .toURI();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A symbol is defined when it is the only element on a line and ends with a ":"
     * There are also anonymous symbols.
     * @throws Exception
     */
    private URI getTestDirURI() {
        File file = new File(
                getClass().getClassLoader().getResource("includeTest/test.sgs").getFile());
        try {
            return file.getParentFile().getCanonicalFile().toURI();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private URI getHomebrewDirURI() {
        try {
            return new File(getClass()
                            .getClassLoader()
                            .getResource("homebrew/X-GSU/.")
                            .getFile())
                    .getCanonicalFile()
                    .toURI();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
