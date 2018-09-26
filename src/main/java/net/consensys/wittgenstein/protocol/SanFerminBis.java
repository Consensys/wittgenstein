package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;


/**
 * San Fermin's protocol adapted to BLS signature aggregation.
 *
 * San Fermin is a protocol for distributed aggregation of a large
 * data set over a large set of nodes. It imposes a special binomial tree
 * structures on
 * the communication patterns of the node so that each node only needs
 * to contact O(log(n)) other nodes to get the final aggregated result.
 * SanFerminNode{nodeId=1000000001, doneAt=4860, sigs=874, msgReceived=272, msgSent=275, KBytesSent=13, KBytesReceived=13, outdatedSwaps=0}
 */
public class SanFerminBis {

    /**
     * The number of nodes in the network
     */
    final int totalCount;

    /**
     * exponent to represent @totalCount in base 2
     * totalCount = 2 ** powerOfTwo;
     * It is used to represent the length of the binary string of a node's id.
     */
    final int powerOfTwo;
    /**
     * The number of signatures to reach to finish the protocol
     */
    final int threshold;

    /**
     * The time it takes to do a pairing for a node
     * i.e. simulation of the most heavy computation
     */
    final int pairingTime;

    /**
     * Size of a BLS signature (can be aggregated or not)
     */
    final int signatureSize;

    /**
     * how much time to wait for a reply upon a given request
     */
    final int replyTimeout;

    /**
     * Do we print logging information from nodes or not
     */
    boolean verbose;

    /**
     * allNodes represent the full list of nodes present in the system.
     * NOTE: This assumption that a node gets access to the full list can
     * be dismissed, as they do in late sections in the paper. For a first
     * version and sake of simplicity, full knowledge of the peers is assumed
     * here. A partial knowledge graph can be used, where nodes will send
     * requests to unknown ID "hoping" some unknown peers yet can answer.
     * This technique works because it uses Pastry ID allocation mechanism.
     * Here no underlying DHT or p2p special structure is assumed.
     */
    public final List<SanFerminNode> allNodes;

    public final List<SanFerminNode> finishedNodes;


    public SanFerminBis(int totalCount,int powerOfTwo, int threshold,
                              int pairingTime,int signatureSize,
                        int replyTimeout) {
        this.totalCount = totalCount;
        this.powerOfTwo = powerOfTwo;
        this.threshold = threshold;
        this.pairingTime = pairingTime;
        this.signatureSize = signatureSize;
        this.replyTimeout = replyTimeout;

        this.network = new Network<SanFerminNode>();
        this.nb = new Node.NodeBuilderWithPosition(network.rd);

        this.allNodes = new ArrayList<SanFerminNode>(totalCount);
        for(int i = 0; i < totalCount; i++) {
            final SanFerminNode n = new SanFerminNode(this.nb);
            this.allNodes.add(n);
            this.network.addNode(n);
        }

        // compute candidate set once all peers have been created
        for(SanFerminNode n : allNodes)
            n.computeCandidateSets();


        finishedNodes = new ArrayList<>();
    }

    final Network<SanFerminNode> network;
    final Node.NodeBuilder nb;

    /**
     * StartAll makes each node starts swapping with each other when the
     * network starts
     */
    public void StartAll() {
        for (SanFerminNode n : allNodes)
            network.registerTask(n::swapNextLevel, 1, n);
    }

    /**
     * Returns the length of the longest common prefix between the ids of the
     * two nodes.
     * NOTE: ids are transformed
     * into binary form first, and the length of the prefix corresponds
     * to the length in binary string form.
     *
     * @param a the  node with whom to compare ids
     * @param b the other node with whom to compare ids
     * @return length of the LCP
     */
    static public int LengthLCP(@NotNull SanFerminNode a,
                                @NotNull SanFerminNode b) {
        String s1 = a.binaryId;
        String s2 = b.binaryId;

        String ret = "";
        int idx = 0;

        while(true){
            char thisLetter = 0;
            for(String word : new String[]{s1,s2}) {
                if(idx == word.length()){
                    return ret.length();
                }
                if(thisLetter == 0){
                    thisLetter = word.charAt(idx);
                }
                if(thisLetter != word.charAt(idx)){
                    return ret.length();
                }
            }
            ret += thisLetter;
            idx++;
        }
    }

