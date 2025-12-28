package com.analyzer.cfg;

import com.github.javaparser.ast.stmt.Statement;

public class CFGNode {
    public final int id;
    public final Statement statement;

    public CFGNode(int id, Statement statement) {
        this.id = id;
        this.statement = statement;
    }

    @Override
    public String toString() {
        return "Node " + id + ": " + statement;
    }
}
