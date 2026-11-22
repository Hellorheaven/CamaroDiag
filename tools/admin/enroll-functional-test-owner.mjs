#!/usr/bin/env node
/**
 * Private provisioning for exactly TWO authorized Google accounts.
 * Emails, the HMAC key, the HMAC allowlist and Firebase credentials are
 * provided only at runtime and MUST NEVER be committed to source control.
 *
 * fingerprint: print indexed, secret-keyed HMAC-SHA256 fingerprints (no emails).
 * grant: require matching fingerprints, validate both real Google
 * identities BEFORE changing claims, revoke this claim from every other UID,
 * and grant the signed Firebase owner_functional_tests claim to both.
 *
 * Environment (outside source control):
 * CAMARODIAG_OWNER_GOOGLE_EMAILS  two Gmail addresses, comma-separated
 * CAMARODIAG_OWNER_HMAC_KEY       secret key, at least 32 characters
 * CAMARODIAG_OWNER_EXPECTED_HMACS two corresponding 64-hex HMACs, comma-separated
 * GOOGLE_APPLICATION_CREDENTIALS Firebase Admin service account path
 *
 * Never log account emails, tokens, secrets or UIDs.
 */
import { createHmac, timingSafeEqual } from 'node:crypto';
import { createRequire } from 'node:module';

// Resolve the SDK from the Firebase Functions package that declares it.
const requireFromFunctions = createRequire(new URL('../../functions/package.json', import.meta.url));

const REQUIRED_OWNER_COUNT = 2;
const OWNER_CLAIM = 'owner_functional_tests';
const mode = process.argv[2];

function fail(message, code = 2) {
    console.error(message);
    process.exit(code);
}

if (!['fingerprint', 'grant'].includes(mode)) {
    fail('Usage: node tools/admin/enroll-functional-test-owner.mjs fingerprint|grant');
}

const splitPair = (value) => (value ?? '')
    .split(',')
    .map((part) => part.trim())
    .filter((part) => part !== '');

const emails = splitPair(process.env.CAMARODIAG_OWNER_GOOGLE_EMAILS)
    .map((email) => email.normalize('NFKC').toLowerCase());
const key = process.env.CAMARODIAG_OWNER_HMAC_KEY ?? '';

if (emails.length !== REQUIRED_OWNER_COUNT ||
    new Set(emails).size !== REQUIRED_OWNER_COUNT ||
    !emails.every((email) => /^[^\s@,]+@(gmail\.com|googlemail\.com)$/.test(email))) {
    fail('Expected exactly two distinct valid Gmail accounts in the private environment.');
}
if (key.length < 32) {
    fail('The private HMAC key must contain at least 32 characters.');
}

const fingerprints = emails.map((email) =>
    createHmac('sha256', key).update(email, 'utf8').digest('hex')
);

if (mode === 'fingerprint') {
    fingerprints.forEach((digest, index) => console.log((index + 1) + ':' + digest));
    process.exit(0);
}

const expected = splitPair(process.env.CAMARODIAG_OWNER_EXPECTED_HMACS)
    .map((digest) => digest.toLowerCase());

if (expected.length !== REQUIRED_OWNER_COUNT ||
    !expected.every((value) => /^[0-9a-f]{64}$/.test(value)) ||
    new Set(expected).size !== REQUIRED_OWNER_COUNT ||
    !fingerprints.every((digest, index) =>
        timingSafeEqual(Buffer.from(digest, 'hex'), Buffer.from(expected[index], 'hex'))
    )
) {
    fail('Private owner fingerprint mismatch. No Firebase changes were made.', 3);
}

try {
    const admin = requireFromFunctions('firebase-admin');
    if (!admin.apps.length) {
        admin.initializeApp({ credential: admin.credential.applicationDefault() });
    }
    const auth = admin.auth();

    // Validate ALL intended accounts before revoking or granting anybody.
    const targetAccounts = await Promise.all(
        emails.map((email) => auth.getUserByEmail(email))
    );
    if (new Set(targetAccounts.map((user) => user.uid)).size !== REQUIRED_OWNER_COUNT ||
        !targetAccounts.every((user, index) =>
            !user.disabled && user.emailVerified === true &&
            (user.email ?? '').normalize('NFKC').toLowerCase() === emails[index] &&
            user.providerData.some((provider) => provider.providerId === 'google.com')
        )
    ) {
        fail('Both Google-backed Firebase accounts must exist and be verified first.', 4);
    }

    const allowedUids = new Set(targetAccounts.map((user) => user.uid));
    let nextPageToken;
    do {
        const page = await auth.listUsers(1000, nextPageToken);
        for (const user of page.users) {
            if (!allowedUids.has(user.uid) &&
                user.customClaims?.[OWNER_CLAIM] === true) {
                const claims = { ...user.customClaims };
                delete claims[OWNER_CLAIM];
                await auth.setCustomUserClaims(user.uid, claims);
            }
        }
        nextPageToken = page.pageToken;
    } while (nextPageToken);

    for (const account of targetAccounts) {
        await auth.setCustomUserClaims(account.uid, {
            ...account.customClaims,
            [OWNER_CLAIM]: true
        });
    }
    console.log('Owner access provisioned for exactly two verified Google accounts.');
} catch {
    fail('Firebase provisioning failed: verify project, admin privileges and accounts.', 5);
}
