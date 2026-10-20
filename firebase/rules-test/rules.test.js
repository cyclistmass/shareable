import { before, after, beforeEach, test } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, collection, query, where, getDoc, getDocs, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, onSnapshot, orderBy, disableNetwork, enableNetwork, Timestamp } from 'firebase/firestore';
let env;
const seat = (uid=null, name='', bot=null, open=true, ready=false) => ({uid,name,bot,open,ready});
const room = (status='lobby') => ({host:'host', status, seed:'42', nextSeq:0, lastMove:'', decks:0, version:1, createdAt:serverTimestamp(), options:{expansion:'BASE',numPlayers:2,vpToWin:10,layout:'RANDOM',avoidAdjacentRedNumbers:true,discardLimit:7,maxTurns:1000,randomDiscard:false,generateTradeOffers:false}, seats:{'0':seat('host','Host',null,false,true),'1':seat(),'2':seat(null,'','GREEDY',false),'3':seat(null,'',null,false)}});
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const ref = uid => doc(db(uid),'games/g');
async function seed(status='lobby') { await env.withSecurityRulesDisabled(async c => { const r=room(status); if(status !== 'lobby') r.seats['1']=seat('guest','Guest',null,false,true); await setDoc(doc(c.firestore(),'games/g'),r); }); }
async function append(uid='host', seq=0, s='0', id=String(seq).padStart(10,'0'), action='1:BUILD_SETTLEMENT:0') {
  return setDoc(doc(db(uid),`games/g/moves/${id}`), {seq,seat:s,uid,action,prev:seq===0?'':String(seq-1).padStart(10,'0')});
}before(async () => { env=await initializeTestEnvironment({projectId:'demo-wgbg',firestore:{rules:readFileSync('../../firestore.rules','utf8'),host:'127.0.0.1',port:Number((process.env.FIRESTORE_EMULATOR_HOST||'').split(':')[1]||8080)}}); });
after(async()=>{await env.cleanup();});
beforeEach(async()=>{await env.clearFirestore();});
test('signed-in host creates; anonymous, forged host, playing create denied',async()=>{await assertSucceeds(setDoc(ref('host'),room())); await assertFails(setDoc(doc(db(null),'games/no'),room())); await assertFails(setDoc(doc(db('other'),'games/no'),room())); await assertFails(setDoc(doc(db('host'),'games/no'),room('playing')));});
test('room versions: 1 (0.2.9), 2 (0.2.10, random first player) and 3 (0.3.4, friends) create; 0, 4 and a string denied',async()=>{await assertSucceeds(setDoc(doc(db('host'),'games/v1'),room())); await assertSucceeds(setDoc(doc(db('host'),'games/v2'),{...room(),version:2})); await assertSucceeds(setDoc(doc(db('host'),'games/v3'),{...room(),version:3})); for(const version of [0,4,'2'])await assertFails(setDoc(doc(db('host'),`games/v${version}x`),{...room(),version}));});
test('a free room code can be checked before creating (the app reads it in a transaction); unsigned denied',async()=>{await assertSucceeds(getDoc(doc(db('host'),'games/free'))); await assertFails(getDoc(doc(db(null),'games/free')));});
test('lobby read and filtered listing; unsigned denied',async()=>{await seed(); await assertSucceeds(getDoc(ref('other'))); await assertSucceeds(getDocs(query(collection(db('other'),'games'),where('status','==','lobby')))); await assertFails(getDoc(ref(null)));});
test('playing and done visible only to seated players',async()=>{for(const status of ['playing','done']){await seed(status);await assertSucceeds(getDoc(ref('host')));await assertSucceeds(getDoc(ref('guest')));await assertFails(getDoc(ref('other')));await assertFails(getDoc(ref(null)));}});
test('self claim empty seat, ready, leave',async()=>{await seed();const s=seat('guest','Guest',null,false,false);await assertSucceeds(updateDoc(ref('guest'),{'seats.1':s}));await assertSucceeds(updateDoc(ref('guest'),{'seats.1.ready':true}));await assertSucceeds(updateDoc(ref('guest'),{'seats.1':seat()}));});
test('claim forged uid, reserved bot, host seat, occupied seat and second seat denied',async()=>{await seed();await assertFails(updateDoc(ref('guest'),{'seats.1':seat('other','Other',null,false)}));await assertFails(updateDoc(ref('guest'),{'seats.2':seat('guest','Guest','GREEDY',false)}));await assertFails(updateDoc(ref('guest'),{'seats.0':seat('guest','Guest',null,false)}));await assertSucceeds(updateDoc(ref('guest'),{'seats.1':seat('guest','Guest',null,false)}));await assertFails(updateDoc(ref('other'),{'seats.1':seat('other','Other',null,false)}));await assertFails(updateDoc(ref('guest'),{'seats.3':seat('guest','Guest',null,false)}));});
test('nonhost cannot edit others, bot, options, name, or status',async()=>{await seed();await assertSucceeds(updateDoc(ref('guest'),{'seats.1':seat('guest','Guest',null,false)}));for(const patch of [{'seats.0.ready':false},{'seats.1.bot':'EASY'},{'seats.1.name':'Changed'},{'options.vpToWin':8},{status:'playing'}])await assertFails(updateDoc(ref('guest'),patch));});
test('host edits lobby seats and starts; immutable metadata and delete denied',async()=>{await seed();await assertSucceeds(updateDoc(ref('host'),{'seats.2.bot':'EASY'}));await assertSucceeds(updateDoc(ref('host'),{status:'playing'}));for(const patch of [{host:'other'},{seed:'7'},{decks:1},{'seats.2.bot':'STRONG'}])await assertFails(updateDoc(ref('host'),patch));await assertFails(deleteDoc(ref('host')));await assertSucceeds(updateDoc(ref('host'),{status:'done'}));await assertFails(updateDoc(ref('host'),{status:'playing'}));});
test('single move create is ordered and immutable; no counter write',async()=>{await seed('playing');await assertSucceeds(append());await assertSucceeds(getDoc(doc(db('guest'),'games/g/moves/0000000000')));await assertFails(getDoc(doc(db('other'),'games/g/moves/0000000000')));await assertFails(updateDoc(doc(db('host'),'games/g/moves/0000000000'),{action:'1:ROLL:0'}));await assertFails(deleteDoc(doc(db('host'),'games/g/moves/0000000000')));await assertFails(append());});
test('guest writes own seat; host writes bot; host cannot impersonate person',async()=>{await seed('playing');await assertSucceeds(append('guest',0,'1'));await assertSucceeds(append('host',1,'2'));await assertFails(append('host',2,'1'));});
test('unauthenticated, unseated, wrong seat, skipped sequence, invalid id and garbage denied',async()=>{await seed('playing');await assertFails(append(null));await assertFails(append('other'));await assertFails(append('guest',0,'0'));await assertFails(append('host',2));await assertFails(append('host',0,'0','0'));await assertFails(append('host',0,'0','0000000001'));await assertFails(append('host',0,'0','0000000000','garbage'));});
test('standalone move succeeds; forged room counter denied',async()=>{await seed('playing');await assertSucceeds(append());await assertFails(updateDoc(ref('host'),{nextSeq:1,lastMove:'0000000000'}));});test('lobby and done reject moves; deny unknown paths and extra fields',async()=>{await seed();await assertFails(append());await seed('done');await assertFails(append());await assertFails(setDoc(doc(db('host'),'unknown/a'),{}));await assertFails(updateDoc(ref('host'),{extra:true}));});
test('a guest quits a running game: the seat goes to a bot the host plays; nobody else can quit it',async()=>{await seed('playing');
  const bot=seat(null,'','GREEDY',false,false);
  await assertFails(updateDoc(ref('other'),{'seats.1':bot}));
  await assertFails(updateDoc(ref('guest'),{'seats.1':seat(null,'',null,false,false)}));
  await assertFails(updateDoc(ref('guest'),{'seats.1':seat(null,'','GREEDY',true,false)}));
  await assertFails(updateDoc(ref('guest'),{'seats.1':bot,'seats.2.bot':'EASY'}));
  await assertFails(updateDoc(ref('host'),{'seats.0':seat(null,'','GREEDY',false,false)}));
  await assertSucceeds(updateDoc(ref('guest'),{'seats.1':bot}));
  await assertSucceeds(append('host',0,'1'));
  await assertFails(append('guest',1,'1'));});
