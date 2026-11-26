import { test } from "node:test";
import assert from "node:assert/strict";

import { KLSwitch } from "../src/client.js";
import { noopLogger } from "../src/logger.js";
import { PollingManager, type FetchLike } from "../src/polling-manager.js";
import type { EvaluationInput, RuleSetPayload } from "../src/types.js";

function ruleset(version: number): RuleSetPayload {
  return {
    schema_version: "1.0",
    rules_version: version,
    rules: [
      {
        id: "r",
        name: "R",
        selector: { kind: "api", keys: { method: "POST", path: "/p" } },
        enabled: true,
        priority: 100,
        condition: { all: [{ field: "body.amount", operator: "gt", value: 100 }] },
        action: { type: "block" },
      },
    ],
  };
}

const input: EvaluationInput = {
  selector: { kind: "api", keys: { method: "POST", path: "/p" } },
  facts: { headers: {}, query: {}, params: {}, body: { amount: 500 }, meta: {} },
};

const fetchReturning =
  (getPayload: () => unknown): FetchLike =>
  async () => ({ ok: true, status: 200, json: async () => getPayload() });

const fetchFailing: FetchLike = async () => {
  throw new Error("network down");
};

// PollingManager unit tests — headers are now caller-supplied.

test("PollingManager: pollOnce hands the parsed payload to onFetched", async () => {
  let got: RuleSetPayload | null = null;
  const pm = new PollingManager(
    { rulesUrl: "http://rules", headers: {}, fetchImpl: fetchReturning(() => ruleset(3)) },
    noopLogger,
    (payload) => {
      got = payload;
    },
    () => {},
  );
  await pm.pollOnce();
  assert.deepEqual(got, ruleset(3));
});

test("PollingManager: fetch failure reports via onError (no throw)", async () => {
  let errored = false;
  const pm = new PollingManager(
    { rulesUrl: "http://rules", headers: {}, fetchImpl: fetchFailing },
    noopLogger,
    () => {},
    () => {
      errored = true;
    },
  );
  await pm.pollOnce(); // must not throw
  assert.equal(errored, true);
});

test("PollingManager: caller-supplied headers are forwarded to fetch", async () => {
  let seen: Record<string, string> | undefined;
  const fetchImpl: FetchLike = async (_url, init) => {
    seen = init?.headers;
    return { ok: true, status: 200, json: async () => ruleset(1) };
  };
  const pm = new PollingManager(
    {
      rulesUrl: "http://rules",
      headers:  { Authorization: "Bearer t", "X-Instance-Key": "k" },
      fetchImpl,
    },
    noopLogger,
    () => {},
    () => {},
  );
  await pm.pollOnce();
  assert.equal(seen?.["Authorization"], "Bearer t");
  assert.equal(seen?.["X-Instance-Key"], "k");
});

test("PollingManager: concurrent pollOnce is coalesced into one fetch", async () => {
  let fetchCount = 0;
  const fetchImpl: FetchLike = async () => {
    fetchCount++;
    await new Promise((r) => setImmediate(r));
    return { ok: true, status: 200, json: async () => ruleset(1) };
  };
  const pm = new PollingManager(
    { rulesUrl: "http://rules", headers: {}, fetchImpl },
    noopLogger,
    () => {},
    () => {},
  );
  await Promise.all([pm.pollOnce(), pm.pollOnce(), pm.pollOnce()]);
  assert.equal(fetchCount, 1, "overlapping polls share one in-flight fetch");
});

// Client-level tests — now require `token` for remote fetch.

test("client: init with token fetches via the default rules URL", async () => {
  let calledUrl: string | undefined;
  const fetchImpl: FetchLike = async (url) => {
    calledUrl = url;
    return { ok: true, status: 200, json: async () => ruleset(5) };
  };
  const ks = await KLSwitch.init({ token: "ks_live_test", fetchImpl });
  assert.equal(calledUrl, "https://api.klswitch.dev/v1/ruleset");
  const status = ks.getStatus();
  assert.equal(status.ready, true);
  assert.equal(status.active_rules_version, 5);
  assert.equal(status.lastRefreshOk, true);
  assert.equal(ks.evaluate(input).matched.length, 1);
  ks.close();
});

test("client: baseUrl overrides the rules URL", async () => {
  let calledUrl: string | undefined;
  const fetchImpl: FetchLike = async (url) => {
    calledUrl = url;
    return { ok: true, status: 200, json: async () => ruleset(5) };
  };
  const ks = await KLSwitch.init({
    token:   "ks_live_test",
    baseUrl: "http://self-hosted:8080",
    fetchImpl,
  });
  assert.equal(calledUrl, "http://self-hosted:8080/v1/ruleset");
  ks.close();
});

test("client: rulesUrl escape hatch wins over baseUrl", async () => {
  let calledUrl: string | undefined;
  const fetchImpl: FetchLike = async (url) => {
    calledUrl = url;
    return { ok: true, status: 200, json: async () => ruleset(5) };
  };
  const ks = await KLSwitch.init({
    token:    "ks_live_test",
    baseUrl:  "http://ignored",
    rulesUrl: "http://custom/rules",
    fetchImpl,
  });
  assert.equal(calledUrl, "http://custom/rules");
  ks.close();
});

