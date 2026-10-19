package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import org.junit.jupiter.api.Test;

public class TokenizedFileTest {

  @Test
  public void testEmptySingleton() {
    TokenizedFile empty = TokenizedFile.EMPTY;
    assertNotNull(empty);
    assertTrue(empty.isEmpty());
    assertEquals(0, empty.textLines());
    assertTrue(empty.getRawLines().isEmpty());
    assertEquals("", empty.getRawLine(0));
    assertEquals("", empty.getLineText(0));
    assertTrue(empty.getLineTokens(0).isEmpty());
    assertNull(empty.getLine(0));
    assertTrue(empty.getDiagnostics().isEmpty());

    assertThrows(UnsupportedOperationException.class, () -> empty.addLine("foo", 0, null));
    assertThrows(UnsupportedOperationException.class, () -> empty.setRawLines(null));
    assertThrows(UnsupportedOperationException.class, () -> empty.addDiagnostic(null));
    assertThrows(UnsupportedOperationException.class, () -> empty.addDiagnostics(null));
    assertThrows(UnsupportedOperationException.class, empty::clearDiagnostics);
  }

  @Test
  public void testScannerTokenOffsetsWithoutWhitespaceLeakage() {
    var scanner = new CA65Scanner();
    var file = scanner.tokenize("    lda #$20\n");

    assertFalse(file.isEmpty());
    var tokens = file.getLineTokens(0);
    assertEquals(3, tokens.size());

    var ldaToken = tokens.get(0);
    assertEquals("lda", ldaToken.text());
    assertEquals(4, ldaToken.getStartIndex(), "startIndex should not include leading spaces");
    assertEquals(7, ldaToken.getEndIndex());
    assertEquals(ldaToken.text().length(), ldaToken.getEndIndex() - ldaToken.getStartIndex());

    var hashToken = tokens.get(1);
    assertEquals("#", hashToken.text());
    assertEquals(8, hashToken.getStartIndex());
    assertEquals(9, hashToken.getEndIndex());

    var numToken = tokens.get(2);
    assertEquals(TokenType.TOK_INTCON, numToken.getType());
    assertEquals(9, numToken.getStartIndex());
    assertEquals(12, numToken.getEndIndex());
  }

  @Test
  public void testGetQualifiedSymbolAt() {
    var scanner = new CA65Scanner();
    var file =
        scanner.tokenize(
            """
            lda Point::coordX
            jsr ::globalFunc
            bra @loop
            jsr mySymbol
            """);

    // Line 0: "lda Point::coordX"
    // lda: 0..3, Point: 4..9, ::: 9..11, coordX: 11..17
    assertEquals("Point::coordX", file.getQualifiedSymbolAt(0, 6).orElse(null));
    assertEquals("Point::coordX", file.getQualifiedSymbolAt(0, 9).orElse(null));
    assertEquals("Point::coordX", file.getQualifiedSymbolAt(0, 10).orElse(null));
    assertEquals("Point::coordX", file.getQualifiedSymbolAt(0, 13).orElse(null));

    // Line 1: "jsr ::globalFunc"
    // jsr: 0..3, ::: 4..6, globalFunc: 6..16
    assertEquals("::globalFunc", file.getQualifiedSymbolAt(1, 4).orElse(null));
    assertEquals("::globalFunc", file.getQualifiedSymbolAt(1, 5).orElse(null));
    assertEquals("::globalFunc", file.getQualifiedSymbolAt(1, 8).orElse(null));

    // Line 2: "bra @loop"
    // bra: 0..3, @loop: 4..9
    assertEquals("@loop", file.getQualifiedSymbolAt(2, 6).orElse(null));

    // Line 3: "jsr mySymbol"
    // jsr: 0..3, mySymbol: 4..12
    assertEquals("mySymbol", file.getQualifiedSymbolAt(3, 8).orElse(null));

    // Invalid / out-of-bounds positions
    assertTrue(file.getQualifiedSymbolAt(-1, 0).isEmpty());
    assertTrue(file.getQualifiedSymbolAt(99, 0).isEmpty());
    assertTrue(file.getQualifiedSymbolAt(0, 50).isEmpty());
  }

  @Test
  public void testGetPrimaryOpcode() {
    var scanner = new CA65Scanner();
    var file =
        scanner.tokenize(
            """
            nop
            my_label: lda #1
            label_only:
            .byte $01
            with r1
            """);

    assertEquals("nop", file.getPrimaryOpcode(0).map(t -> t.text().toLowerCase()).orElse(null));
    assertEquals("lda", file.getPrimaryOpcode(1).map(t -> t.text().toLowerCase()).orElse(null));
    assertTrue(file.getPrimaryOpcode(2).isEmpty()); // label only
    assertTrue(file.getPrimaryOpcode(3).isEmpty()); // directive
    assertEquals("with", file.getPrimaryOpcode(4).map(t -> t.text().toLowerCase()).orElse(null));
  }

  @Test
  public void testIsLabelOnly() {
    var scanner = new CA65Scanner();
    var file =
        scanner.tokenize(
            """
            my_label:
            @local_loop:
            my_label: lda #1
            nop
            .segment "CODE"
            """);

    assertTrue(file.isLabelOnly(0));
    assertTrue(file.isLabelOnly(1));
    assertFalse(file.isLabelOnly(2));
    assertFalse(file.isLabelOnly(3));
    assertFalse(file.isLabelOnly(4));
  }
}
