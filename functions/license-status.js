"use strict";
/**
 * CamaroDiag keeps ONLY license information in Firestore:
 * licenses/{firebaseUid}. Identity, roles and account state remain in Auth.
 * All OBD, CAN, DTC, ECU and vehicle data stay on the device.
 *
 * Automatic launch grants are free promotional licenses, NOT purchases.
 * Future disablement: CAMARODIAG_AUTO_GRANT_LICENSES=false in server config.
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const { readEntitlement, shouldAutoGrant } = require("./entitlement-policy");
const { isSessionRevoked, isValidAuthTime, isVerifiedGoogleSession } = require("./account-policy");

const REGION = "europe-west1";
// Start in monitoring mode: enable enforcement only after debug and release
// attestations have been tested. Firebase Auth checks remain mandatory.
const ENFORCE_APP_CHECK = process.env.CAMARODIAG_ENFORCE_APP_CHECK === "true";
const AUTO_GRANT_ENABLED = () =>
  process.env.CAMARODIAG_AUTO_GRANT_LICENSES !== "false";

async function licenseStatus(uid, { autoGrant = false, authTime = null, allowOwnerEntitlement = false } = {}) {
  const user = await getAuth().getUser(uid);
  if (authTime !== null && isSessionRevoked(user.tokensValidAfterTime, authTime))
    throw new HttpsError("unauthenticated", "Session révoquée. Reconnectez-vous.");
  if (user.disabled)
    throw new HttpsError("permission-denied", "Compte désactivé.");
  if (!user.emailVerified || !user.email)
    throw new HttpsError("failed-precondition", "Adresse e-mail non vérifiée.");

  // Owner privileges live in Auth, never replicated in Firestore.
  if (allowOwnerEntitlement && user.customClaims?.owner_functional_tests === true &&
      user.providerData.some(provider => provider.providerId === "google.com"))
    return { licensed: true, source: "owner", verified: true };

  const licenseRef = getFirestore().collection("licenses").doc(uid);
  const emailLower = user.email.trim().toLowerCase();

  return getFirestore().runTransaction(async transaction => {
    const snapshot = await transaction.get(licenseRef);
    const license = snapshot.data();

    // Every existing document is authoritative, including a manual NO.
    const existing = readEntitlement(license, uid, emailLower);
    if (existing) return existing;

    if (!shouldAutoGrant(snapshot.exists, autoGrant, AUTO_GRANT_ENABLED()))
      return { licensed: false, source: "none", verified: true };

    transaction.create(licenseRef, {
      uid,
      emailLower,
      status: "active",
      kind: "perpetual",
      source: "promotion",
      grantedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    });
    return { licensed: true, source: "promotion", verified: true };
  });
}

exports.licenseStatus = licenseStatus;
exports.getMyLicenseStatus = onCall(
  { region: REGION, enforceAppCheck: ENFORCE_APP_CHECK },
  async request => {
    if (!request.auth?.uid)
      throw new HttpsError("unauthenticated", "Connexion requise.");
    if (!isValidAuthTime(request.auth.token?.auth_time))
      throw new HttpsError("unauthenticated", "Session invalide.");
    return licenseStatus(request.auth.uid, {
      autoGrant: true,
      authTime: request.auth.token.auth_time,
      allowOwnerEntitlement: isVerifiedGoogleSession(request.auth.token)
    });
  }
);
