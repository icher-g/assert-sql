package io.assertsql;

import io.sqm.parser.core.Lexer;
import io.sqm.parser.core.TokenType;
import io.sqm.parser.spi.IdentifierQuoting;

import java.lang.reflect.Array;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Per-evaluation disclosure policy. SQL token classification comes from SQM, never an ad hoc SQL parser.
 */
final class DiagnosticContext {
    private static final int MAX_TEXT = 2000;
    private final Redaction redaction;
    private final IdentifierQuoting quoting;
    private final Set<String> secrets = new LinkedHashSet<>();
    private final boolean bindingsPresent;
    private boolean malformed;
    private boolean opaqueBindings;

    DiagnosticContext(Redaction redaction, IdentifierQuoting quoting, List<String> sources, List<Object> expectedBindings, ParameterizedSql input) {
        this.redaction = Objects.requireNonNull(redaction, "redaction");
        this.quoting = quoting;
        bindingsPresent = input.hasBindings() || !expectedBindings.isEmpty();
        if (redaction != Redaction.NONE) {
            expectedBindings.forEach(this::bindingSecret);
            input.namedBindings().values().forEach(this::bindingSecret);
            input.positionalBindings().forEach(this::bindingSecret);
            input.ordinalBindings().values().forEach(this::bindingSecret);
        }
        if (redaction == Redaction.ALL) {
            sources.forEach(this::literalSecrets);
            literalSecrets(input.sql());
        }
    }

    static Match evaluate(Expect expectation, ParameterizedSql input, Redaction override) {
        if (expectation instanceof DefaultExpect standard) {
            return standard.evaluate(input, override);
        }
        if (expectation instanceof OrExpect alternatives) {
            return alternatives.evaluate(input, override);
        }
        var result = expectation.evaluate(input);
        if (override == null || override == Redaction.NONE) {
            return result;
        }
        // An external Expect has no trusted model/diagnostic provenance; fail closed for its free-form detail.
        var diagnostics = result.mismatches().stream()
            .map(mismatch -> new Mismatch(mismatch.code(), mismatch.path(), "Expectation did not match; external detail omitted."))
            .toList();
        return Match.of(diagnostics);
    }

    private static boolean literal(TokenType type) {
        return switch (type) {
            case STRING, ESCAPE_STRING, DOLLAR_STRING, BIT_STRING, HEX_STRING, NUMBER, NULL, TRUE, FALSE -> true;
            default -> false;
        };
    }

    private static String bounded(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "… (truncated)";
    }

    Redaction redaction() {
        return redaction;
    }

    Mismatch sanitize(Mismatch mismatch) {
        String message;
        if (redaction == Redaction.ALL && mismatch.code().equals("PARSE_ERROR")) {
            message = "SQL could not be parsed; raw parser detail omitted under full redaction.";
        }
        else
            if (mismatch.code().endsWith("_COUNT_MISMATCH") || mismatch.code().endsWith("_MATCH_MISMATCH")) {
                // Counts are structural metadata, not inline SQL or binding values.
                message = bounded(mismatch.message());
            }
            else {
                message = text(mismatch.message());
            }
        var labels = mismatch.descriptions().stream()
            .map(this::description)
            .toList();
        return new Mismatch(mismatch.code(), mismatch.path(), message, labels);
    }

    List<Mismatch> custom(List<Mismatch> mismatches) {
        var snapshot = List.copyOf(mismatches);
        if (redaction == Redaction.NONE || (redaction == Redaction.BINDINGS && !bindingsPresent)) {
            return snapshot;
        }
        return snapshot.stream()
            .map(mismatch -> new Mismatch(
                mismatch.code(),
                mismatch.path(),
                "Custom check did not match; free-form detail omitted under the redaction policy.",
                mismatch.descriptions()
            ))
            .toList();
    }

