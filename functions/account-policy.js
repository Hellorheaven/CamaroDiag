"use strict";

/**
 * Account status is visible to authorized administrators even if an
 * account has never verified its email address.
 * This is not a license grant and cannot unlock Premium.
 */
function displayLicenseWithoutLookup(user) {
  if (user.disabled === true)
    return { licensed: false, source: "disabled", verified: true };
  if (user.emailVerified !== true || !user.email)
    return { licensed: false, source: "unverified", verified: false };
  return null;
}

/** Require a real Firebase authentication epoch, not null/empty coercing to zero. */
function isValidAuthTime(value) {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecentAuthTime(value, nowSeconds, maxAgeSeconds) {
  return isValidAuthTime(value) && value <= nowSeconds && nowSeconds - value <= maxAgeSeconds;
}

/** Compare Firebase auth_time with the Admin SDK revocation boundary. */
function isSessionRevoked(tokensValidAfterTime, authTimeSeconds) {
  if (!tokensValidAfterTime) return false;
  const validAfterSeconds = Date.parse(tokensValidAfterTime) / 1000;
  // A malformed token cannot bypass a known revocation timestamp.
  if (!Number.isFinite(validAfterSeconds)) return true;
  return !isValidAuthTime(authTimeSeconds) || authTimeSeconds < validAfterSeconds;
}

function isVerifiedGoogleSession(token) {
  return token?.email_verified === true &&
    token?.firebase?.sign_in_provider === "google.com";
}

// Account deletion never targets the caller, an owner or an administrator.
function mayDeleteAccount(actorUid, target) {
  return typeof actorUid === "string" && !!actorUid && typeof target?.uid === "string" &&
    !!target.uid && target.uid !== actorUid &&
    target.customClaims?.owner_functional_tests !== true &&
    target.customClaims?.admin_accounts !== true;
}

module.exports = { mayDeleteAccount, displayLicenseWithoutLookup, isSessionRevoked, isVerifiedGoogleSession, isValidAuthTime, isRecentAuthTime };