test('two concurrent appends only one succeeds',async()=>{await seed('playing');const r=await Promise.allSettled([append(),append()]);if(r.filter(x=>x.status==='fulfilled').length !== 1)throw Error('append race failed');});
test('all guest seats may claim, ready and leave; lobby log is readable',async()=>{await seed();await assertSucceeds(getDocs(collection(db('other'),'games/g/moves')));for(const k of ['1','2','3']){await assertSucceeds(updateDoc(ref('host'),{['seats.'+k]:seat()}));await assertSucceeds(updateDoc(ref('guest'),{['seats.'+k]:seat('guest','Guest',null,false)}));await assertSucceeds(updateDoc(ref('guest'),{['seats.'+k+'.ready']:true}));await assertSucceeds(updateDoc(ref('guest'),{['seats.'+k]:seat()}));}});
test('missing, forged and malformed predecessors, uid and extra fields denied',async()=>{
  await seed('playing'); await assertSucceeds(append());
  for(const patch of [{uid:'guest'},{extra:true},{seat:'9'},{seq:1.5},{prev:'0000000009'},{prev:'0'},{prev:''}]) {
    await assertFails(setDoc(doc(db('host'),'games/g/moves/0000000001'), {seq:1,seat:'0',uid:'host',action:'1:ROLL:0',prev:'0000000000',...patch}));
  }
});test('two concurrent seat claims only one succeeds',async()=>{await seed();const r=await Promise.allSettled(['guest','other'].map(uid=>updateDoc(ref(uid),{'seats.1':seat(uid,uid,null,false)})));if(r.filter(x=>x.status==='fulfilled').length!==1)throw Error('seat race failed');});


