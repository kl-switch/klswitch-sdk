use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Selector {
    pub kind: SelectorKind,
    pub keys: ApiKeys,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SelectorKind {
    Api,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ApiKeys {
    pub method: String,
    pub path: String,
}

/// One segment of a compiled path pattern (rule-model §8.1).
#[derive(Debug, Clone, PartialEq)]
pub enum Segment {
    Literal(String),
    /// `*` — exactly one segment.
    Single,
    /// `**` — zero or more trailing segments. Only ever the final segment.
    Trailing,
}

/// A rule's compiled selector path: either an exact string or a segment pattern.
///
/// Compiled once at load time and never re-parsed during evaluation — `matches`
/// runs inside the caller's request path, so the parsing cost must not be paid
/// per request (rule-model §8.3).
#[derive(Debug, Clone, PartialEq)]
pub enum PathMatcher {
    Exact(String),
    Pattern(Vec<Segment>),
}

impl PathMatcher {
    /// Compile `path` per rule-model §8.1. `Err` carries an operator-facing
    /// reason; the caller wraps it as `INVALID_SELECTOR_PATTERN`.
    pub fn compile(path: &str) -> Result<Self, String> {
        // No `*` anywhere is the overwhelmingly common case and stays exact, so
        // a ruleset without patterns keeps the schema-1.1 lookup cost exactly.
        if !path.contains('*') {
            return Ok(PathMatcher::Exact(path.to_string()));
        }

        let raw: Vec<&str> = path.split('/').collect();
        let last = raw.len() - 1;
        let mut segments = Vec::with_capacity(raw.len());

        for (i, part) in raw.iter().enumerate() {
            let segment = match *part {
                "*" => Segment::Single,
                "**" => {
                    if i != last {
                        return Err(format!(
                            "'**' is only valid as the final segment of a path pattern (in '{path}')."
                        ));
                    }
                    Segment::Trailing
                }
                // A `*` mixed with other characters would be a partial-segment
                // glob; §8.1 does not support those, and silently treating one
                // as a literal would match nothing with no explanation.
                other if other.contains('*') => {
                    return Err(format!(
                        "Path segment '{other}' mixes '*' with other characters; \
                         a wildcard segment must be exactly '*' or '**' (in '{path}')."
                    ));
                }
                other => Segment::Literal(other.to_string()),
            };
            segments.push(segment);
        }

        Ok(PathMatcher::Pattern(segments))
    }

    /// Does this matcher accept `path`? `path` is the normalized templated route
    /// supplied by the wrapper (rule-model §8).
    pub fn matches(&self, path: &str) -> bool {
        match self {
            PathMatcher::Exact(exact) => exact == path,
            PathMatcher::Pattern(segments) => {
                let parts: Vec<&str> = path.split('/').collect();
                let trailing = matches!(segments.last(), Some(Segment::Trailing));
                // Segments that must line up one-for-one with `parts`.
                let fixed = if trailing { segments.len() - 1 } else { segments.len() };

                if trailing {
                    // `**` absorbs zero or more, so `/v1/**` matches bare `/v1`.
                    if parts.len() < fixed {
                        return false;
                    }
                } else if parts.len() != fixed {
                    return false;
                }

                segments.iter().take(fixed).zip(parts.iter()).all(|(seg, part)| match seg {
                    Segment::Literal(literal) => literal == part,
                    Segment::Single => true,
                    Segment::Trailing => unreachable!("trailing is excluded by `fixed`"),
                })
            }
        }
    }

    /// The exact path this matcher keys on, or `None` for a pattern.
    pub fn exact_key(&self) -> Option<&str> {
        match self {
            PathMatcher::Exact(p) => Some(p.as_str()),
            PathMatcher::Pattern(_) => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(pattern: &str, path: &str) -> bool {
        PathMatcher::compile(pattern).expect("pattern compiles").matches(path)
    }

    #[test]
    fn exact_paths_stay_exact() {
        assert!(matches!(PathMatcher::compile("/v1/orders"), Ok(PathMatcher::Exact(_))));
        assert!(m("/v1/orders", "/v1/orders"));
        assert!(!m("/v1/orders", "/v1/orders/9"));
        assert!(!m("/v1/orders", "/v1/order"));
    }

    #[test]
    fn single_star_is_exactly_one_segment() {
        assert!(m("/v1/*", "/v1/orders"));
        assert!(!m("/v1/*", "/v1"), "* requires a segment to be present");
        assert!(!m("/v1/*", "/v1/orders/9"), "* does not span segments");
        assert!(m("/v1/*/refund", "/v1/payments/refund"));
        assert!(!m("/v1/*/refund", "/v1/refund"));
    }

    #[test]
    fn star_matches_a_templated_parameter_segment() {
        // Paths arrive templated, so the parameter is ordinary text (§8.1).
        assert!(m("/v1/orders/*", "/v1/orders/:id"));
        assert!(m("/v1/orders/*", "/v1/orders/{id}"));
    }

    #[test]
    fn double_star_absorbs_zero_or_more_trailing_segments() {
        assert!(m("/v1/**", "/v1"), "zero segments — the bare prefix matches");
        assert!(m("/v1/**", "/v1/a"));
        assert!(m("/v1/**", "/v1/a/b/c"));
        assert!(!m("/v1/**", "/v2"));
        assert!(!m("/v1/**", "/v11/a"), "prefix match is per segment, not per char");
    }

    #[test]
    fn malformed_patterns_are_rejected() {
        assert!(PathMatcher::compile("/v1/**/x").is_err(), "** must be final");
        assert!(PathMatcher::compile("/v1/or*ders").is_err(), "no partial-segment globs");
        assert!(PathMatcher::compile("/v1/***").is_err());
    }

    #[test]
    fn only_exact_matchers_expose_an_index_key() {
        assert_eq!(PathMatcher::compile("/v1/a").unwrap().exact_key(), Some("/v1/a"));
        assert_eq!(PathMatcher::compile("/v1/*").unwrap().exact_key(), None);
    }
}
