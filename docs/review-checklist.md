# Review Checklist

Before accepting a change, verify all of the following.

## Architecture
- Is the code in the correct folder?
- Does it preserve WASM vs wrapper boundaries?
- Does WASM contain only engine logic and state?
- Does the wrapper contain runtime/integration concerns?

## WASM Contract
- Does it use JSON input/output for v1?
- Is memory ownership explicit?
- Are returned buffers freed correctly?
- Are there any raw pointer lifecycle risks?

## Rule Engine Behavior
- Does it preserve selector-first lookup?
- Does it preserve nested condition trees?
- Does it preserve missing-field=false behavior?
- Does it preserve enabled=false skip behavior?
- Does it preserve returning all matched rules in priority-descending order?

## Rule Replacement
- Are rules fully parsed, validated, and compiled before replacement?
- If replacement fails, does the previous active ruleset remain intact?
- Is there any risk of partial state visibility?

## Wrapper Behavior
- Is polling outside WASM?
- Is logging outside WASM?
- Is analytics sending outside WASM?
- Is framework integration outside WASM?

## Quality
- Is module responsibility clear?
- Is code readable?
- Are tests added or updated?
- Does the change align with docs/ and spec/?