    String description(String description) {
        if (description == null) {
            return null;
        }
        if (redaction == Redaction.ALL && malformed) {
            return "description omitted under full redaction";
        }
        if (redaction != Redaction.NONE && opaqueBindings) {
            return "description omitted while opaque bindings are redacted";
        }
        return text(replaceSecrets(description));
    }

    IllegalArgumentException configuration(InvalidExpectationException failure) {
        var message = redaction == Redaction.ALL || bindingsPresent && redaction == Redaction.BINDINGS
            ? "Invalid expected SQL or fragment; raw configuration detail omitted under the redaction policy."
            : failure.getMessage();
        // Do not retain the raw exception as a cause in a redacted exception chain.
        return new IllegalArgumentException(message);
    }

    private String text(String text) {
        if (redaction != Redaction.ALL) {
            return bounded(text);
        }
        try {
            var tokens = Lexer.lexAll(text, quoting);
            var result = new StringBuilder();
            boolean previousLiteral = false;
            for (var token : tokens) {
                if (token.type() == TokenType.EOF || token.type() == TokenType.COMMENT_HINT) {
                    continue;
                }
                String part;
                if (literal(token.type())) {
                    part = "?";
                }
                else
                    if (token.quotedIdentifier()) {
                        var delimiter = token.quoteChar();
                        var closing = delimiter == '[' ? ']' : delimiter;
                        var closeText = String.valueOf(closing);
                        var escaped = token.lexeme().replace(closeText, closeText + closeText);
                        part = delimiter + escaped + closing;
                    }
                    else {
                        part = token.lexeme();
                    }
                // Token offsets preserve punctuation/spacing without copying comments or raw literal text.
                boolean space = token.pos() > 0 && Character.isWhitespace(text.charAt(token.pos() - 1));
                if (!result.isEmpty() && !part.isEmpty()) {
                    boolean adjacentWords = Character.isJavaIdentifierPart(result.charAt(result.length() - 1)) && Character.isJavaIdentifierPart(part.charAt(0));
                    if (space || adjacentWords || previousLiteral && Character.isLetter(part.charAt(0))) {
                        result.append(' ');
                    }
                }
                result.append(part);
                previousLiteral = literal(token.type());
            }
            return result.toString().isBlank() ? "detail omitted under full redaction" : bounded(result.toString());
        } catch (RuntimeException ignored) {
            return "Detail omitted because safe redaction could not be guaranteed.";
        }
    }

    private String replaceSecrets(String text) {
        if (redaction == Redaction.NONE) {
            return text;
        }
        var ordered = secrets.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        var result = text;
        for (var secret : ordered) {
            if (secret.length() > 2) {
                result = result.replace(secret, "?");
            }
            else {
                var pattern = "(?<![\\p{L}\\p{N}_])" + Pattern.quote(secret) + "(?![\\p{L}\\p{N}_])";
                result = result.replaceAll(pattern, "?");
            }
        }
        return result;
    }

    private void literalSecrets(String sql) {
        try {
            for (var token : Lexer.lexAll(sql, quoting)) {
                if (literal(token.type()) && token.lexeme() != null && !token.lexeme().isEmpty()) {
                    secrets.add(token.lexeme());
                }
            }
        } catch (RuntimeException ignored) {
            malformed = true;
        }
    }

    private void bindingSecret(Object value) {
        if (value == null) {
            secrets.add("null");
            return;
        }
        var standard = value.getClass().getName().startsWith("java.");
        if (
            value instanceof String || value instanceof Boolean || value instanceof Character
                || standard && (value instanceof Number || value instanceof java.util.Date
                || value instanceof java.util.UUID || value instanceof java.time.temporal.TemporalAccessor)
        ) {
            if (!value.toString().isEmpty()) {
                secrets.add(value.toString());
            }
        }
        else
            if (value.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(value); index++) {
                    bindingSecret(Array.get(value, index));
                }
            }
            else {
                opaqueBindings = true;
            }
    }
}
