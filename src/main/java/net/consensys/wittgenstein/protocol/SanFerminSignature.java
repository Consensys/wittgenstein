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
 */
public class SanFerminSignature {

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


    public SanFerminSignature(int totalCount,int powerOfTwo, int threshold,
                              int pairingTime,int signatureSize) {
        this.totalCount = totalCount;
        this.powerOfTwo = powerOfTwo;
        this.threshold = threshold;
        this.pairingTime = pairingTime;
        this.signatureSize = signatureSize;

        this.network = new Network<SanFerminNode>();
        this.nb = new Node.NodeBuilderWithPosition(network.rd);

        this.allNodes = new ArrayList<SanFerminNode>(totalCount);
        for(int i = 0; i < totalCount; i++) {
            final SanFerminNode n = new SanFerminNode(this.nb);
            this.allNodes.add(n);
        }
        finishedNodes = new ArrayList<>();
    }

    final Network<SanFerminNode> network;
    final Node.NodeBuilder nb;

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
         * Set of node identifiers with whom this node has sent an invitation
         * or replied positively to an invitation, but has not yet finished
         * the swapping process.
         */
        HashMap<Integer,Set<Integer>> pendingInvits;


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

        public SanFerminNode(@NotNull NodeBuilder nb) {
            super(nb);
            this.binaryId = Integer.toBinaryString(this.nodeId);
            this.pendingInvits = new HashMap<>();
            this.candidateSets = new HashMap<>();
            this.computeCandidateSets();
            this.done = false;
        }

        /**
         * Code that deals with an invitation to swap with another node. It
         * must check if both node are at the same level of aggregation or
         * not. See doc of Reply to understand the different replies possible.
         */
        public void onInvitation(@NotNull SanFerminNode from,
                                 @NotNull Invitation invit) {
            Reply r;
            if (invit.prefixLength < this.currentPrefixLength) {
                // sender is already farther in the protocol since he wants
                // to aggregate from smaller common prefix nodes
                // answer with a maybe later reply to indicate this node may
                // be ready to swap at the requested length later
                r = new Reply(Status.MAYBE_LATER);
            } else if (invit.prefixLength == this.currentPrefixLength) {
                // sender and this node must swap at the same level so they
                // are good candidates
                r = new Reply(Status.OK,this.currentPrefixLength,this.aggValue);
                // save the invitation, since we should receive a reply from it
                addNodeToPending(from.nodeId);
            } else {
                // invit.prefixLength > this.currentPrefixLength
                // sender node is lagging behind, this node's already in
                // advance so we don't want to swap with the sender
                r = new Reply(Status.NO);
            }
            network.send(r,this, Collections.singleton(from));
        }

        /**
         * Code that deals with the receipt of a reply after an invitation.
         * If the reply has a positive status, then the two nodes will
         * perform the exchange of aggregated data.
         */
        public void onReply(@NotNull SanFerminNode from, @NotNull Reply reply) {
            if (reply.level != this.currentPrefixLength ) {
                // out of date reply, we simply skip it
                return;
            }
            switch (reply.status) {
                case OK:
                    // we reply with our own value
                    Swap s = new Swap(currentPrefixLength,this.aggValue);
                    network.send(s, this, Collections.singleton(from));
                    // we aggregate values
                    this.aggValue += reply.aggValue;
                    // go to the next level of swapping
                    this.swapNextLevel();
                case MAYBE_LATER:
                    // TODO or not since this features seems to only be there
                    // if we dont know the full set of nodes,
                case NO:
                    SanFerminNode next = this.pickNextNode();
                    this.sendInvitation(next);
                default:
                    throw new Error("That should never happen");
            }
        }

        /**
         * OnSwap performs the swap of aggregated data with another node.
         * It checks if the swap comes from a previous invitation and if it
         * does aggregate with the received value and move on to the next
         * swapping.
         */
        public void onSwap(@NotNull SanFerminNode from, @NotNull Swap swap) {
            Set<Integer> set =
                    this.pendingInvits.getOrDefault(this.currentPrefixLength,
                    new HashSet<Integer>());
            if (!set.contains(from.nodeId)) {
                throw new IllegalStateException("That should never happen, " +
                        "since we are living in a perfect world");
            }
            this.aggValue += swap.aggValue;
            this.swapNextLevel();

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
            if (this.aggValue >= threshold && !done) {
                // we have finished :)
                doneAt = network.time + pairingTime * 2;
                finishedNodes.add(this);
                done = true;
                return;
            }
            // we aggregate
            aggValue += 1; // fixed amount for testing
            this.pendingInvits.remove(currentPrefixLength);
            this.currentPrefixLength--;
            SanFerminNode toSwap = this.pickNextNode();
            this.sendInvitation(toSwap);
        }

