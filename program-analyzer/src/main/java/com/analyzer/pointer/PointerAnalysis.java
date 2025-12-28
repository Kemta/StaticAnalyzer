package com.analyzer.pointer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Expression;


import com.analyzer.cfg.CFGNode;

public class PointerAnalysis {

    private final Map<String, Set<AllocationSite>> pointsTo;
    private final List<Constraint> constraints;

    // Track variables that are actually dereferenced (x.foo())
    private final Set<String> dereferencedVars;

    private static final AllocationSite NULL_SITE = new AllocationSite("NULL");

    // Regex patterns that also handle optional type declarations like: "B x = new B();"
    private static final Pattern NEW_PATTERN =
            Pattern.compile("^\\s*(?:\\w+\\s+)?(\\w+)\\s*=\\s*new\\s+\\w+\\s*\\(.*\\)\\s*;\\s*$");

    private static final Pattern NULL_PATTERN =
            Pattern.compile("^\\s*(?:\\w+\\s+)?(\\w+)\\s*=\\s*null\\s*;\\s*$");

    private static final Pattern COPY_PATTERN =
            Pattern.compile("^\\s*(?:\\w+\\s+)?(\\w+)\\s*=\\s*(\\w+)\\s*;\\s*$");

    private static final Pattern DEREF_PATTERN =
            Pattern.compile("^\\s*(\\w+)\\s*\\.\\s*\\w+\\s*\\(.*\\)\\s*;\\s*$");

    public PointerAnalysis() {
        this.pointsTo = new HashMap<>();
        this.constraints = new ArrayList<>();
        this.dereferencedVars = new HashSet<>();
    }

    // Called by Main.java
    public void analyze(List<CFGNode> cfg) {
        extractConstraints(cfg);
        solve();
        report();
    }

    /* ================= CONSTRAINT EXTRACTION ================= */

    private void extractConstraints(List<CFGNode> cfg) {
        for (CFGNode node : cfg) {
            String stmt = node.statement.toString().trim();
            int nodeId = node.id;

            // 1) p = new A();
            Matcher mNew = NEW_PATTERN.matcher(stmt);
            if (mNew.matches()) {
                String lhs = mNew.group(1);
                ensureVar(lhs);

                constraints.add(new Constraint(
                        Constraint.Type.ADDR,
                        lhs,
                        "alloc@Node" + nodeId
                ));
                continue;
            }

            // 2) p = null;
            Matcher mNull = NULL_PATTERN.matcher(stmt);
            if (mNull.matches()) {
                String lhs = mNull.group(1);
                ensureVar(lhs);

                constraints.add(new Constraint(
                        Constraint.Type.ADDR,
                        lhs,
                        "NULL"
                ));
                continue;
            }

            // 3) p = q;   (also matches "Type p = q;")
            Matcher mCopy = COPY_PATTERN.matcher(stmt);
            if (mCopy.matches()) {
                String lhs = mCopy.group(1);
                String rhs = mCopy.group(2);

                // IMPORTANT: Avoid treating "x = null;" as COPY; it's already handled above,
                // but keep a safety guard.
                if ("null".equals(rhs)) {
                    ensureVar(lhs);
                    constraints.add(new Constraint(Constraint.Type.ADDR, lhs, "NULL"));
                    continue;
                }

                ensureVar(lhs);
                ensureVar(rhs);

                constraints.add(new Constraint(
                        Constraint.Type.COPY,
                        lhs,
                        rhs
                ));
                continue;
            }

            // 4) Dereference: p.foo();
            node.statement.findAll(MethodCallExpr.class).forEach(call -> {
                call.getScope().ifPresent(scope -> {
                if (scope.isNameExpr()) {
                    String var = scope.asNameExpr().getNameAsString();
                    ensureVar(var);
                    dereferencedVars.add(var);
            }
        });
    });

        }
    }

    private void ensureVar(String var) {
        pointsTo.putIfAbsent(var, new HashSet<>());
    }

    /* ================= ANDERSEN SOLVER ================= */

