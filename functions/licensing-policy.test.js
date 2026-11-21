"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const {
    billingRolloutEnabled,
    verifiedFirebaseIdentity,
    isCurrentActiveLicense,
    checkoutMatchesIdentity,
    canRevokeThisLicense
} = require("./licensing-policy");

test("payment processing is disabled by default, and accepts only explicit true", () => {
    assert.equal(billingRolloutEnabled(undefined), false);
    assert.equal(billingRolloutEnabled("false"), false);
    assert.equal(billingRolloutEnabled("1"), false);
    assert.equal(billingRolloutEnabled("TRUE"), false);
    assert.equal(billingRolloutEnabled("true"), true);
});
test("a license is bound to a verified identity and a matching email", () => {
    const identity = verifiedFirebaseIdentity({
        uid: "user-1", email: "Example@Gmail.com", emailVerified: true
    });
    assert.deepEqual(identity, { uid: "user-1", email: "example@gmail.com" });
    assert.equal(verifiedFirebaseIdentity({
        uid: "user-1", email: "example@gmail.com", emailVerified: false
    }), null);
    const license = {
        uid: "user-1", emailLower: "example@gmail.com", status: "active", kind: "perpetual"
    };
    assert.equal(isCurrentActiveLicense(license, identity), true);
    assert.equal(isCurrentActiveLicense(license, { uid: "user-2", email: identity.email }), false);
    assert.equal(isCurrentActiveLicense(license, { uid: "user-1", email: "other@gmail.com" }), false);
});
test("checkout must be fully paid for exactly the expected price and account", () => {
    const identity = { uid: "uid-1", email: "buyer@gmail.com" };
    const session = {
        mode: "payment", payment_status: "paid", client_reference_id: identity.uid,
        metadata: { license_uid: identity.uid }, customer_details: { email: identity.email },
        amount_total: 999, currency: "eur"
    };
    const items = [{ price: { id: "price_ABC" }, quantity: 1, amount_total: 999 }];
    assert.equal(checkoutMatchesIdentity(session, identity, "price_ABC", items), true);
    assert.equal(checkoutMatchesIdentity({ ...session, payment_status: "unpaid" },
        identity, "price_ABC", items), false);
    assert.equal(checkoutMatchesIdentity(session, identity, "price_OTHER", items), false);
    assert.equal(checkoutMatchesIdentity(session, identity, "price_ABC", [
        { ...items[0], quantity: 2 }
    ]), false);
});
test("refund revocation matches the originating payment only", () => {
    const license = { status: "active", stripePaymentIntentId: "pi_abc" };
    assert.equal(canRevokeThisLicense(license, "pi_abc"), true);
    assert.equal(canRevokeThisLicense(license, "pi_different"), false);
});
