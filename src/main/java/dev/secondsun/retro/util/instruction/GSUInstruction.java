package dev.secondsun.retro.util.instruction;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenAttribute;
import dev.secondsun.retro.util.vo.Tokens;

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
    public enum Instructions {
        JMP(new GSUInstruction("JMP", "Rn")),
        LJMP(new GSUInstruction("LJMP", "Rn")),
        BRA(new GSUInstruction("BRA", "E")),
        IWT_JUMP(new GSUInstruction("IWT", "R15", "#n")),
        ADC_REGISTER(new GSUInstruction("ADC","Rn")),
        ADC_CONST(new GSUInstruction("ADC","#n")),
        ADD_REGISTER(new GSUInstruction("ADD","Rn")),
        ADD_CONST(new GSUInstruction("ADD","#n")),
        ALT1(new GSUInstruction("ALT1")),

        ALT2(new GSUInstruction("ALT2")),

        ALT3(new GSUInstruction("ALT3")),

        AND_REGISTER(new GSUInstruction("AND", "Rn")),

        AND_CONST(new GSUInstruction("AND", "#n")),

        ASR(new GSUInstruction("ASR")),
        BCC(new GSUInstruction("BCC", "e")),

        BCS(new GSUInstruction("BCS", "e")),

        BEQ(new GSUInstruction("BEQ", "e")),

        BGE(new GSUInstruction("BGE", "e")),

        BIC_REGISTER(new GSUInstruction("BIC", "Rn")),

        BIC_CONST(new GSUInstruction("BIC", "#n")),

        BLT(new GSUInstruction("BLT", "e")),

        BMI(new GSUInstruction("BMI", "e")),

        BNE(new GSUInstruction("BNE", "e")),

        BPL(new GSUInstruction("BPL", "e")),

        BVC(new GSUInstruction("BVC", "e")),

        BVS(new GSUInstruction("BVS", "e")),

        CACHE(new GSUInstruction("CACHE")),
        CMODE(new GSUInstruction("CMODE")),
        CMP(new GSUInstruction("CMP", "Rn")),

        COLOR(new GSUInstruction("COLOR")),
        DEC(new GSUInstruction("DEC", "Rn")),

        DIV2(new GSUInstruction("DIV2")),
        FMULT(new GSUInstruction("FMULT")),
        FROM(new GSUInstruction("FROM", "Rn")),

        GETB(new GSUInstruction("GETB")),
        GETBH(new GSUInstruction("GETBH")),
        GETBL(new GSUInstruction("GETBL")),
        GETBS(new GSUInstruction("GETBS")),
        GETC(new GSUInstruction("GETC")),
        HIB(new GSUInstruction("HIB")),

        IBT(new GSUInstruction("IBT", "Rn", "#pp")),

        INC(new GSUInstruction("INC", "Rn")),

        IWT(new GSUInstruction("IWT", "Rn" , "#x")),


        LDB(new GSUInstruction("LDB", "Rm")),

        LDW(new GSUInstruction("LDW", "Rm")),

        LINK(new GSUInstruction("LINK", "#n")),

        LM(new GSUInstruction("LM", "Rn", "(xx)")),

        LMS(new GSUInstruction("LMS", "Rn", "(yy)")),

        LMULT(new GSUInstruction("LMULT")),
        LOB(new GSUInstruction("LOB")),
        LOOP(new GSUInstruction("LOOP")),
        LSR(new GSUInstruction("LSR")),
        MERGE(new GSUInstruction("MERGE")),
        MOVE(new GSUInstruction("MOVE", "Rn" , "Rn")),
        MOVES(new GSUInstruction("MOVES", "Rn" , "Rn")),

        MULT_REGISTER(new GSUInstruction("MULT", "Rn")),

        MULT_CONST(new GSUInstruction("MULT", "#n")),

        NOP(new GSUInstruction("NOP")),
        NOT(new GSUInstruction("NOT")),
        OR_REGISTER(new GSUInstruction("OR", "Rn")),

        OR_CONST(new GSUInstruction("OR", "#n")),

        PLOT(new GSUInstruction("PLOT")),
        RAMB(new GSUInstruction("RAMB")),
        ROL(new GSUInstruction("ROL")),
        ROMB(new GSUInstruction("ROMB")),
        ROR(new GSUInstruction("ROR")),
        RPIX(new GSUInstruction("RPIX")),
        SBC(new GSUInstruction("SBC", "Rn")),

        SBK(new GSUInstruction("SBK")),
        SEX(new GSUInstruction("SEX")),
        SM(new GSUInstruction("SM", "Rn" , "(xx)")),

        SMS(new GSUInstruction("SMS", "Rn" , "(yy)")),

        STB(new GSUInstruction("STB", "Rm")),

        STOP(new GSUInstruction("STOP")),
        STW(new GSUInstruction("STW", "Rm")),

        SUB_REGISTER(new GSUInstruction("SUB", "Rn")),

        SUB_CONST(new GSUInstruction("SUB", "#n")),

        SWAP(new GSUInstruction("SWAP")),
        TO(new GSUInstruction("TO", "Rn")),

        UMULT_REGISTER(new GSUInstruction("UMULT", "Rn")),

        UMULT_CONST(new GSUInstruction("UMULT", "#n")),

        WITH(new GSUInstruction("WITH", "Rn")),

        XOR_REGISTER(new GSUInstruction("XOR", "Rn")),

        XOR_CONST(new GSUInstruction("XOR", "#n"));
        private final GSUInstruction instruction;

        Instructions(GSUInstruction gsuInstruction) {
            this.instruction = gsuInstruction;
        }

        public boolean matches(Tokens tokens) {
            return this.instruction.matches(tokens);
        }

    }

    private static final List<Instructions> unconditionalJumpInstructions;
    static {
        unconditionalJumpInstructions = List.of(
                Instructions.JMP,Instructions.LJMP,Instructions.BRA, Instructions.IWT_JUMP
        );
    }
    public GSUInstruction(String instruction, String... argumentDefinitions) {
        this.instruction = instruction;
        for (int i = 0; i < argumentDefinitions.length; i++) {
            var argument = argumentDefinitions[i];
            if (argument.startsWith("R") || argument.startsWith("r")) {
                //parse register
            } else if (argument.startsWith("#")) {
                //parse number
                //n/
                //x
                //p
            } else if (argument.startsWith("(")) {
                //parse address
            } else if (argument.equalsIgnoreCase("e")) {
                //parse offset
            }
            else {
                throw new IllegalStateException("Illegal argument definition:" + argument) ;
            }
        }
    }

    public boolean matches(Tokens tokenizedLine) {
        var tokens = tokenizedLine.tokens();

        if (tokens.isEmpty()) {
            return false;
        }

        var firstToken = tokens.getFirst();
        return firstToken.text().equalsIgnoreCase(this.instruction);
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
