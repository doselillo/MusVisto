// Tests de las reglas de seguridad RTDB (../database.rules.json) contra el emulador.
// Ejecuta desde firebase-tests/:  npm install  &&  npm test
// Requiere JDK 11+ en el PATH (usa el JBR de Android Studio: ver README.md).
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import { ref, set, update, get } from 'firebase/database';
import { readFileSync } from 'node:fs';

const host = process.env.FIREBASE_DATABASE_EMULATOR_HOST || '127.0.0.1:9000';
const [emHost, emPort] = host.split(':');

const testEnv = await initializeTestEnvironment({
  projectId: 'mus-visto',
  database: {
    rules: readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'),
    host: emHost,
    port: Number(emPort),
  },
});

// --- Semilla: una sala de ejemplo escrita como ADMIN (saltándose las reglas). ---
// alice = host (p1) · bob = p2 · p3 vacío · p4 = IA.
await testEnv.withSecurityRulesDisabled(async (ctx) => {
  const db = ctx.database();
  await set(ref(db, 'codes/ABCD'), 'ROOM1');
  await set(ref(db, 'rooms/ROOM1'), {
    meta: { hostUid: 'alice', status: 'lobby', code: 'ABCD', createdAt: 0, settingsJson: '{}' },
    seats: {
      p1: { team: 'teamA', uid: 'alice', displayName: 'Alice', isAi: false, ready: true, connected: true },
      p2: { team: 'teamB', uid: 'bob', displayName: 'Bob', isAi: false, ready: false, connected: true },
      p3: { team: 'teamA', uid: null, displayName: '', isAi: false, ready: false, connected: false },
      p4: { team: 'teamB', uid: null, displayName: 'IA', isAi: true, ready: true, connected: true },
    },
  });
});

const alice = testEnv.authenticatedContext('alice').database();     // host + p1
const bob = testEnv.authenticatedContext('bob').database();         // p2
const carol = testEnv.authenticatedContext('carol').database();     // sin asiento (se unirá)
const dave = testEnv.authenticatedContext('dave').database();       // crea sala nueva
const mallory = testEnv.authenticatedContext('mallory').database(); // atacante
const anon = testEnv.unauthenticatedContext().database();

let pass = 0;
let fail = 0;
async function expect(name, shouldAllow, op) {
  try {
    await (shouldAllow ? assertSucceeds(op()) : assertFails(op()));
    console.log(`  ✓ ${name}`);
    pass++;
  } catch (e) {
    console.log(`  ✗ ${name}  — ${String(e.message).split('\n')[0]}`);
    fail++;
  }
}

console.log('PERMITIDOS:');
await expect('bob lee la sala', true, () => get(ref(bob, 'rooms/ROOM1')));
await expect('bob togglea su propio ready', true, () => set(ref(bob, 'rooms/ROOM1/seats/p2/ready'), true));
await expect('bob actualiza su propio connected', true, () => set(ref(bob, 'rooms/ROOM1/seats/p2/connected'), false));
await expect('bob manda su propia accion', true, () => set(ref(bob, 'rooms/ROOM1/actions/p2'), '{}'));
await expect('alice (host) publica una vista', true, () => set(ref(alice, 'rooms/ROOM1/views/p2'), '{}'));
await expect('alice (host) cambia el status', true, () => set(ref(alice, 'rooms/ROOM1/meta/status'), 'playing'));
await expect('carol reclama el asiento vacio p3', true, () =>
  update(ref(carol, 'rooms/ROOM1/seats'), { 'p3/uid': 'carol', 'p3/displayName': 'Carol', 'p3/connected': true }));
await expect('dave crea una sala nueva siendo el host', true, () =>
  set(ref(dave, 'rooms/ROOM2'), { meta: { hostUid: 'dave', status: 'lobby' }, seats: { p1: { uid: 'dave', isAi: false } } }));

console.log('DENEGADOS:');
await expect('anonimo NO lee la sala', false, () => get(ref(anon, 'rooms/ROOM1')));
await expect('bob NO manda la accion de p1 (ajena)', false, () => set(ref(bob, 'rooms/ROOM1/actions/p1'), 'x'));
await expect('bob NO publica vistas (no es host)', false, () => set(ref(bob, 'rooms/ROOM1/views/p1'), 'x'));
await expect('bob NO cambia el status (no es host)', false, () => set(ref(bob, 'rooms/ROOM1/meta/status'), 'finished'));
await expect('mallory NO suplanta el uid de p1', false, () => set(ref(mallory, 'rooms/ROOM1/seats/p1/uid'), 'mallory'));
await expect('mallory NO expulsa a bob (p2.uid=null)', false, () => set(ref(mallory, 'rooms/ROOM1/seats/p2/uid'), null));
await expect('carol NO reclama un asiento ocupado (p2)', false, () => set(ref(carol, 'rooms/ROOM1/seats/p2/uid'), 'carol'));
await expect('mallory NO secuestra el codigo ABCD', false, () => set(ref(mallory, 'codes/ABCD'), 'ROOM2'));
await expect('connected como string viola .validate', false, () => set(ref(bob, 'rooms/ROOM1/seats/p2/connected'), 'true'));
await expect('dave NO crea sala poniendo a otro de host', false, () =>
  set(ref(dave, 'rooms/ROOM3'), { meta: { hostUid: 'eve', status: 'lobby' } }));

await testEnv.cleanup();
console.log(`\n${pass} OK, ${fail} fallos`);
process.exit(fail ? 1 : 0);
