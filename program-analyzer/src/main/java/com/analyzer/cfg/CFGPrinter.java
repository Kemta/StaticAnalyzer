package com.analyzer.cfg;

import com.github.javaparser.ast.stmt.*;

import java.util.List;

public class CFGPrinter {

    private static int nodeCounter;
    private static int lastNode;

    public static void printCFG(List<Statement> statements) {
        nodeCounter = 0;
        lastNode = -1;

        System.out.println("\n===== CFG (Graphviz DOT) =====");
        System.out.println("digraph CFG {");
        System.out.println("  node [shape=box, fontname=\"Courier\"];");

        for (Statement stmt : statements) {
            handleStatement(stmt);
        }

        System.out.println("}");
    }

    private static void handleStatement(Statement stmt) {

        /* ===============================
         * SEQUENTIAL STATEMENT
         * =============================== */
        if (stmt.isExpressionStmt()) {
            int node = nodeCounter++;
            printNode(node, stmt.toString());

            if (lastNode != -1) {
                printEdge(lastNode, node);
            }

            lastNode = node;
        }

        /* ===============================
         * IF / ELSE STATEMENT
         * =============================== */
        else if (stmt.isIfStmt()) {
            IfStmt ifStmt = stmt.asIfStmt();

            int condNode = nodeCounter++;
            printNode(condNode, "if (" + ifStmt.getCondition() + ")");

            if (lastNode != -1) {
                printEdge(lastNode, condNode);
            }

            // THEN branch
            int thenStart = nodeCounter;
            handleBlock(ifStmt.getThenStmt(), condNode);

            // ELSE branch
            int elseStart = -1;
            if (ifStmt.getElseStmt().isPresent()) {
                elseStart = nodeCounter;
                handleBlock(ifStmt.getElseStmt().get(), condNode);
            }

            // JOIN node
            int joinNode = nodeCounter++;
            printNode(joinNode, "<join>");

            printEdge(thenStart, joinNode);
            if (elseStart != -1) {
                printEdge(elseStart, joinNode);
            }

            lastNode = joinNode;
        }

        /* ===============================
         * WHILE LOOP
         * =============================== */
        else if (stmt.isWhileStmt()) {
            WhileStmt whileStmt = stmt.asWhileStmt();

            int condNode = nodeCounter++;
            printNode(condNode, "while (" + whileStmt.getCondition() + ")");

            if (lastNode != -1) {
                printEdge(lastNode, condNode);
            }

            int bodyStart = nodeCounter;
            handleBlock(whileStmt.getBody(), condNode);

            // back edge
            printEdge(nodeCounter - 1, condNode);

            int exitNode = nodeCounter++;
            printNode(exitNode, "<while-exit>");
            printEdge(condNode, exitNode);

            lastNode = exitNode;
        }
    }

    /* ===============================
     * BLOCK HANDLING
     * =============================== */
    private static void handleBlock(Statement stmt, int fromNode) {
        if (stmt.isBlockStmt()) {
            for (Statement s : stmt.asBlockStmt().getStatements()) {
                int node = nodeCounter++;
                printNode(node, s.toString());
                printEdge(fromNode, node);
            }
        } else {
            int node = nodeCounter++;
            printNode(node, stmt.toString());
            printEdge(fromNode, node);
        }
    }

    /* ===============================
     * GRAPHVIZ HELPERS
     * =============================== */

    private static void printNode(int id, String label) {
        System.out.println(
            "  " + id + " [label=\"" + escape(label) + "\"];"
        );
    }

    private static void printEdge(int from, int to) {
        System.out.println("  " + from + " -> " + to + ";");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
