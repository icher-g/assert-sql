package io.assertsql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class OrExpect implements Expect {
    private final List<Expect> alternatives;

    private OrExpect(List<Expect> alternatives) {
        this.alternatives = alternatives;
    }

    static Expect of(Expect[] expects) {
        var alternatives = List.of(Objects.requireNonNull(expects, "expects"));
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one OR alternative.");
        }
        return new OrExpect(alternatives);
    }

    @Override
    public Match evaluate(String actualSql) {
        Objects.requireNonNull(actualSql, "actualSql");
        var mismatches = new ArrayList<Mismatch>();
        mismatches.add(new Mismatch("OR_NOT_MATCHED", "or", "None of the alternatives matched."));
        for (int i = 0; i < alternatives.size(); i++) {
            var alternative = alternatives.get(i);
            var result = alternative.evaluate(actualSql);
            if (result.matches()) {
                return Match.of(List.of());
            }
            for (var mismatch : result.mismatches()) {
                var path = "or[" + i + "]" + (mismatch.path().isEmpty() ? "" : "." + mismatch.path());
                mismatches.add(new Mismatch(mismatch.code(), path, mismatch.message()));
            }
        }
        return Match.of(mismatches);
    }
}
