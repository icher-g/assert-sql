package io.assertsql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class OrExpect implements Expect {
    private final List<Expect> alternatives;
    private final Redaction redaction;

    private OrExpect(List<Expect> alternatives, Redaction redaction) {
        this.alternatives = alternatives;
        this.redaction = redaction;
    }

    static Expect of(Expect[] expects) {
        return of(null, expects);
    }

    static Expect of(Redaction redaction, Expect[] expects) {
        var alternatives = List.of(Objects.requireNonNull(expects, "expects"));
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one OR alternative.");
        }
        return new OrExpect(alternatives, redaction);
    }

    @Override
    public Match evaluate(String actualSql) {
        return evaluate(ParameterizedSql.of(Objects.requireNonNull(actualSql, "actualSql")));
    }

    @Override
    public Match evaluate(ParameterizedSql input) {
        return evaluate(input, null);
    }

    Match evaluate(ParameterizedSql input, Redaction override) {
        Objects.requireNonNull(input, "input");
        var mismatches = new ArrayList<Mismatch>();
        mismatches.add(new Mismatch("OR_NOT_MATCHED", "or", "None of the alternatives matched."));
        for (int i = 0; i < alternatives.size(); i++) {
            var alternative = alternatives.get(i);
            var result = DiagnosticContext.evaluate(alternative, input, override == null ? redaction : override);
            if (result.matches()) {
                return Match.of(List.of());
            }
            for (var mismatch : result.mismatches()) {
                var path = "or[" + i + "]" + (mismatch.path().isEmpty() ? "" : "." + mismatch.path());
                mismatches.add(new Mismatch(mismatch.code(), path, mismatch.message(), mismatch.descriptions()));
            }
        }
        return Match.of(mismatches);
    }
}
