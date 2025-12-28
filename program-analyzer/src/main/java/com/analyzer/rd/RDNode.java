package com.analyzer.rd;

import java.util.HashSet;
import java.util.Set;

public class RDNode {
    public int id;
    public Set<Definition> gen = new HashSet<>();
    public Set<Definition> kill = new HashSet<>();
    public Set<Definition> in = new HashSet<>();
    public Set<Definition> out = new HashSet<>();

    public RDNode(int id) {
        this.id = id;
    }
}
