// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: poset/event.proto

package poset.proto;

public interface EventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:poset.proto.Event)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.poset.proto.EventMessage Message = 1;</code>
   */
  boolean hasMessage();
  /**
   * <code>.poset.proto.EventMessage Message = 1;</code>
   */
  poset.proto.EventMessage getMessage();
  /**
   * <code>.poset.proto.EventMessage Message = 1;</code>
   */
  poset.proto.EventMessageOrBuilder getMessageOrBuilder();

  /**
   * <code>int64 Round = 2;</code>
   */
  long getRound();

  /**
   * <code>int64 LamportTimestamp = 3;</code>
   */
  long getLamportTimestamp();

  /**
   * <code>int64 RoundReceived = 4;</code>
   */
  long getRoundReceived();

  /**
   * <code>string Creator = 5;</code>
   */
  java.lang.String getCreator();
  /**
   * <code>string Creator = 5;</code>
   */
  com.google.protobuf.ByteString
      getCreatorBytes();

  /**
   * <code>bytes Hash = 6;</code>
   */
  com.google.protobuf.ByteString getHash();

  /**
   * <code>string Hex = 7;</code>
   */
  java.lang.String getHex();
  /**
   * <code>string Hex = 7;</code>
   */
  com.google.protobuf.ByteString
      getHexBytes();
}
