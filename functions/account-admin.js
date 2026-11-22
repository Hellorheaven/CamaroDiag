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
const { licenseStatus } = require("./license-status");

function adminIdentity(request) {
    if (!request.auth?.uid || (request.auth.token?.[adminRole] !== true &&
        request.auth.token?.owner_functional_tests !== true))
        throw new HttpsError("permission-denied", "Droits administrateur requis.");
    const authenticatedAt = Number(request.auth.token.auth_time);
    if (!Number.isFinite(authenticatedAt) ||
        Math.floor(Date.now() / 1000) - authenticatedAt > maxSessionAge)
        throw new HttpsError("failed-precondition", "Reconnectez-vous avant cette opération.");
    if (request.auth.token.email_verified !== true)
        throw new HttpsError("permission-denied", "Adresse e-mail non vérifiée.");
    return request.auth.uid;
}

async function requireCurrentAdmin(uid) {
    // A revoked custom claim in an older token is insufficient to authorize.
    const user = await getAuth().getUser(uid);
    if (user.disabled || user.emailVerified !== true ||
        (user.customClaims?.[adminRole] !== true &&
         user.customClaims?.owner_functional_tests !== true) ||
        !user.providerData.some(p => p.providerId === "google.com"))
        throw new HttpsError("permission-denied", "Compte administrateur non autorisé.");
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

exports.adminGetAccount = onCall({ region, enforceAppCheck: true }, async request => {
    const actor = adminIdentity(request);
    await requireCurrentAdmin(actor);
    const email = request.data?.email;
    if (typeof email !== "string" || email.length > 254 ||
        !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))
        throw new HttpsError("invalid-argument", "Adresse e-mail invalide.");
    try {
        const user = await getAuth().getUserByEmail(email.trim().toLowerCase());
        return { account: { ...publicUser(user), license: await licenseStatus(user.uid) } };
    } catch (e) {
        if (e.code === "auth/user-not-found")
            throw new HttpsError("not-found", "Compte introuvable.");
        throw e;
    }
});

exports.adminUpdateAccount = onCall({ region, enforceAppCheck: true }, async request => {
    const actor = adminIdentity(request);
    await requireCurrentAdmin(actor);
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
    return { account: { ...publicUser(await auth.getUser(uid)), license: await licenseStatus(uid) } };
});
