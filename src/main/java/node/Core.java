package node;

import java.security.KeyPair;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Level;
import org.jcsp.lang.One2OneChannel;

import autils.Appender;
import autils.Logger;
import autils.time;
import common.RResult;
import common.RResult3;
import common.error;
import peers.Peer;
import poset.BlockSignature;
import poset.Event;
import poset.EventComparatorByTopologicalOrder;
import poset.Poset;
import poset.Root;
import poset.Utils;

public class Core {
	long id;
	KeyPair key;
	byte[] pubKey;
	String hexID;
	poset.Poset poset;

	Map<String,Long> inDegrees;

	peers.Peers participants; // [PubKey] => id
	String head;
	long Seq;

	byte[][] transactionPool;
	poset.InternalTransaction[] internalTransactionPool;
	poset.BlockSignature[] blockSignaturePool;

	Logger logger;

	int maxTransactionsInEvent;

	public Core(long id, KeyPair key, peers.Peers participants,
			poset.Store store, One2OneChannel<poset.Block>commitCh /**chan **/ , Logger logger) {

		if (logger == null) {
			logger = Logger.getLogger(Core.class);
			logger.setLevel(Level.DEBUG);
//			lachesis_log.NewLocal(logger, logger.Level.String());
		}
		logger = logger.field("id", id);

		Map<String,Long> inDegrees = new ConcurrentHashMap<String,Long>();
		for (String pubKey : participants.getByPubKey().keySet()) {
			inDegrees.put(pubKey, (long) 0);
		}

		Poset p2 = new poset.Poset(participants, store, commitCh, logger);
		this.id = id;
		this.key = key;
		this.poset= p2;
		this.inDegrees=               inDegrees;
		this.participants=            participants;
		this.transactionPool=         new byte[][] {};
		this.internalTransactionPool= new poset.InternalTransaction[]{};
		this.blockSignaturePool=      new poset.BlockSignature[] {};
		this.logger=                  logger;
		this.head=  "";
		this.Seq=   -1;
			// MaxReceiveMessageSize limitation in grpc: https://github.com/grpc/grpc-go/blob/master/clientconn.go#L96
			// default value is 4 * 1024 * 1024 bytes
			// we use transactions of 120 bytes in tester, thus rounding it down to 16384
		this.maxTransactionsInEvent= 16384;

		p2.SetCore(this);
	}

	public long ID() {
		return id;
	}

	public byte[] pubKey() {
		if (pubKey == null) {
			pubKey = crypto.Utils.FromECDSAPub(key.getPublic());
		}
		return pubKey;
	}

	public String hexID() {
		if (hexID == null || hexID.isEmpty()) {
			pubKey = pubKey();
//			hexID = String.format("0x%X", pubKey);
			hexID = crypto.Utils.toHexString(pubKey);
		}
		return hexID;
	}

	public String head() {
		return head;
	}

	// Heights returns map with heights for each participants
	public Map<String,Long> heights() {
		HashMap<String, Long> heights = new HashMap<String,Long>();
		for (String pubKey : participants.getByPubKey().keySet()) {
			RResult<String[]> participantEventsCre = poset.Store.participantEvents(pubKey, -1);
			String[] participantEvents = participantEventsCre.result;
			error err = participantEventsCre.err;

			if (err == null) {
				heights.put(pubKey, (long) participantEvents.length);
			} else {
				heights.put(pubKey, (long) 0);
			}
		}
		return heights;
	}

	public Map<String,Long> inDegrees() {
		return inDegrees;
	}

	public error setHeadAndSeq() {
		String head;
		long seq;

		RResult3<String, Boolean> lastEventFrom = poset.Store.lastEventFrom(hexID());
		String last = lastEventFrom.result1;
		Boolean isRoot = lastEventFrom.result2;
		error err = lastEventFrom.err;
		if (err != null) {
			return err;
		}

		if (isRoot) {
			RResult<Root> getRoot = poset.Store.getRoot(hexID());
			Root root = getRoot.result;
			err = getRoot.err;
			if (err != null) {
				return err;
			}
			head = root.GetSelfParent().GetHash();
			seq = root.GetSelfParent().GetIndex();
		} else {
			RResult<Event> getEvent = getEvent(last);
			Event lastEvent = getEvent.result;
			err = getEvent.err;
			if (err != null) {
				return err;
			}
			head = last;
			seq = lastEvent.index();
		}

		this.head = head;
		this.Seq = seq;

		logger.field("core.head", head).field("core.Seq", Seq)
		.field("is_root", isRoot).debugf("SetHeadAndSeq()");

		return null;
	}

