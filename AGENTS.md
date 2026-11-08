# Coding rules

These rules apply throughout the Assert SQL repository. Follow them for new and modified code.

## Branches, pull requests, and CI

- Make changes on a dedicated branch; use the `codex/` prefix for agent-created branches.
- Push changes to that branch and open a pull request. Never push directly to `main` or bypass branch protection.
- Wait for all required CI checks to pass on the latest PR commit before merging. Local verification does not replace CI.
- If CI fails, fix the failure on the PR branch and wait for the updated checks to pass before merging.
- Merge through the pull request only after required checks and repository review requirements are satisfied.
- Create release tags only from commits merged through a pull request whose required CI checks passed. Release tag pushes may trigger publishing.

## Line length and wrapping

- Keep a statement, method signature, or expression on one line when it fits within 150 characters, including indentation, except for fluent call chains.
- Do not introduce line breaks merely to satisfy a shorter formatter default.
- Preserve the normal structure of classes, methods, control-flow blocks, and documentation; the line-length rule does not mean compressing blocks onto one line.
- When an expression exceeds 150 characters and needs wrapping, format the complete expression for readability instead of breaking it at an arbitrary character position.
- For wrapped nested calls, place arguments on separate lines, indent each nesting level by four spaces, and align closing parentheses with the corresponding call.
- Expand nested calls and lambda bodies where that makes the nesting clear. Lines within an expanded expression may be shorter than 150 characters.
- Do not force an unbreakable string or URL to split in a way that harms readability or changes its value.

Preferred format for a nested call that needs wrapping:

```java
return satisfies(
    outerClause(statement ->
        result(
            literalCount(ModelInspector.offset(statement), count),
            "OFFSET_MISMATCH",
            "statement.offset",
            "Expected a literal outer offset of " + count + "."
        )
    )
);
```

Avoid arbitrary wrapping that leaves several nested calls and arguments crowded onto continuation lines:

```java
return satisfies(outerClause(statement -> result(literalCount(ModelInspector.offset(statement), count),
    "OFFSET_MISMATCH", "statement.offset", "Expected a literal outer offset of " + count + ".")));
```

## Fluent calls

- In fluent API chains, including builders and stream pipelines, put every call after the initial call on its own line.
- This rule takes precedence over the 150-character rule, even when the entire chain would fit on one line.
- Include terminal calls such as `build()`, `evaluate(...)`, `matches(...)`, and `assertMatches(...)` on their own lines.
- Apply the same formatting to chains inside arguments, lambdas, and nested builder callbacks.
- Indent chained calls by four spaces from the start of the expression, keeping calls at the same chain level aligned.

```java
var expectation = Expect.sql(POSTGRESQL)
    .binding("tenantId", 42L)
    .matchAllParameters()
    .build();
```

## Method order

- Keep public methods together so readers can understand the public API without encountering private implementation helpers between them.
- Place private helper methods at the end of their enclosing class, after public, protected, and package-private methods.
- Apply the same ordering inside nested classes, including private builder implementations.
- Constructors may remain near the fields and before the methods; they are not subject to the private-helper ordering rule.
