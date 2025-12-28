package com.analyzer.rd;

import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.expr.AssignExpr;

import java.util.Optional;

public class DefinitionExtractor {

    public static Optional<String> getDefinedVariable(Statement stmt) {
        if (stmt.isExpressionStmt()) {
            var expr = stmt.asExpressionStmt().getExpression();
            if (expr.isAssignExpr()) {
                AssignExpr assign = expr.asAssignExpr();
                return Optional.of(assign.getTarget().toString());
            }
        }
        return Optional.empty();
    }
}
