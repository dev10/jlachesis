package poset;

import java.util.Arrays;

import com.google.protobuf.ByteString;

import common.RetResult;
import common.error;
import crypto.hash;
import poset.proto.EventBody.Builder;

public class EventBody {
	byte[][] Transactions; //  `protobuf:"bytes,1,rep,name=Transactions,json=transactions,proto3" json:"Transactions,omitempty"`
	InternalTransaction[] InternalTransactions; // `protobuf:"bytes,2,rep,name=InternalTransactions,json=internalTransactions" json:"InternalTransactions,omitempty"`
	String[] Parents ; //  `protobuf:"bytes,3,rep,name=Parents,json=parents" json:"Parents,omitempty"`
	byte[] Creator; //  `protobuf:"bytes,4,opt,name=Creator,json=creator,proto3" json:"Creator,omitempty"`
	long Index; // `protobuf:"varint,5,opt,name=Index,json=index" json:"Index,omitempty"`
	BlockSignature [] BlockSignatures; //     `protobuf:"bytes,6,rep,name=BlockSignatures,json=blockSignatures" json:"BlockSignatures,omitempty"`

	public EventBody(byte[][] transactions, InternalTransaction[] internalTransactions, String[] parents,
			byte[] creator, long index, BlockSignature[] blockSignatures) {
		super();
		Transactions = transactions;
		InternalTransactions = internalTransactions;
		Parents = parents;
		Creator = creator;
		Index = index;
		BlockSignatures = blockSignatures;
	}

	public EventBody() {
		super();
		Transactions = null;
		InternalTransactions = null;
		Parents = null;
		Creator = null;
		Index = -1;
		BlockSignatures = null;
	}

	public void Reset()                    {
		Transactions = null;
		InternalTransactions = null;
		Parents = null;
		Creator = null;
		Index = -1;
		BlockSignatures = null;
	}

	public byte[][] GetTransactions() {
		return Transactions;
	}

	public InternalTransaction[] GetInternalTransactions() {
		return InternalTransactions;
	}

	public String[] GetParents() {
		return Parents;
	}

	public byte[] GetCreator() {
		return Creator;
	}

	public long GetIndex() {
		return Index;
	}

	public BlockSignature[] GetBlockSignatures() {
		return BlockSignatures;
	}


	public boolean equals(EventBody that) {
		return Utils.ByteArraysEquals(this.Transactions, that.Transactions) &&
				Utils.InternalTransactionListEquals(this.InternalTransactions, that.InternalTransactions) &&
				Utils.<String>ListEquals(this.Parents, that.Parents) &&
				Utils.BytesEquals(this.Creator, that.Creator) &&
				this.Index == that.Index &&
				Utils.BlockSignatureListEquals(this.BlockSignatures, that.BlockSignatures);
	}

	public RetResult<byte[]> ProtoMarshal() {
		Builder builder = poset.proto.EventBody.newBuilder();
		if (this.Transactions != null) {
			Arrays.asList(this.Transactions)
			.forEach(transaction -> builder.addTransactions(ByteString.copyFrom(transaction)));
		}

//		InternalTransaction[] InternalTransactions; // `protobuf:"bytes,2,rep,name=InternalTransactions,json=internalTransactions" json:"InternalTransactions,omitempty"`

//		if (Parents != null) {
//			Arrays.asList(this.Parents).forEach(
//					parent ->
//					{
//						if (parent != null)
//							builder.addParents(parent);
//					});
//		}

//		builder.setCreator(ByteString.copyFrom(Creator));
//		builder.setIndex(Index);

		if (BlockSignatures != null) {
			Arrays.asList(this.BlockSignatures).forEach(
					block ->
					{
//						if (block != null)
//							builder.addBlockSignatures(block);
					});
		}

		byte[] byteArray = builder.build().toByteArray();

		// TBD
		return new RetResult<byte[]>(byteArray, null);
	}

	public error ProtoUnmarshal(byte[] data) {
		// TBD
		return null;
	}

	public RetResult<byte[]> Hash() {
		RetResult<byte[]> protoMarshal = ProtoMarshal();
		byte[] hashBytes = protoMarshal.result;
		error err = protoMarshal.err;
		if (err != null) {
			return new RetResult<byte[]>(null, err);
		}
		return new RetResult<byte[]>(hash.SHA256(hashBytes), null);
	}
}