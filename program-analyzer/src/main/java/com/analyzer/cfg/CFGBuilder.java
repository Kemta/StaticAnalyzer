package com.analyzer.cfg;

import com.github.javaparser.ast.stmt.*;

import java.util.*;

public class CFGBuilder {

    public final List<CFGNode> nodes = new ArrayList<>();

    // BOTH directions are required
    public final Map<Integer, List<Integer>> predecessors = new HashMap<>();
    public final Map<Integer, List<Integer>> successors   = new HashMap<>();

    public CFGBuilder(List<Statement> statements) {
        buildSequence(statements, new LinkedHashSet<>());
    }

    /* ----------------- Core building ----------------- */

    // Builds a sequence of statements. Returns the set of "exit" node IDs after the sequence.
    private Set<Integer> buildSequence(List<Statement> stmts, Set<Integer> incomingPreds) {
        Set<Integer> preds = new LinkedHashSet<>(incomingPreds);

        for (Statement stmt : stmts) {
            preds = buildStatement(stmt, preds);
        }
        return preds;
    }

    // Builds one statement. Returns new "exit" predecessors after this statement.
    private Set<Integer> buildStatement(Statement stmt, Set<Integer> incomingPreds) {

        // IF / ELSE
        if (stmt.isIfStmt()) {
            return buildIf(stmt.asIfStmt(), incomingPreds);
        }

        // WHILE
        if (stmt.isWhileStmt()) {
            return buildWhile(stmt.asWhileStmt(), incomingPreds);
        }

        // BLOCK (just flatten)
        if (stmt.isBlockStmt()) {
            return buildSequence(stmt.asBlockStmt().getStatements(), incomingPreds);
        }

        // Simple statement -> one node
        int id = newNode(stmt);
        connect(incomingPreds, id);

        // exits = this node
        return setOf(id);
    }

    private Set<Integer> buildIf(IfStmt ifStmt, Set<Integer> incomingPreds) {
        // Condition node
        int condId = newNode(
                ifStmt.getCondition() != null
                        ? new ExpressionStmt(ifStmt.getCondition())
                        : new EmptyStmt()
        );
        connect(incomingPreds, condId);

        // THEN branch
        List<Statement> thenStmts = unwrapToStatements(ifStmt.getThenStmt());
        Set<Integer> thenExits = buildSequence(thenStmts, setOf(condId));

        // ELSE branch (or fall-through)
        Set<Integer> elseExits;
        if (ifStmt.getElseStmt().isPresent()) {
            List<Statement> elseStmts = unwrapToStatements(ifStmt.getElseStmt().get());
            elseExits = buildSequence(elseStmts, setOf(condId));
        } else {
            // false branch goes directly to merge
            elseExits = setOf(condId);
        }

        // Merge exits
        Set<Integer> merged = new LinkedHashSet<>();
        merged.addAll(thenExits);
        merged.addAll(elseExits);
        return merged;
    }

    private Set<Integer> buildWhile(WhileStmt whileStmt, Set<Integer> incomingPreds) {
        // Condition node
        int condId = newNode(
                whileStmt.getCondition() != null
                        ? new ExpressionStmt(whileStmt.getCondition())
                        : new EmptyStmt()
        );
        connect(incomingPreds, condId);

        // Body
        List<Statement> bodyStmts = unwrapToStatements(whileStmt.getBody());
        Set<Integer> bodyExits = buildSequence(bodyStmts, setOf(condId));

        // Back edge(s): end of body -> condition
        connect(bodyExits, condId);

        // Exit when condition is false
        return setOf(condId);
    }

    /* ----------------- Graph helpers ----------------- */

    private int newNode(Statement stmt) {
        int id = nodes.size();
        CFGNode n = new CFGNode(id, stmt);
        nodes.add(n);

        predecessors.putIfAbsent(id, new ArrayList<>());
        successors.putIfAbsent(id, new ArrayList<>());

        return id;
    }

    // Connect all preds -> toId
    private void connect(Set<Integer> preds, int toId) {
        predecessors.putIfAbsent(toId, new ArrayList<>());
        successors.putIfAbsent(toId, new ArrayList<>());

        for (int p : preds) {
            // predecessor edge
            if (!predecessors.get(toId).contains(p)) {
                predecessors.get(toId).add(p);
            }
            // successor edge
            successors.putIfAbsent(p, new ArrayList<>());
            if (!successors.get(p).contains(toId)) {
                successors.get(p).add(toId);
            }
        }
    }

    private static Set<Integer> setOf(int x) {
        Set<Integer> s = new LinkedHashSet<>();
        s.add(x);
        return s;
    }

    private static List<Statement> unwrapToStatements(Statement stmt) {
        if (stmt == null) return List.of();
        if (stmt.isBlockStmt()) return stmt.asBlockStmt().getStatements();
        return List.of(stmt);
    }
}
