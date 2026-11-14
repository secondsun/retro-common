package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.DefaultWorkspaceIndex;
import dev.secondsun.retro.util.DiskEventKind;
import dev.secondsun.retro.util.WorkspaceIndex;
import dev.secondsun.retro.util.WorkspaceSnapshot;
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

  @BeforeEach
  public void setUp() {
    workspaceIndex = new DefaultWorkspaceIndex(tempDir);
  }

  @Test
  public void testSnippetResolutionAndOverlayDoNotMutatePersistentState() throws IOException {
    Path helperFile = tempDir.resolve("helper.i");
    Files.writeString(
        helperFile,
        """
        ; Multiplies two numbers
        function mul16 a, b : out
            return out
        endfunction
        """);
    workspaceIndex.includeDir(tempDir.toUri());

    String snippetCode =
        """
        ; Local snippet helper
        inline function snippetFn x : res
            call mul16 x, x : res
            return res
        endfunction
        """;
    TokenizedFile file = workspaceIndex.resolveFile(null, snippetCode);
    assertEquals(URI.create("snippet:///inline.s"), file.uri());

    WorkspaceSnapshot overlay = workspaceIndex.snapshotFor(null, snippetCode);
    // Ephemeral overlay resolves BOTH snippet symbols AND persistent workspace symbols!
    assertNotNull(overlay.resolveLocation("snippetFn"));
    assertEquals("Local snippet helper", overlay.resolveDocumentation("snippetFn"));
    assertNotNull(overlay.resolveLocation("mul16"));
    assertEquals(1, overlay.findReferences("mul16", null, null).size());

    // Persistent workspace index remains completely untouched by transient snippet!
    assertNull(workspaceIndex.resolveLocation("snippetFn", null, null));
    assertTrue(workspaceIndex.findReferences("mul16", null, null).isEmpty());
    assertEquals(
        TokenizedFile.EMPTY, workspaceIndex.getFileContents(URI.create("snippet:///inline.s")));
  }

  @Test
  public void testBufferPrecedenceSurvivesDiskWatcherEventsAndRevertsOnClose() throws IOException {
    Path sourceFile = tempDir.resolve("main.s");
    Files.writeString(sourceFile, "disk_v1:\n  nop\n");
    URI fileUri = sourceFile.toUri();

    // 1. Load initial disk contents
    TokenizedFile diskTokenized = workspaceIndex.resolveFile(fileUri, null);
    assertEquals("disk_v1:", diskTokenized.getLineText(0).trim());
    assertNotNull(workspaceIndex.resolveLocation("disk_v1", fileUri, 0));

    // 2. Open dirty editor buffer (Buffer Precedence)
    workspaceIndex.openOrUpdateBuffer(fileUri, "buffer_dirty:\n  stop\n");
    assertEquals("buffer_dirty:", workspaceIndex.getFileContents(fileUri).getLineText(0).trim());
    assertNotNull(workspaceIndex.resolveLocation("buffer_dirty", fileUri, 0));
    assertNull(workspaceIndex.resolveLocation("disk_v1", fileUri, 0));

    // 3. External OS file-watcher MODIFIED event writes disk_v2 -> MUST NOT clobber open buffer!
    Files.writeString(sourceFile, "disk_v2:\n  nop\n");
    workspaceIndex.notifyDiskEvent(fileUri, DiskEventKind.MODIFIED);
    assertEquals("buffer_dirty:", workspaceIndex.getFileContents(fileUri).getLineText(0).trim());
    assertNotNull(workspaceIndex.resolveLocation("buffer_dirty", fileUri, 0));
    assertNull(workspaceIndex.resolveLocation("disk_v2", fileUri, 0));

    // 4. Closing editor buffer reveals latest disk_v2 state!
    workspaceIndex.closeBuffer(fileUri);
    assertEquals("disk_v2:", workspaceIndex.getFileContents(fileUri).getLineText(0).trim());
    assertNotNull(workspaceIndex.resolveLocation("disk_v2", fileUri, 0));
    assertNull(workspaceIndex.resolveLocation("buffer_dirty", fileUri, 0));

    // 5. Re-open buffer, then external DELETED disk event -> buffer stays alive until closed!
    workspaceIndex.openOrUpdateBuffer(fileUri, "unsaved_after_delete:\n  nop\n");
    Files.delete(sourceFile);
    workspaceIndex.notifyDiskEvent(fileUri, DiskEventKind.DELETED);
    assertNotNull(workspaceIndex.resolveLocation("unsaved_after_delete", fileUri, 0));

    workspaceIndex.closeBuffer(fileUri);
    assertEquals(TokenizedFile.EMPTY, workspaceIndex.getFileContents(fileUri));
    assertNull(workspaceIndex.resolveLocation("unsaved_after_delete", fileUri, 0));
  }

  @Test
  public void testWorkspaceSandboxBlocksSnippetPathTraversalAndGetFileContents()
      throws IOException {
    Path outsideFile = tempDir.getParent().resolve("outside_secret_" + System.nanoTime() + ".s");
    URI outsideUri = outsideFile.toUri();

    assertThrows(
        SecurityException.class, () -> workspaceIndex.validateUriWithinWorkspace(outsideUri));
    assertThrows(SecurityException.class, () -> workspaceIndex.resolveFile(outsideUri, null));
    assertThrows(SecurityException.class, () -> workspaceIndex.getFileContents(outsideUri));
    assertFalse(workspaceIndex.isInsideWorkspace(outsideUri));

    // Path traversal disguised under file:///snippet/../../etc/passwd MUST be normalized first and
    // blocked!
    URI traversalUri = URI.create("file:///snippet/../../etc/passwd");
    assertThrows(
        SecurityException.class, () -> workspaceIndex.validateUriWithinWorkspace(traversalUri));
    assertThrows(SecurityException.class, () -> workspaceIndex.getFileContents(traversalUri));
    assertThrows(SecurityException.class, () -> workspaceIndex.resolveFile(traversalUri, null));

    // Legitimate synthetic snippet URIs remain allowed without touching disk
    assertDoesNotThrow(
        () -> workspaceIndex.validateUriWithinWorkspace(URI.create("snippet:///inline.s")));
    assertDoesNotThrow(
        () -> workspaceIndex.validateUriWithinWorkspace(URI.create("file:///snippet.s")));

    Path insideFile = tempDir.resolve("inside.s");
    assertDoesNotThrow(() -> workspaceIndex.validateUriWithinWorkspace(insideFile.toUri()));
    assertTrue(workspaceIndex.isInsideWorkspace(insideFile.toUri()));
  }

  @Test
  public void testExternalRelativeIncludeDoesNotAbortWorkspaceIndexing() throws IOException {
    Path subWorkspace = tempDir.resolve("project");
    Path externalShared = tempDir.resolve("external_shared");
    Files.createDirectories(subWorkspace);
    Files.createDirectories(externalShared);

    Files.writeString(externalShared.resolve("shared.i"), "ext_sym = 1\n");
    Files.writeString(
        subWorkspace.resolve("main.s"),
        ".include \"../external_shared/shared.i\"\nlocal_sym:\n  nop\n");

    WorkspaceIndex boundedSubIndex = new DefaultWorkspaceIndex(subWorkspace);
    assertDoesNotThrow(() -> boundedSubIndex.includeDir(subWorkspace.toUri()));
    assertNotNull(boundedSubIndex.resolveLocation("local_sym", null, 0));
    assertNull(boundedSubIndex.resolveLocation("ext_sym", null, 0));
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
  public void testConcurrentBufferAndSnapshotReads() throws Exception {
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
                  String sym = "label_" + threadId + "_" + i;
                  String code = sym + ":\n  nop\n";
                  TokenizedFile tf = workspaceIndex.resolveFile(fileUri, code);
                  assertNotNull(tf);
                  assertFalse(tf.isEmpty());
                  assertTrue(workspaceIndex.snapshot().allSymbolNames().contains(sym));
                }
              }));
    }

    for (var future : futures) {
      future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }
    executor.shutdown();
  }

  @Test
  public void testFindReferencesBasicAndInlineFunctionSupport() {
    URI uri = tempDir.resolve("math.s").toUri();
    String code =
        """
        inline function computeSum a : out
            stw (targetVar)
            ldw (sourceVar)
            call computeSum a : out
            return out
        endfunction
        """;
    workspaceIndex.updateFileContents(uri, code);

    var refs = workspaceIndex.findDetailedReferences("computeSum", null, null, false);
    assertEquals(1, refs.size());
    assertEquals("call", refs.get(0).kind());
    assertEquals(3, refs.get(0).line());

    var all = workspaceIndex.findDetailedReferences("computeSum", null, null, true);
    assertEquals(2, all.size());
    assertEquals("definition", all.get(0).kind());
    assertEquals("call", all.get(1).kind());

    // Cursor on "inline" keyword (col 2) or "function" keyword (col 9) resolves to computeSum
    TokenizedFile tf = workspaceIndex.getFileContents(uri);
    assertEquals("computeSum", workspaceIndex.resolveSymbolAt(tf, 0, 2).orElse(""));
    assertEquals("computeSum", workspaceIndex.resolveSymbolAt(tf, 0, 9).orElse(""));

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
  public void testCheapLocalLabelAndStructFieldScopingDisambiguation() {
    URI uri1 = tempDir.resolve("func1.s").toUri();
    workspaceIndex.updateFileContents(
        uri1,
        """
        function funcOne
        @loop:
            bra @loop
            return
        endfunction
        """);
    URI uri2 = tempDir.resolve("func2.s").toUri();
    workspaceIndex.updateFileContents(
        uri2,
        """
        function funcTwo
        @loop:
            bra @loop
            return
        endfunction
        """);

    assertEquals(2, workspaceIndex.findDetailedReferences("@loop", null, null, false).size());
    var scopedRefs = workspaceIndex.findDetailedReferences("@loop", uri1, 1, false);
    assertEquals(1, scopedRefs.size());
    assertEquals(uri1, scopedRefs.get(0).filename());

    URI structsUri = tempDir.resolve("structs.s").toUri();
    workspaceIndex.updateFileContents(
        structsUri,
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
        """);

    assertEquals(2, workspaceIndex.findDetailedReferences("drop_ns", null, null, false).size());
    var structBRefs = workspaceIndex.findDetailedReferences("drop_ns", structsUri, 7, false);
    assertEquals(1, structBRefs.size());
    assertEquals(11, structBRefs.get(0).line());

    var qualifiedRefs =
        workspaceIndex.findDetailedReferences("structA::drop_ns", null, null, false);
    assertEquals(1, qualifiedRefs.size());
    assertEquals(12, qualifiedRefs.get(0).line());
  }

  @Test
  public void testSharedSymbolSurvivesRemovingOneOfTwoDefiningFiles() {
    URI file1 = tempDir.resolve("file1.s").toUri();
    URI file2 = tempDir.resolve("file2.s").toUri();
    workspaceIndex.openOrUpdateBuffer(file1, "shared_sym:\nunique_sym:\n  nop\n");
    workspaceIndex.openOrUpdateBuffer(file2, "shared_sym:\n  nop\n");

    assertEquals(2, workspaceIndex.resolveLocations("shared_sym", null, null).size());
    assertNotNull(workspaceIndex.resolveLocation("unique_sym", null, null));

    workspaceIndex.removeFile(file1);
    assertNull(workspaceIndex.resolveLocation("unique_sym", null, null));
    List<Location> remaining = workspaceIndex.resolveLocations("shared_sym", null, null);
    assertEquals(1, remaining.size());
    assertEquals(file2, remaining.get(0).filename());

    workspaceIndex.removeFile(file2);
    assertNull(workspaceIndex.resolveLocation("shared_sym", null, null));
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
    assertNotNull(workspaceIndex.resolveLocation("src_symbol", null, 0));
    assertNull(workspaceIndex.resolveLocation("tools_symbol", null, 0));
    assertNull(workspaceIndex.resolveLocation("build_tools_symbol", null, 0));

    workspaceIndex.includeDir(toolsDir.toUri());
    assertNotNull(workspaceIndex.resolveLocation("tools_symbol", null, 0));
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

    workspaceIndex.includeDir(realDir.toUri());
    workspaceIndex.includeDir(fixtureDir.toUri());

    TokenizedFile sqrtFile = workspaceIndex.getFileContents(realSqrt.toUri());
    List<Location> refs = workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 12, false);
    assertEquals(1, refs.size());
    assertEquals(realVector.toUri(), refs.get(0).filename());
    assertEquals(2, refs.get(0).line());

    List<Location> refsWithDefs =
        workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 12, true);
    assertTrue(refsWithDefs.size() >= 2);
    assertFalse(refsWithDefs.get(0).filename().toString().contains("/src/test/resources/"));

    List<Location> paramRefs =
        workspaceIndex.findReferencesAt(sqrtFile, realSqrt.toUri(), 0, 22, false);
    assertEquals(1, paramRefs.size());
    assertEquals(realSqrt.toUri(), paramRefs.get(0).filename());
    assertEquals(2, paramRefs.get(0).line());

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
