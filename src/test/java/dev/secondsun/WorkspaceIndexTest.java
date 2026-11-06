package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.FileService;
import dev.secondsun.retro.util.SymbolService;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WorkspaceIndexTest {

  @TempDir Path tempDir;

  private WorkspaceIndex workspaceIndex;
  private FileService fileService;
  private SymbolService symbolService;

  @BeforeEach
  public void setUp() {
    fileService = new FileService();
    symbolService = new SymbolService();
    workspaceIndex = new DefaultWorkspaceIndex(fileService, symbolService, tempDir);
  }

  @Test
  public void testSnippetResolutionDoesNotMutatePersistentState() {
    String snippetCode = "iwt r0, #42\nstop\n";
    TokenizedFile file = workspaceIndex.resolveFile(null, snippetCode);

    assertNotNull(file);
    assertNotEquals(TokenizedFile.EMPTY, file);
    assertEquals(URI.create("snippet:///inline.s"), file.uri);
    assertTrue(file.textLines() >= 2);

    // Persistent index should remain empty
    assertEquals(
        TokenizedFile.EMPTY, workspaceIndex.getFileContents(URI.create("snippet:///inline.s")));
  }

  @Test
  public void testBufferPrecedenceOverDiskFile() throws IOException {
    Path sourceFile = tempDir.resolve("main.s");
    Files.writeString(sourceFile, "disk_label:\n  nop\n");

    URI fileUri = sourceFile.toUri();

    // 1. Resolve from disk
    TokenizedFile diskTokenized = workspaceIndex.resolveFile(fileUri, null);
    assertNotNull(diskTokenized);
    assertEquals("disk_label:", diskTokenized.getLineText(0).trim());

    Location diskLoc = workspaceIndex.resolveLocation("disk_label", fileUri, 1);
    assertNotNull(diskLoc);

    // 2. Update buffer in memory (Buffer Precedence)
    String editorBuffer = "buffer_label:\n  stop\n";
    TokenizedFile bufferTokenized = workspaceIndex.resolveFile(fileUri, editorBuffer);
    assertNotNull(bufferTokenized);
    assertEquals("buffer_label:", bufferTokenized.getLineText(0).trim());

    // Querying file contents should return buffer state, not disk state
    TokenizedFile current = workspaceIndex.getFileContents(fileUri);
    assertEquals("buffer_label:", current.getLineText(0).trim());

    // 3. Close buffer -> restores disk content
    workspaceIndex.closeBuffer(fileUri);
    TokenizedFile restored = workspaceIndex.getFileContents(fileUri);
    assertEquals("disk_label:", restored.getLineText(0).trim());
  }

  @Test
  public void testWorkspaceSandboxBoundaryEnforcement() {
    Path outsideFile = tempDir.getParent().resolve("outside_secret.s");
    URI outsideUri = outsideFile.toUri();

    assertThrows(
        SecurityException.class, () -> workspaceIndex.validateUriWithinWorkspace(outsideUri));
    assertThrows(SecurityException.class, () -> workspaceIndex.resolveFile(outsideUri, null));
    assertFalse(workspaceIndex.isInsideWorkspace(outsideUri));

    // Inside files pass
    Path insideFile = tempDir.resolve("inside.s");
    assertDoesNotThrow(() -> workspaceIndex.validateUriWithinWorkspace(insideFile.toUri()));
    assertTrue(workspaceIndex.isInsideWorkspace(insideFile.toUri()));
  }

  @Test
  public void testDirectoryIndexedListenerNotification() throws IOException {
    Path subDir = tempDir.resolve("subdir");
    Files.createDirectories(subDir);
    Path asmFile = subDir.resolve("test.s");
    Files.writeString(asmFile, "sub_label:\n  nop\n");

    List<Path> indexedPaths = new ArrayList<>();
    workspaceIndex.setDirectoryIndexedListener(indexedPaths::add);

    workspaceIndex.includeDir(subDir.toUri());

    assertTrue(indexedPaths.contains(subDir.toAbsolutePath().normalize()));
    TokenizedFile file = workspaceIndex.getFileContents(asmFile.toUri());
    assertNotNull(file);
    assertEquals("sub_label:", file.getLineText(0).trim());
  }

  @Test
  public void testSymbolAndDocumentationResolution() {
    URI uri = tempDir.resolve("symbols.s").toUri();
    String code = ";; @doc Adds two numbers\nmy_func:\n  add r1\n";

    workspaceIndex.resolveFile(uri, code);

    Location loc = workspaceIndex.resolveLocation("my_func", uri, 1);
    assertNotNull(loc);
    assertEquals(uri, loc.filename());

    String doc = workspaceIndex.resolveDocumentation("my_func", uri, 1);
    assertNotNull(doc);
    assertTrue(doc.contains("Adds two numbers"));

    List<Location> locs = workspaceIndex.resolveLocations("my_func", uri, 1);
    assertEquals(1, locs.size());
  }

  @Test
  public void testConcurrentBufferResolution() throws Exception {
    int threadCount = 8;
    int iterations = 20;
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    var futures = new ArrayList<java.util.concurrent.Future<?>>();

    for (int t = 0; t < threadCount; t++) {
      final int threadId = t;
      futures.add(
          executor.submit(
              () -> {
                for (int i = 0; i < iterations; i++) {
                  URI fileUri = tempDir.resolve("thread_" + threadId + "_file_" + i + ".s").toUri();
                  String code = "label_" + threadId + "_" + i + ":\n  nop\n";
                  TokenizedFile tf = workspaceIndex.resolveFile(fileUri, code);
                  assertNotNull(tf);
                  assertFalse(tf.isEmpty());
                  assertEquals(tf, workspaceIndex.getFileContents(fileUri));
                }
              }));
    }

    for (var future : futures) {
      future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    executor.shutdown();
  }

  @Test
  public void testFindReferencesBasicAndClassification() {
    URI uri = tempDir.resolve("math.s").toUri();
    String code =
        """
        function computeSum
            stw (targetVar)
            ldw (sourceVar)
            call computeSum
            return
        endfunction
        """;
    workspaceIndex.updateFileContents(uri, code);

    // Call without definitions
    var refs = workspaceIndex.findDetailedReferences("computeSum", null, null, false);
    assertEquals(1, refs.size());
    assertEquals("call", refs.get(0).kind());
    assertEquals(3, refs.get(0).line());

    // With definitions
    var all = workspaceIndex.findDetailedReferences("computeSum", null, null, true);
    assertEquals(2, all.size());
    assertEquals("definition", all.get(0).kind());
    assertEquals("call", all.get(1).kind());

    // Writes and reads
    var writeRefs = workspaceIndex.findDetailedReferences("targetVar", null, null, false);
    assertEquals(1, writeRefs.size());
    assertEquals("write", writeRefs.get(0).kind());

    var readRefs = workspaceIndex.findDetailedReferences("sourceVar", null, null, false);
    assertEquals(1, readRefs.size());
    assertEquals("read", readRefs.get(0).kind());
  }

  @Test
  public void testSymbolOnlyInCommentOrStringIgnored() {
    URI uri = tempDir.resolve("comments.s").toUri();
    String code =
        """
        ; This is a comment about unused_sym
        .asciiz "unused_sym in a string literal"
        nop
        """;
    workspaceIndex.updateFileContents(uri, code);

    var refs = workspaceIndex.findReferences("unused_sym", null, null);
    assertTrue(refs.isEmpty());
  }

  @Test
  public void testCheapLocalLabelScopingDisambiguation() {
    URI uri1 = tempDir.resolve("func1.s").toUri();
    String code1 =
        """
        function funcOne
        @loop:
            bra @loop
            return
        endfunction
        """;
    URI uri2 = tempDir.resolve("func2.s").toUri();
    String code2 =
        """
        function funcTwo
        @loop:
            bra @loop
            return
        endfunction
        """;
    workspaceIndex.updateFileContents(uri1, code1);
    workspaceIndex.updateFileContents(uri2, code2);

    // Without URI/line context, both @loop labels match
    var allRefs = workspaceIndex.findDetailedReferences("@loop", null, null, false);
    assertEquals(2, allRefs.size());

    // With URI/line scoped to funcOne (line 1 of func1.s), only funcOne's @loop is returned
    var scopedRefs = workspaceIndex.findDetailedReferences("@loop", uri1, 1, false);
    assertEquals(1, scopedRefs.size());
    assertEquals(uri1, scopedRefs.get(0).filename());
    assertEquals(2, scopedRefs.get(0).line());
  }

  @Test
  public void testStructFieldDisambiguation() {
    URI uri = tempDir.resolve("structs.s").toUri();
    String code =
        """
        .struct structA
            fieldA .byte
            drop_ns .byte
        .endstruct

        .struct structB
            fieldB .word
            drop_ns .word
        .endstruct

        main:
            iwt r1, #structB::drop_ns
            iwt r2, #structA::drop_ns
            nop
        """;
    workspaceIndex.updateFileContents(uri, code);

    // Without context, searching drop_ns returns both uses
    var allRefs = workspaceIndex.findDetailedReferences("drop_ns", null, null, false);
    assertEquals(2, allRefs.size());

    // Scoped to structB's definition of drop_ns (line 7)
    var structBRefs = workspaceIndex.findDetailedReferences("drop_ns", uri, 7, false);
    assertEquals(1, structBRefs.size());
    assertEquals(11, structBRefs.get(0).line());

    // Searching specifically by qualified name
    var qualifiedRefs =
        workspaceIndex.findDetailedReferences("structA::drop_ns", null, null, false);
    assertEquals(1, qualifiedRefs.size());
    assertEquals(12, qualifiedRefs.get(0).line());
  }

  @Test
  public void testIndexStaysCorrectAfterFileEditAndDelete() {
    URI uri = tempDir.resolve("dynamic.s").toUri();
    workspaceIndex.updateFileContents(uri, "call targetSym\n");

    var refs1 = workspaceIndex.findReferences("targetSym", null, null);
    assertEquals(1, refs1.size());

    // Update file: targetSym changed to otherSym
    workspaceIndex.updateFileContents(uri, "call otherSym\n");
    var refs2 = workspaceIndex.findReferences("targetSym", null, null);
    assertEquals(0, refs2.size());
    var refsOther = workspaceIndex.findReferences("otherSym", null, null);
    assertEquals(1, refsOther.size());

    // Remove file completely
    workspaceIndex.removeFile(uri);
    var refsAfterDelete = workspaceIndex.findReferences("otherSym", null, null);
    assertEquals(0, refsAfterDelete.size());
  }

  @Test
  public void testIncludeDirSkipsToolsAndBuildToolsSubdirectories() throws IOException {
    Path srcDir = tempDir.resolve("src");
    Path toolsDir = tempDir.resolve("tools");
    Path buildToolsDir = tempDir.resolve("build_tools");
    Files.createDirectories(srcDir);
    Files.createDirectories(toolsDir);
    Files.createDirectories(buildToolsDir);

    Files.writeString(srcDir.resolve("code.s"), "src_symbol:\n  nop\n");
    Files.writeString(toolsDir.resolve("tool.s"), "tools_symbol:\n  nop\n");
    Files.writeString(buildToolsDir.resolve("build_tool.s"), "build_tools_symbol:\n  nop\n");

    workspaceIndex.includeDir(tempDir.toUri());

    // src_symbol should be discovered
    assertNotNull(workspaceIndex.resolveLocation("src_symbol", null, 0));

    // tools and build_tools subdirectories should be skipped during recursive traversal
    assertNull(workspaceIndex.resolveLocation("tools_symbol", null, 0));
    assertNull(workspaceIndex.resolveLocation("build_tools_symbol", null, 0));

    // Explicitly indexing tools directory should still work
    workspaceIndex.includeDir(toolsDir.toUri());
    assertNotNull(workspaceIndex.resolveLocation("tools_symbol", null, 0));
  }

  @Test
  public void testSymbolServiceRemoveFileWithReverseIndex() {
    URI file1Uri = URI.create("file:///test/file1.s");
    URI file2Uri = URI.create("file:///test/file2.s");

    Location sharedLoc1 = new Location(file1Uri, 10, 0, 13);
    Location sharedLoc2 = new Location(file2Uri, 20, 0, 13);
    Location uniqueLoc1 = new Location(file1Uri, 5, 0, 15);

    symbolService.addDefinition("shared_symbol", sharedLoc1);
    symbolService.addDefinition("shared_symbol", sharedLoc2);
    symbolService.addDefinition("unique_to_file1", uniqueLoc1);

    assertEquals(2, symbolService.getLocations("shared_symbol").size());
    assertNotNull(symbolService.getLocation("shared_symbol"));
    assertNotNull(symbolService.getLocation("unique_to_file1"));

    // Remove file 1
    symbolService.removeFile(file1Uri);

    // unique_to_file1 should be completely removed
    assertNull(symbolService.getLocation("unique_to_file1"));
    assertTrue(symbolService.getLocations("unique_to_file1").isEmpty());

    // shared_symbol should still have definition from file 2
    List<Location> remainingShared = symbolService.getLocations("shared_symbol");
    assertEquals(1, remainingShared.size());
    assertEquals(file2Uri, remainingShared.get(0).filename());
    assertEquals(file2Uri, symbolService.getLocation("shared_symbol").filename());

    // Removing file 1 again should be a clean no-op
    symbolService.removeFile(file1Uri);
    assertEquals(1, symbolService.getLocations("shared_symbol").size());

    // Removing file 2 should clean up shared_symbol
    symbolService.removeFile(file2Uri);
    assertNull(symbolService.getLocation("shared_symbol"));
    assertTrue(symbolService.getLocations("shared_symbol").isEmpty());
  }

  @Test
  public void testFindReferencesFromTopLevelFunctionDeclarationAcrossFilesAndFixtures()
      throws IOException {
    Path realDir = tempDir.resolve("snes-sfx-demo/X-GSU/gsu_maths");
    Path fixtureDir = tempDir.resolve("retro-lsp/src/test/resources/X-GSU/gsu_maths");
    Files.createDirectories(realDir);
    Files.createDirectories(fixtureDir);

    String sqrtCode =
        """
        function gsu_sqrt32 dividendhi, dividendlo : root
            register partial_dividend
            with dividendhi
            rol
            return root
        endfunction
        """;
    String vectorCode =
        """
        function vector3_length v1 : len
            register val, mult_lo
            call gsu_sqrt32 val, mult_lo : len
            return len
        endfunction
        """;

    Path realSqrt = realDir.resolve("gsu_sqrt.i");
    Path realVector = realDir.resolve("gsu_vector.i");
    Path fixtureSqrt = fixtureDir.resolve("gsu_sqrt.i");

    Files.writeString(realSqrt, sqrtCode);
    Files.writeString(realVector, vectorCode);
    Files.writeString(fixtureSqrt, sqrtCode);

    // Index real first, then fixture (so fixture is indexed last)
    workspaceIndex.includeDir(realDir.toUri());
    workspaceIndex.includeDir(fixtureDir.toUri());

    TokenizedFile sqrtFile = workspaceIndex.getFileContents(realSqrt.toUri());
    assertNotNull(sqrtFile);

    // Cursor on "gsu_sqrt32" at line 0, column 12 of gsu_sqrt.i (excluding definitions)
    List<Location> refs = workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 12, false);
    assertEquals(1, refs.size());
    assertEquals(realVector.toUri(), refs.get(0).filename());
    assertEquals(2, refs.get(0).line());

    // Including definitions: real definitions/usages outrank fixture definitions
    List<Location> refsWithDefs =
        workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 12, true);
    assertTrue(refsWithDefs.size() >= 2);
    assertFalse(refsWithDefs.get(0).filename().toString().contains("/src/test/resources/"));

    // Cursor on parameter "dividendhi" at line 0, column 22 -> scoped only to gsu_sqrt.i
    List<Location> paramRefs =
        workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 22, false);
    assertEquals(1, paramRefs.size());
    assertEquals(realSqrt.toUri(), paramRefs.get(0).filename());
    assertEquals(2, paramRefs.get(0).line());

    // ReferenceService structured result
    var result =
        workspaceIndex.findReferencesResult(
            "gsu_sqrt32", null, realSqrt.toUri().toString(), 0, "snes-sfx-demo/", 10, false, 1);
    assertEquals(1, result.totalReferences());
    assertEquals("snes-sfx-demo/X-GSU/gsu_maths/gsu_vector.i", result.references().get(0).path());
    assertEquals("call", result.references().get(0).kind());
    assertTrue(result.references().get(0).text().contains("call gsu_sqrt32"));
  }

  @Test
  public void testResolveFileNonExistentRootUriDoesNotIndexFilesystemRoot() {
    WorkspaceIndex index = new DefaultWorkspaceIndex();
    List<Path> indexedPaths = new ArrayList<>();
    index.setDirectoryIndexedListener(indexedPaths::add);

    TokenizedFile result = index.resolveFile(URI.create("file:///nonexistent_root_test.s"), null);

    assertEquals(TokenizedFile.EMPTY, result);
    assertTrue(indexedPaths.isEmpty());
  }
}
