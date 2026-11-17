import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { initializeApp, deleteApp } from 'firebase/app';
import { getAuth, connectAuthEmulator, createUserWithEmailAndPassword } from 'firebase/auth';
import { getFirestore, connectFirestoreEmulator, doc, setDoc, getDoc, getDocs, collection, query, where, updateDoc, writeBatch, serverTimestamp, setLogLevel } from 'firebase/firestore';
setLogLevel('silent'); // Negative permission assertions are expected; test failures still report full errors.

let env;
const apps = [];
const profile = (uid, role = 1) => ({ uid, email: `${uid}@senyas.test`, name: uid, role, active: true });
const client = uid => env.authenticatedContext(uid, { email: `${uid}@senyas.test` }).firestore();
const start = () => {
  const date = new Date(Date.now() + 86400000);
  date.setUTCHours(2, 17, 0, 0); // 10:17 PHT: arbitrary minute, not a half-hour-only grid.
  return date.getTime();
};
const lesson = (id = 1001, overrides = {}) => ({ id, tutorId: 'tutor', tutor: 'tutor', title: 'Math in FSL', category: 'K-12 Academic Support', description: 'A real lesson', price: 600, duration: 60, available: true, reviewed: true, days: ['Tomorrow'], slots: '10:17 am', offers: [start()], city: 'Quezon City', distanceKm: 0, tileColor: -14796104, darkText: false, hasCover: false, hasPreview: false, previewSeconds: 0, ...overrides });
const booking = (reference = 'SEN-test1', overrides = {}) => ({ reference, lessonId: 1001, learnerId: 'student', tutorId: 'tutor', learner: 'student', tutor: 'tutor', title: 'Math in FSL', category: 'K-12 Academic Support', startAtMillis: start(), endAtMillis: start() + 1800000, duration: 30, price: 300, paymentKind: 'GCash', paymentLast4: '', cancelled: false, released: false, createdAt: serverTimestamp(), ...overrides });
const dayId = b => `${b.tutorId}_${Math.floor((b.startAtMillis + 28800000) / 86400000)}`;
const minuteKeys = b => Array.from({ length: b.duration }, (_, i) => String(Math.floor(b.startAtMillis / 60000) + i));
const claims = (b, busy = {}) => ({ tutorId: b.tutorId, day: Math.floor((b.startAtMillis + 28800000) / 86400000), bookingId: b.reference, busy: { ...busy, ...Object.fromEntries(minuteKeys(b).map(k => [k, true])) } });

async function seed(records = {}) {
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    for (const [path, value] of Object.entries(records)) await setDoc(doc(db, path), value);
  });
}
async function reserve(db, b, oldBusy = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, 'bookings', b.reference), b);
  batch.set(doc(db, 'tutorDays', dayId(b)), claims(b, oldBusy));
  return batch.commit();
}
async function cancel(db, b, busy) {
  const remaining = { ...busy };
  for (const key of minuteKeys(b)) delete remaining[key];
  const batch = writeBatch(db);
  batch.update(doc(db, 'bookings', b.reference), { cancelled: true });
  batch.set(doc(db, 'tutorDays', dayId(b)), { ...claims(b), busy: remaining });
  return batch.commit();
}

before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-senyas', firestore: { host: '127.0.0.1', port: 8180, rules: await readFile('firestore.rules', 'utf8') } }); });
after(async () => { await Promise.all(apps.map(deleteApp)); await env.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await seed({ 'profiles/student': profile('student'), 'profiles/other': profile('other'), 'profiles/tutor': profile('tutor', 2), 'profiles/admin': profile('admin', 3), 'lessons/1001': lesson() });
});

test('registration creates only its own non-admin profile and keeps other profiles private', async () => {
  const db = client('new-user');
  await assertFails(setDoc(doc(db, 'profiles', 'new-user'), profile('new-user', 3)));
  await assertSucceeds(setDoc(doc(db, 'profiles', 'new-user'), profile('new-user', 2)));
  await assertFails(updateDoc(doc(db, 'profiles', 'new-user'), { role: 3 }));
  await assertFails(getDoc(doc(client('student'), 'profiles', 'tutor')));
  await assertSucceeds(getDoc(doc(client('admin'), 'profiles', 'student')));
});

test('pending lessons need a separate active administrator before students see them', async () => {
  await assertSucceeds(setDoc(doc(client('tutor'), 'lessons', '1002'), lesson(1002, { reviewed: false })));
  await assertFails(updateDoc(doc(client('tutor'), 'lessons', '1002'), { reviewed: true }));
  await assertFails(getDoc(doc(client('student'), 'lessons', '1002')));
  assert.equal((await getDocs(query(collection(client('student'), 'lessons'), where('reviewed', '==', true)))).size, 1);
  await assertFails(updateDoc(doc(client('student'), 'lessons', '1002'), { reviewed: true }));
  await assertSucceeds(updateDoc(doc(client('admin'), 'lessons', '1002'), { reviewed: true }));
  await assertSucceeds(getDoc(doc(client('student'), 'lessons', '1002')));
  await assertFails(updateDoc(doc(client('tutor'), 'lessons', '1002'), { price: 1 }));
  await assertSucceeds(updateDoc(doc(client('tutor'), 'lessons', '1002'), { price: 700, reviewed: false }));
});