test("client: Authorization + X-Instance-Key go out on every fetch", async () => {
  let seen: Record<string, string> | undefined;
  const fetchImpl: FetchLike = async (_url, init) => {
    seen = init?.headers;
    return { ok: true, status: 200, json: async () => ruleset(1) };
  };
  const ks = await KLSwitch.init({
    token:      "ks_live_secret",
    instanceId: "pod-7f4a",
    fetchImpl,
  });
  assert.equal(seen?.["Authorization"], "Bearer ks_live_secret");
  assert.equal(seen?.["X-Instance-Key"], "pod-7f4a");
  assert.match(seen?.["User-Agent"] ?? "", /^klswitch-node\/\d+\.\d+\.\d+/);
  ks.close();
});

test("client: extraHeaders are forwarded, but Authorization can't be overridden", async () => {
  let seen: Record<string, string> | undefined;
  const fetchImpl: FetchLike = async (_url, init) => {
    seen = init?.headers;
    return { ok: true, status: 200, json: async () => ruleset(1) };
  };
  const ks = await KLSwitch.init({
    token: "ks_live_test",
    extraHeaders: {
      "X-Custom":      "value",
      "Authorization": "Bearer ATTACK", // operator typo — SDK must win
    },
    fetchImpl,
  });
  assert.equal(seen?.["X-Custom"], "value");
  assert.equal(seen?.["Authorization"], "Bearer ks_live_test");
  ks.close();
});

test("client: refresh re-fetches and swaps in the new version", async () => {
  let version = 5;
  const ks = await KLSwitch.init({
    token: "ks_live_test",
    fetchImpl: fetchReturning(() => ruleset(version)),
  });
  assert.equal(ks.getStatus().active_rules_version, 5);

  version = 9;
  await ks.refresh();
  const status = ks.getStatus();
  assert.equal(status.active_rules_version, 9);
  assert.equal(status.lastRefreshOk, true);
  ks.close();
});

test("client: refresh failure keeps the last known good ruleset", async () => {
  let fail = false;
  const fetchImpl: FetchLike = async () => {
    if (fail) throw new Error("network down");
    return { ok: true, status: 200, json: async () => ruleset(5) };
  };
  const ks = await KLSwitch.init({ token: "ks_live_test", fetchImpl });
  assert.equal(ks.getStatus().active_rules_version, 5);

  fail = true;
  await ks.refresh(); // fetch fails; active rules must be preserved
  const status = ks.getStatus();
  assert.equal(status.active_rules_version, 5, "last known good preserved");
  assert.equal(status.lastRefreshOk, false);
  assert.equal(ks.evaluate(input).matched.length, 1, "still evaluates against old rules");
  ks.close();
});

test("client: init fails when remote fetch fails and no inline fallback", async () => {
  await assert.rejects(
    () => KLSwitch.init({ token: "ks_live_test", fetchImpl: fetchFailing }),
    /no rules available/i,
  );
});

test("config: negative refreshInterval is rejected", async () => {
  await assert.rejects(
    () => KLSwitch.init({ rules: ruleset(1), refreshInterval: -1 }),
    /refreshInterval/i,
  );
});

test("config: refreshInterval=0 disables polling but inline rules still work", async () => {
  const ks = await KLSwitch.init({ rules: ruleset(1), refreshInterval: 0 });
  assert.equal(ks.getStatus().polling, false);
  ks.close();
});

test("config: non-positive analytics options are rejected", async () => {
  await assert.rejects(
    () => KLSwitch.init({ rules: ruleset(1), analytics: { sink: () => {}, batchSize: 0 } }),
    /batchSize must be positive/i,
  );
});

test("client: no outbound calls at all in inline-rules mode", async () => {
  const seenUrls: string[] = [];
  const fetchImpl: FetchLike = async (url) => {
    seenUrls.push(url);
    return { ok: true, status: 204, json: async () => null };
  };
  const ks = await KLSwitch.init({ rules: ruleset(1), fetchImpl });
  // No remote configured → no polling, nothing on the wire.
  await new Promise((r) => setTimeout(r, 30));
  assert.equal(seenUrls.length, 0, `expected zero outbound calls in inline-rules mode, saw: ${seenUrls.join(",")}`);
  ks.close();
});

test("client: a poll resolving after close does not throw or mutate state", async () => {
  let mode: "ok" | "hang" = "ok";
  let release: (() => void) | null = null;
  const fetchImpl: FetchLike = async () => {
    if (mode === "hang") {
      await new Promise<void>((resolve) => {
        release = resolve;
      });
    }
    return { ok: true, status: 200, json: async () => ruleset(7) };
  };

  const ks = await KLSwitch.init({ token: "ks_live_test", fetchImpl }); // initial fetch (ok)
  mode = "hang";
  const pending = ks.refresh(); // fetch parks mid-flight
  await new Promise((r) => setImmediate(r));
  ks.close(); // close while the fetch is in flight
  release?.(); // let the fetch resolve afterward
  await pending; // must not throw

  assert.equal(ks.getStatus().ready, false);
});
