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
}
