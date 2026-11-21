"use strict";

// Pure authorization/billing rules, shared with the offline Node tests.
// These functions do NOT grant or persist entitlements.
const normalizeEmail = (email) =>
    typeof email === "string" ? email.trim().normalize("NFKC").toLowerCase() : "";

function verifiedFirebaseIdentity(user) {
    const email = normalizeEmail(user?.email);
    if (!user?.uid || !user.emailVerified || !email || !email.includes("@"))
        return null;
    return { uid: user.uid, email };
}
function isCurrentActiveLicense(license, identity) {
    return !!identity && !!license &&
        license.uid === identity.uid &&
        license.emailLower === identity.email &&
        license.status === "active" && license.kind === "perpetual";
}
function checkoutMatchesIdentity(session, identity, priceId, lineItems) {
    if (!identity || !session || !priceId ||
        session.mode !== "payment" ||
        session.payment_status !== "paid" ||
        session.client_reference_id !== identity.uid ||
        session.metadata?.license_uid !== identity.uid ||
        normalizeEmail(session.customer_details?.email) !== identity.email ||
        !(session.amount_total > 0) ||
        !Array.isArray(lineItems) || lineItems.length !== 1
    ) return false;
    const item = lineItems[0];
    return item.price?.id === priceId && item.quantity === 1 &&
        item.amount_total > 0;
}
function canRevokeThisLicense(license, paymentIntentId) {
    return !!paymentIntentId && !!license &&
        license.status === "active" &&
        license.stripePaymentIntentId === paymentIntentId;
}
module.exports = {
    normalizeEmail, verifiedFirebaseIdentity,
    isCurrentActiveLicense, checkoutMatchesIdentity, canRevokeThisLicense
};