    private void solve() {
        boolean changed;
        do {
            changed = false;

            for (Constraint c : constraints) {
                switch (c.type) {
                    case ADDR:
                        changed |= handleAddr(c.lhs, c.rhs);
                        break;

                    case COPY:
                        changed |= handleCopy(c.lhs, c.rhs);
                        break;

                    // Not implemented for this project scope:
                    case LOAD:
                    case STORE:
                        break;
                }
            }

        } while (changed);
    }

    private boolean handleAddr(String var, String siteId) {
        AllocationSite site = siteId.equals("NULL")
                ? NULL_SITE
                : new AllocationSite(siteId);

        ensureVar(var);
        return pointsTo.get(var).add(site);
    }

    private boolean handleCopy(String lhs, String rhs) {
        ensureVar(lhs);
        ensureVar(rhs);

        boolean changed = false;
        for (AllocationSite site : pointsTo.get(rhs)) {
            if (pointsTo.get(lhs).add(site)) {
                changed = true;
            }
        }
        return changed;
    }

    /* ================= REPORTING ================= */

    private void report() {
        reportPointsTo();
        reportAliases();
        reportNullWarnings();
    }

    private void reportPointsTo() {
        System.out.println("\n===== Points-To Sets =====");

        List<String> vars = new ArrayList<>(pointsTo.keySet());
        Collections.sort(vars);

        for (String var : vars) {
            // Print stable ordering of allocation sites
            List<String> sites = new ArrayList<>();
            for (AllocationSite s : pointsTo.get(var)) sites.add(s.toString());
            Collections.sort(sites);

            System.out.println(var + " -> " + sites);
        }
    }

    /**
     * Correct alias sets = connected components in a graph where
     * edge(v1,v2) exists if they share ANY non-NULL allocation site.
     */
    private void reportAliases() {
        System.out.println("\n===== Alias Sets =====");

        // Graph adjacency
        Map<String, Set<String>> graph = new HashMap<>();
        for (String v : pointsTo.keySet()) {
            graph.putIfAbsent(v, new HashSet<>());
        }

        List<String> vars = new ArrayList<>(pointsTo.keySet());
        Collections.sort(vars);

        // Build edges
        for (int i = 0; i < vars.size(); i++) {
            for (int j = i + 1; j < vars.size(); j++) {
                String v1 = vars.get(i);
                String v2 = vars.get(j);

                Set<AllocationSite> s1 = new HashSet<>(pointsTo.get(v1));
                Set<AllocationSite> s2 = new HashSet<>(pointsTo.get(v2));

                // Usually NULL is not considered an alias target
                s1.remove(NULL_SITE);
                s2.remove(NULL_SITE);

                if (!Collections.disjoint(s1, s2)) {
                    graph.get(v1).add(v2);
                    graph.get(v2).add(v1);
                }
            }
        }

        // DFS/BFS for connected components
        Set<String> visited = new HashSet<>();

        for (String start : vars) {
            if (visited.contains(start)) continue;

            Set<String> component = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);

            while (!stack.isEmpty()) {
                String cur = stack.pop();
                if (!visited.add(cur)) continue;
                component.add(cur);

                for (String nei : graph.get(cur)) {
                    if (!visited.contains(nei)) {
                        stack.push(nei);
                    }
                }
            }

            if (component.size() > 1) {
                List<String> compList = new ArrayList<>(component);
                Collections.sort(compList);
                System.out.println(compList);
            }
        }
    }

    /**
     * Improved warning policy:
     * Warn only if a variable is actually dereferenced and may be NULL (or unknown).
     */
    private void reportNullWarnings() {
        System.out.println("\n===== Pointer Warnings =====");

        List<String> derefs = new ArrayList<>(dereferencedVars);
        Collections.sort(derefs);

        for (String var : derefs) {
            Set<AllocationSite> pts = pointsTo.get(var);

            // Unknown/untracked = risky; Empty set = unknown; NULL in set = may be NULL
            if (pts == null || pts.isEmpty() || pts.contains(NULL_SITE)) {
                System.out.println(
                        "[Warning] Variable '" + var + "' may be NULL at dereference"
                );
            }
        }
    }
}
