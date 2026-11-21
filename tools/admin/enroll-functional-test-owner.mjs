#!/usr/bin/env node
/**
 * Private owner provisioning; never bundles email or hashing key into Android.
 *
 * Mode "fingerprint": print HMAC-SHA256(normalized owner Google email).
 * Mode "grant": compare against independently configured expected HMAC,
 * remove this privileged claim from all other Firebase users, and grant it
 * only to the owner Google account. GOOGLE_APPLICATION_CREDENTIALS must
 * point to an administrator service account OUTSIDE the source tree.
 *
 * Environment:
 * CAMARODIAG_OWNER_GOOGLE_EMAIL  (runtime only; not committed)
 * CAMARODIAG_OWNER_HMAC_KEY      (>=32 chars; runtime only)
 * CAMARODIAG_OWNER_EXPECTED_HMAC (64-hex, grant mode)
 */
import { createHmac, timingSafeEqual } from 'node:crypto';

const mode = process.argv[2];
const email = (process.env.CAMARODIAG_OWNER_GOOGLE_EMAIL ?? '')
    .trim().normalize('NFKC').toLowerCase();
const key = process.env.CAMARODIAG_OWNER_HMAC_KEY ?? '';
if (!email.endsWith('@gmail.com') && !email.endsWith('@googlemail.com')) {
    console.error('A Google Gmail address must be supplied privately.');
    process.exit(2);
}
if (key.length < 32) {
    console.error('The private HMAC key must contain at least 32 characters.');
    process.exit(2);
}
const digest = createHmac('sha256', key).update(email, 'utf8').digest('hex');
if (mode === 'fingerprint') {
    console.log(digest);
    process.exit(0);
}
if (mode !== 'grant') {
    console.error('Usage: node enroll-functional-test-owner.mjs fingerprint|grant');
    process.exit(2);
}
const expected = (process.env.CAMARODIAG_OWNER_EXPECTED_HMAC ?? '').toLowerCase();
if (!/^[0-9a-f]{64}$/.test(expected) ||
    !timingSafeEqual(Buffer.from(digest, 'hex'), Buffer.from(expected, 'hex'))) {
    console.error('Owner HMAC mismatch: refusing any account changes.');
    process.exit(3);
}
try {
    const { default: admin } = await import('firebase-admin');
    if (!admin.apps.length) admin.initializeApp({
        credential: admin.credential.applicationDefault()
    });
    const auth = admin.auth();
    const target = await auth.getUserByEmail(email);
    if (!target.providerData.some((p) => p.providerId === 'google.com')) {
        console.error('Owner must link the authentic Google sign-in provider first.');
        process.exit(4);
    }
    // Fail closed: any previous privileged owner is revoked, preserving other claims.
    let nextPageToken;
    do {
        const page = await auth.listUsers(1000, nextPageToken);
        for (const user of page.users) {
            if (user.uid !== target.uid && user.customClaims?.owner_functional_tests === true) {
                const claims = { ...user.customClaims };
                delete claims.owner_functional_tests;
                await auth.setCustomUserClaims(user.uid, claims);
            }
        }
        nextPageToken = page.pageToken;
    } while (nextPageToken);
    await auth.setCustomUserClaims(target.uid, {
        ...target.customClaims,
        owner_functional_tests: true
    });
    console.log('Owner entitlement provisioned for one Firebase UID. No email disclosed.');
} catch {
    console.error('Provisioning failed. Verify Firebase Admin credentials and project access.');
    process.exit(5);
}
