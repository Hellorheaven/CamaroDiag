"use strict";
// Read-only display status. Does not enable premium access while rollout is off.
// Manual administrative decisions never rewrite Stripe payment evidence.
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { getFirestore } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const REGION = "europe-west1";

async function licenseStatus(uid) {
  const user = await getAuth().getUser(uid);
  if (user.disabled) throw new HttpsError("permission-denied", "Compte désactivé.");
  const db = getFirestore();
  const [override, paid] = await Promise.all([
    db.collection("manualLicenseOverrides").doc(uid).get(),
    db.collection("licenses").doc(uid).get()
  ]);
  if (override.exists && typeof override.get("licensed") === "boolean") {
    return {
      licensed: override.get("licensed"),
      source: "administrative",
      verified: true
    };
  }
  const purchase = paid.data();
  const valid = purchase?.uid === uid &&
    purchase?.emailLower === (user.email || "").toLowerCase() &&
    purchase?.status === "active" &&
    purchase?.kind === "perpetual" &&
    purchase?.stripePaymentIntentId &&
    purchase?.expiresAt == null;
  return {
    licensed: Boolean(valid),
    source: valid ? "purchase" : "none",
    verified: true
  };
}

exports.licenseStatus = licenseStatus;
exports.getMyLicenseStatus = onCall(
  { region: REGION, enforceAppCheck: true },
  async request => {
    if (!request.auth?.uid)
      throw new HttpsError("unauthenticated", "Connexion requise.");
    return licenseStatus(request.auth.uid);
  }
);
