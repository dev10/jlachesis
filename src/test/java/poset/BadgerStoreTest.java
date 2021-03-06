package poset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import autils.Appender;
import autils.FileUtils;
import common.RResult2;
import common.RResult;
import common.RResult3;
import common.error;
import peers.Peer;
import peers.Peers;
import peers.Peers.PubKeyPeers;

/**
 * Blank test
 * @author qn
 *
 */
public class BadgerStoreTest {
	static File currentDirectory = new File(new File(".").getAbsolutePath());

	private String testDir = currentDirectory.getAbsolutePath() + "test_data";

	private String dbPath = Paths.get(testDir, "badger").toString();

	private RResult2<BadgerStore, pub[]> initBadgerStore(int cacheSize) {
		int n = 3;
		pub[] participantPubs = null;
		Peers participants = new Peers();
		for (int i = 0; i < n; i++) {
			KeyPair key = crypto.Utils.GenerateECDSAKeyPair().result;
			byte[] pubKey = crypto.Utils.FromECDSAPub(key.getPublic());
			Peer peer = new Peer(crypto.Utils.toHexString(pubKey), "");
			participants.addPeer(peer);
			participantPubs = Appender.append(participantPubs,
				new pub(peer.getID(), key, pubKey, peer.getPubKeyHex()));
		}

		recreateTestDir();

		RResult<BadgerStore> newBadgerStore = BadgerStore.NewBadgerStore(participants, cacheSize, dbPath);
		BadgerStore store = newBadgerStore.result;
		error err = newBadgerStore.err;
		assertNull("No error creating badger store", err);

		return new RResult2<>(store, participantPubs);
	}

	private void recreateTestDir() {
		error err = FileUtils.delete(testDir);
		assertNull("No error deleting folder", err);

		FileUtils.mkdirs(testDir, FileUtils.MOD_777);
		err = FileUtils.mkdirs(dbPath, FileUtils.MOD_755).err;
		assertNull("No error creating a file", err);
	}

	private void removeBadgerStore(BadgerStore store) {
		error err = store.close();
		assertNull("No error", err);

		err = FileUtils.delete(testDir);
		assertNull("No error deleting folder", err);
	}

	private BadgerStore createTestDB(String dir) {
		Peers participants = Peers.newPeersFromSlice(new peers.Peer[]{
			new Peer("0xaa", ""),
			new Peer("0xbb", ""),
			new Peer("0xcc", ""),
		});

		int cacheSize = 1;
		RResult<BadgerStore> newStore = BadgerStore.NewBadgerStore(participants, cacheSize, dbPath);
		BadgerStore store = newStore.result;
		error err = newStore.err;
		assertNull("No error", err);
		return store;
	}

