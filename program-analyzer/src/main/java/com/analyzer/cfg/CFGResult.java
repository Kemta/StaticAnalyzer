package com.analyzer.cfg;

import java.util.List;
import java.util.Map;

public class CFGResult {

    public List<CFGNode> nodes;
    public Map<Integer, List<Integer>> predecessors;

    public CFGResult(List<CFGNode> nodes,
                     Map<Integer, List<Integer>> predecessors) {
        this.nodes = nodes;
        this.predecessors = predecessors;
    }

    public void printCFG() {
        System.out.println("\n===== CFG =====");
        for (CFGNode n : nodes) {
            System.out.println(n);
            if (predecessors.containsKey(n.id)) {
                for (int p : predecessors.get(n.id)) {
                    System.out.println("  Edge: " + p + " -> " + n.id);
                }
            }
        }
    }
}