test('an arbitrary-minute booking atomically locks every occupied minute', async () => {
  const b = booking();
  await assertFails(setDoc(doc(client('student'), 'bookings', b.reference), b));
  await assertSucceeds(reserve(client('student'), b));
  await assertSucceeds(getDoc(doc(client('tutor'), 'bookings', b.reference)));
  await assertFails(getDoc(doc(client('other'), 'bookings', b.reference)));
  await assertFails(updateDoc(doc(client('student'), 'bookings', b.reference), { price: 1 }));
});

test('transactions can check unused references without reading another learner booking', async () => {
  assert.equal((await assertSucceeds(getDoc(doc(client('tutor'), 'lessons', '991234')))).exists(), false);
  assert.equal((await assertSucceeds(getDoc(doc(client('student'), 'bookings', 'SEN-unused')))).exists(), false);
  await reserve(client('student'), booking());
  await assertFails(getDoc(doc(client('other'), 'bookings', 'SEN-test1')));
});

test('a full sixty-minute booking locks all minutes and cannot cross midnight', async () => {
  await assertSucceeds(reserve(client('student'), booking('SEN-hour', { duration: 60, price: 600, endAtMillis: start() + 3600000 })));
  const lateStart = start() + (13 * 60 + 13) * 60000; // 23:30 PHT.
  await seed({ 'lessons/1002': lesson(1002, { offers: [lateStart] }) });
  await assertFails(reserve(client('other'), booking('SEN-overnight', { lessonId: 1002, learnerId: 'other', learner: 'other', duration: 60, price: 600, startAtMillis: lateStart, endAtMillis: lateStart + 3600000 })));
});