        /**
         * sendInvitation adds the nodeid to the list of pending invits and
         * send the invitation out.
         */
        private void sendInvitation(@NotNull SanFerminNode node) {
            Invitation i = new Invitation(this.currentPrefixLength);
            addNodeToPending(node.nodeId);
            network.send(i,this, Collections.singleton(node));
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
            if (list.size() == 0)
                // TODO This can and will happen if the number of nodes is not
                // a power of two, as there will be some nodes without a
                // potentical swappable candidate
                throw new IllegalStateException("an empty list should not " +
                        "happen in a simulation, where everything is perfect");

            Collections.shuffle(list);
            SanFerminNode node = list.remove(0);
            candidateSets.put(currentPrefixLength,list);
            return node;
        }

        private  void addNodeToPending(int nodeId) {
            Set<Integer> set =
                    pendingInvits.getOrDefault(currentPrefixLength,
                            new HashSet<>());
            set.add(nodeId);
            pendingInvits.put(currentPrefixLength,set);
        }

        @Override
        public String toString() {
            return "SanFerminNode{" +
                    "nodeId=" + nodeId +
                    ", doneAt=" + doneAt +
                    ", sigs=" + aggValue +
                    ", msgReceived=" + msgReceived +
                    ", msgSent=" + msgSent +
                    ", KBytesSent=" + bytesSent / 1024 +
                    ", KBytesReceived=" + bytesReceived / 1024 +
                    '}';
        }
    }



    /**
     * An invitation is sent from one node to the other to ask if the
     * destination wishes to swap the data they have aggregated so far.
     */
    class Invitation extends Network.MessageContent<SanFerminNode> {
         /**
          * Requested level of common prefix on which to swap data
          */
         final int prefixLength;

        public Invitation(int length ) {
            this.prefixLength = length;
        }

        @Override
        public void action(@NotNull SanFerminNode from, @NotNull SanFerminNode to) {
            to.onInvitation(from,this);
        }

        @Override
        public int size() {
            return 4; // uint32
        }
    }

    /**
     * Reply is a response to an Invitation that can have different status:
     *  - OK means the nodes are ready to perform the swap. The data is
     *  embedded in the reply in this case already.
     *  - NO means the other node is not willing to perform the swap, as he's
     *  likely at a lower level in his binomial swap tree,i.e. he is closer
     *  to finishing the protocol
     *  - MAYBE means the node is a at a higher level in the binomial swap
     *  tree,i.e. he is "late" in the protocol compared to the node that sent
     *  the invitation.
     */
     class Reply extends Network.MessageContent<SanFerminNode> {

        public Status status;
        // just as a reminder as it's probably going to be needed
        // in real code anyway, if the status is OK.
        public int level;
        // number of aggregated values in total
        public int aggValue;

        public Reply(Status status, int level,int aggValue) {
            this.status = status;
            this.level = level;
            this.aggValue = aggValue;
        }

        public Reply(Status status) {
            this.status = status;
        }

        @Override
        public void action(@NotNull SanFerminNode from, @NotNull SanFerminNode to) {
            to.onReply(from,this);
        }

        @Override
        public int size() {
            int min = 1 + 4; // uint8 + uint32
            if (this.status == Status.OK) {
                return min + signatureSize;
            }
            return min;
        }
    }

    public enum Status {
        OK, NO, MAYBE_LATER
    }

    class Swap extends Network.MessageContent<SanFerminNode> {

        final int level;
        int aggValue; // see Reply.aggValue
        // String data -- no need to specify it, but only in the size() method


        public Swap(int level,int aggValue) {
            this.level = level;
        }

        @Override
        public void action(@NotNull SanFerminNode from, @NotNull SanFerminNode to) {
            to.onSwap(from,this);
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

        SanFerminSignature p2ps;
        p2ps = new SanFerminSignature(1024, 10,
                1024/2, 3,48);
        p2ps.network.setNetworkLatency(distribProp, distribVal).setMsgDiscardTime(1000);
        //p2ps.network.removeNetworkLatency();
        p2ps.network.run(15);
        System.out.println(p2ps.finishedNodes);
    }
}
