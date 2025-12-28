package com.analyzer;

import com.analyzer.cfg.CFGBuilder;
import com.analyzer.cfg.CFGNode;
import com.analyzer.rd.ReachingDefinitions;
import com.analyzer.lva.LiveVariableAnalysis;
import com.analyzer.pointer.PointerAnalysis;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.File;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            System.out.println("Usage: java Main <Java source file>");
            return;
        }

        File sourceFile = new File(args[0]);
        System.out.println("Parsing file: " + sourceFile.getAbsolutePath());

        CompilationUnit cu = StaticJavaParser.parse(sourceFile);

        System.out.println("\n===== AST =====");
        System.out.println(cu);

        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (method.getBody().isPresent()) {

                System.out.println("\n===== Analyzing method: " + method.getName() + " =====");

                CFGBuilder cfg = new CFGBuilder(
                        method.getBody().get().getStatements()
                );

                System.out.println("\n===== CFG =====");
                for (CFGNode n : cfg.nodes) {
                    System.out.println(n);
                }

                ReachingDefinitions.analyze(cfg.nodes, cfg.predecessors);
                LiveVariableAnalysis.analyze(cfg.nodes, cfg.successors);
                PointerAnalysis pa = new PointerAnalysis();
                pa.analyze(cfg.nodes);

            }
        }
    }
}
