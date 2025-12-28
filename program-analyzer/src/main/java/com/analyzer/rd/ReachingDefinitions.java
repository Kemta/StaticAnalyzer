package com.analyzer.rd;

import com.analyzer.cfg.CFGNode;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;

import java.util.*;

public class ReachingDefinitions {

    // Optional: ignore Java “names” you don’t want counted as variables
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("System", "out", "println"));

    public static void analyze(List<CFGNode> nodes,
                               Map<Integer, List<Integer>> preds) {

        Map<Integer, Set<Definition>> gen  = new HashMap<>();
        Map<Integer, Set<Definition>> kill = new HashMap<>();
        Map<Integer, Set<Definition>> in   = new HashMap<>();
        Map<Integer, Set<Definition>> out  = new HashMap<>();

        // Extra (for rubric)
        Map<Integer, Set<String>> use = new HashMap<>();

        /* --------------------------------------------------
           1) Collect ALL definitions in the CFG
        -------------------------------------------------- */
        Set<Definition> allDefs = new HashSet<>();
        for (CFGNode n : nodes) {
            allDefs.addAll(extractGen(n));
        }

        /* --------------------------------------------------
           2) Build GEN / KILL + USE
        -------------------------------------------------- */
        for (CFGNode n : nodes) {
            Set<Definition> g = extractGen(n);
            gen.put(n.id, g);

            // USE set for bug detection
            use.put(n.id, extractUse(n));

            // KILL: any other definition of same variable (regardless of init flag)
            Set<Definition> k = new HashSet<>();
            Set<String> varsDefinedHere = varsDefinedByGen(g);

            for (Definition d : allDefs) {
                if (varsDefinedHere.contains(d.variable) && d.nodeId != n.id) {
                    k.add(d);
                }
            }

            kill.put(n.id, k);
            in.put(n.id, new HashSet<>());
            out.put(n.id, new HashSet<>());
        }

        /* --------------------------------------------------
           3) Fixpoint iteration
           IN[n]  = ⋃ OUT[pred]
           OUT[n] = GEN[n] ∪ (IN[n] − KILL[n])
        -------------------------------------------------- */
        boolean changed;
        do {
            changed = false;

            for (CFGNode n : nodes) {

                Set<Definition> newIn = new HashSet<>();
                for (int p : preds.getOrDefault(n.id, List.of())) {
                    newIn.addAll(out.getOrDefault(p, Set.of()));
                }

                Set<Definition> newOut = new HashSet<>(newIn);
                newOut.removeAll(kill.getOrDefault(n.id, Set.of()));
                newOut.addAll(gen.getOrDefault(n.id, Set.of()));

                if (!newIn.equals(in.get(n.id)) || !newOut.equals(out.get(n.id))) {
                    in.put(n.id, newIn);
                    out.put(n.id, newOut);
                    changed = true;
                }
            }
        } while (changed);

        /* --------------------------------------------------
           4) Print RD results
        -------------------------------------------------- */
        System.out.println("\n===== Reaching Definitions =====");
        for (CFGNode n : nodes) {
            System.out.println("Node " + n.id + ": " + n.statement);
            System.out.println("  GEN  = " + gen.get(n.id));
            System.out.println("  KILL = " + kill.get(n.id));
            System.out.println("  IN   = " + in.get(n.id));
            System.out.println("  OUT  = " + out.get(n.id));
        }

        /* --------------------------------------------------
           5) Overwritten / shadowed defs (rubric item)
           “At this node, previous defs of same var are overwritten”
        -------------------------------------------------- */
        System.out.println("\n===== Overwritten (Shadowed) Definitions =====");
        for (CFGNode n : nodes) {
            Set<Definition> g = gen.getOrDefault(n.id, Set.of());
            if (g.isEmpty()) continue;

            Set<String> varsRedefined = varsDefinedByGen(g);

            Map<String, Set<Definition>> reachingSameVar = new HashMap<>();
            for (Definition d : in.getOrDefault(n.id, Set.of())) {
                if (varsRedefined.contains(d.variable)) {
                    reachingSameVar.computeIfAbsent(d.variable, k -> new HashSet<>()).add(d);
                }
            }

            if (!reachingSameVar.isEmpty()) {
                System.out.println("Node " + n.id + " overwrites:");
                for (var entry : reachingSameVar.entrySet()) {
                    System.out.println("  " + entry.getKey() + " previous defs reaching here: " + entry.getValue()
                            + "  -> overwritten by " + defsForVar(g, entry.getKey()));
                }
            }
        }

        /* --------------------------------------------------
           6) Uninitialized variable usage (rubric item)
           If var is USED but there is no reaching INITIALIZED def
        -------------------------------------------------- */
        System.out.println("\n===== Potential Bugs: Uninitialized Variable Usage =====");
        for (CFGNode n : nodes) {
            Set<String> used = use.getOrDefault(n.id, Set.of());
            if (used.isEmpty()) continue;

            for (String v : used) {
                boolean hasInitializedReachingDef = false;
                for (Definition d : in.getOrDefault(n.id, Set.of())) {
                    if (d.variable.equals(v) && d.initialized) {
                        hasInitializedReachingDef = true;
                        break;
                    }
                }
                // If never initialized before use -> warn
                if (!hasInitializedReachingDef) {
                    System.out.println("Warning: variable '" + v + "' may be uninitialized at Node "
                            + n.id + " : " + n.statement);
                }
            }
        }
    }

    /* ---------------- GEN extraction ---------------- */

    private static Set<Definition> extractGen(CFGNode node) {
        Set<Definition> gen = new HashSet<>();
        Statement stmt = node.statement;

        if (!stmt.isExpressionStmt()) return gen;
        Expression e = stmt.asExpressionStmt().getExpression();

        // x = ...  / x += ... / etc.
        if (e.isAssignExpr()) {
            AssignExpr a = e.asAssignExpr();
            gen.addAll(defsFromTarget(a.getTarget(), node.id, true));
        }

        // int x = ...  OR  int x;
        if (e.isVariableDeclarationExpr()) {
            VariableDeclarationExpr v = e.asVariableDeclarationExpr();
            v.getVariables().forEach(var -> {
                boolean initialized = var.getInitializer().isPresent();
                gen.add(new Definition(var.getNameAsString(), node.id, initialized));
            });
        }

        // x++, ++x, x--, --x  (these write)
        if (e.isUnaryExpr()) {
            UnaryExpr u = e.asUnaryExpr();
            if (isIncDec(u.getOperator())) {
                gen.addAll(defsFromTarget(u.getExpression(), node.id, true));
            }
        }

        return gen;
    }

    private static boolean isIncDec(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_INCREMENT
                || op == UnaryExpr.Operator.POSTFIX_DECREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT;
    }

    private static Set<Definition> defsFromTarget(Expression target, int nodeId, boolean initialized) {
        Set<Definition> defs = new HashSet<>();

        if (target.isNameExpr()) {
            String v = target.asNameExpr().getNameAsString();
            if (!IGNORED.contains(v)) defs.add(new Definition(v, nodeId, initialized));
            return defs;
        }

        // a[i] = ...  => defines a
        if (target.isArrayAccessExpr()) {
            ArrayAccessExpr a = target.asArrayAccessExpr();
            defs.addAll(defsFromTarget(a.getName(), nodeId, initialized));
            return defs;
        }

        // obj.field = ...  => treat "obj" as defined (approximation)
        if (target.isFieldAccessExpr()) {
            FieldAccessExpr f = target.asFieldAccessExpr();
            defs.addAll(defsFromTarget(f.getScope(), nodeId, initialized));
            return defs;
        }

        return defs;
    }

    private static Set<String> varsDefinedByGen(Set<Definition> gen) {
        Set<String> s = new HashSet<>();
        for (Definition d : gen) s.add(d.variable);
        return s;
    }

    private static Set<Definition> defsForVar(Set<Definition> gen, String var) {
        Set<Definition> s = new HashSet<>();
        for (Definition d : gen) if (d.variable.equals(var)) s.add(d);
        return s;
    }

    /* ---------------- USE extraction (for bug detection) ---------------- */

    private static Set<String> extractUse(CFGNode node) {
        Set<String> use = new HashSet<>();
        Statement stmt = node.statement;

        // if/while conditions use vars
        if (stmt.isIfStmt()) {
            use.addAll(varsInExpr(stmt.asIfStmt().getCondition()));
            return clean(use);
        }
        if (stmt.isWhileStmt()) {
            use.addAll(varsInExpr(stmt.asWhileStmt().getCondition()));
            return clean(use);
        }

        if (!stmt.isExpressionStmt()) return clean(use);

        Expression e = stmt.asExpressionStmt().getExpression();

        // int x = rhs  => rhs uses
        if (e.isVariableDeclarationExpr()) {
            VariableDeclarationExpr v = e.asVariableDeclarationExpr();
            v.getVariables().forEach(var ->
                    var.getInitializer().ifPresent(init -> use.addAll(varsInExpr(init)))
            );
            return clean(use);
        }

        // x = rhs  => rhs uses; and for x += rhs, x is also used
        if (e.isAssignExpr()) {
            AssignExpr a = e.asAssignExpr();
            use.addAll(varsInExpr(a.getValue()));
            if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
                use.addAll(varsInExpr(a.getTarget()));
            }
            return clean(use);
        }

        // ++x uses x
        if (e.isUnaryExpr()) {
            UnaryExpr u = e.asUnaryExpr();
            if (isIncDec(u.getOperator())) {
                use.addAll(varsInExpr(u.getExpression()));
            }
            return clean(use);
        }

        // method calls: foo(x) uses x
        use.addAll(varsInExpr(e));
        return clean(use);
    }

    private static Set<String> varsInExpr(Expression expr) {
        Set<String> vars = new HashSet<>();
        for (NameExpr n : expr.findAll(NameExpr.class)) {
            vars.add(n.getNameAsString());
        }
        // Field access: obj.field -> treat obj as used
        for (FieldAccessExpr f : expr.findAll(FieldAccessExpr.class)) {
            Expression scope = f.getScope();
            if (scope.isNameExpr()) vars.add(scope.asNameExpr().getNameAsString());
        }
        return vars;
    }

    private static Set<String> clean(Set<String> s) {
        s.removeIf(v -> v == null || v.isBlank() || IGNORED.contains(v));
        return s;
    }
}