test('only one of two simultaneous conflicting reservations can commit', async () => {
  const outcomes = await Promise.allSettled([
    reserve(client('student'), booking('SEN-raceA')),
    reserve(client('other'), booking('SEN-raceB', { learnerId: 'other', learner: 'other' }))
  ]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal((await getDocs(collection(client('admin'), 'bookings'))).size, 1);
});

test('price, tutor, offered time, owner and duration cannot be forged', async () => {
  for (const [index, change] of [{ price: 1 }, { tutorId: 'other' }, { startAtMillis: start() + 60000, endAtMillis: start() + 1860000 }, { learnerId: 'other' }, { duration: 120, endAtMillis: start() + 7200000 }].entries()) {
    await assertFails(reserve(client('student'), booking(`SEN-forged${index}`, change)));
  }
  await seed({ 'lessons/1001': lesson(1001, { offers: [Date.now() - 3600000] }) });
  await assertFails(reserve(client('student'), booking('SEN-past', { startAtMillis: Date.now() - 3600000, endAtMillis: Date.now() - 1800000 })));
});

test('overlaps across different tutor listings are rejected, including partial overlaps', async () => {
  const first = booking();
  await reserve(client('student'), first);
  const secondStart = first.startAtMillis + 900000;
  await seed({ 'lessons/1002': lesson(1002, { offers: [secondStart] }) });
  const second = booking('SEN-conflict', { lessonId: 1002, learnerId: 'other', learner: 'other', startAtMillis: secondStart, endAtMillis: secondStart + 1800000 });
  await assertFails(reserve(client('other'), second, claims(first).busy));
  await assertFails(reserve(client('other'), second)); // Cannot overwrite the existing occupancy map.
  const adjacent = booking('SEN-adjacent', { learnerId: 'other', learner: 'other', lessonId: 1002, startAtMillis: first.endAtMillis, endAtMillis: first.endAtMillis + 1800000 });
  await seed({ 'lessons/1002': lesson(1002, { offers: [adjacent.startAtMillis] }) });
  await assertSucceeds(reserve(client('other'), adjacent, claims(first).busy));
});

test('only the owner can cancel; cancellation frees exactly its minutes for rebooking', async () => {
  const b = booking(); await reserve(client('student'), b);
  await assertFails(cancel(client('other'), b, claims(b).busy));
  await assertFails(updateDoc(doc(client('student'), 'bookings', b.reference), { cancelled: true }));
  await assertFails(setDoc(doc(client('student'), 'tutorDays', dayId(b)), { ...claims(b), busy: {} }));
  await assertSucceeds(cancel(client('student'), b, claims(b).busy));
  await assertFails(cancel(client('student'), b, {}));
  await assertSucceeds(reserve(client('other'), booking('SEN-rebook', { learnerId: 'other', learner: 'other' })));
});

test('suspension blocks an existing authenticated client and clients cannot provision admins', async () => {
  const student = client('student');
  await assertFails(updateDoc(doc(student, 'profiles', 'student'), { active: false }));
  await assertSucceeds(updateDoc(doc(client('admin'), 'profiles', 'student'), { active: false }));
  await assertFails(getDoc(doc(student, 'lessons', '1001')));
  await assertFails(reserve(student, booking()));
  await assertSucceeds(getDoc(doc(student, 'profiles', 'student'))); // Explain suspension on login.
  await assertFails(updateDoc(doc(client('admin'), 'profiles', 'admin'), { role: 1 }));
});

test('progress and read markers belong only to the authenticated learner', async () => {
  const progress = { scenario: 0, index: 0, scores: Array(10).fill(-1), path: { '0': Array(10).fill(-1), '1': Array(5).fill(-1), '2': Array(5).fill(-1) }, xp: 0, dailyXp: 0, streak: 0, rewarded: [], day: '2026-11-05' };
  await assertSucceeds(setDoc(doc(client('student'), 'progress', 'student'), progress));
  await assertFails(getDoc(doc(client('other'), 'progress', 'student')));
  await assertFails(setDoc(doc(client('other'), 'progress', 'student'), progress));
  await assertFails(setDoc(doc(client('tutor'), 'progress', 'tutor'), progress));
  await assertFails(setDoc(doc(client('student'), 'progress', 'student'), { ...progress, xp: -1 }));
  await assertSucceeds(setDoc(doc(client('student'), 'profiles', 'student', 'notificationReads', 'booking-one'), { read: true }));
  await assertFails(setDoc(doc(client('other'), 'profiles', 'student', 'notificationReads', 'booking-one'), { read: true }));
});

test('the server rejects early or unrelated classroom attendance', async () => {
  const b = booking(); await reserve(client('student'), b);
  const attendance = db => doc(db, 'attendance', b.reference, 'participants', 'student');
  await assertFails(setDoc(attendance(client('student')), { uid: 'student', joinedAt: serverTimestamp() }));
  await seed({ [`bookings/${b.reference}`]: { ...b, createdAt: new Date(), startAtMillis: Date.now() - 60000, endAtMillis: Date.now() + 60000 } });
  await assertSucceeds(setDoc(attendance(client('student')), { uid: 'student', joinedAt: serverTimestamp() }));
  await assertFails(setDoc(doc(client('other'), 'attendance', b.reference, 'participants', 'other'), { uid: 'other', joinedAt: serverTimestamp() }));
});

test('payout release requires an administrator and an ended, uncancelled session', async () => {
  const b = booking(); await reserve(client('student'), b);
  await assertFails(updateDoc(doc(client('admin'), 'bookings', b.reference), { released: true }));
  await seed({ [`bookings/${b.reference}`]: { ...b, createdAt: new Date(), startAtMillis: Date.now() - 3600000, endAtMillis: Date.now() - 1800000 } });
  await assertFails(updateDoc(doc(client('tutor'), 'bookings', b.reference), { released: true }));
  await assertSucceeds(updateDoc(doc(client('admin'), 'bookings', b.reference), { released: true }));
});

test('two independent Firebase Auth clients share approved catalogue data', async () => {
  const makeClient = async prefix => {
    const app = initializeApp({ projectId: 'demo-senyas', apiKey: 'fake-emulator-key', appId: `1:123:android:${prefix}` }, `${prefix}-${Date.now()}`);
    apps.push(app);
    const auth = getAuth(app); connectAuthEmulator(auth, 'http://127.0.0.1:9199', { disableWarnings: true });
    const db = getFirestore(app); connectFirestoreEmulator(db, '127.0.0.1', 8180);
    const email = `${prefix}-${Date.now()}@senyas.test`;
    const uid = (await createUserWithEmailAndPassword(auth, email, 'Emulator-only-123')).user.uid;
    return { db, uid, email };
  };
  const tutor = await makeClient('tutor'), learner = await makeClient('learner');
  await assertSucceeds(setDoc(doc(tutor.db, 'profiles', tutor.uid), { ...profile(tutor.uid, 2), email: tutor.email }));
  await assertSucceeds(setDoc(doc(learner.db, 'profiles', learner.uid), { ...profile(learner.uid), email: learner.email }));
  await assertSucceeds(setDoc(doc(tutor.db, 'lessons', '2001'), lesson(2001, { tutorId: tutor.uid, tutor: tutor.uid, reviewed: false })));
  await assertFails(getDoc(doc(learner.db, 'lessons', '2001')));
  await assertSucceeds(updateDoc(doc(client('admin'), 'lessons', '2001'), { reviewed: true }));
  assert.equal((await getDoc(doc(learner.db, 'lessons', '2001'))).data().tutorId, tutor.uid);
});
