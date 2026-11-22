"use strict";
/**
 * License policy: paid, explicit admin overrides and launch promotion are
 * distinct records. A promotional grant is NOT evidence of a purchase.
 * Firebase Admin is the only writer. No client can grant itself a license.
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getAuth } = require("firebase-admin/auth");
const REGION = "europe-west1";

async function licenseStatus(uid, {autoGrant = false} = {}) {
  const user = await getAuth().getUser(uid);
  if (user.disabled) throw new HttpsError("permission-denied", "Compte désactivé.");
  if (!user.emailVerified || !(user.email || "").includes("@"))
    throw new HttpsError("failed-precondition", "Adresse e-mail non vérifiée.");

  if (user.customClaims?.owner_functional_tests === true) {
    return { licensed: true, source: "owner", verified: true };
  }

  const db = getFirestore();
  const overrideRef = db.collection("manualLicenseOverrides").doc(uid);
  const paidRef = db.collection("licenses").doc(uid);
  const promoRef = db.collection("promotionalLicenses").doc(uid);
  const settingsRef = db.collection("licenseSettings").doc("current");

  // One transaction avoids racing an admin revocation against auto-grant.
  return db.runTransaction(async transaction => {
    const [override, paid, promo, settings] = await Promise.all([
      transaction.get(overrideRef),
      transaction.get(paidRef),
      transaction.get(promoRef),
      transaction.get(settingsRef)
    ]);
    const manuallyLicensed = override.get("licensed");
    if (typeof manuallyLicensed === "boolean") {
      return { licensed: manuallyLicensed, source: "administrative", verified: true };
    }

    const purchase = paid.data();
    const purchased = purchase?.uid === uid &&
      purchase?.emailLower === user.email.toLowerCase() &&
      purchase?.status === "active" &&
      purchase?.kind === "perpetual" &&
      typeof purchase?.stripePaymentIntentId === "string" &&
      purchase.stripePaymentIntentId.length > 0 &&
      purchase?.expiresAt == null;
    if (purchased) return { licensed: true, source: "purchase", verified: true };

    const promotion = promo.data();
    if (promotion?.emailLower === user.email.toLowerCase() &&
        promotion?.active === true) {
      return { licensed: true, source: "promotion", verified: true };
    }

    // Initially on for all users; future change to false is a single
    // backend configuration write, not a new app release.
    const autoGrantEnabled = settings.exists ?
      settings.get("autoGrantEnabled") === true : true;
    if (autoGrant && autoGrantEnabled && !promo.exists) {
      transaction.create(promoRef, {
        uid, emailLower: user.email.toLowerCase(), active: true,
        origin: "launch-promotion", createdAt: FieldValue.serverTimestamp()
      });
      return { licensed: true, source: "promotion", verified: true };
    }
    return { licensed: false, source: "none", verified: true };
  });
}

exports.licenseStatus = licenseStatus;
exports.getMyLicenseStatus = onCall(
  { region: REGION, enforceAppCheck: true },
  async request => {
    if (!request.auth?.uid)
      throw new HttpsError("unauthenticated", "Connexion requise.");
    return licenseStatus(request.auth.uid, { autoGrant: true });
  }
);