test('warm independent remote listener: measure write to push delivery', async () => {
  await seed('playing');
  const remote = db('guest'); const writer = db('host');
  const samples = []; const starts = new Map(); const waits = new Map();
  let readyResolve; const ready = new Promise(r => { readyResolve=r; });
  const unsubscribe = onSnapshot(query(collection(remote,'games/g/moves'), orderBy('seq'), where('seq','>',-1)),
    {includeMetadataChanges:true}, snapshot => {
      if (!snapshot.metadata.fromCache) readyResolve();
      for (const c of snapshot.docChanges({includeMetadataChanges:true})) {
        if (c.type==='removed' || c.doc.metadata.hasPendingWrites || snapshot.metadata.fromCache) continue;
        const seq = c.doc.data().seq;
        if(starts.has(seq) && waits.has(seq)) { samples.push(performance.now()-starts.get(seq)); waits.get(seq)(); waits.delete(seq); }
      }
    });
  try {
    await ready;
    for(let seq=0;seq<30;seq++) {
      const delivered = new Promise(r=>waits.set(seq,r)); starts.set(seq,performance.now());
      const data={seq,seat:'0',uid:'host',action:'1:ROLL:0',prev:seq===0?'':String(seq-1).padStart(10,'0')};
      await Promise.all([setDoc(doc(writer,`games/g/moves/${String(seq).padStart(10,'0')}`),data),delivered]);
    }
    samples.sort((a,b)=>a-b);
    console.log(`push latency: n=${samples.length}, median=${samples[15].toFixed(3)} ms, p95=${samples[28].toFixed(3)} ms, max=${samples[29].toFixed(3)} ms; payload=${Buffer.byteLength(JSON.stringify({seq:0,seat:'0',uid:'host',action:'1:ROLL:0',prev:''}))} JSON bytes`);
    if(samples.length!==30) throw Error('missing remote delivery');
  } finally { unsubscribe(); }
});

test('pending local echoes confirm once and resume query only returns suffix', async()=>{
  await seed('playing'); const local=db('host');
  let pendingSeen=false, confirmed=0, resolvePending, resolveConfirmed;
  const pending=new Promise(r=>resolvePending=r); const done=new Promise(r=>resolveConfirmed=r);
  const unsubscribe=onSnapshot(collection(local,'games/g/moves'),{includeMetadataChanges:true},snapshot=>{
    for(const c of snapshot.docChanges({includeMetadataChanges:true})) {
      if(c.doc.metadata.hasPendingWrites) {pendingSeen=true;resolvePending();}
      else if(c.type!=='removed' && !snapshot.metadata.fromCache) {confirmed++;resolveConfirmed();}
    }
  });
  try {
    await disableNetwork(local);
    const write=setDoc(doc(local,'games/g/moves/0000000000'),{seq:0,seat:'0',uid:'host',action:'1:ROLL:0',prev:''});
    await pending; if(!pendingSeen || confirmed!==0) throw Error('pending echo counted as confirmation');
    await enableNetwork(local); await Promise.all([write,done]);
    if(confirmed!==1) throw Error('double apply');
  } finally {unsubscribe();}
  await assertSucceeds(append('host',1)); await assertSucceeds(append('host',2));
  const suffix=await new Promise((resolve,reject)=>{
    const stop=onSnapshot(query(collection(db('guest'),'games/g/moves'),orderBy('seq'),where('seq','>',1)),snapshot=>{
      if(snapshot.metadata.fromCache)return;
      stop();resolve(snapshot.docs.map(d=>d.data().seq));
    },reject);
  });
  if(JSON.stringify(suffix)!=='[2]')throw Error('resume reread history');
});

