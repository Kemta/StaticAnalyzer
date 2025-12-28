package com.analyzer.rd;

import java.util.Objects;

public class Definition {
    public final String variable;
    public final int nodeId;
    public final boolean initialized; // true if it assigns a value, false if "int x;" only

    public Definition(String variable, int nodeId, boolean initialized) {
        this.variable = variable;
        this.nodeId = nodeId;
        this.initialized = initialized;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Definition)) return false;
        Definition d = (Definition) o;
        return nodeId == d.nodeId
                && initialized == d.initialized
                && Objects.equals(variable, d.variable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variable, nodeId, initialized);
    }

    @Override
    public String toString() {
        return variable + "@" + nodeId + (initialized ? "" : "(decl-only)");
    }
}
