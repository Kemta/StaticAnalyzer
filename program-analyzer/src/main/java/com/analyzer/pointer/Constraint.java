package com.analyzer.pointer;

import java.util.*;  

public class Constraint {

    public enum Type {
        ADDR,   // p = new A()
        COPY,   // p = q
        LOAD,   // p = *q
        STORE   // *p = q
    }

    public final Type type;
    public final String lhs;
    public final String rhs;

    public Constraint(Type type, String lhs, String rhs) {
        this.type = type;
        this.lhs = lhs;
        this.rhs = rhs;
    }

    @Override
    public String toString() {
        return type + ": " + lhs + " , " + rhs;
    }
}