test('crash reports: a signed-in player can add one; nobody can read, edit or delete; forged or oversized denied', async () => {
  const add = (uid, data) => setDoc(doc(db(uid), 'crashes/' + Math.random().toString(36).slice(2)), data);
  await assertSucceeds(add('p1', {uid: 'p1', report: 'WGBG crash report\nerror: boom', createdAt: serverTimestamp()}));
  await assertFails(add(null, {uid: 'p1', report: 'x', createdAt: serverTimestamp()}));
  await assertFails(add('p1', {uid: 'p2', report: 'x', createdAt: serverTimestamp()}));
  await assertFails(add('p1', {uid: 'p1', report: 'x'.repeat(200001), createdAt: serverTimestamp()}));
  await assertSucceeds(add('p1', {uid: 'p1', report: 'x'.repeat(200000), createdAt: serverTimestamp()}));
  await assertFails(add('p1', {uid: 'p1', report: 'x', createdAt: serverTimestamp(), extra: 1}));
  await assertFails(getDocs(collection(db('p1'), 'crashes')));
  await env.withSecurityRulesDisabled(async c => { await setDoc(doc(c.firestore(), 'crashes/c1'), {uid: 'p1', report: 'x'}); });
  await assertFails(getDoc(doc(db('p1'), 'crashes/c1')));
  await assertFails(updateDoc(doc(db('p1'), 'crashes/c1'), {report: 'y'}));
  await assertFails(deleteDoc(doc(db('p1'), 'crashes/c1')));
});

test('report screenshots (#128): anyone signed in may add one under the size cap; nobody reads, changes or deletes them', async () => {
  const add = (uid, data) => setDoc(doc(db(uid), 'shots/' + Math.random().toString(36).slice(2)), data);
  const shot = (over) => Object.assign({uid: 'p1', crash: 'c1', n: 0, image: '/9j/4AAQ', createdAt: serverTimestamp()}, over);
  await assertSucceeds(add('p1', shot({})));
  await assertSucceeds(add('p1', shot({n: 1, image: 'x'.repeat(450000)})));
  await assertFails(add(null, shot({})));
  await assertFails(add('p1', shot({uid: 'p2'})));
  await assertFails(add('p1', shot({n: 2})));
  await assertFails(add('p1', shot({image: 'x'.repeat(450001)})));
  await assertFails(add('p1', shot({extra: 1})));
  await assertFails(getDocs(collection(db('p1'), 'shots')));
  await env.withSecurityRulesDisabled(async c => { await setDoc(doc(c.firestore(), 'shots/s1'), shot({createdAt: 1})); });
  await assertFails(getDoc(doc(db('p1'), 'shots/s1')));
  await assertFails(deleteDoc(doc(db('p1'), 'shots/s1')));
});

// #72: friends, in-app invites, the online rematch and rooms that expire.
const inDays = d => Timestamp.fromMillis(Date.now() + d * 86400000);
const v3 = (extra={}) => ({...room(), version:3, invited:['erin'], expireAt:inDays(1), ...extra});
test('a v3 room carries invites, its rematch link and an expiry; too many invites or too far an expiry denied',async()=>{
  await assertSucceeds(setDoc(doc(db('host'),'games/a'),v3({rematchOf:'ROVER-42'})));
  await assertFails(setDoc(doc(db('host'),'games/b'),v3({invited:['a','b','c','d']})));
  await assertFails(setDoc(doc(db('host'),'games/c'),v3({expireAt:inDays(90)})));
  await assertFails(setDoc(doc(db('host'),'games/d'),v3({expireAt:'soon'})));
  await assertFails(setDoc(doc(db('host'),'games/e'),v3({rematchOf:'A-VERY-LONG-CODE-1'})));});
