package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;

import java.util.*;

/**
* A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
* Idea: To change the node discovery protocol to allow node record sending through gossiping
 */

public class ENRGossiping implements Protocol {
    private final P2PNetwork<ETHNode> network;
    private final NodeBuilder nb;
    long signature;
    private final int timeToChange;
    private final int capGossipTime;
    private final int discardTime;
    private final int timeToLeave;
    private final int totalPeers ;

    public ENRGossiping( int totalPeers, int timeToChange, int capGossipTime, int discardTime, int timeToLeave,NodeBuilder nb, NetworkLatency nl) {
        this.totalPeers = totalPeers;
        this.timeToChange = timeToChange;
        this.capGossipTime = capGossipTime;
        this.discardTime = discardTime;
        this.timeToLeave = timeToLeave;
        this.network = new P2PNetwork<>(totalPeers,true);
        this.nb = nb;
        this.network.setNetworkLatency(nl);
    }

    @Override
    public Network<ETHNode> network() {
        return network;
    }

    @Override
    public ENRGossiping copy() {
        return new ENRGossiping(totalPeers,timeToChange,capGossipTime,discardTime,timeToLeave,nb,network.networkLatency);
    }
    //Generate new capabilities for new nodes or nodes that periodically change.
    Map<Byte,Integer> generateCap(int i){
        Map<Byte,Integer> k_v = new HashMap<Byte,Integer>();
        k_v.put( Byte.valueOf("id"),4);
        k_v.put( Byte.valueOf("secp256k1"),i);
        k_v.put( Byte.valueOf("ip"),123);
        k_v.put( Byte.valueOf("tcp"),8088);
        k_v.put( Byte.valueOf("udp"),1200);
        return k_v;
    }

    @Override
    public void init() {
        for(int i = 0; i<totalPeers; i++){
            network.addNode(new ETHNode(network.rd,this.nb,generateCap(i)));
        }
        network.setPeers();

    }
    /**
    * The components of a node record are:
    * signature: cryptographic signature of record contents
    * seq: The sequence number, a 64 bit integer. Nodes should increase the number whenever the record changes and republish the record.
    * The remainder of the record consists of arbitrary key/value pairs, which must be sorted by key.
     * A Node sends this message to query for specific capabilities in the network
     */
    private static class FindRecord extends P2PNetwork.Message<ETHNode> {
        long signature;
        int seq;
        Map<Byte,Integer> k_v;
        boolean updateReceived;

        FindRecord(int se, long signature, Map<Byte,Integer> k_v, boolean updateReceived){
         this.signature = signature;
         this.seq = seq;
         this.k_v = k_v;
         this.updateReceived = updateReceived;
        }

        @Override
        public void action(ETHNode from, ETHNode to) {
            from.onJoinning();
        }
    }
    //When capabilities are found,before exiting the network the node sends it's capabilities through gossiping
    private static class RecordFound extends P2PNetwork.Message<ETHNode> {

        @Override
        public void action(ETHNode from, ETHNode to) {
            from.onLeaving();
        }
    }

    class ETHNode extends P2PNode<ETHNode> {
        int nodeId;
        boolean CapFound;
        final Map<Byte,Integer> capabilities;

        public ETHNode(Random rd, NodeBuilder nb, Map<Byte,Integer> capabilities) {
            super(rd, nb);
            this.capabilities = capabilities;

        }
        @Override
        protected void onFlood(ETHNode from, P2PNetwork.FloodMessage floodMessage) {
            if (CapFound) {
                doneAt = network.time;
            }
        }
        //When a node first joins the network it gossips to its peers about its capabilities i.e. : sends its key/value pairs
        // Also sends a request for a given set of capabilities it's looking for
        void onJoinning(){

           // network.send(new Records(this.signature,this.k_V), this, this.peers);
        }

        void onLeaving(){
           // network.send(new Records(this.signature,this.k_v), this, this.peers);
        }

        void atCapChange(){
           // network.send(new Records(this.signature,this.k_v), this, this.peers);
        }

    }

    private static void capSearch(){
        NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();
        NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();

        ENRGossiping p = new ENRGossiping(4000,10,10,10,10,nb,nl);
    }

}
