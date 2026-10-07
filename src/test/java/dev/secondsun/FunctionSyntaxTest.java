package dev.secondsun;

import static org.junit.jupiter.api.Assertions.*;

import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;
import org.junit.jupiter.api.Test;

public class FunctionSyntaxTest {

    private final CA65Scanner scanner = new CA65Scanner();

    @Test
    public void testTokenizeKeywords() {
        var file = scanner.tokenize("""
            function my_func
            return
            endfunction
            call other_func
            """);

        assertEquals(TokenType.TOK_FUNCTION, file.getLineTokens(0).get(0).type);
        assertEquals(TokenType.TOK_RETURN, file.getLineTokens(1).get(0).type);
        assertEquals(TokenType.TOK_ENDFUNCTION, file.getLineTokens(2).get(0).type);
        assertEquals(TokenType.TOK_CALL, file.getLineTokens(3).get(0).type);
    }

    @Test
    public void testFunctionWithParamsAndReturn() {
        var file = scanner.tokenize("function add_vec v1, v2 : result");
        var tokens = file.getLine(0);

        var func = FunctionSyntaxHelper.parseFunction(tokens);
        assertFalse(func.hasErrors());
        assertEquals("add_vec", func.name());
        assertEquals(2, func.parameters().size());
        assertEquals("v1", func.parameters().get(0).text());
        assertEquals("v2", func.parameters().get(1).text());
        assertTrue(func.returnVariable().isPresent());
        assertEquals("result", func.returnVariable().get().text());
    }

    @Test
    public void testFunctionWithoutParamsOrReturn() {
        var file = scanner.tokenize("function reset_all");
        var tokens = file.getLine(0);

        var func = FunctionSyntaxHelper.parseFunction(tokens);
        assertFalse(func.hasErrors());
        assertEquals("reset_all", func.name());
        assertTrue(func.parameters().isEmpty());
        assertTrue(func.returnVariable().isEmpty());
    }

    @Test
    public void testCallWithArgsAndDestination() {
        var file = scanner.tokenize("call add_vec r1, r2 : sum");
        var tokens = file.getLine(0);

        var call = FunctionSyntaxHelper.parseCall(tokens);
        assertFalse(call.hasErrors());
        assertEquals("add_vec", call.targetName());
        assertEquals(2, call.arguments().size());
        assertEquals("r1", call.arguments().get(0).text());
        assertEquals("r2", call.arguments().get(1).text());
        assertTrue(call.destinationVariable().isPresent());
        assertEquals("sum", call.destinationVariable().get().text());
    }

    @Test
    public void testCallWithoutArgsOrDestination() {
        var file = scanner.tokenize("call sync_vblank");
        var tokens = file.getLine(0);

        var call = FunctionSyntaxHelper.parseCall(tokens);
        assertFalse(call.hasErrors());
        assertEquals("sync_vblank", call.targetName());
        assertTrue(call.arguments().isEmpty());
        assertTrue(call.destinationVariable().isEmpty());
    }

    @Test
    public void testReturnWithValue() {
        var file = scanner.tokenize("return my_val");
        var ret = FunctionSyntaxHelper.parseReturn(file.getLine(0));

        assertFalse(ret.hasErrors());
        assertTrue(ret.returnVariable().isPresent());
        assertEquals("my_val", ret.returnVariable().get().text());
    }

    @Test
    public void testVoidReturn() {
        var file = scanner.tokenize("return");
        var ret = FunctionSyntaxHelper.parseReturn(file.getLine(0));

        assertFalse(ret.hasErrors());
        assertTrue(ret.returnVariable().isEmpty());
    }

    @Test
    public void testFunctionDuplicateParamError() {
        var file = scanner.tokenize("function bad_func a, a");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertTrue(func.hasErrors());
        assertTrue(func.parameters().get(1).hasAttribute(TokenAttribute.ERROR));
        assertEquals("Duplicate parameter name: a", func.parameters().get(1).message);
    }

    @Test
    public void testFunctionDanglingColonError() {
        var file = scanner.tokenize("function bad_func a, b :");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertTrue(func.hasErrors());
    }

    @Test
    public void testCallMultipleDestinationError() {
        var file = scanner.tokenize("call bad_call a, b : out1, out2");
        var call = FunctionSyntaxHelper.parseCall(file.getLine(0));

        assertTrue(call.hasErrors());
    }

    @Test
    public void testReturnMultipleValuesError() {
        var file = scanner.tokenize("return val1, val2");
        var ret = FunctionSyntaxHelper.parseReturn(file.getLine(0));

        assertTrue(ret.hasErrors());
    }

    @Test
    public void testFunctionWithRegisterReturn() {
        var file = scanner.tokenize("function get_tick : r0");
        var tokens = file.getLine(0);

        var func = FunctionSyntaxHelper.parseFunction(tokens);
        assertFalse(func.hasErrors());
        assertEquals("get_tick", func.name());
        assertTrue(func.parameters().isEmpty());
        assertTrue(func.returnVariable().isPresent());
        assertEquals(TokenType.TOK_REGISTER, func.returnVariable().get().type);
        assertEquals("r0", func.returnVariable().get().text());
    }

