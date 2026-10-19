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
}
