import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MiniC -> Jasmin compiler (requires Java 17+).
 *
 * Grammar (BNF):
 *
 *   <program>     ::= <statements>
 *   <statements>  ::= <statement> <statements> | ""
 *   <statement>   ::= <assignment> | <if-stmt> | <while-stmt> | <for-stmt>
 *   <block>       ::= "{" <statements> "}"
 *   <assignment>  ::= <ident> "=" <expr> ";"
 *   <if-stmt>     ::= "if" "(" <cond> ")" <block>
 *   <while-stmt>  ::= "while" "(" <cond> ")" <block>
 *   <for-stmt>    ::= "for" "(" <assignment> <cond> ";" <update> ")" <block>
 *   <update>      ::= <ident> "=" <expr>
 *   <cond>        ::= <term> <relop> <term>
 *   <relop>       ::= "<" | "=="
 *   <expr>        ::= <expr> "+" <term> | <term>
 *   <term>        ::= <number> | <ident>
 *   <ident>       ::= <letter> { <letter> | <digit> }       (not a keyword)
 *   <number>      ::= <digit> { <digit> }
 *
 * Design: lexer -> recursive-descent parser -> AST -> code generator.
 * Semantics: all variables are 32-bit ints, declared implicitly by use, and
 * initialised to 0 at method entry (so reading a not-yet-assigned variable is
 * well defined and always passes the JVM verifier).
 *
 * Usage:  java MiniCJasminCompiler [--dump] [file | -]
 *   --dump   make the generated program print every variable on exit
 *   no file  compiles a built-in sample
 *
 * @author Mario Gianota <mariogianota@protonmail.com>
 */
public class MiniCJasminCompiler {

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------
    static class CompileError extends RuntimeException {
        CompileError(String msg, int line, int col) {
            super("line " + line + ", column " + col + ": " + msg);
        }
    }

    // ------------------------------------------------------------------
    // Lexer
    // ------------------------------------------------------------------
    /** kind is "ident", "number", "eof", or the exact text of a keyword/symbol. */
    record Token(String kind, String text, int line, int col) {
        String describe() {
            return kind.equals("eof") ? "end of input" : "'" + text + "'";
        }
    }

    static final Set<String> KEYWORDS = Set.of("if", "while", "for");

