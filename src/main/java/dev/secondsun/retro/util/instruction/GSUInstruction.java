package dev.secondsun.retro.util.instruction;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.Tokens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GSUInstruction {

    private final String instruction;

    private static final  Map<String, Instructions> instructionLookupTable;
    static {
        instructionLookupTable = new HashMap<>(Instructions.values().length);
        for (Instructions i : Instructions.values()) {
            instructionLookupTable.put(i.instruction.instruction, i);
        }
    }

    private final ArrayList<ArgumentMatcher> argumentMatchers;

    public static final List<Instructions> unconditionalJumpInstructions;
    public static final List<Instructions> conditionalJumpInstructions;
    static {
        unconditionalJumpInstructions = List.of(
                Instructions.JMP,Instructions.LJMP,Instructions.BRA, Instructions.IWT_JUMP
        );

        conditionalJumpInstructions = List.of(
                Instructions.BGE,Instructions.BNE,Instructions.BCC,Instructions.BNE,Instructions.BCS,Instructions.BEQ,
                Instructions.BLT,Instructions.BMI,Instructions.BMI,Instructions.BPL,Instructions.BVS,Instructions.BVC
                );
    }
    public GSUInstruction(String instruction, String... argumentDefinitions) {
        this.instruction = instruction;
        this.argumentMatchers = new ArrayList<ArgumentMatcher>();
        for (int i = 0; i < argumentDefinitions.length; i++) {
            var argument = argumentDefinitions[i];
            argumentMatchers.add(ArgumentMatcher.create(argument));
        }
    }

    public boolean matches(Tokens tokenizedLine) {
        var listTokens = new ArrayList<>(tokenizedLine.tokens());

        if (listTokens.isEmpty()) {
            return false;
        }

        var firstToken = listTokens.getFirst();
        if (!firstToken.text().equalsIgnoreCase(this.instruction)) {
            return false;
        }

        listTokens.remove(0);//consume instruction token

        var matches = true;

        for (ArgumentMatcher matcher : argumentMatchers) {
            matches = matches && matcher.match(listTokens);
            if (listTokens.size() > 0) {
                matches = matches && listTokens.remove(0).type == TokenType.TOK_COMMA;
            }
        }

        return matches;
    }

    public static boolean isInstruction(Token firstToken) {
        return instructionLookupTable.get(firstToken.text().trim().toUpperCase()) != null;
    }
    public static void mark(Token firstToken) {
        var instruction = instructionLookupTable.get(firstToken.text().trim().toUpperCase());
        if (instruction != null) {
            firstToken.addAttribute(TokenAttribute.GSU_INSTRUCTION);
        }
    }
}
