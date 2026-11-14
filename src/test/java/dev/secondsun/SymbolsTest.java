package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.Util;
import dev.secondsun.retro.util.WorkspaceSnapshot;
import dev.secondsun.retro.util.vo.Location;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Boundary tests verifying symbol identification, documentation extraction, lexical scoping,
 * shadowing, and buffer refresh via {@link DefaultWorkspaceIndex} and {@link WorkspaceSnapshot}.
 */
public class SymbolsTest {

  @Test
  public void canIdentifySymbolDefinition() {
    File dir = new File(getClass().getClassLoader().getResource("symbolTest").getFile());
    var index = new DefaultWorkspaceIndex(dir.toPath());
    URI symbolUri = dir.toPath().resolve("symbol.s").toUri();
    var lines = index.resolveFile(symbolUri, null);
    var snap = index.snapshot();

    var location = snap.resolveLocation("labelDef");
    assertEquals(new Location(lines.uri(), 0, 0, 8), location);

    var bobLocation = snap.resolveLocation("bob");
    assertEquals(new Location(lines.uri(), 14, 0, 3), bobLocation);
  }

  @Test
  public void canIdentifyStructDefinition() {
    File dir = new File(getClass().getClassLoader().getResource("symbolTest").getFile());
    var index = new DefaultWorkspaceIndex(dir.toPath());
    URI symbolUri = dir.toPath().resolve("symbol.s").toUri();
    var lines = index.resolveFile(symbolUri, null);

    var location = index.snapshot().resolveLocation("camera");
    assertEquals(new Location(lines.uri(), 5, 0, 14), location);
  }

  @Test
  public void fullSystemLabelLink() {
    var index = new DefaultWorkspaceIndex();
    index.includeDir(getHomebrewDirURI());

    var lines = index.getFileContents(getHomebrewDirURI().resolve("./tests/decode_rnc/Test.sgs"));
    assertFalse(lines.isEmpty());

    var location = index.snapshot().resolveLocation("initialize_buffer");
    assertNotNull(location);
  }

  @Test
  public void semiColonInStringIsNotAComment() {
    var program =
        """
                ;This is a comment
                ";" This is not a comment
                ";" ; This is a comment
                """;

    var expected =
        """
                                 \s
                ";" This is not a comment
                ";"                   \s
                """;

    var stripped = Util.removeComments(program);
    assertEquals(expected, stripped);
  }

  @Test
  public void includeDocumentation() {
    File dir = new File(getClass().getClassLoader().getResource("includeTest").getFile());
    var index = new DefaultWorkspaceIndex(dir.toPath());
    URI macrosUri = dir.toPath().resolve("macros.s").toUri();
    index.resolveFile(macrosUri, null);
    var snap = index.snapshot();

    assertTrue(snap.hasDocumentation("gsuRunning"));
    var expectedGsuRunningDoc =
        """
                Is the GSU running
                zflag = 1 -> Yes
                zflag = 0 -> No
                Clobbers the Accumulator""";
    assertEquals(expectedGsuRunningDoc, snap.resolveDocumentation("gsuRunning"));
    assertNull(snap.resolveDocumentation("gsuOff"));

    var functionSnippet =
        """
                ;Calculates LOOKAT_MATRIX from CAMERA
                ; This operates on Static values and has no input/output
                ; Clobbers : All
                function camera_lookAt
                    nop
                """;
    var snippetSnap = index.snapshotFor(null, functionSnippet);
    assertTrue(snippetSnap.hasDocumentation("camera_lookAt"));
    var expectedFunctionDoc =
        """
                Calculates LOOKAT_MATRIX from CAMERA
                This operates on Static values and has no input/output
                Clobbers : All""";
    assertEquals(expectedFunctionDoc, snippetSnap.resolveDocumentation("camera_lookAt"));
    // Transient snippet must not leak into persistent index
    assertFalse(index.snapshot().hasDocumentation("camera_lookAt"));
  }

  @Test
  public void twoFunctionsCanDefineSameLabelAndResolveByContext() {
    var program =
        """
                function foo
                loop:
                    bra loop
                endfunction

                function bar
                loop:
                    bra loop
                endfunction
                """;
    URI uri = URI.create("file:///test/funcs.s");
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    var fooLoop = snap.resolveLocation("loop", uri, 2);
    assertNotNull(fooLoop);
    assertEquals(1, fooLoop.line());

    var barLoop = snap.resolveLocation("loop", uri, 7);
    assertNotNull(barLoop);
    assertEquals(6, barLoop.line());

    assertEquals(1, snap.resolveLocation("foo::loop").line());
    assertEquals(6, snap.resolveLocation("bar::loop").line());

    var allLoops = snap.resolveLocations("loop");
    assertEquals(2, allLoops.size());

    var fooContextLoops = snap.resolveLocations("loop", uri, 2);
    assertEquals(1, fooContextLoops.size());
    assertEquals(1, fooContextLoops.get(0).line());

    var barContextLoops = snap.resolveLocations("loop", uri, 7);
    assertEquals(1, barContextLoops.size());
    assertEquals(6, barContextLoops.get(0).line());
  }

  @Test
  public void goToDefinitionUsageWithLocationAndToken() {
    var program =
        """
                function foo
                loop:
                    bra loop
                endfunction

                function bar
                loop:
                    bra loop
                endfunction
                """;
    URI uri = URI.create("file:///test/lsp_test.s");
    var file = new CA65Scanner().tokenize(program, uri);
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    var fooCursor = new Location(uri, 2, 8, 12);
    assertEquals(1, snap.resolveLocation("loop", fooCursor).line());

    var barCursor = new Location(uri, 7, 8, 12);
    assertEquals(6, snap.resolveLocation("loop", barCursor).line());

    var fooToken = file.getLineTokens(2).get(1);
    assertEquals("loop", fooToken.text());
    assertEquals(1, snap.resolveLocation(fooToken, file).line());

    var barToken = file.getLineTokens(7).get(1);
    assertEquals("loop", barToken.text());
    assertEquals(6, snap.resolveLocation(barToken, file).line());
  }