    static List<Token> lex(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0, line = 1, col = 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') { line++; col = 1; i++; continue; }
            if (Character.isWhitespace(c)) { col++; i++; continue; }

            int startCol = col;
            if (isAsciiLetter(c)) {
                int j = i;
                while (j < s.length() && (isAsciiLetter(s.charAt(j)) || isAsciiDigit(s.charAt(j)))) j++;
                String word = s.substring(i, j);
                out.add(new Token(KEYWORDS.contains(word) ? word : "ident", word, line, startCol));
                col += j - i;
                i = j;
            } else if (isAsciiDigit(c)) {
                int j = i;
                while (j < s.length() && isAsciiDigit(s.charAt(j))) j++;
                out.add(new Token("number", s.substring(i, j), line, startCol));
                col += j - i;
                i = j;
            } else if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                out.add(new Token("==", "==", line, startCol));
                col += 2;
                i += 2;
            } else if ("(){};=<+".indexOf(c) >= 0) {
                out.add(new Token(String.valueOf(c), String.valueOf(c), line, startCol));
                col++;
                i++;
            } else {
                throw new CompileError("unexpected character '" + c + "'", line, col);
            }
        }
        out.add(new Token("eof", "", line, col));
        return out;
    }

    static boolean isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    static boolean isAsciiDigit(char c)  { return c >= '0' && c <= '9'; }

    // ------------------------------------------------------------------
    // AST
    // ------------------------------------------------------------------
    sealed interface Expr permits Num, Var, Add {}
    record Num(int value) implements Expr {}
    record Var(String name) implements Expr {}
    record Add(Expr left, Expr right) implements Expr {}

    /** left and right are always Num or Var (grammar: <term>). */
    record Cond(Expr left, String op, Expr right) {}

    sealed interface Stmt permits Assign, If, While, For {}
    record Assign(String name, Expr value) implements Stmt {}
    record If(Cond cond, List<Stmt> body) implements Stmt {}
    record While(Cond cond, List<Stmt> body) implements Stmt {}
    record For(Assign init, Cond cond, Assign update, List<Stmt> body) implements Stmt {}

    // ------------------------------------------------------------------
    // Parser (one method per grammar rule)
    // ------------------------------------------------------------------
    static class Parser {
        private final List<Token> toks;
        private int p = 0;
        /** Variables in order of first appearance in the source. */
        final Set<String> variables = new LinkedHashSet<>();

        Parser(List<Token> toks) { this.toks = toks; }

        private Token cur() { return toks.get(p); }

        private boolean at(String kind) { return cur().kind().equals(kind); }

        private Token expect(String kind) {
            Token t = cur();
            if (!t.kind().equals(kind)) {
                String want = kind.equals("ident") ? "identifier"
                            : kind.equals("number") ? "number" : "'" + kind + "'";
                throw new CompileError("expected " + want + " but found " + t.describe(), t.line(), t.col());
            }
            p++;
            return t;
        }

        List<Stmt> program() {
            List<Stmt> stmts = new ArrayList<>();
            while (!at("eof")) stmts.add(statement());
            return stmts;
        }

        private Stmt statement() {
            Token t = cur();
            switch (t.kind()) {
                case "if":    return ifStmt();
                case "while": return whileStmt();
                case "for":   return forStmt();
                case "ident": return assignment();
                default:
                    throw new CompileError("expected a statement but found " + t.describe(), t.line(), t.col());
            }
        }

        private List<Stmt> block() {
            expect("{");
            List<Stmt> stmts = new ArrayList<>();
            while (!at("}")) {
                if (at("eof")) expect("}"); // reports "expected '}' but found end of input"
                stmts.add(statement());
            }
            expect("}");
            return stmts;
        }

        private Assign assignment() {
            Assign a = update();
            expect(";");
            return a;
        }

        /** <update> ::= <ident> "=" <expr>   (an assignment without the ';') */
        private Assign update() {
            String name = expect("ident").text();
            variables.add(name);
            expect("=");
            return new Assign(name, expr());
        }

        private If ifStmt() {
            expect("if");
            expect("(");
            Cond c = cond();
            expect(")");
            return new If(c, block());
        }

        private While whileStmt() {
            expect("while");
            expect("(");
            Cond c = cond();
            expect(")");
            return new While(c, block());
        }

        private For forStmt() {
            expect("for");
            expect("(");
            Assign init = assignment();     // consumes its own ';'
            Cond c = cond();
            expect(";");
            Assign upd = update();          // no trailing ';'
            expect(")");
            return new For(init, c, upd, block());
        }

        private Cond cond() {
            Expr l = term();
            Token op = cur();
            if (!at("<") && !at("==")) {
                throw new CompileError("expected '<' or '==' but found " + op.describe(), op.line(), op.col());
            }
            p++;
            return new Cond(l, op.kind(), term());
        }

        /** <expr> ::= <term> { "+" <term> }   (left associative) */
        private Expr expr() {
            Expr e = term();
            while (at("+")) {
                p++;
                e = new Add(e, term());
            }
            return e;
        }

        private Expr term() {
            Token t = cur();
            if (at("number")) {
                p++;
                try {
                    return new Num(Integer.parseInt(t.text()));
                } catch (NumberFormatException ex) {
                    throw new CompileError("integer literal too large: " + t.text(), t.line(), t.col());
                }
            }
            if (at("ident")) {
                p++;
                variables.add(t.text());
                return new Var(t.text());
            }
            throw new CompileError("expected number or identifier but found " + t.describe(), t.line(), t.col());
        }
    }

    // ------------------------------------------------------------------
    // Code generator
    // ------------------------------------------------------------------
    static class CodeGen {
        private final StringBuilder out = new StringBuilder();
        private final Map<String, Integer> slot = new LinkedHashMap<>();
        private int labels = 0;

        String generate(List<Stmt> program, Set<String> variables, boolean dump) {
            // Local 0 holds args, so variables start at slot 1.
            int n = 1;
            for (String v : variables) slot.put(v, n++);

            out.append(".class public Main\n");
            out.append(".super java/lang/Object\n\n");
            out.append(".method public static main([Ljava/lang/String;)V\n");
            out.append("    .limit stack 2\n");       // deepest need: two operands
            out.append("    .limit locals ").append(n).append("\n\n");

            // Implicit declaration: every variable starts at 0.
            for (int s : slot.values()) {
                emit("iconst_0");
                emit("istore " + s);
            }
            if (!slot.isEmpty()) out.append("\n");

            block(program);

            if (dump) {
                out.append("\n");
                for (Map.Entry<String, Integer> e : slot.entrySet()) {
                    emit("getstatic java/lang/System/out Ljava/io/PrintStream;");
                    emit("ldc \"" + e.getKey() + " = \"");
                    emit("invokevirtual java/io/PrintStream/print(Ljava/lang/String;)V");
                    emit("getstatic java/lang/System/out Ljava/io/PrintStream;");
                    emit("iload " + e.getValue());
                    emit("invokevirtual java/io/PrintStream/println(I)V");
                }
            }

            out.append("\n");
            emit("return");
            out.append(".end method\n");
            return out.toString();
        }

        private void emit(String instr) { out.append("    ").append(instr).append("\n"); }
        private void label(String l)    { out.append(l).append(":\n"); }
        private String newLabel(String hint) { return "L" + (labels++) + "_" + hint; }

        private void block(List<Stmt> stmts) { for (Stmt s : stmts) stmt(s); }

        private void stmt(Stmt s) {
            if (s instanceof Assign a) {
                assign(a);
            } else if (s instanceof If i) {
                String end = newLabel("endif");
                condJumpIfFalse(i.cond(), end);
                block(i.body());
                label(end);
            } else if (s instanceof While w) {
                String top = newLabel("while");
                String end = newLabel("endwhile");
                label(top);
                condJumpIfFalse(w.cond(), end);
                block(w.body());
                emit("goto " + top);
                label(end);
            } else if (s instanceof For f) {
                String top = newLabel("for");
                String end = newLabel("endfor");
                assign(f.init());
                label(top);
                condJumpIfFalse(f.cond(), end);
                block(f.body());
                assign(f.update());   // update runs after the body, before re-testing
                emit("goto " + top);
                label(end);
            }
        }

        private void assign(Assign a) {
            expr(a.value());
            emit("istore " + slot.get(a.name()));
        }

        private void expr(Expr e) {
            if (e instanceof Num n) {
                pushInt(n.value());
            } else if (e instanceof Var v) {
                emit("iload " + slot.get(v.name()));
            } else if (e instanceof Add a) {
                expr(a.left());
                expr(a.right());
                emit("iadd");
            }
        }

        /** Evaluates the condition; jumps to target when it is FALSE, falls through when true. */
        private void condJumpIfFalse(Cond c, String target) {
            expr(c.left());
            expr(c.right());
            emit((c.op().equals("<") ? "if_icmpge " : "if_icmpne ") + target);
        }

        private void pushInt(int v) {
            if (v >= -1 && v <= 5)                          emit(v == -1 ? "iconst_m1" : "iconst_" + v);
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE)   emit("bipush " + v);
            else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE)  emit("sipush " + v);
            else                                            emit("ldc " + v);
        }
    }

    // ------------------------------------------------------------------
    // Driver
    // ------------------------------------------------------------------
    static final String SAMPLE =
        "x = 0;\n" +
        "for (i = 0; i < 3; i = i + 1) {\n" +
        "    x = x + i;\n" +
        "}\n";

    public static String compile(String source, boolean dump) {
        Parser parser = new Parser(lex(source));
        List<Stmt> program = parser.program();
        return new CodeGen().generate(program, parser.variables, dump);
    }

    public static void main(String[] args) throws IOException {
        boolean dump = false;
        String file = null;
        for (String a : args) {
            if (a.equals("--dump")) dump = true;
            else file = a;
        }

        String source;
        if (file == null) source = SAMPLE;
        else if (file.equals("-")) source = new String(System.in.readAllBytes());
        else source = Files.readString(Path.of(file));

        try {
            System.out.print(compile(source, dump));
        } catch (CompileError e) {
            System.err.println("Compile error: " + e.getMessage());
            System.exit(1);
        }
    }
}
