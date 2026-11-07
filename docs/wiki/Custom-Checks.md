[Wiki home](https://github.com/icher-g/assert-sql/wiki) · [Repository](https://github.com/icher-g/assert-sql)

# Custom checks using SQM

```java
import io.assertsql.Mismatch;
import io.sqm.core.SelectQuery;
import java.util.List;

var singleProjection = Expect.sql(POSTGRESQL)
    .satisfies(statement -> {
        if (statement instanceof SelectQuery query && query.items().size() == 1) {
            return List.of();
        }
        return List.of(new Mismatch("SINGLE_PROJECTION", "statement.items", "Expected one SELECT projection."));
    })
    .build();

singleProjection.assertMatches("SELECT id FROM users");
```

Each SQL expectation evaluation parses actual SQL once (OR evaluates its branches
independently). Custom checks receive that same SQM
`Statement` instance and return an empty list for success or non-null diagnostics
for failure. Custom checks used in shared expectations must be thread-safe.