    @Test
    public void testFunctionSingleParamSpaceSeparated() {
        var file = scanner.tokenize("function add_one x");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertFalse(func.hasErrors());
        assertEquals("add_one", func.name());
        assertEquals(1, func.parameters().size());
        assertEquals("x", func.parameters().get(0).text());
        assertTrue(func.returnVariable().isEmpty());
    }

    @Test
    public void testFunctionMissingNameError() {
        var file = scanner.tokenize("function");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertTrue(func.hasErrors());
        assertTrue(func.functionToken().hasAttribute(TokenAttribute.ERROR));
    }

    @Test
    public void testFunctionNameNotIdentError() {
        var file = scanner.tokenize("function 123");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertTrue(func.hasErrors());
        assertTrue(func.nameToken().hasAttribute(TokenAttribute.ERROR));
    }

    @Test
    public void testFunctionMultipleReturnVarsError() {
        var file = scanner.tokenize("function foo p1 : a, b");
        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));

        assertTrue(func.hasErrors());
    }

    @Test
    public void testCallMissingTargetError() {
        var file = scanner.tokenize("call");
        var call = FunctionSyntaxHelper.parseCall(file.getLine(0));

        assertTrue(call.hasErrors());
        assertTrue(call.callToken().hasAttribute(TokenAttribute.ERROR));
    }

    @Test
    public void testCallDanglingColonError() {
        var file = scanner.tokenize("call foo :");
        var call = FunctionSyntaxHelper.parseCall(file.getLine(0));

        assertTrue(call.hasErrors());
    }

    @Test
    public void testCallRegisterDestination() {
        var file = scanner.tokenize("call get_count : r3");
        var call = FunctionSyntaxHelper.parseCall(file.getLine(0));

        assertFalse(call.hasErrors());
        assertEquals("get_count", call.targetName());
        assertTrue(call.arguments().isEmpty());
        assertTrue(call.destinationVariable().isPresent());
        assertEquals("r3", call.destinationVariable().get().text());
        assertEquals(TokenType.TOK_REGISTER, call.destinationVariable().get().type);
    }

    @Test
    public void testReturnRegister() {
        var file = scanner.tokenize("return r0");
        var ret = FunctionSyntaxHelper.parseReturn(file.getLine(0));

        assertFalse(ret.hasErrors());
        assertTrue(ret.returnVariable().isPresent());
        assertEquals("r0", ret.returnVariable().get().text());
        assertEquals(TokenType.TOK_REGISTER, ret.returnVariable().get().type);
    }

    @Test
    public void testEndFunctionValid() {
        var file = scanner.tokenize("endfunction");
        var tokens = file.getLine(0);

        assertTrue(FunctionSyntaxHelper.isEndFunction(tokens));
        assertTrue(FunctionSyntaxHelper.validateEndFunction(tokens));
        assertFalse(tokens.tokens().get(0).hasAttribute(TokenAttribute.ERROR));
    }

    @Test
    public void testEndFunctionTrailingTokensError() {
        var file = scanner.tokenize("endfunction extra_arg");
        var tokens = file.getLine(0);

        assertTrue(FunctionSyntaxHelper.isEndFunction(tokens));
        assertFalse(FunctionSyntaxHelper.validateEndFunction(tokens));
        assertTrue(tokens.tokens().get(1).hasAttribute(TokenAttribute.ERROR));
        assertEquals(
                "Unexpected token after endfunction: extra_arg", tokens.tokens().get(1).message);
    }

    @Test
    public void testCaseInsensitivityAndPredicates() {
        var file = scanner.tokenize("""
            FUNCTION upper_func : r0
            CALL other_func
            RETURN
            ENDFUNCTION
            """);

        assertTrue(FunctionSyntaxHelper.isFunction(file.getLine(0)));
        assertTrue(FunctionSyntaxHelper.isCall(file.getLine(1)));
        assertTrue(FunctionSyntaxHelper.isReturn(file.getLine(2)));
        assertTrue(FunctionSyntaxHelper.isEndFunction(file.getLine(3)));

        var func = FunctionSyntaxHelper.parseFunction(file.getLine(0));
        assertFalse(func.hasErrors());
        assertEquals("upper_func", func.name());
        assertEquals("r0", func.returnVariable().get().text());

        var call = FunctionSyntaxHelper.parseCall(file.getLine(1));
        assertFalse(call.hasErrors());
        assertEquals("other_func", call.targetName());

        var ret = FunctionSyntaxHelper.parseReturn(file.getLine(2));
        assertFalse(ret.hasErrors());
        assertTrue(ret.returnVariable().isEmpty());
    }
}
