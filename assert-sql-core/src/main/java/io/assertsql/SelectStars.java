package io.assertsql;

import io.sqm.core.QualifiedStarSelectItem;
import io.sqm.core.StarSelectItem;
import io.sqm.core.Statement;
import io.sqm.core.walk.RecursiveNodeVisitor;

import java.util.ArrayList;
import java.util.List;

final class SelectStars extends RecursiveNodeVisitor<Void> {
    private final List<Mismatch> mismatches = new ArrayList<>();

    static List<Mismatch> find(Statement statement) {
        var visitor = new SelectStars();
        statement.accept(visitor);
        return List.copyOf(visitor.mismatches);
    }

    @Override
    protected Void defaultResult() {
        return null;
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
        mismatches.add(new Mismatch("SELECT_STAR", "statement",
            "Unexpected SELECT wildcard projection: " + wildcard + "."));
    }
}