test('the invited find their rooms by query, in any state; nobody else can list them',async()=>{
  for(const [id,status] of [['l','lobby'],['p','playing']]) await env.withSecurityRulesDisabled(async c=>{await setDoc(doc(c.firestore(),'games/'+id),{...v3(),status});});
  const q=uid=>query(collection(db(uid),'games'),where('invited','array-contains',uid));
  const r=await assertSucceeds(getDocs(q('erin'))); if(r.size!==2) throw Error('expected 2 invites, got '+r.size);
  await assertSucceeds(getDoc(doc(db('erin'),'games/p')));
  await assertFails(getDoc(doc(db('other'),'games/p')));
  await assertFails(getDocs(query(collection(db('other'),'games'),where('invited','array-contains','erin'))));});
test('the host invites in the lobby and starts with a new expiry; a guest cannot change invites',async()=>{
  await env.withSecurityRulesDisabled(async c=>{await setDoc(doc(c.firestore(),'games/g'),v3());});
  await assertFails(updateDoc(ref('guest'),{invited:['guest']}));
  await assertSucceeds(updateDoc(ref('host'),{invited:['erin','bo']}));
  await assertSucceeds(updateDoc(ref('host'),{status:'playing',expireAt:inDays(30)}));});
test('a seat may say its player signed in with Google, as a bool',async()=>{await seed();
  await assertFails(updateDoc(ref('guest'),{'seats.1':{...seat('guest','Guest',null,false),google:'yes'}}));
  await assertSucceeds(updateDoc(ref('guest'),{'seats.1':{...seat('guest','Guest',null,false),google:true}}));});
test('ending a game sets its expiry; the host links a finished room to its rematch once; nobody else can',async()=>{await seed('playing');
  await assertFails(updateDoc(ref('host'),{next:'ROVER-43'}));
  await assertSucceeds(updateDoc(ref('host'),{status:'done',expireAt:inDays(7)}));
  await assertFails(updateDoc(ref('guest'),{next:'ROVER-43'}));
  await assertFails(updateDoc(ref('host'),{next:'ROVER-43',status:'lobby'}));
  await assertSucceeds(updateDoc(ref('host'),{next:'ROVER-43'}));
  await assertFails(updateDoc(ref('host'),{next:'ROVER-44'}));});
test('a move may carry an expiry, never further off than two months',async()=>{await seed('playing');
  await assertFails(setDoc(doc(db('host'),'games/g/moves/0000000000'),{seq:0,seat:'0',uid:'host',action:'1:BUILD_SETTLEMENT:0',prev:'',expireAt:inDays(90)}));
  await assertSucceeds(setDoc(doc(db('host'),'games/g/moves/0000000000'),{seq:0,seat:'0',uid:'host',action:'1:BUILD_SETTLEMENT:0',prev:'',expireAt:inDays(30)}));});
test('the people you played with are yours alone',async()=>{
  const f={name:'Erin',lastPlayed:Timestamp.now(),games:1,starred:false};
  const met=(uid,owner,other)=>doc(db(uid),`users/${owner}/met/${other}`);
  await assertSucceeds(setDoc(met('host','host','erin'),f));
  await assertSucceeds(getDoc(met('host','host','erin')));
  await assertSucceeds(getDocs(collection(db('host'),'users/host/met')));
  await assertFails(getDoc(met('erin','host','erin')));
  await assertFails(getDocs(collection(db('erin'),'users/host/met')));
  await assertFails(setDoc(met('erin','host','bo'),f));
  await assertFails(setDoc(met('host','host','host'),f));
  for(const bad of [{extra:1},{games:-1},{starred:'yes'},{name:'x'.repeat(81)},{lastPlayed:5}]) await assertFails(setDoc(met('host','host','bo'),{...f,...bad}));
  await assertSucceeds(updateDoc(met('host','host','erin'),{starred:true}));
  await assertFails(deleteDoc(met('erin','host','erin')));
  await assertSucceeds(deleteDoc(met('host','host','erin')));
  await assertFails(setDoc(met(null,'host','x'),f));});
