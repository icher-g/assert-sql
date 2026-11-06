package io.assertsql;

import io.sqm.core.QualifiedStarSelectItem;
import io.sqm.core.Node;
import io.sqm.core.StarSelectItem;
import io.sqm.core.Statement;
import io.sqm.core.walk.RecursiveNodeVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SelectStars extends RecursiveNodeVisitor<Void> {
    private final List<Mismatch> mismatches = new ArrayList<>();
    private final Set<Statement> level;

    private SelectStars(Statement statement, boolean local) {
        level = ModelInspector.queryLevel(statement, local);
    }

    static List<Mismatch> find(Statement statement, boolean local) {
        var visitor = new SelectStars(statement, local);
        statement.accept(visitor);
        return List.copyOf(visitor.mismatches);
    }

    @Override
    protected Void defaultResult() {
        return null;
    }

    @Override
    protected Void accept(Node node) {
        return ModelInspector.withinLevel(node, level) ? super.accept(node) : null;
    }

    @Override
    public Void visitStarSelectItem(StarSelectItem item) {
        report("*");
        return null;
    }

    @Override
    public Void visitQualifiedStarSelectItem(QualifiedStarSelectItem item) {
        report("qualified *");
        return null;
    }

    private void report(String wildcard) {
        mismatches.add(new Mismatch("SELECT_STAR", "statement", "Unexpected SELECT wildcard projection: " + wildcard + "."));
    }
}