	public error bootstrap() {
		error err = poset.Bootstrap();
		if  (err != null) {
			return err;
		}
		bootstrapInDegrees();
		return null;
	}

	public void bootstrapInDegrees() {
		for (String pubKey : participants.getByPubKey().keySet()) {
			inDegrees.put(pubKey, (long) 0);
			RResult3<String, Boolean> lastEventFrom = poset.Store.lastEventFrom(pubKey);
			String eventHash = lastEventFrom.result1;
			error err = lastEventFrom.err;
			if (err != null) {
				continue;
			}
			for (String otherPubKey : participants.getByPubKey().keySet()) {
				if (otherPubKey.equals(pubKey)) {
					continue;
				}
				RResult<String[]> participantEventsCr = poset.Store.participantEvents(otherPubKey, -1);
				String[] events = participantEventsCr.result;
				err = participantEventsCr.err;
				if (err != null) {
					continue;
				}
				for (String eh : events) {
					RResult<Event> getEvent = poset.Store.getEvent(eh);
					Event event = getEvent.result;
					err = getEvent.err;
					if (err != null) {
						continue;
					}
					if (event.otherParent().equals(eventHash)) {
						inDegrees.put(pubKey, inDegrees.get(pubKey)+1);
					}
				}
			}
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	public error signAndInsertSelfEvent( poset.Event event) {
		error err = poset.SetWireInfoAndSign(event, key.getPrivate());
		if (err != null){
			return err;
		}

		return insertEvent(event, true);
	}

	public error insertEvent(poset.Event event, boolean setWireInfo ) {

		logger.field("event", event).field("creator", event.creator())
		.field("selfParent", event.selfParent()).field("index", event.index())
		.field("hex", event.hex()).debugf("InsertEvent(event poset.Event, setWireInfo bool)");

		error err = poset.InsertEvent(event, setWireInfo);
		if (err != null) {
			return err;
		}

		if (event.creator().equals(hexID())) {
			head = event.hex();
			Seq = event.index();
		}

		inDegrees.put(event.creator(), (long) 0);
		RResult<Event> getEvent = poset.Store.getEvent(event.otherParent());
		Event otherEvent = getEvent.result;
		err = getEvent.err;
		if  (err == null) {
			inDegrees.put(otherEvent.creator(),
					inDegrees.get(otherEvent.creator()) + 1);
		}
		return null;
	}

	public Map<Long,Long> knownEvents() {
		return poset.Store.knownEvents();
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	public RResult<poset.BlockSignature> SignBlock(poset.Block block) {
		RResult<BlockSignature> signCall = block.sign(key);
		BlockSignature sig = signCall.result;
		error err = signCall.err;
		if (err != null) {
			return new RResult<poset.BlockSignature>(new poset.BlockSignature(), err);
		}
		err = block.setSignature(sig);
		if  (err != null) {
			return new RResult<poset.BlockSignature>(new poset.BlockSignature(), err);
		}
		return new RResult<poset.BlockSignature>(sig, poset.Store.setBlock(block));
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	public boolean overSyncLimit(Map<Long,Long> knownEvents, long syncLimit) {
		int totUnknown = 0;
		Map<Long, Long> myKnownEvents = knownEvents();
		for (long i : myKnownEvents.keySet()) {
			long li = myKnownEvents.get(i);
			if (li > knownEvents.get(i)) {
				totUnknown += li - knownEvents.get(i);
			}
		}
		if (totUnknown > syncLimit) {
			return true;
		}
		return false;
	}

	public RResult3<poset.Block, poset.Frame> getAnchorBlockWithFrame() {
		return poset.GetAnchorBlockWithFrame();
	}

	// returns events that c knows about and are not in 'known'
	public RResult<poset.Event[]> eventDiff(Map<Long,Long> known) {
		poset.Event[] unknown = new poset.Event[0];
		// known represents the index of the last event known for every participant
		// compare this to our view of events and fill unknown with events that we know of
		// and the other doesn't
		for (long id: known.keySet()) {
			long ct = known.get(id);
			Peer peer = participants.byId(id);
			if (peer == null) {
				// unknown peer detected.
				// TODO: we should handle this nicely
				continue;
			}
			// get participant Events with index > ct
			RResult<String[]> ParticipantEventsCall = poset.Store.participantEvents(peer.getPubKeyHex(), ct);
			String[] participantEvents = ParticipantEventsCall.result;
			error err = ParticipantEventsCall.err;
			if (err != null) {
				return new RResult<poset.Event[]> ( new poset.Event[] {}, err);
			}
			for (String e : participantEvents) {
				RResult<Event> getEvent = poset.Store.getEvent(e);
				Event ev = getEvent.result;
				err = getEvent.err;
				if (err != null) {
					return new RResult<poset.Event[]>(new poset.Event[] {}, err);
				}
				logger.field("event", ev).field("creator", ev.creator())
				.field("selfParent", ev.selfParent())
				.field("index", ev.index()).field("hex", ev.hex())
				.debugf("Sending Unknown Event");
				unknown = Appender.append(unknown,  ev);
			}
		}

//		sort.Stable(poset.ByTopologicalOrder(unknown));
		Arrays.sort(unknown, new EventComparatorByTopologicalOrder());


		return new RResult<poset.Event[]>(unknown, null);
	}

	public error Sync(poset.WireEvent[] unknownEvents)  {

		logger.field("unknown_events", unknownEvents)
		.field("transaction_pool", transactionPool.length)
		.field("internal_transaction_pool", internalTransactionPool.length)
		.field("block_signature_pool", blockSignaturePool.length)
		.field("poset.PendingLoadedEvents", poset.getPendingLoadedEvents())
		.debug("Sync(unknownEventBlocks []poset.EventBlock)");

		Map<Long, Long> myKnownEvents = knownEvents();
		String otherHead = "";
		// add unknown events
		for (int k = 0; k < unknownEvents.length; ++k) {
			poset.WireEvent we = unknownEvents[k];
			logger.field("we", we).error("Sync");

			RResult<Event> readWireInfo = poset.ReadWireInfo(we);
			Event ev = readWireInfo.result;
			logger.field("ev", ev).error("Sync");
			error err = readWireInfo.err;
			if (err != null) {
				return err;
			}

			if (ev.index() > myKnownEvents.get(ev.creatorID())) {
				err = insertEvent(ev, false);
				if (err != null) {
					return err;
				}
			}

			// assume last event corresponds to other-head
			if (k == unknownEvents.length-1) {
				otherHead = ev.hex();
			}
		}

		// create new event with self head and other head only if there are pending
		// loaded events or the pools are not empty
		if (poset.getPendingLoadedEvents() > 0 ||
			transactionPool.length > 0 ||
			internalTransactionPool.length > 0 ||
			blockSignaturePool.length > 0) {
			return addSelfEventBlock(otherHead);
		}
		return null;
	}

	public error fastForward(String peer, poset.Block block, poset.Frame frame) {

		logger.field("peer", peer).debug("FastForward()");

		// Check Block Signatures
		error err = poset.CheckBlock(block);
		if (err != null) {
			return err;
		}

		// Check Frame Hash
		RResult<byte[]> hashCall = frame.Hash();
		byte[] frameHash = hashCall.result;
		err = hashCall.err;

		logger.field("err1", err).debug("FastForward()");

		if (err != null) {
			return err;
		}

		if (!Utils.bytesEquals(block.getFrameHash(), frameHash)) {

			logger.field("err2", err).debug("FastForward()");

			return error.Errorf("invalid Frame Hash");
		}

		logger.debug("FastForward() here");

		err = poset.Reset(block, frame);
		if (err != null) {
			return err;
		}

		err = setHeadAndSeq();
		if (err != null) {
			return err;
		}

		err = runConsensus();
		if (err != null) {
			return err;
		}

		return null;
	}

	public error addSelfEventBlock(String otherHead) {
		RResult<Event> getEvent = poset.Store.getEvent(head);
		// Get flag tables from parents
		Event parentEvent = getEvent.result;
		error errSelf = getEvent.err;
		if (errSelf != null) {
			logger.warn(String.format("failed to get parent: %s", errSelf));
		}
		RResult<Event> getEventOtherCall = poset.Store.getEvent(otherHead);
		Event otherParentEvent = getEventOtherCall.result;
		error errOther = getEventOtherCall.err;
		if (errOther != null) {
			logger.warn(String.format("failed to get other parent: %s", errOther));
		}

		Map<String,Long> flagTable;
		error err;

		if (errSelf != null) {
			flagTable = new HashMap<String,Long>();
			flagTable.put(head, (long) 1);
		} else {
			RResult<Map<String, Long>> getFlagTable = parentEvent.getFlagTable();
			flagTable = getFlagTable.result;
			err = getFlagTable.err;
			if (err != null) {
				return error.Errorf(String.format("failed to get self flag table: %s", err));
			}
		}

		if (errOther == null) {
			RResult<Map<String, Long>> mergeFlagTableCall = otherParentEvent.mergeFlagTable(flagTable);
			flagTable = mergeFlagTableCall.result;
			err = mergeFlagTableCall.err;
			if (err != null) {
				return error.Errorf(String.format("failed to marge flag tables: %s", err));
			}
		}

		// create new event with self head and empty other parent
		// empty transaction pool in its payload
		byte[][] batch;
		int nTxs = Math.min(transactionPool.length, maxTransactionsInEvent);
		batch = Appender.slice(transactionPool, 0, nTxs); //transactionPool[0:nTxs:nTxs];
		Event newHead = new poset.Event(batch,
			internalTransactionPool,
			blockSignaturePool,
			new String[]{head, otherHead}, pubKey(), Seq+1, flagTable);

		err = signAndInsertSelfEvent(newHead);
		if ( err != null) {
			return error.Errorf(String.format("newHead := poset.NewEventBlock: %s", err));
		}
		logger
			.field("transactions",          transactionPool.length)
			.field("internal_transactions", internalTransactionPool.length)
			.field("block_signatures",      blockSignaturePool.length)
			.debug("newHead := poset.NewEventBlock");

		transactionPool = Appender.slice(transactionPool, nTxs, transactionPool.length); //transactionPool[nTxs:]; //[][]byte{}
		internalTransactionPool = new poset.InternalTransaction[]{};
		// retain blockSignaturePool until transactionPool is empty
		// FIXIT: is there any better strategy?
		if (transactionPool.length == 0) {
			blockSignaturePool = new poset.BlockSignature[]{};
		}

		return null;
	}

	public RResult<poset.Event[]> fromWire(poset.WireEvent[] wireEvents)  {
		poset.Event[] events = new poset.Event[wireEvents.length];
		for (int i = 0; i < wireEvents.length; ++i) {
			RResult<Event> readWireInfo = poset.ReadWireInfo(wireEvents[i]);
			Event ev = readWireInfo.result;
			error err = readWireInfo.err;
			if (err != null) {
				return new RResult<poset.Event[]>(null, err);
			}
			events[i] = new Event(ev);
		}
		return new RResult<poset.Event[]>(events, null);
	}

	public RResult<poset.WireEvent[]> toWire(poset.Event[] events) {
		poset.WireEvent[] wireEvents = new poset.WireEvent[events.length];
		for (int i = 0; i < events.length; ++i) {
			wireEvents[i] = events[i].toWire();
		}
		return new RResult<poset.WireEvent[]>(wireEvents, null);
	}

	public error runConsensus()  {

		long start = System.nanoTime();
		error err = poset.DivideRounds();
		logger.field("Duration", time.Since(start)).debug("poset.DivideAtropos()");
		if (err != null) {
			logger.field("Error", err).error("poset.DivideAtropos()");
			return err;
		}

		start = System.nanoTime();
		err = poset.DecideFame();
		logger.field("Duration", time.Since(start)).debug("poset.DecideClotho()");
		if (err != null) {
			logger.field("Error", err).error("poset.DecideClotho()");
			return err;
		}

		start = System.nanoTime();
		err = poset.DecideRoundReceived();
		logger.field("Duration", time.Since(start)).debug("poset.DecideAtroposRoundReceived()");
		if (err != null) {
			logger.field("Error", err).error("poset.DecideAtroposRoundReceived()");
			return err;
		}

		start = System.nanoTime();
		err = poset.ProcessDecidedRounds();
		logger.field("Duration", time.Since(start)).debug("poset.ProcessAtroposRounds()");
		if (err != null) {
			logger.field("Error", err).error("poset.ProcessAtroposRounds()");
			return err;
		}

		start = System.nanoTime();
		err = poset.ProcessSigPool();
		logger.field("Duration", time.Since(start)).debug("poset.ProcessSigPool()");
		if (err != null) {
			logger.field("Error", err).error("poset.ProcessSigPool()");
			return err;
		}

		logger.field("transaction_pool", transactionPool.length)
			.field("block_signature_pool", blockSignaturePool.length)
			.field("poset.PendingLoadedEvents", poset.getPendingLoadedEvents())
			.debug("RunConsensus()");

		return null;
	}

	public void addTransactions(byte[][] txs) {
		transactionPool = Appender.append(transactionPool, txs);
	}

	public void addInternalTransactions(poset.InternalTransaction[] txs) {
		internalTransactionPool = Appender.append(internalTransactionPool, txs);
	}

	public void addBlockSignature(poset.BlockSignature bs) {
		blockSignaturePool = Appender.append(blockSignaturePool, bs);
	}

	public RResult<poset.Event> getHead() {
		return poset.Store.getEvent(head);
	}

	public RResult<poset.Event> getEvent(String hash) {
		return poset.Store.getEvent(hash);
	}

	public RResult<byte[][]> getEventTransactions(String hash){
		byte[][] txs = null;
		RResult<Event> getEvent = getEvent(hash);
		Event ex = getEvent.result;
		error err = getEvent.err;
		if (err != null) {
			return new RResult<byte[][]>(txs, err);
		}
		txs = ex.transactions();
		return new RResult<byte[][]>(txs, null);
	}

	public String[] getConsensusEvents() {
		return poset.Store.consensusEvents();
	}

	public long getConsensusEventsCount() {
		return poset.Store.consensusEventsCount();
	}

	public List<String> getUndeterminedEvents() {
		return poset.getUndeterminedEvents();
	}

	public int getPendingLoadedEvents() {
		return poset.getPendingLoadedEvents();
	}

	public RResult<byte[][]> getConsensusTransactions() {
		byte[][] txs = null;
		for (String e : getConsensusEvents()) {
			RResult<byte[][]> getTrans = getEventTransactions(e);
			byte[][] eTxs = getTrans.result;
			error err = getTrans.err;
			if (err != null) {
				return new RResult<byte[][]>(txs, error.Errorf(
						String.format("GetConsensusTransactions(): %s", e)));
			}
			txs = Appender.append(txs, eTxs);
		}
		return new RResult<byte[][]>(txs, null);
	}

	public long getLastConsensusRoundIndex() {
		return poset.getLastConsensusRound();
	}

	public long getConsensusTransactionsCount() {
		return poset.getConsensusTransactions();
	}

	public int getLastCommittedRoundEventsCount() {
		return poset.getLastCommitedRoundEvents();
	}

	public long getLastBlockIndex() {
		return poset.Store.lastBlockIndex();
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("Core [id=").append(id).append(", key=").append(key)
				.append(", hexID=").append(hexID).append(", poset=").append(poset.hashCode())
				.append(", inDegrees=").append(inDegrees).append(", participants=").append(participants)
				.append(", head=").append(head).append(", Seq=").append(Seq).append(", transactionPool=")
				.append(Arrays.toString(transactionPool)).append(", internalTransactionPool=")
				.append(Arrays.toString(internalTransactionPool)).append(", blockSignaturePool=")
				.append(Arrays.toString(blockSignaturePool)).append(", logger=").append(logger)
				.append(", maxTransactionsInEvent=").append(maxTransactionsInEvent).append("]");
		return builder.toString();
	}
}
