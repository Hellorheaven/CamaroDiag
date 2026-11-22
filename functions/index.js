"use strict";

/**
 * CamaroDiag one-time personal license checkout.
 *
 * DEPLOYMENT REQUIRED: functions+Firestore are not deployed by committing code.
 * Uses Stripe Checkout hosted by Stripe, verified signature webhooks and
 * Firebase Admin only. No credit-card details reach the app or Firestore.
 *
 * Environment (private Firebase deployment configuration):
 *   CAMARODIAG_STRIPE_PRICE_ID       Stripe one-time price (price_...)
 *   CAMARODIAG_LICENSE_RETURN_URL    HTTPS status page owned by the publisher
 * Firebase Secret Manager (never commit):
 *   CAMARODIAG_STRIPE_SECRET_KEY
 *   CAMARODIAG_STRIPE_WEBHOOK_SECRET
 */
const { onCall, onRequest, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const { initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const Stripe = require("stripe");
const {
    normalizeEmail, verifiedFirebaseIdentity, isCurrentActiveLicense,
    checkoutMatchesIdentity, canRevokeThisLicense, billingRolloutEnabled
} = require("./licensing-policy");

initializeApp();

const REGION = "europe-west1";
const stripeKey = defineSecret("CAMARODIAG_STRIPE_SECRET_KEY");
const stripeWebhookSecret = defineSecret("CAMARODIAG_STRIPE_WEBHOOK_SECRET");
const db = getFirestore();

/**
 * Billing entry points are inactive until the explicit server-side switch
 * is enabled; a committed feature cannot process payment by itself.
 */

function priceId() {
    const id = (process.env.CAMARODIAG_STRIPE_PRICE_ID ?? "").trim();
    if (!/^price_[A-Za-z0-9]+$/.test(id)) {
        throw new Error("Stripe one-time price ID is not configured.");
    }
    return id;
}

function publicReturnUrl() {
    const input = process.env.CAMARODIAG_LICENSE_RETURN_URL ?? "";
    const url = new URL(input);
    if (url.protocol !== "https:" || url.username || url.password ||
        url.search || url.hash || !url.hostname || url.hostname === "localhost") {
        throw new Error("Configure a public HTTPS license-return page.");
    }
    return url.toString();
}

function stripe() {
    return new Stripe(stripeKey.value());
}

async function userIdentity(uid) {
    if (typeof uid !== "string" || !uid) return null;
    const user = await getAuth().getUser(uid);
    return verifiedFirebaseIdentity(user);
}

async function updatePaidClaim(uid, enabled) {
    const auth = getAuth();
    const user = await auth.getUser(uid);
    const claims = { ...(user.customClaims ?? {}) };
    if (enabled) claims.license_active = true;
    else delete claims.license_active;
    // Crucially preserve the unrelated signed owner_functional_tests claim.
    await auth.setCustomUserClaims(uid, claims);
}

/**
 * The callable SDK supplies a verified Firebase Auth identity.
 * Never accept UID, email, price or a checkout URL from the client.
 * A purchase grants exactly the signed-in verified account its license.
 */
exports.createLicenseCheckout = onCall(
    { region: REGION, secrets: [stripeKey], maxInstances: 20 },
    async (request) => {
        if (!billingRolloutEnabled()) {
            throw new HttpsError(
                "failed-precondition",
                "La vente de licences n'est pas activée."
            );
        }
        const uid = request.auth?.uid;
        if (!uid) throw new HttpsError("unauthenticated", "Connectez votre compte Firebase.");
        let identity;
        try {
            identity = await userIdentity(uid);
        } catch {
            throw new HttpsError("unauthenticated", "Compte Firebase indisponible.");
        }
        if (!identity)
            throw new HttpsError("failed-precondition", "Adresse e-mail Firebase non vérifiée.");
        const user = await getAuth().getUser(uid);
        if (user.customClaims?.owner_functional_tests === true) {
            throw new HttpsError("already-exists", "Compte propriétaire : licence non nécessaire.");
        }

        const existing = (await db.collection("licenses").doc(uid).get()).data();
        if (isCurrentActiveLicense(existing, identity)) {
            throw new HttpsError("already-exists", "Une licence est déjà active sur ce compte.");
        }

        let price, returnUrl;
        try {
            price = priceId();
            returnUrl = publicReturnUrl();
        } catch {
            throw new HttpsError("failed-precondition", "La vente de licences n'est pas configurée.");
        }

        const checkout = await stripe().checkout.sessions.create({
            mode: "payment",
            client_reference_id: uid,
            customer_email: identity.email,
            line_items: [{ price, quantity: 1 }],
            metadata: { license_uid: uid, source: "camarodiag" },
            payment_intent_data: { metadata: { license_uid: uid, source: "camarodiag" } },
            success_url: returnUrl + (returnUrl.includes("?") ? "&" : "?") +
                "license=success&session_id={CHECKOUT_SESSION_ID}",
            cancel_url: returnUrl + (returnUrl.includes("?") ? "&" : "?") + "license=cancelled"
        });
        if (!checkout.url || !checkout.url.startsWith("https://checkout.stripe.com/")) {
            throw new HttpsError("internal", "URL de paiement indisponible.");
        }
        return { url: checkout.url };
    }
);

async function grantPaidSession(stripeClient, sessionId) {
    const session = await stripeClient.checkout.sessions.retrieve(sessionId);
    // Payment can be asynchronous. Never grant for an unpaid browser redirect.
    if (session.payment_status !== "paid" || session.mode !== "payment") return;
    if (session.metadata?.source !== "camarodiag") return;
    const uid = session.client_reference_id;
    if (!uid || uid !== session.metadata?.license_uid) return;

    const identity = await userIdentity(uid);
    if (!identity) return;
    const items = (await stripeClient.checkout.sessions.listLineItems(sessionId, { limit: 10 })).data;
    if (!checkoutMatchesIdentity(session, identity, priceId(), items)) return;

    const paymentIntentId =
        typeof session.payment_intent === "string" ? session.payment_intent
        : session.payment_intent?.id;
    if (!paymentIntentId) throw new Error("Paid checkout has no PaymentIntent.");
    const intent = await stripeClient.paymentIntents.retrieve(paymentIntentId, {
        expand: ["latest_charge"]
    });
    const charge = typeof intent.latest_charge === "object" ? intent.latest_charge : null;
    if (intent.status !== "succeeded" ||
        intent.metadata?.license_uid !== uid ||
        intent.metadata?.source !== "camarodiag" ||
        intent.amount_received < session.amount_total ||
        intent.currency !== session.currency ||
        charge?.refunded === true || charge?.disputed === true
    ) return;

    const licenseRef = db.collection("licenses").doc(uid);
    const sessionRef = db.collection("licenseCheckoutSessions").doc(sessionId);
    const paymentRef = db.collection("licensePayments").doc(paymentIntentId);
    const revokedRef = db.collection("revokedLicensePayments").doc(paymentIntentId);

    // A claim alone cannot unlock paid features: the client also requires
    // a separate online Firestore document owned by this UID + verified email.
    await updatePaidClaim(uid, true);
    const granted = await db.runTransaction(async (transaction) => {
        const [revoked, previousSession, priorLicense] = await Promise.all([
            transaction.get(revokedRef),
            transaction.get(sessionRef),
            transaction.get(licenseRef)
        ]);
        if (revoked.exists) return false; // refund / dispute raced with payment
        if (previousSession.exists) return true; // idempotent Stripe retry
        const prior = priorLicense.data();
        if (prior?.status === "active" &&
            prior.stripePaymentIntentId !== paymentIntentId) return false;
        transaction.set(licenseRef, {
            uid, emailLower: identity.email, status: "active", kind: "perpetual",
            stripeCheckoutSessionId: sessionId,
            stripePaymentIntentId: paymentIntentId,
            expiresAt: null,
            issuedAt: FieldValue.serverTimestamp(),
            updatedAt: FieldValue.serverTimestamp()
        });
        transaction.set(sessionRef, {
            uid, paymentIntentId, processedAt: FieldValue.serverTimestamp()
        });
        transaction.set(paymentRef, {
            uid, sessionId, processedAt: FieldValue.serverTimestamp()
        });
        return true;
    });
    // An earlier dispute means this payment must NEVER leave a positive claim.
    if (!granted) {
        const currentLicense = (await licenseRef.get()).data();
        if (!isCurrentActiveLicense(currentLicense, identity))
            await updatePaidClaim(uid, false);
    }
}

async function revokePayment(stripeClient, paymentIntentId, reason) {
    if (!paymentIntentId || typeof paymentIntentId !== "string") return;
    // Stripe signature verifies the event. Stripe's original metadata binds
    // the payment to the UID even if webhook events arrive out of order.
    const intent = await stripeClient.paymentIntents.retrieve(paymentIntentId);
    if (intent.metadata?.source !== "camarodiag") return;
    const uid = intent.metadata?.license_uid;
    if (!uid) return;
    const licenseRef = db.collection("licenses").doc(uid);
    const revokedRef = db.collection("revokedLicensePayments").doc(paymentIntentId);

    const revoked = await db.runTransaction(async (transaction) => {
        const [licenseSnap] = await Promise.all([transaction.get(licenseRef)]);
        // Record revocation even if checkout fulfillment hasn't happened yet.
        transaction.set(revokedRef, {
            uid, reason, updatedAt: FieldValue.serverTimestamp()
        }, { merge: true });
        const license = licenseSnap.data();
        if (!canRevokeThisLicense(license, paymentIntentId)) return false;
        transaction.update(licenseRef, {
            status: "revoked", revokedReason: reason,
            updatedAt: FieldValue.serverTimestamp()
        });
        return true;
    });
    if (revoked) await updatePaidClaim(uid, false);
}

/**
 * Configure this HTTPS URL as a Stripe webhook endpoint and subscribe to:
 * checkout.session.completed, checkout.session.async_payment_succeeded,
 * charge.refunded and charge.dispute.created.
 *
 * The raw byte buffer plus Stripe-Signature is mandatory.
 */
exports.stripeLicenseWebhook = onRequest(
    { region: REGION, secrets: [stripeKey, stripeWebhookSecret], maxInstances: 20 },
    async (req, res) => {
        if (!billingRolloutEnabled()) {
            res.status(503).send("Licensing is disabled.");
            return;
        }
        if (req.method !== "POST") {
            res.status(405).send("POST only");
            return;
        }
        const client = stripe();
        let event;
        try {
            event = client.webhooks.constructEvent(
                req.rawBody, req.headers["stripe-signature"], stripeWebhookSecret.value()
            );
        } catch {
            res.status(400).send("Invalid Stripe signature");
            return;
        }
        try {
            if (event.type === "checkout.session.completed" ||
                event.type === "checkout.session.async_payment_succeeded") {
                await grantPaidSession(client, event.data.object.id);
            } else if (event.type === "charge.refunded") {
                await revokePayment(client, event.data.object.payment_intent, "refunded");
            } else if (event.type === "charge.dispute.created") {
                await revokePayment(client, event.data.object.payment_intent, "disputed");
            }
            res.status(200).send("OK");
        } catch (error) {
            // Stripe retries 5xx. Do not log e-mail, token, card data or secrets.
            console.error("License webhook processing failed", {
                eventType: event.type, errorType: error?.name ?? "Error"
            });
            res.status(500).send("Retry");
        }
    }
);

// Account management is permissioned separately from license and functional tests.
const accountAdmin = require("./account-admin");
exports.adminGetAccount = accountAdmin.adminGetAccount;
exports.adminUpdateAccount = accountAdmin.adminUpdateAccount;
