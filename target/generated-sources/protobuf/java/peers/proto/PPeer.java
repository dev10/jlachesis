// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: peers/peer.proto

package peers.proto;

public final class PPeer {
  private PPeer() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_peers_proto_Peer_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_peers_proto_Peer_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\020peers/peer.proto\022\013peers.proto\"6\n\004Peer\022" +
      "\n\n\002ID\030\001 \001(\003\022\017\n\007NetAddr\030\002 \001(\t\022\021\n\tPubKeyHe" +
      "x\030\003 \001(\tB\tB\005PPeerP\001b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        }, assigner);
    internal_static_peers_proto_Peer_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_peers_proto_Peer_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_peers_proto_Peer_descriptor,
        new java.lang.String[] { "ID", "NetAddr", "PubKeyHex", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
