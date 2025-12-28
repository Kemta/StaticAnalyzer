package com.analyzer.lva;

import com.analyzer.cfg.CFGNode;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.util.*;

/**
 * Live Variable Analysis (Backward data-flow)
 *
 * IN[n]  = USE[n] ∪ (OUT[n] - DEF[n])
 * OUT[n] = ⋃ IN[s] for all successors s of n
 *
 * Dead assignment:
 *  - Node defines a variable
 *  - That variable is not live after the node (DEF ∩ OUT == ∅)
 *  - AND statement has no side effects (we conservatively exclude calls)
 *
 * Also prints a DOT graph (Graphviz) with IN/OUT per node.
 */
public class LiveVariableAnalysis {

    // tokens to ignore as “variables”
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("System", "out", "println"));

    public static void analyze(List<CFGNode> nodes,
                               Map<Integer, List<Integer>> successorsInput) {

        // --- normalize ids ---
        Set<Integer> nodeIds = new HashSet<>();
        for (CFGNode n : nodes) nodeIds.add(n.id);

        // --- sanitize + auto-fix reversed edges if needed ---
        Map<Integer, List<Integer>> sanitized = sanitizeEdges(successorsInput, nodeIds);
        Map<Integer, List<Integer>> successors =
                looksReversed(nodes, sanitized) ? invertEdges(sanitized, nodeIds) : sanitized;

        Map<Integer, Set<String>> use = new HashMap<>();
        Map<Integer, Set<String>> def = new HashMap<>();
        Map<Integer, Set<String>> in  = new HashMap<>();
        Map<Integer, Set<String>> out = new HashMap<>();

        /* 1) Build USE/DEF per node */
        for (CFGNode n : nodes) {
            Set<String> u = new HashSet<>();
            Set<String> d = new HashSet<>();
            computeUseDef(n.statement, u, d);

            use.put(n.id, u);
            def.put(n.id, d);
            in.put(n.id, new HashSet<>());
            out.put(n.id, new HashSet<>());
        }

        /* 2) Fixpoint iteration (backward) */
        boolean changed;
        do {
            changed = false;

            for (int i = nodes.size() - 1; i >= 0; i--) {
                CFGNode n = nodes.get(i);

                // OUT[n] = union of IN[succ]
                Set<String> newOut = new HashSet<>();
                for (int s : successors.getOrDefault(n.id, List.of())) {
                    newOut.addAll(in.getOrDefault(s, Set.of()));
                }

                // IN[n] = USE[n] ∪ (OUT[n] - DEF[n])
                Set<String> newIn = new HashSet<>(use.getOrDefault(n.id, Set.of()));
                Set<String> outMinusDef = new HashSet<>(newOut);
                outMinusDef.removeAll(def.getOrDefault(n.id, Set.of()));
                newIn.addAll(outMinusDef);

                if (!newIn.equals(in.get(n.id)) || !newOut.equals(out.get(n.id))) {
                    in.put(n.id, newIn);
                    out.put(n.id, newOut);
                    changed = true;
                }
            }
        } while (changed);

        /* 3) Print results */
        System.out.println("\n===== Live Variable Analysis =====");
        for (CFGNode n : nodes) {
            System.out.println("Node " + n.id + ": " + n.statement);
            System.out.println("  USE = " + use.get(n.id));
            System.out.println("  DEF = " + def.get(n.id));
            System.out.println("  IN  = " + in.get(n.id));
            System.out.println("  OUT = " + out.get(n.id));
        }

        /* 4) Dead assignment detection (unnecessary computations) */
        System.out.println("\n===== Dead Code Detection (Dead Assignments) =====");
        for (CFGNode n : nodes) {
            Statement stmt = n.statement;

            // Side effects => never report as dead
            if (hasSideEffects(stmt)) continue;

            Set<String> defs = def.getOrDefault(n.id, Set.of());
            if (defs.isEmpty()) continue;

            Set<String> liveAfter = new HashSet<>(defs);
            liveAfter.retainAll(out.getOrDefault(n.id, Set.of()));

            if (liveAfter.isEmpty()) {
                System.out.println("Dead assignment at Node " + n.id + " : " + stmt);
            }
        }

        /* 5) Visual representation (Graphviz DOT) */
        System.out.println("\n===== Live Variables DOT Graph (Graphviz) =====");
        System.out.println(toDot(nodes, successors, in, out));
    }

    // ---------------- USE/DEF extraction ----------------

    private static void computeUseDef(Statement stmt, Set<String> use, Set<String> def) {

        // if (cond)
        if (stmt.isIfStmt()) {
            use.addAll(varsInExpr(stmt.asIfStmt().getCondition()));
            clean(use); clean(def);
            return;
        }

        // while (cond)
        if (stmt.isWhileStmt()) {
            use.addAll(varsInExpr(stmt.asWhileStmt().getCondition()));
            clean(use); clean(def);
            return;
        }

        // for (...)  handle init/compare/update
        if (stmt.isForStmt()) {
            ForStmt s = stmt.asForStmt();

            s.getInitialization().forEach(init -> {
                if (init.isVariableDeclarationExpr()) {
                    VariableDeclarationExpr v = init.asVariableDeclarationExpr();
                    v.getVariables().forEach(var -> {
                        def.add(var.getNameAsString());
                        var.getInitializer().ifPresent(initExpr -> use.addAll(varsInExpr(initExpr)));
                    });
                } else {
                    // init could be assignment / call
                    use.addAll(varsInExpr(init));
                    def.addAll(defsInExpr(init));
                }
            });

            s.getCompare().ifPresent(c -> use.addAll(varsInExpr(c)));

            s.getUpdate().forEach(u -> {
                use.addAll(varsInExpr(u));
                def.addAll(defsInExpr(u));
            });

            clean(use); clean(def);
            return;
        }

        // return expr;
        if (stmt.isReturnStmt()) {
            stmt.asReturnStmt().getExpression().ifPresent(e -> use.addAll(varsInExpr(e)));
            clean(use); clean(def);
            return;
        }

        // expression stmt
        if (!stmt.isExpressionStmt()) {
            clean(use); clean(def);
            return;
        }

        Expression e = stmt.asExpressionStmt().getExpression();

        // int x = rhs; / int x;
        if (e.isVariableDeclarationExpr()) {
            VariableDeclarationExpr v = e.asVariableDeclarationExpr();
            v.getVariables().forEach(var -> {
                def.add(var.getNameAsString());
                var.getInitializer().ifPresent(initExpr -> use.addAll(varsInExpr(initExpr)));
            });
            clean(use); clean(def);
            return;
        }

        // x = rhs / x += rhs
        if (e.isAssignExpr()) {
            AssignExpr a = e.asAssignExpr();

            // DEF: target
            def.addAll(defsInExpr(a.getTarget()));

            // USE: rhs
            use.addAll(varsInExpr(a.getValue()));

            // compound assign uses target too (x += y uses x)
            if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
                use.addAll(varsInExpr(a.getTarget()));
            }

            clean(use); clean(def);
            return;
        }

        // ++x / x++ (uses + defs)
        if (e.isUnaryExpr()) {
            UnaryExpr u = e.asUnaryExpr();
            if (isIncDec(u.getOperator())) {
                use.addAll(varsInExpr(u.getExpression()));
                def.addAll(defsInExpr(u.getExpression()));
            }
            clean(use); clean(def);
            return;
        }

        // method call statement: foo(x) -> USE includes args (and scope, e.g. obj.foo(x) uses obj)
        if (e.isMethodCallExpr()) {
            use.addAll(varsInExpr(e)); // this will collect args + scope
            clean(use); clean(def);
            return;
        }

        // fallback: collect uses from expression
        use.addAll(varsInExpr(e));
        clean(use); clean(def);
    }

    private static boolean isIncDec(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_INCREMENT
                || op == UnaryExpr.Operator.POSTFIX_DECREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT;
    }

    private static Set<String> varsInExpr(Expression expr) {
        Set<String> vars = new HashSet<>();

        // Names (x, y)
        for (NameExpr n : expr.findAll(NameExpr.class)) {
            vars.add(n.getNameAsString());
        }

        // Field access: obj.field -> treat obj as use
        for (FieldAccessExpr f : expr.findAll(FieldAccessExpr.class)) {
            Expression scope = f.getScope();
            vars.addAll(varsInScope(scope));
        }

        clean(vars);
        return vars;
    }

    private static Set<String> varsInScope(Expression scope) {
        Set<String> vars = new HashSet<>();
        if (scope.isNameExpr()) vars.add(scope.asNameExpr().getNameAsString());
        else for (NameExpr n : scope.findAll(NameExpr.class)) vars.add(n.getNameAsString());
        clean(vars);
        return vars;
    }

    private static Set<String> defsInExpr(Expression expr) {
        Set<String> defs = new HashSet<>();

        if (expr.isNameExpr()) {
            defs.add(expr.asNameExpr().getNameAsString());
        } else if (expr.isArrayAccessExpr()) {
            // a[i] = ... defines a
            ArrayAccessExpr a = expr.asArrayAccessExpr();
            defs.addAll(varsInScope(a.getName()));
        } else if (expr.isFieldAccessExpr()) {
            // obj.field = ... defines obj (approx)
            FieldAccessExpr f = expr.asFieldAccessExpr();
            defs.addAll(varsInScope(f.getScope()));
        }

        clean(defs);
        return defs;
    }

    private static void clean(Set<String> s) {
        s.removeIf(v -> v == null || v.isBlank() || IGNORED.contains(v));
    }

    // ---------------- Dead code safety ----------------

    /**
     * Conservative side effects check:
     * - standalone method call => side effects
     * - assignments whose RHS contains a method call or object creation => side effects
     * You can expand later if needed.
     */
    private static boolean hasSideEffects(Statement stmt) {
        if (!stmt.isExpressionStmt()) return false;
        Expression e = stmt.asExpressionStmt().getExpression();

        if (e.isMethodCallExpr()) return true;

        if (e.isAssignExpr()) {
            Expression rhs = e.asAssignExpr().getValue();
            if (!rhs.findAll(MethodCallExpr.class).isEmpty()) return true;
            if (!rhs.findAll(ObjectCreationExpr.class).isEmpty()) return true;
        }

        if (e.isVariableDeclarationExpr()) {
            VariableDeclarationExpr v = e.asVariableDeclarationExpr();
            for (var var : v.getVariables()) {
                if (var.getInitializer().isPresent()) {
                    Expression init = var.getInitializer().get();
                    if (!init.findAll(MethodCallExpr.class).isEmpty()) return true;
                    if (!init.findAll(ObjectCreationExpr.class).isEmpty()) return true;
                }
            }
        }

        return false;
    }

    // ---------------- DOT visualization ----------------

    private static String toDot(List<CFGNode> nodes,
                                Map<Integer, List<Integer>> succ,
                                Map<Integer, Set<String>> in,
                                Map<Integer, Set<String>> out) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph LVA {\n");
        sb.append("  node [shape=box];\n");

        for (CFGNode n : nodes) {
            String label = "Node " + n.id
                    + "\\nIN: " + in.getOrDefault(n.id, Set.of())
                    + "\\nOUT: " + out.getOrDefault(n.id, Set.of())
                    + "\\n" + escape(n.statement.toString());
            sb.append("  ").append(n.id).append(" [label=\"").append(label).append("\"];\n");
        }

        for (CFGNode n : nodes) {
            for (int s : succ.getOrDefault(n.id, List.of())) {
                sb.append("  ").append(n.id).append(" -> ").append(s).append(";\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---------------- Graph helpers (successors sanity) ----------------

    private static Map<Integer, List<Integer>> sanitizeEdges(Map<Integer, List<Integer>> edges, Set<Integer> nodeIds) {
        Map<Integer, List<Integer>> cleaned = new HashMap<>();
        for (int id : nodeIds) cleaned.put(id, new ArrayList<>());

        for (var e : edges.entrySet()) {
            if (!nodeIds.contains(e.getKey())) continue;
            List<Integer> tos = new ArrayList<>();
            for (int to : e.getValue()) if (nodeIds.contains(to)) tos.add(to);
            cleaned.put(e.getKey(), tos);
        }
        return cleaned;
    }

    private static Map<Integer, List<Integer>> invertEdges(Map<Integer, List<Integer>> edges, Set<Integer> nodeIds) {
        Map<Integer, List<Integer>> inv = new HashMap<>();
        for (int id : nodeIds) inv.put(id, new ArrayList<>());

        for (var e : edges.entrySet()) {
            int from = e.getKey();
            for (int to : e.getValue()) {
                inv.get(to).add(from);
            }
        }
        return inv;
    }

    private static boolean looksReversed(List<CFGNode> nodes, Map<Integer, List<Integer>> edges) {
        if (nodes.size() < 2) return false;
        int first = nodes.get(0).id;
        int last  = nodes.get(nodes.size() - 1).id;

        int outFirst = edges.getOrDefault(first, List.of()).size();
        int outLast  = edges.getOrDefault(last, List.of()).size();

        return outFirst == 0 && outLast > 0;
    }
}
