import { test } from "node:test";
import assert from "node:assert/strict";

import { DEFAULT_BASE_URL, buildUrls } from "../src/url-builder.js";

test("buildUrls: default baseUrl when nothing supplied", () => {
  const urls = buildUrls({});
  assert.equal(urls.rules,     `${DEFAULT_BASE_URL}/v1/ruleset`);
  assert.equal(urls.decisions, `${DEFAULT_BASE_URL}/v1/analytics`);
  assert.equal(urls.manifest,  `${DEFAULT_BASE_URL}/v1/manifest`);
});

test("buildUrls: custom baseUrl flows through to every endpoint", () => {
  const urls = buildUrls({ baseUrl: "http://self-hosted:8080" });
  assert.equal(urls.rules,     "http://self-hosted:8080/v1/ruleset");
  assert.equal(urls.decisions, "http://self-hosted:8080/v1/analytics");
});

test("buildUrls: trailing slash on baseUrl is normalized", () => {
  const urls = buildUrls({ baseUrl: "http://api/" });
  assert.equal(urls.rules, "http://api/v1/ruleset");
});

test("buildUrls: per-endpoint overrides take precedence over baseUrl", () => {
  const urls = buildUrls({
    baseUrl:      "http://ignored",
    rulesUrl:     "http://custom/rules",
    decisionsUrl: "http://custom/dec",
  });
  assert.equal(urls.rules,     "http://custom/rules");
  assert.equal(urls.decisions, "http://custom/dec");
});

test("buildUrls: mix of baseUrl and one override", () => {
  const urls = buildUrls({
    baseUrl:  "http://api.example.com",
    rulesUrl: "http://separate-cdn/rules",
  });
  assert.equal(urls.rules,     "http://separate-cdn/rules");
  assert.equal(urls.decisions, "http://api.example.com/v1/analytics");
});