  @Test
  public void functionParametersAndReturnVariableAreScoped() {
    var program =
        """
                function add_vectors v1, v2 : result
                    lda v1
                    add v2
                    sta result
                    return result
                endfunction
                """;
    URI uri = URI.create("file:///test/params.s");
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    assertEquals(0, snap.resolveLocation("add_vectors").line());
    assertEquals(0, snap.resolveLocation("v1", uri, 1).line());
    assertEquals(0, snap.resolveLocation("v2", uri, 2).line());
    assertEquals(0, snap.resolveLocation("result", uri, 3).line());
    assertNull(snap.resolveLocation("v1", uri, 10));
  }

  @Test
  public void scopeShadowingAndRootNamespaceResolution() {
    var program =
        """
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
    URI uri = URI.create("file:///test/shadow.s");
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    assertEquals(0, snap.resolveLocation("loop", uri, 0).line());
    assertEquals(4, snap.resolveLocation("loop", uri, 5).line());
    assertEquals(0, snap.resolveLocation("::loop", uri, 6).line());
    assertEquals(0, snap.resolveLocation("loop", uri, 10).line());
  }

  @Test
  public void contextAwareDocumentationForScopedSymbols() {
    var program =
        """
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
    URI uri = URI.create("file:///test/docs.s");
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    assertTrue(snap.hasDocumentation("loop", uri, 3));
    assertEquals("Documentation for foo's loop", snap.resolveDocumentation("loop", uri, 3));

    assertTrue(snap.hasDocumentation("loop", uri, 9));
    assertEquals("Documentation for bar's loop", snap.resolveDocumentation("loop", uri, 9));

    assertEquals("Documentation for foo's loop", snap.resolveDocumentation("foo::loop"));
    assertEquals("Documentation for bar's loop", snap.resolveDocumentation("bar::loop"));
  }

  @Test
  public void procAndScopeBlockSupport() {
    var program =
        """
                .proc my_proc
                local_sym:
                    .scope inner
                    inner_sym = 42
                    .endscope
                    rts
                .endproc
                """;
    URI uri = URI.create("file:///test/proc.s");
    var snap = WorkspaceSnapshot.EMPTY.overlay(uri, program);

    assertEquals(3, snap.resolveLocation("inner_sym", uri, 3).line());
    assertEquals(1, snap.resolveLocation("local_sym", uri, 3).line());
    assertEquals(1, snap.resolveLocation("local_sym", uri, 5).line());
    assertEquals(3, snap.resolveLocation("inner::inner_sym", uri, 5).line());
    assertEquals(1, snap.resolveLocation("my_proc::local_sym").line());
    assertEquals(3, snap.resolveLocation("my_proc::inner::inner_sym").line());
  }

  @Test
  public void refreshingFileRemovesStaleDefinitions() {
    var uri = URI.create("file:///test/refresh.s");
    var index = new DefaultWorkspaceIndex();
    index.openOrUpdateBuffer(
        uri,
        """
                function foo
                old_label:
                    nop
                endfunction
                """);

    assertNotNull(index.resolveLocation("old_label", uri, 1));

    index.openOrUpdateBuffer(
        uri,
        """
                function foo
                new_label:
                    nop
                endfunction
                """);

    assertNull(index.resolveLocation("old_label", uri, 1));
    assertNotNull(index.resolveLocation("new_label", uri, 1));
  }

  @Test
  public void globalAndScopedRegisterVariables() {
    URI globalUri = URI.create("file:///test/register_global.s");
    var globalSnap =
        WorkspaceSnapshot.EMPTY.overlay(
            globalUri,
            """
            register input
            register output = r6
            register varA = r1, varB = r2, varC
            ; Temporary counter
            REGISTER counter = r0
            """);

    assertEquals(new Location(globalUri, 0, 9, 14), globalSnap.resolveLocation("input"));
    assertEquals(new Location(globalUri, 1, 9, 15), globalSnap.resolveLocation("output"));
    assertEquals(new Location(globalUri, 2, 9, 13), globalSnap.resolveLocation("varA"));
    assertEquals(new Location(globalUri, 2, 20, 24), globalSnap.resolveLocation("varB"));
    assertEquals(new Location(globalUri, 2, 31, 35), globalSnap.resolveLocation("varC"));
    assertEquals("Temporary counter", globalSnap.resolveDocumentation("counter"));

    URI scopedUri = URI.create("file:///test/register_scopes.s");
    var scopedSnap =
        WorkspaceSnapshot.EMPTY.overlay(
            scopedUri,
            """
            function calculate
                register temp = r3
                nop
            endfunction

            inline function process
                register temp = r4
                nop
            endfunction
            """);

    assertEquals(1, scopedSnap.resolveLocation("temp", scopedUri, 2).line());
    assertEquals(6, scopedSnap.resolveLocation("temp", scopedUri, 7).line());
    assertEquals(1, scopedSnap.resolveLocation("calculate::temp").line());
    assertEquals(6, scopedSnap.resolveLocation("process::temp").line());
  }

  private URI getHomebrewDirURI() {
    try {
      return new File(getClass().getClassLoader().getResource("homebrew/X-GSU/.").getFile())
          .getCanonicalFile()
          .toURI();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
