"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const { displayLicenseWithoutLookup, isSessionRevoked, isVerifiedGoogleSession } = require("./account-policy");

test("administrator can inspect unverified accounts without an entitlement read", () => {
  assert.deepEqual(displayLicenseWithoutLookup({disabled: false, emailVerified: false, email: "u@example.com"}),
    {licensed: false, source: "unverified", verified: false});
  assert.deepEqual(displayLicenseWithoutLookup({disabled: false, emailVerified: true, email: ""}),
    {licensed: false, source: "unverified", verified: false});
});
test("disabled account still appears in administration", () => {
  assert.deepEqual(displayLicenseWithoutLookup({disabled: true, emailVerified: true, email: "u@example.com"}),
    {licensed: false, source: "disabled", verified: true});
  assert.equal(displayLicenseWithoutLookup({disabled: false, emailVerified: true, email: "u@example.com"}), null);
});
test("rejected token cannot outlive the server revocation cutoff", () => {
  const boundary = "2026-11-22T09:00:00.000Z";
  const seconds = Date.parse(boundary) / 1000;
  assert.equal(isSessionRevoked(boundary, seconds - 1), true);
  assert.equal(isSessionRevoked(boundary, seconds), false);
  assert.equal(isSessionRevoked(boundary, seconds + 1), false);
  assert.equal(isSessionRevoked(boundary, undefined), true);
  assert.equal(isSessionRevoked("unparseable", seconds + 10), true);
  assert.equal(isSessionRevoked(undefined, seconds - 1), false);
});

test("administration requires a verified current Google sign-in, not merely a linked Google provider", () => {
  assert.equal(isVerifiedGoogleSession({
    email_verified: true, firebase: {sign_in_provider: "google.com"}
  }), true);
  assert.equal(isVerifiedGoogleSession({
    email_verified: true, firebase: {sign_in_provider: "password"}
  }), false);
  assert.equal(isVerifiedGoogleSession({
    email_verified: false, firebase: {sign_in_provider: "google.com"}
  }), false);
  assert.equal(isVerifiedGoogleSession({ email_verified: true }), false);
});