    /**
     * Simply pads the binary string id to the exact length = n where N = 2^n
     * @param originalString
     * @param length
     * @return
     */
    public static String leftPadWithZeroes(String originalString, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<length; i++) {
            sb.append("0");
        }
        String padding = sb.toString();
        String paddedString = padding.substring(originalString.length()) + originalString;
        return paddedString;
    }



    /**
     * SanFerminNode is a node that carefully selects the peers he needs to
     * contact to get the final aggregated result
     */
    public class SanFerminNode  extends Node {
        /**
         * The node's id in binary string form
         */
        public final String binaryId;

        /**
         * This node needs to exchange with another node having a current
         * common prefix length of currentPrefixLength. A node starts by the
         * highest possible prefix length and works towards a prefix length
         * of 0.
         */
        public int currentPrefixLength;

        /**
         * List of nodes that are candidates to swap with this node sorted
         * through different prefix length.
         */
        HashMap<Integer, List<SanFerminNode>> candidateSets;

        /**
         * Id of the node we have sent a SwapRequest to. Only one node at a
         * time is asked to swap.
         */
        int pendingNode;

        /**
         *  ----- STATS INFORMATION -----
         */
        /**
         * Toy field that keeps increasing as the node do more swaps. It
         * assumes each node has the value "1" and the aggregation operation
         * is the addition.
         */
        int aggValue;

        /**
         * time when this node has finished the protocol entirely
         */
        long doneAt;
        /**
         * Are we done yet or not
         */
        boolean done;

        /**
         * outdatedSwaps indicates the number of times a node received a Swap
         * message for a previous invitation/reply flow at a different level.
         * The node simply skip it.
         */
        int outdatedSwaps;

        public SanFerminNode(@NotNull NodeBuilder nb) {
            super(nb);
            this.binaryId =
                    leftPadWithZeroes(Integer.toBinaryString(this.nodeId),
                            powerOfTwo);
            this.candidateSets = new HashMap<>();
            this.done = false;
            this.aggValue = 1;
            // node should start at n-1 with N = 2^n
            // this counter gets decreased with `swapNextLevel`.
            this.currentPrefixLength = powerOfTwo;
        }

        /**
         * onSwapRequest checks if it is a request for the same current
         * level, and if it is, swap and go to the next level.
         * onSwapRequest is the fastest way to swap, since the value is
         * already embedded in the message.
         * @param node
         * @param request
         */
       public void onSwapRequest(@NotNull SanFerminNode node,
                                 @NotNull SwapRequest request) {

            if (request.level != this.currentPrefixLength || done) {
                this.sendSwapReply(node,Status.NO);
                return;
            }
            // indication that we should not accept replies anymore for this
           // round since we are doing already the aggregation + pairing
            this.pendingNode = -1;
            // performs the swap , reply with our own agg value and move on
           network.registerTask( () -> {
               SanFerminNode.this.sendSwapReply(node,Status.OK);
               int before = SanFerminNode.this.aggValue;
               SanFerminNode.this.aggValue += request.aggValue;
               print(" received valid swap request lvl="+request.level + " from "
                       + node.binaryId +
                       " aggValue " + before + " -> " + SanFerminNode.this.aggValue);
               this.swapNextLevel();
           },network.time + pairingTime, SanFerminNode.this);
       }

        public void onSwapReply(@NotNull SanFerminNode from,
                            @NotNull SwapReply reply) {
            if (reply.level != this.currentPrefixLength || done ) {
                // invalid or outdated request
                return;
            }
            switch (reply.status) {
                case OK:
                    if (this.pendingNode != from.nodeId) {
                        // even though we could swap, it may result in
                        // invalid aggregated data etc, since we can already
                        // swap upon a request. So we SKIP.
                        return;
                    }
                    this.pendingNode = -1; // tricks to say we don't accept
                    // anymore aggregation now.
                    network.registerTask(() -> {
                        int before = this.aggValue;
                        this.aggValue += reply.aggValue;
                        print( " received valid swap REPLY lvl="+reply.level+
                                        " from " + from.binaryId
                                + " aggValue " + before + " -> " + this.aggValue);
                        this.swapNextLevel();
                    },network.time + pairingTime, SanFerminNode.this);
                    break;
                case NO:
                    print(" received SwapReply NO from " + from.binaryId);
                    // only try the next one if this is an expected reply
                    if (this.pendingNode == from.nodeId)
                        tryNextNode();
                    break;
                default:
                    throw new Error("That should never happen");
            }
        }

        /**
         * tryNextNode simply picks the next eligible candidate from the list
         * and send a swap request to it. It attaches a timeout to the
         * request. If no SwapReply has been received before timeout,
         * tryNextNode() will be called again.
         */
        private void tryNextNode() {
            SanFerminNode next = this.pickNextNode();
            if (next == null) {
                print(" is OUT (no more " +
                        "nodes to pick)");
                return;
            }
            this.pendingNode = next.nodeId;
            SwapRequest r = new SwapRequest(this.currentPrefixLength,
                    this.aggValue);
            network.registerTask(() -> {
                // only if we are still waiting on an answer from this node
                // we try a new one. It can happen that we receive a NO
                // answer so we already switched to a new one.
                if (!done && SanFerminNode.this.pendingNode == next.nodeId) {
                    print("TIMEOUT of SwapRequest to " + next.binaryId);
                    // that means we haven't got a successful reply for that
                    // level so we try another node
                    tryNextNode();
                }
            },network.time + replyTimeout, SanFerminNode.this);
            print(" send SwapRequest to " + next.binaryId);
            network.send(r,this,Collections.singleton(next));
        }


        /**
         * swapNextLevel reduces the required length of common prefix of one,
         * computes the new set of potential nodes and sends an invitation to
         * the "next" one. There are many ways to select the order of which
         * node to choose. In case the number of nodes is 2^n, there is a
         * 1-to-1 mapping that exists so that each node has exactly one
         * unique node to swap with. In case it's not, there are going to be
         * some nodes who will be unable to continue the protocol since the
         * "chosen" node will likely already have swapped. See
         * `pickNextNode` for more information.
         */
        private void swapNextLevel() {
            if (done) {return;}
            boolean enoughSigs = this.aggValue >= threshold;
            boolean noMoreSwap = this.currentPrefixLength == 0;
            if (enoughSigs || noMoreSwap) {
                print(" --- FINISHED ---- protocol");
                // we have finished
                doneAt = network.time + pairingTime * 2;
                finishedNodes.add(this);
                if (noMoreSwap) done = true;
                return;
            }
            this.currentPrefixLength--;
            this.tryNextNode();
        }

        private void sendSwapReply(@NotNull SanFerminNode n, Status s) {
            SwapReply r = new SwapReply(s,this.currentPrefixLength,
                    this.aggValue);
            network.send(r,SanFerminNode.this,Collections.singleton(n));
        }

        /**
         * computeCandidateSets computes the set of nodes that are eligible
         * for swapping with, at each level.
         * NOTE: This function assumes knowledge of the whole graph of node.
         * See "allNodes" field for further discussion about this assumption.
         */
        private void computeCandidateSets() {
            for (SanFerminNode node : allNodes) {
                if (node.nodeId == this.nodeId) {
                    continue; // we skip ourself
                }
                int length = LengthLCP(this,node);
                List<SanFerminNode> list = candidateSets.getOrDefault(length,
                        new LinkedList<SanFerminNode>());
                list.add(node);
                candidateSets.put(length,list);
            }
        }

        /**
         * One big question is which node
         * to chose amongst the list so that it minimizes the number of "NO"
         * replies,ie. so that it minimizes the number of nodes who contact
         * another node who already swapped at this level. This
         * implementation randomly shuffle the list and pick the first one,
         * and perform the same steps for further nodes if the first one does
         * not work. There are tons of ways to perform this decision-making,
         * some which brings better guarantees probably. This PoC chooses to
         * use a random assignement, as most often, uniformity performs
         * better in computer science.
         */
        private SanFerminNode pickNextNode() {
            List<SanFerminNode> list =
                    candidateSets.getOrDefault(currentPrefixLength,
                            new ArrayList<>());
            if (list.size() == 0) {
                // TODO This can and will happen if the number of nodes is not
                // a power of two, as there will be some nodes without a
                // potentical swappable candidate
                //throw new IllegalStateException("an empty list should not " +
                //        "happen in a simulation, where everything is
                // perfect");
                return null;
            }

            //Collections.shuffle(list);
            SanFerminNode node = list.remove(0);
            candidateSets.put(currentPrefixLength,list);
            return node;
        }

        private void timeout(int level) {
            // number of potential candidates at a given level
            int diff = powerOfTwo - currentPrefixLength;
            int potentials = (int) Math.pow(2,diff) - 1;

        }

        /**
         * simple helper method to print node info + message
         */
        private void print(String s) {
            if (verbose)
                System.out.println("t=" + network.time + ", id=" + this.binaryId +
                        ", lvl=" + this.currentPrefixLength + " -> " + s);
        }

        @Override
        public String toString() {
            return "SanFerminNode{" +
                    "nodeId=" + binaryId +
                    ", doneAt=" + doneAt +
                    ", sigs=" + aggValue +
                    ", msgReceived=" + msgReceived +
                    ", msgSent=" + msgSent +
                    ", KBytesSent=" + bytesSent / 1024 +
                    ", KBytesReceived=" + bytesReceived / 1024 +
                    ", outdatedSwaps=" + outdatedSwaps +
                    '}';
        }
    }

    enum Status {
        OK, NO
    }

    class SwapReply extends Network.MessageContent<SanFerminNode> {

        Status status;
        final int level;
        int aggValue; // see Reply.aggValue
        // String data -- no need to specify it, but only in the size() method


        public SwapReply(Status s, int level,int aggValue) {
            this.level = level;
            this.status = s;
            this.aggValue = aggValue;
        }

        @Override
        public void action(@NotNull SanFerminNode from, @NotNull SanFerminNode to) {
            to.onSwapReply(from,this);
        }

        @Override
        public int size() {
            // uint32 + sig size
            return 4 + signatureSize;
        }
    }


    class SwapRequest extends Network.MessageContent<SanFerminNode> {

        final int level;
        int aggValue; // see Reply.aggValue
        // String data -- no need to specify it, but only in the size() method


        public SwapRequest(int level,int aggValue) {
            this.level = level;
            this.aggValue = aggValue;
        }

        @Override
        public void action(@NotNull SanFerminNode from, @NotNull SanFerminNode to) {
            to.onSwapRequest(from,this);
        }

        @Override
        public int size() {
            // uint32 + sig size
            return 4 + signatureSize;
        }
    }


    public static void main(String... args) {
        int[] distribProp = {1, 33, 17, 12, 8, 5, 4, 3, 3, 1, 1, 2, 1, 1, 8};
        long[] distribVal = {12, 15, 19, 32, 35, 37, 40, 42, 45, 87, 155, 160, 185, 297, 1200};

        SanFerminBis p2ps;
        //p2ps = new SanFerminBis(1024, 10,1024/2, 2,48,100);
       p2ps = new SanFerminBis(8, 3,4, 2,48,150);
        p2ps.StartAll();
        p2ps.verbose = true;
        p2ps.network.setNetworkLatency(distribProp, distribVal).setMsgDiscardTime(1000);
        //p2ps.network.removeNetworkLatency();
        p2ps.network.run(15);
        Collections.sort(p2ps.finishedNodes,
                (n1,n2) -> {  return Long.compare(n1.doneAt,n2.doneAt);});
        int max = p2ps.finishedNodes.size() < 10 ? p2ps.finishedNodes.size()
                : 10;
        for(SanFerminNode n: p2ps.finishedNodes.subList(0,max))
            System.out.println(n);
    }
}
