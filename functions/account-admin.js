"use strict";
/**
 * Firebase-only account administration. NEVER trust a client supplied role.
 * Deployment is a separate, explicitly authorised step.
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");

const region = "europe-west1";
const adminRole = "admin_accounts";
const maxSessionAge = 5 * 60;
// Do not require attestation until real debug/release validation succeeds.
const ENFORCE_APP_CHECK = process.env.CAMARODIAG_ENFORCE_APP_CHECK === "true";
const { licenseStatus } = require("./license-status");
const { isSessionRevoked, displayLicenseWithoutLookup, isVerifiedGoogleSession, isRecentAuthTime } = require("./account-policy");

function adminIdentity(request) {
    if (!request.auth?.uid || (request.auth.token?.[adminRole] !== true &&
        request.auth.token?.owner_functional_tests !== true))
        throw new HttpsError("permission-denied", "Droits administrateur requis.");
    if (!isRecentAuthTime(request.auth.token.auth_time, Math.floor(Date.now() / 1000), maxSessionAge))
        throw new HttpsError("failed-precondition", "Reconnectez-vous avant cette opération.");
    if (!isVerifiedGoogleSession(request.auth.token))
        throw new HttpsError("permission-denied", "Compte Google vérifié requis pour l'administration.");
    return request.auth.uid;
}

async function requireCurrentAdmin(uid, authTime) {
    // A revoked custom claim in an older token is insufficient to authorize.
    const user = await getAuth().getUser(uid);
    if (isSessionRevoked(user.tokensValidAfterTime, authTime))
        throw new HttpsError("unauthenticated", "Session révoquée. Reconnectez-vous.");
    if (user.disabled || user.emailVerified !== true ||
        (user.customClaims?.[adminRole] !== true &&
         user.customClaims?.owner_functional_tests !== true) ||
        !user.providerData.some(p => p.providerId === "google.com"))
        throw new HttpsError("permission-denied", "Compte administrateur non autorisé.");
}

async function visibleAccount(user) {
    // Disabled accounts cannot invoke licenseStatus; admins must still be
    // able to review/reactivate them and inspect their last license choice.
    const license = displayLicenseWithoutLookup(user) || await licenseStatus(user.uid, { allowOwnerEntitlement: true });
    return { ...publicUser(user), license };
}

function publicUser(user) {
    return {
        uid: user.uid,
        email: user.email || "",
        emailVerified: user.emailVerified === true,
        disabled: user.disabled === true,
        providers: user.providerData.map(p => p.providerId),
        administrator: user.customClaims?.[adminRole] === true,
        functionalTests: user.customClaims?.owner_functional_tests === true
    };
}

exports.adminGetAccount = onCall({ region, enforceAppCheck: ENFORCE_APP_CHECK }, async request => {
    const actor = adminIdentity(request);
    await requireCurrentAdmin(actor, request.auth.token.auth_time);
    const email = request.data?.email;
    if (typeof email !== "string" || email.length > 254 ||
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
        throw new HttpsError("invalid-argument", "Adresse e-mail invalide.");
    try {
        const user = await getAuth().getUserByEmail(email.trim().toLowerCase());
        return { account: await visibleAccount(user) };
    } catch (e) {
        if (e.code === "auth/user-not-found")
            throw new HttpsError("not-found", "Compte introuvable.");
        throw e;
    }
});

exports.adminUpdateAccount = onCall({ region, enforceAppCheck: ENFORCE_APP_CHECK }, async request => {
    const actor = adminIdentity(request);
    await requireCurrentAdmin(actor, request.auth.token.auth_time);
    const { uid, action, value } = request.data || {};
    if (typeof uid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(uid))
        throw new HttpsError("invalid-argument", "UID invalide.");
    if (!["disable", "revoke_sessions", "administrator", "license"].includes(action))
        throw new HttpsError("invalid-argument", "Action non autorisée.");
    if (uid === actor)
        throw new HttpsError("failed-precondition", "Modification de son propre compte interdite.");
    const auth = getAuth();
    const target = await auth.getUser(uid);
    // Never allow ordinary admins to disable or alter a functional-test owner.
    // Owner enrolment stays exclusive to its separate, HMAC-guarded script.
    if (target.customClaims?.owner_functional_tests === true)
        throw new HttpsError("permission-denied", "Compte propriétaire protégé.");
    if (action === "license") {
        if (typeof value !== "boolean")
            throw new HttpsError("invalid-argument", "Valeur booléenne requise.");
        if (value && (!target.emailVerified || !target.email))
            throw new HttpsError("failed-precondition", "Un compte avec e-mail vérifié est requis.");
        await getFirestore().collection("licenses").doc(uid).set({
            uid,
            emailLower: (target.email || "").trim().toLowerCase(),
            manualOverride: value,
            manualChangedBy: actor,
            manualChangedAt: FieldValue.serverTimestamp()
        }, { merge: true });
    } else if (action === "disable") {
        if (typeof value !== "boolean")
            throw new HttpsError("invalid-argument", "Valeur booléenne requise.");
        if (target.customClaims?.[adminRole] === true && value)
            throw new HttpsError("failed-precondition", "Retirez le rôle administrateur au préalable.");
        await auth.updateUser(uid, { disabled: value });
        if (value) await auth.revokeRefreshTokens(uid);
    } else if (action === "revoke_sessions") {
        await auth.revokeRefreshTokens(uid);
    } else {
        if (typeof value !== "boolean")
            throw new HttpsError("invalid-argument", "Valeur booléenne requise.");
        if (value && (target.disabled || !target.emailVerified ||
            !target.providerData.some(p => p.providerId === "google.com")))
            throw new HttpsError("failed-precondition", "Compte Google vérifié requis.");
        const claims = { ...(target.customClaims || {}) };
        if (value) claims[adminRole] = true;
        else delete claims[adminRole];
        await auth.setCustomUserClaims(uid, claims);
        await auth.revokeRefreshTokens(uid);
    }
    return { account: await visibleAccount(await auth.getUser(uid)) };
});
