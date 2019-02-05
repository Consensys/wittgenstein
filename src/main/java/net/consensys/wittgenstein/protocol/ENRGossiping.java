package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.List;

/*
* A Protocol that uses p2p flooding to gather data on time needed to find desired node capabilities
* Idea: To change the node discov ery protocol to allow node record sending through gossiping
* */

public class ENRGossiping implements Protocol {
    private final P2PNetwork<ETHNode> network;
    private final NodeBuilder nb;

    final int totalPeers ;

    public ENRGossiping(P2PNetwork<ETHNode> network, NodeBuilder nb, int totalPeers) {
        this.network = network;
        this.nb = nb;
        this.totalPeers = totalPeers;
    }

    @Override
    public Network<ETHNode> network() {
        return network;
    }

    @Override
    public Protocol copy() {
        return new ENRGossiping(network,nb, totalPeers);
    }

    @Override
    public void init() {
        for(int i = 0; i<totalPeers; i++){
            //network.addNode(new ETHNode(i,new Records()) );
        }
    }
    /*
    * The components of a node record are:
    * signature: cryptographic signature of record contents
    * seq: The sequence number, a 64 bit integer. Nodes should increase the number whenever the record changes and republish the record.
    * The remainder of the record consists of arbitrary key/value pairs, which must be sorted by key.
    * */
    static class Records extends Network.Message<ETHNode>{
        long signature;
        int seq = 0;
        Map<Byte,Integer> k_v;
        boolean updateReceived;

        Records(long signature, Map<Byte,Integer> k_v ){
         this.signature = signature;
         this.seq++;
         this.k_v = k_v;
        }
        @Override
        public void action(ETHNode from, ETHNode to) {
            from.joinning();
        }
    }

    class ETHNode extends P2PNode<ETHNode> {
        int nodeId;
        long signature;
        Map<Byte,Integer> k_v;
        Records capabilities;
        int timeToChange = 0;
        int capGossipTime;
        int discardTime;
        int timeToLeave;
        final List<Records> records = new ArrayList<>();

        public ETHNode(Random rd, NodeBuilder nb, Records capabilities, int timeToChange, int capGossipTime, int discardTime, int timeToLeave) {
            super(rd, nb);
            this.capabilities = capabilities;
            this.timeToChange = timeToChange;
            this.capGossipTime = capGossipTime;
            this.discardTime = discardTime;
            this.timeToLeave = timeToLeave;
        }
        //When a node first joins the network it gossips to its peers about its capabilities i.e. : sends its key/value pairs
        void joinning(){
            network.send(new Records(this.signature,this.k_v), this, this.peers);
        }

        void leave(){
            network.send(new Records(this.signature,this.k_v), this, this.peers);
        }

        void changeCapabilities(){
            network.send(new Records(this.signature,this.k_v), this, this.peers);
        }




    }

}
