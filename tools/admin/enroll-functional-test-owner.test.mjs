import test from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./enroll-functional-test-owner.mjs', import.meta.url));
const key = 'two-owner-test-key-example-very-long-0123456789';
const first = 'sample.first@gmail.com';
const second = 'sample.second@gmail.com';
const digest = (email) => createHmac('sha256', key).update(email, 'utf8').digest('hex');
const goodEnvironment = {
    ...process.env,
    CAMARODIAG_OWNER_GOOGLE_EMAILS: first + ',' + second,
    CAMARODIAG_OWNER_HMAC_KEY: key
};
function run(mode, overrides = {}) {
    return spawnSync(process.execPath, [script, mode], {
        encoding: 'utf8',
        env: { ...goodEnvironment, ...overrides }
    });
}

test('fingerprints exactly two distinct addresses without exposing either email', () => {
    const result = run('fingerprint');
    assert.equal(result.status, 0);
    assert.deepEqual(result.stdout.trim().split('\n'), [
        '1:' + digest(first),
        '2:' + digest(second)
    ]);
    assert.equal(result.stdout.includes(first), false);
    assert.equal(result.stdout.includes(second), false);
});

test('case and surrounding whitespace are normalized consistently', () => {
    const result = run('fingerprint', {
        CAMARODIAG_OWNER_GOOGLE_EMAILS: '  SAMPLE.FIRST@gmail.com  ,  sample.second@GMAIL.COM '
    });
    assert.equal(result.status, 0);
    assert.deepEqual(result.stdout.trim().split('\n'), [
        '1:' + digest(first),
        '2:' + digest(second)
    ]);
});

test('zero, one, duplicate or three accounts are rejected', () => {
    for (const emails of ['', first, first + ',' + first,
        first + ',' + second + ',sample.third@gmail.com']) {
        const result = run('fingerprint', { CAMARODIAG_OWNER_GOOGLE_EMAILS: emails });
        assert.equal(result.status, 2);
        assert.equal(result.stdout, '');
    }
});

test('grant with missing or mismatched HMACs is rejected before contacting Firebase', () => {
    for (const hashes of ['', digest(first), digest(first) + ',' + digest(first),
        digest(second) + ',' + digest(first)]) {
        const result = run('grant', { CAMARODIAG_OWNER_EXPECTED_HMACS: hashes });
        assert.equal(result.status, 3);
        assert.equal(result.stdout, '');
        assert.match(result.stderr, /fingerprint mismatch/);
    }
});

test('insufficient HMAC key length is rejected', () => {
    const result = run('fingerprint', { CAMARODIAG_OWNER_HMAC_KEY: 'short' });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /at least 32/);
});