	@Test
	public void TestNewBadgerStore() {
		recreateTestDir();
		BadgerStore store = createTestDB(dbPath);

		assertEquals("Store path should mathc", store.path, dbPath);
		assertTrue("Path exists", FileUtils.fileExist(dbPath));

		//check roots
		Map<String, Root> inmemRoots = store.inmemStore.rootsByParticipant;
		assertEquals("DB root should have 3 items", 3, inmemRoots.size());

		error err;
		for (String participant : inmemRoots.keySet()) {
			Root root = inmemRoots.get(participant);
			RResult<Root> dbGetRoot = store.dbGetRoot(participant);
			Root dbRoot = dbGetRoot.result;
			err = dbGetRoot.err;
			assertNull(String.format("No error when retrieving DB root for participant %s", participant), err);
			assertEquals(String.format("%s DB root should match", participant), root, dbRoot);
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestLoadBadgerStore() {
		recreateTestDir();
		BadgerStore store = createTestDB(dbPath);
		store.close();
		int cacheSize = 100;
		RResult<BadgerStore> loadBadgerStore = BadgerStore.LoadBadgerStore(cacheSize, store.path);
		store = loadBadgerStore.result;
		error err = loadBadgerStore.err;
		assertNull("No error", err);

		RResult<Peers> dbGetParticipants = store.dbGetParticipants();
		Peers dbParticipants = dbGetParticipants.result;
		err = dbGetParticipants.err;
		assertNull("No error", err);

		assertEquals("store.participants  length should be 3", 3, store.participants.length());

		assertEquals("store.participants length should match", dbParticipants.length(), store.participants.length());

		PubKeyPeers byPubKey = dbParticipants.getByPubKey();
		for (String dbP : byPubKey.keySet()) {
			Peer dbPeer = byPubKey.get(dbP);
			Peer peer = store.participants.byPubKey(dbP);
			assertNotNull(String.format("BadgerStore participants should contain %s", dbP), peer);
			assertEquals(String.format("participant %s ID should match", dbP), dbPeer.getID(), peer.getID());
		}

		removeBadgerStore(store);
	}

	//++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	//Call DB methods directly
	@Test
	public void TestDBEventMethods() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		int testSize = 100;
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		//insert events in db directly
		Map<String, Event[]> events = new HashMap<String, Event[]>();
		long topologicalIndex = 0L;
		Event[] topologicalEvents = null;
		for (pub p : participants) {
			Event[] items = null;
			for (int k =0; k < testSize; k++){
				Event event = new Event(
					new byte[][]{String.format("%s_%d", p.hex.substring(0,5), k).getBytes()},
					new InternalTransaction[]{},
					new BlockSignature[]{new BlockSignature("validator".getBytes(), 0, "r|s")},
					new String[]{"", ""},
					p.pubKey,
					k, null);

				event.sign(p.privKey.getPrivate());
				event.message.TopologicalIndex = topologicalIndex;
				topologicalIndex++;
				topologicalEvents = Appender.append(topologicalEvents, event);

				items = Appender.append(items, event);
				error err = store.dbSetEvents(new Event[]{event});
				assertNull("No error", err);
			}
			events.put(p.hex, items);
		}

		//check events where correctly inserted and can be retrieved
		error err;
		for (String p : events.keySet()) {
			Event[] evs = events.get(p);
			for (int k  = 0; k< evs.length; ++k) {
				Event ev = evs[k];
				RResult<Event> dbGetEvent = store.dbGetEvent(ev.hex());
				Event rev = dbGetEvent.result;
				err = dbGetEvent.err;
				assertNull("No error", err);

				assertEquals(String.format("events[%s][%d].Body should match", p, k), ev.message.Body, rev.message.Body);
				assertEquals(String.format("events[%s][%d].Signature should match", p, k), ev.message.Signature, rev.message.Signature);

				RResult<Boolean> verify = rev.verify();
				boolean ver = verify.result;
				err = verify.err;
				assertNull("No error", err);
				assertTrue("Verified signature returns true", ver);
			}
		}

		//check topological order of events was correctly created
		RResult<Event[]> dbTopologicalEventsCall = store.dbTopologicalEvents();
		Event[] dbTopologicalEvents = dbTopologicalEventsCall.result;
		err = dbTopologicalEventsCall.err;
		assertNull("No error", err);
		assertEquals("Length of dbTopologicalEvents should match", topologicalEvents.length, dbTopologicalEvents.length);

		for (int i = 0; i< dbTopologicalEvents.length; ++i) {
			Event dte = dbTopologicalEvents[i];
			Event te = topologicalEvents[i];
			assertEquals(String.format("dbTopologicalEvents[%d].Hex should match", i), te.hex(), dte.hex());
			assertEquals(String.format("dbTopologicalEvents[%d].Body should match", i), te.message.Body, dte.message.Body);
			assertEquals(String.format("dbTopologicalEvents[%d].Signature should match", i),
						te.message.Signature, dte.message.Signature);

			RResult<Boolean> verify = dte.verify();
			boolean ver = verify.result;
			err = verify.err;
			assertNull("No error", err);
			assertTrue("Verified signature returns true", ver);
		}

		//check that participant events where correctly added
		int skipIndex = -1; //do not skip any indexes
		for (pub p : participants) {
			RResult<String[]> dbParticipantEventsCall= store.dbParticipantEvents(p.hex, skipIndex);
			String[] pEvents = dbParticipantEventsCall.result;
			err = dbParticipantEventsCall.err;
			assertNull("No error", err);

			assertEquals(String.format("%s should have matching number events", p.hex), testSize, pEvents.length);

			Event[] expectedEvents = Appender.sliceFromToEnd(events.get(p.hex), skipIndex+1);
			for (int k =0; k < expectedEvents.length; ++k) {
				Event e = expectedEvents[k];
				assertEquals(String.format("ParticipantEvents[%s][%d] should match", p.hex, k), e.hex(), pEvents[k]);
			}
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestDBRoundMethods() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		RoundInfo round = new RoundInfo();
		HashMap<String, Event> events = new HashMap<String,Event>();
		for (pub p: participants) {
			Event event = new Event(new byte[][]{},
				new InternalTransaction[]{},
				new BlockSignature[]{},
				new String[]{"", ""},
				p.pubKey,
				0, null);
			events.put(p.hex, event);
			round.AddEvent(event.hex(), true);
		}

		error err = store.dbSetRound(0, round);
		assertNull("No error", err);

		RResult<RoundInfo> dbGetRound = store.dbGetRound(0);
		RoundInfo storedRound = dbGetRound.result;
		err = dbGetRound.err;
		assertNull("No error", err);

		assertEquals("Round and StoredRound do not match", round, storedRound);

		String[] witnesses = store.roundWitnesses(0);
		String[] expectedWitnesses = round.Witnesses();
		assertEquals("There should be match length of witnesses", expectedWitnesses.length, witnesses.length);

		for (String w : expectedWitnesses) {
			assertTrue(String.format("Witnesses should contain %s", w), Arrays.asList(witnesses).contains(w));
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestDBParticipantMethods() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		BadgerStore store = initBadgerStore(cacheSize).result1;

		error err = store.dbSetParticipants(store.participants);
		assertNull("No error", err);

		RResult<Peers> dbGetParticipants = store.dbGetParticipants();
		Peers participantsFromDB = dbGetParticipants.result;
		err = dbGetParticipants.err;
		assertNull("No error", err);

		PubKeyPeers byPubKey = store.participants.getByPubKey();
		for (String p : byPubKey.keySet()) {
			Peer peer = byPubKey.get(p);
			Peer dbPeer = participantsFromDB.byPubKey(p);
			assertNotNull(String.format("DB contains participant %s", p), dbPeer);
			assertEquals(String.format("DB participant %s should have matching ID", p), peer.getID(), dbPeer.getID());
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestDBBlockMethods() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		int index = 0;
		int roundReceived = 5;
		byte[][] transactions = new byte[][]{
			"tx1".getBytes(),
			"tx2".getBytes(),
			"tx3".getBytes(),
			"tx4".getBytes(),
			"tx5".getBytes(),
		};
		byte[] frameHash = "this is the frame hash".getBytes();

		Block block = new Block(index, roundReceived, frameHash, transactions);

		RResult<BlockSignature> signCall = block.sign(participants[0].privKey);
		BlockSignature sig1 = signCall.result;
		error err = signCall.err;
		assertNull("No error", err);

		signCall = block.sign(participants[1].privKey);
		BlockSignature sig2 = signCall.result;
		err = signCall.err;
		assertNull("No error", err);

		block.setSignature(sig1);
		block.setSignature(sig2);

		// "Store Block"
		err = store.dbSetBlock(block);
		assertNull("No error", err);

		RResult<Block> dbGetBlock = store.dbGetBlock(index);
		Block storedBlock = dbGetBlock.result;
		err = dbGetBlock.err;
		assertNull("No error", err);
		assertEquals("Block and StoredBlock do not match", storedBlock, block);

		// "Check signatures in stored Block"
		dbGetBlock = store.dbGetBlock(index);
		storedBlock = dbGetBlock.result;
		err = dbGetBlock.err;
		assertNull("No error", err);

		String val1Sig = storedBlock.getSignatures().get(participants[0].hex);
		assertNotNull("Validator1 signature is stored in block", val1Sig);
		assertEquals("Validator1 block signatures matches", val1Sig, sig1.signature);

		String val2Sig = storedBlock.getSignatures().get(participants[1].hex);
		assertNotNull("Validator2 signature not stored in block", val2Sig);
		assertEquals("Validator2 block signatures matches", val2Sig, sig2.signature);

		removeBadgerStore(store);
	}

	@Test
	public void TestDBFrameMethods() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStoreCall = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStoreCall.result1;
		pub[] participants = initBadgerStoreCall.result2;

		EventMessage[] events = new EventMessage[participants.length];
		Root[] roots = new Root[participants.length];
		for (int id = 0; id < participants.length; ++id) {
			pub p = participants[id];
			Event event = new Event(
				new byte[][]{String.format("%s_%d", p.hex.substring(0,5), 0).getBytes()},
				new InternalTransaction[]{},
				new BlockSignature[]{new BlockSignature("validator".getBytes(), 0, "r|s")},
				new String[]{"", ""},
				p.pubKey,
				0, null);
			event.sign(p.privKey.getPrivate());
			events[id] = event.message;
			roots[id] = new Root(id);
		}
		Frame frame = new Frame(1L, roots, events);

		// "Store Frame"
		error err = store.dbSetFrame(frame);
		assertNull("No error", err);

		RResult<Frame> dbGetFrame = store.dbGetFrame(frame.Round);
		Frame storedFrame = dbGetFrame.result;
		err = dbGetFrame.err;
		assertNull("No error", err);
		assertEquals("Frame and StoredFrame do not match", storedFrame, frame);

		removeBadgerStore(store);
	}

	//++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	//Check that the wrapper methods work
	//These methods use the inmemStore as a cache on top of the DB
	@Test
	public void TestBadgerEvents() {
		//Insert more events than can fit in cache to test retrieving from db.
		int cacheSize = 10;
		int testSize = 100;
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		//insert event
		HashMap<String, Event[]> events = new HashMap<String,Event[]>();
		for (pub p : participants) {
			Event[] items = null;
			for (int k = 0; k < testSize; k++) {
				Event event = new Event(
						new byte[][]{String.format("%s_%d", p.hex.substring(0,5), k).getBytes()},
						new InternalTransaction[]{},
						new BlockSignature[]{new BlockSignature("validator".getBytes(), 0, "r|s")},
						new String[]{"", ""},
						p.pubKey,
						k, null);
				items = Appender.append(items, event);
				error err = store.setEvent(event);
				assertNull("No error", err);
			}
			events.put(p.hex, items);
		}

		// check that events were correclty inserted
		for ( String p : events.keySet()) {
			Event[] evs = events.get(p);

			for (int k = 0; k < evs.length; ++k) {
				Event ev = evs[k];
				RResult<Event> getEvent = store.getEvent(ev.hex());
				Event rev = getEvent.result;
				error err = getEvent.err;
				assertNull("No error", err);

				assertEquals(String.format("events[%s][%d].Body should match", p, k), ev.message.Body, rev.message.Body);
				assertEquals(String.format("events[%s][%d].Signature should match", p, k), ev.message.Signature, rev.message.Signature);
			}
		}

		//check retrieving events per participant
		int skipIndex = -1; //do not skip any indexes
		for (pub p : participants) {
			RResult<String[]> pEventsCall = store.participantEvents(p.hex, skipIndex);
			String[] pEvents = pEventsCall.result;
			error err = pEventsCall.err;
			assertNull("No error", err);
			assertEquals(String.format("%s should have match length of events", p.hex), testSize, pEvents.length);

			Event[] expectedEvents = Appender.sliceFromToEnd(events.get(p.hex), skipIndex+1);
			for (int k = 0; k < expectedEvents.length; ++k) {
				Event e = expectedEvents[k];
				assertEquals(String.format("ParticipantEvents[%s][%d] should be match",
						p.hex, k), e.hex(), pEvents[k]);
			}
		}

		//check retrieving participant last
		for (pub p : participants) {
			RResult3<String, Boolean> lastEventFrom = store.lastEventFrom(p.hex);
			String last = lastEventFrom.result1;
			error err = lastEventFrom.err;
			assertNull("No error", err);

			Event[] evs = events.get(p.hex);
			Event expectedLast = evs[evs.length-1];
			assertEquals(String.format("%s last should match", p.hex), expectedLast.hex(), last);
		}

		HashMap<Long, Long> expectedKnown = new HashMap<Long,Long>();
		for (pub p : participants) {
			expectedKnown.put((long) p.id, (long) testSize - 1);
		}
		Map<Long, Long> known = store.knownEvents();
		assertEquals("Known should match", known, expectedKnown);

		for (pub p : participants) {
			Event[] evs = events.get(p.hex);
			for (Event ev : evs) {
				error err = store.addConsensusEvent(ev);
				assertNull("No error", err);
			}
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestBadgerRounds() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		RoundInfo round = new RoundInfo();
		HashMap<String, Event> events = new HashMap<String,Event>();
		for (pub p : participants) {
			Event event= new Event(new byte[][]{},
				new InternalTransaction[]{},
				new BlockSignature[]{},
				new String[]{"", ""},
				p.pubKey,
				0, null);
			events.put(p.hex,event);
			round.AddEvent(event.hex(), true);
		}

		error err = store.setRound(0, round);
		assertNull("No error", err);

		long c = store.lastRound();
		assertEquals("Store LastRound should be 0", 0, c);

		RResult<RoundInfo> getRound = store.getRound(0);
		RoundInfo storedRound = getRound.result;
		err = getRound.err;
		assertNull("No error", err);

		assertEquals("Round and StoredRound do not match", round, storedRound);

		String[] witnesses = store.roundWitnesses(0);
		String[] expectedWitnesses = round.Witnesses();
		assertEquals("There should be %d witnesses, not %d", expectedWitnesses.length, witnesses.length);
		for (String w : expectedWitnesses) {
			assertTrue(String.format("Witnesses should contain %s", w), Arrays.asList(witnesses).contains(w));
		}

		removeBadgerStore(store);
	}

	@Test
	public void TestBadgerBlocks() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;

		int index = 0;
		int roundReceived = 5;
		byte[][] transactions = new byte[][]{
			"tx1".getBytes(),
			"tx2".getBytes(),
			"tx3".getBytes(),
			"tx4".getBytes(),
			"tx5".getBytes(),
		};
		byte[] frameHash = "this is the frame hash".getBytes();
		Block block = new Block(index, roundReceived, frameHash, transactions);
		RResult<BlockSignature> signCall = block.sign(participants[0].privKey);
		BlockSignature sig1 = signCall.result;
		error err = signCall.err;
		assertNull("No error", err);

		signCall = block.sign(participants[1].privKey);
		BlockSignature sig2 = signCall.result;
		err = signCall.err;
		assertNull("No error", err);

		block.setSignature(sig1);
		block.setSignature(sig2);

		//"Store Block"
		err = store.setBlock(block);
		assertNull("No error", err);

		RResult<Block> getBlock = store.getBlock(index);
		Block storedBlock = getBlock.result;
		err = getBlock.err;
		assertNull("No error", err);
		assertEquals("Block and StoredBlock do not match", storedBlock, block);

		// "Check signatures in stored Block"
		RResult<Block> getBlock2 = store.getBlock(index);
		storedBlock = getBlock2.result;
		err = getBlock2.err;
		assertNull("No error", err);

		String val1Sig = storedBlock.getSignatures().get(participants[0].hex);
		assertNotNull("Validator1 signature is stored in block", val1Sig);
		assertEquals("Validator1 block signatures should match", val1Sig, sig1.signature);

		String val2Sig = storedBlock.getSignatures().get(participants[1].hex);
		assertNotNull("Validator2 signature not stored in block", val2Sig);
		assertEquals("Validator2 block signatures should match", val2Sig, sig2.signature);

		removeBadgerStore(store);
	}

	@Test
	public void TestBadgerFrames() {
		int cacheSize = 1; // Inmem_store's caches accept positive cacheSize only
		RResult2<BadgerStore, pub[]> initBadgerStore = initBadgerStore(cacheSize);
		BadgerStore store = initBadgerStore.result1;
		pub[] participants = initBadgerStore.result2;


		EventMessage[] events = new EventMessage[participants.length];
		Root[] roots = new Root[participants.length];
		for (int id = 0; id< participants.length; ++id) {
			pub p = participants[id];
			Event event = new Event(
					new byte[][]{String.format("%s_%d", p.hex.substring(0,5), 0).getBytes()},
					new InternalTransaction[]{},
					new BlockSignature[]{new BlockSignature("validator".getBytes(), 0, "r|s")},
					new String[]{"", ""},
					p.pubKey,
					0, null);
			event.sign(p.privKey.getPrivate());
			events[id] = event.message;
			roots[id] = new Root(id);
		}
		Frame frame = new Frame(1L, roots, events);

		// "Store Frame"
		error err = store.setFrame(frame);
		assertNull("No error", err);
		RResult<Frame> getFrame = store.getFrame(frame.Round);
		Frame storedFrame = getFrame.result;
		err = getFrame.err;
		assertNull("No error", err);

		assertEquals("Frame and StoredFrame do not match", frame, storedFrame);

		removeBadgerStore(store);
	}
}