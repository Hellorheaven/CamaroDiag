"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { readEntitlement, shouldAutoGrant } = require("./entitlement-policy");

const uid = "firebase-user-1";
const emailLower = "user@example.com";
const base = { uid, emailLower, status: "active", source: "promotion", kind: "perpetual" };
const result = (licensed, source) => ({ licensed, source, verified: true });

test("a missing license can be granted only when first login and promotion are enabled", () => {
  assert.equal(readEntitlement(undefined, uid, emailLower), null);
  assert.equal(shouldAutoGrant(false, true, true), true);
  assert.equal(shouldAutoGrant(false, true, false), false);
  assert.equal(shouldAutoGrant(false, false, true), false);
  assert.equal(shouldAutoGrant(true, true, true), false);
});
test("promotion is an actual entitlement, not a fabricated purchase", () => {
  assert.deepEqual(readEntitlement(base, uid, emailLower), result(true, "promotion"));
});
test("manual revocation overrides an existing promotion and prevents re-grant", () => {
  assert.deepEqual(readEntitlement({ ...base, manualOverride: false }, uid, emailLower),
    result(false, "administrative"));
  assert.equal(shouldAutoGrant(true, true, true), false);
});
test("administrator can explicitly grant an inactive account license", () => {
  assert.deepEqual(readEntitlement({ ...base, status: "inactive", manualOverride: true }, uid, emailLower),
    result(true, "administrative"));
});
test("UID and verified email binding cannot be bypassed by a license", () => {
  assert.deepEqual(readEntitlement({ ...base, uid: "other-user" }, uid, emailLower),
    result(false, "mismatch"));
  assert.deepEqual(readEntitlement({ ...base, emailLower: "different@example.com" }, uid, emailLower),
    result(false, "mismatch"));
});
test("unverified payments are not treated as paid licenses", () => {
  assert.deepEqual(readEntitlement({ ...base, source: "purchase" }, uid, emailLower),
    result(false, "none"));
  assert.deepEqual(readEntitlement({ ...base, source: "purchase", stripePaymentIntentId: "pi_test", expiresAt: "tomorrow" }, uid, emailLower),
    result(false, "none"));
});
test("verified perpetual purchase record stays recognized for future rollout", () => {
  assert.deepEqual(readEntitlement({ ...base, source: "purchase", stripePaymentIntentId: "pi_test", expiresAt: null }, uid, emailLower),
    result(true, "purchase"));
});
test("unknown and inactive licenses never become promotional automatically", () => {
  assert.deepEqual(readEntitlement({ ...base, status: "inactive" }, uid, emailLower),
    result(false, "none"));
  assert.deepEqual(readEntitlement({ ...base, source: "unknown" }, uid, emailLower),
    result(false, "none"));
});
