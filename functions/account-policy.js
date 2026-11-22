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

/** Compare Firebase auth_time with the Admin SDK revocation boundary. */
function isSessionRevoked(tokensValidAfterTime, authTimeSeconds) {
  if (!tokensValidAfterTime) return false;
  const validAfterSeconds = Date.parse(tokensValidAfterTime) / 1000;
  // A malformed token cannot bypass a known revocation timestamp.
  if (!Number.isFinite(validAfterSeconds)) return true;
  const authTime = Number(authTimeSeconds);
  return !Number.isFinite(authTime) || authTime < validAfterSeconds;
}

function isVerifiedGoogleSession(token) {
  return token?.email_verified === true &&
    token?.firebase?.sign_in_provider === "google.com";
}

module.exports = { displayLicenseWithoutLookup, isSessionRevoked, isVerifiedGoogleSession };
