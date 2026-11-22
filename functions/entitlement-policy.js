"use strict";

/**
 * Pure license decision logic; no diagnostic data and no Firestore access.
 * Administrative NO is authoritative and cannot be undone by an automatic grant.
 */
function readEntitlement(license, uid, emailLower) {
  if (!license) return null;

  if (license.uid !== uid || license.emailLower !== emailLower)
    return { licensed: false, source: "mismatch", verified: true };

  if (typeof license.manualOverride === "boolean")
    return { licensed: license.manualOverride, source: "administrative", verified: true };

  if (license.status === "active" &&
      license.kind === "perpetual" &&
      typeof license.stripePaymentIntentId === "string" &&
      license.stripePaymentIntentId.length > 0 &&
      license.expiresAt == null)
    return { licensed: true, source: "purchase", verified: true };

  if (license.status === "active" && license.source === "promotion")
    return { licensed: true, source: "promotion", verified: true };

  return { licensed: false, source: "none", verified: true };
}

function shouldAutoGrant(exists, requestAllowsAutoGrant, configEnabled) {
  return !exists && requestAllowsAutoGrant && configEnabled;
}

module.exports = { readEntitlement, shouldAutoGrant };
