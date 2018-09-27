package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;


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
public class SanFerminOptimistic {

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
     * Should we shuffle the list of the candidate sets at each peers
     */
    boolean shuffledLists;

    /**
     * how many candidate do we try to reach at the same time for a given level
     */
    int candidateCount;

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


    public SanFerminOptimistic(int totalCount,int powerOfTwo, int threshold,
                              int pairingTime,int signatureSize,
                        int replyTimeout,int candidateCount, boolean shuffledLists) {
        this.totalCount = totalCount;
        this.powerOfTwo = powerOfTwo;
        this.threshold = threshold;
        this.pairingTime = pairingTime;
        this.signatureSize = signatureSize;
        this.replyTimeout = replyTimeout;
        this.candidateCount = candidateCount;
        this.shuffledLists = shuffledLists;

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
            network.registerTask(n::goNextLevel, 1, n);
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
         * BitSet to save which node have we sent swaprequest so far
         */
        HashMap<Integer, BitSet> usedCandidates;

        /**
         * Set of aggregated signatures saved. Since it's only a logarithmic
         * number it's not much to save (log_2(20k) = 15)
         */
        HashMap<Integer,Integer> signatureCache;

        /**
         * Ids of the nodes we have sent a SwapRequest to. Different
         * strategies to pick the nodes are detailled in `pickNextNodes`
         */
        Set<Integer> pendingNodes;

        /**
         * isSwapping indicates whether we are in a state where we can swap at
         * the current level or not. This is acting as a Lock, since between
         * the time we receive a valid swap and the time we aggregate it, we
         * need to verify it. In the meantime, another valid swap can come
         * and thus we would end up swapping twice at a given level.
         */
        boolean isSwapping;

        /**
         *  ----- STATS INFORMATION -----
         */
        /**
         * Integer field that simulate an aggregated signature badly. It keeps
         * increasing as the node do more swaps. It
         * assumes each node has the value "1" and the aggregation operation
         * is the addition.
         */
        int aggValue;

        /**
         * time when threshold of signature is reached
         */
        long thresholdAt;
        /**
         * have we reached the threshold or not
         */
        boolean thresholdDone;
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
            this.binaryId =
                    leftPadWithZeroes(Integer.toBinaryString(this.nodeId),
                            powerOfTwo);
            this.candidateSets = new HashMap<>();
            this.usedCandidates = new HashMap<>();
            this.done = false;
            this.thresholdDone = false;
            this.aggValue = 1;
            this.isSwapping = false;
            // node should start at n-1 with N = 2^n
            // this counter gets decreased with `goNextLevel`.
            this.currentPrefixLength = powerOfTwo;
            this.signatureCache = new HashMap<>();
        }

        /**
         * onSwap checks if it is a swap a the current level and from a
         * candidate node. If it is, it reply with his own swap, aggregates the
         * value and move on to the next level.
         * If it is not at the current level, it replies with a cached value
         * if it has, or save the value for later if valid.
         * If it is not from a candidate set, then it drops the message.
         */
       public void onSwap(@NotNull SanFerminNode from,
                                 @NotNull Swap swap) {
           boolean wantReply = swap.wantReply;
           if (done || swap.level != this.currentPrefixLength) {
                boolean isValueCached =
                        this.signatureCache.containsKey(swap.level);
                if (wantReply && isValueCached){
                        print("sending back CACHED signature at level " +
                                swap.level + " to node "+ from.binaryId);
                        this.sendSwap(Collections.singletonList(from),swap.level,
                                this.signatureCache.get(swap.level), false);
                } else {
                   // TODO check if it is a valid value and save it for later
                   //this.sendSwap(from,Status.NO,0,0);
               }
               return;
            }

            if (wantReply) {
                this.sendSwap(Collections.singletonList(from),swap.level,
                        this.aggValue,false);
                return;
            }

            if (isSwapping) return; // do nothing if we are already swapping


            // accept if it is a valid swap !
            boolean isCandidate =
                   candidateSets.get(currentPrefixLength).contains(from);
            boolean goodLevel = swap.level == currentPrefixLength;
            boolean isValidSig = true; // as always :)
            if (isCandidate && goodLevel && isValidSig) {
               transition(" received valid SWAP ",from.binaryId,
                       swap.level,swap.aggValue);
            } else {
               print(" received  INVALID Swap" +
                       "from " + from.binaryId + " at level " + swap.level);
            }
        }

        /**
         * tryNextNode simply picks the next eligible candidate from the list
         * and send a swap request to it. It attaches a timeout to the
         * request. If no SwapReply has been received before timeout,
         * tryNextNode() will be called again.
         */
        private void tryNextNode() {
            // TODO move to fully multiple node mode ! but git commit before
            List<SanFerminNode> candidates = this.pickNextNodes();
            if (candidates.size() == 0) {
                // when malicious actors are introduced or some nodes are
                // failing this case can happen. In that case, the node
                // should go to the next level since he has nothing better to
                // do. The final aggregated signature will miss this level
                // but it may still reach the threshold and can be completed
                // later on, through the help of a bit field.
                print(" is OUT (no more " +
                        "nodes to pick)");
                return;
            }

            // add ids to the set of pending nodes
            this.pendingNodes.addAll(candidates.stream().map(n -> n.nodeId).collect(Collectors.toList()));

            print(" send SwapRequest to " + String.join(" - ",
                    candidates.stream().map(n -> n.binaryId).collect(Collectors.toList())));
            this.sendSwap(candidates,this.currentPrefixLength,this.aggValue,
                    true);

            int currLevel = this.currentPrefixLength;
            network.registerTask(() -> {
                // If we are still waiting on an answer for this level, we
                // try a new one.
                if (!SanFerminNode.this.done &&
                        SanFerminNode.this.currentPrefixLength == currLevel) {
                    print("TIMEOUT of SwapRequest at level " + currLevel);
                    // that means we haven't got a successful reply for that
                    // level so we try another node
                    tryNextNode();
                }
            },network.time + replyTimeout, SanFerminNode.this);

        }


        /**
         * goNextLevel reduces the required length of common prefix of one,
         * computes the new set of potential nodes and sends an invitation to
         * the "next" one. There are many ways to select the order of which
         * node to choose. In case the number of nodes is 2^n, there is a
         * 1-to-1 mapping that exists so that each node has exactly one
         * unique node to swap with. In case it's not, there are going to be
         * some nodes who will be unable to continue the protocol since the
         * "chosen" node will likely already have swapped. See
         * `pickNextNode` for more information.
         */
        private void goNextLevel() {

            if (done) {return;}

            boolean enoughSigs = this.aggValue >= threshold;
            boolean noMoreSwap = this.currentPrefixLength == 0;

            if (enoughSigs && !thresholdDone) {
                print(" --- THRESHOLD REACHED --- ");
                thresholdDone = true;
                thresholdAt = network.time + pairingTime *2;
            }

            if (noMoreSwap && !done) {
                print(" --- FINISHED ---- protocol");
                doneAt = network.time + pairingTime * 2;
                finishedNodes.add(this);
                done = true;
                return;
            }
            this.currentPrefixLength--;
            this.signatureCache.put(this.currentPrefixLength,this.aggValue);
            this.isSwapping = false;
            this.pendingNodes = new HashSet<>();
            this.tryNextNode();
        }

        private void sendSwap(@NotNull List<SanFerminNode> nodes,
                                   int level, int value,boolean reply) {
            Swap r = new Swap(level,value,reply);
            network.send(r,SanFerminNode.this,nodes);
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
                        new LinkedList<>());
                list.add(node);
                candidateSets.put(length,list);
            }
            if (shuffledLists)
                candidateSets.replaceAll((k,v) -> {
                    Collections.shuffle(v,network.rd);
                    return v;
                });

            for(Map.Entry<Integer,List<SanFerminNode>> entry :
                    candidateSets.entrySet()) {
                int size = entry.getValue().size();
                usedCandidates.put(entry.getKey(),new BitSet(size));
            }
        }

        /**
         * One big question is which node
         * to chose amongst the list so that it minimizes the number of "NO"
         * replies,ie. so that it minimizes the number of nodes who contact
         * another node who already swapped at this level.
         */
        private List<SanFerminNode> pickNextNodes() {
            return pickNextNode1();
        }

        /**
         * This
         * implementation randomly shuffle the list and pick the first one,
         * and perform the same steps for further nodes if the first one does
         * not work. There are tons of ways to perform this decision-making,
         * some which brings better guarantees probably. This PoC chooses to
         * use a random assignement, as most often, uniformity performs
         * better in computer science.
         * @return
         */
        private List<SanFerminNode> pickNextNode1(){
            List<SanFerminNode> list =
                    candidateSets.getOrDefault(currentPrefixLength,
                            new ArrayList<>());

            // iterate over bitset to find non-asked node yet
            BitSet set = usedCandidates.getOrDefault(currentPrefixLength,
                    new BitSet(0));

            // we expect to choose candidateCount number of candidates
            List<SanFerminNode> selectedCandidates = new ArrayList<>(candidateCount);
            boolean found = false;
            for(int i = 0; i < list.size() && selectedCandidates.size() <= candidateCount; i++){
                if (set.get(i))
                    continue;

                found = true;
                selectedCandidates.add(list.get(i));
                set.set(i,true);
                break;
            }
            if (!found) {
                // This may happen in case the number of nodes is not a power
                // of two, or more generally if the node already tried to
                // contact all of his eligible peers already
                return Collections.EMPTY_LIST;
            }
            usedCandidates.put(currentPrefixLength,set);
            return selectedCandidates;
        }

        /**
         * Transition prevents any more aggregation at this level, and launch
         * the "verification routine" and move on to the next level.
         * The first three parameters are only here for logging purposes.
         */
        private void transition(String type, String fromId, int level,
                                int toAggregate) {
            this.isSwapping = true;
            network.registerTask(() -> {
                int before = this.aggValue;
                this.aggValue += toAggregate;
                print(" received "+ type +" lvl=" + level +
                        " from " + fromId
                        + " aggValue " + before + " -> " + this.aggValue);
                this.goNextLevel();
            },network.time + pairingTime,this);
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
                System.out.println("t=" + network.time + ", id=" + this.nodeId +
                        ", lvl=" + this.currentPrefixLength + ", sent=" + this.msgSent +
                        " -> " + s);
        }

        @Override
        public String toString() {
            return "SanFerminNode{" +
                    "nodeId=" + binaryId +
                    ", thresholdAt=" + thresholdAt +
                    ", doneAt=" + doneAt +
                    ", sigs=" + aggValue +
                    ", msgReceived=" + msgReceived +
                    ", msgSent=" + msgSent +
                    ", KBytesSent=" + bytesSent / 1024 +
                    ", KBytesReceived=" + bytesReceived / 1024 +
                    '}';
        }
    }

    class Swap extends Network.MessageContent<SanFerminNode> {

        boolean wantReply; // indicate that the other needs a reply to this
        // Swap
        final int level;
        int aggValue; // see Reply.aggValue
        // String data -- no need to specify it, but only in the size() method


        public Swap(int level,int aggValue,boolean reply) {
            this.level = level;
            this.wantReply = reply;
            this.aggValue = aggValue;
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

        SanFerminOptimistic p2ps;
        //p2ps = new SanFerminOptimistic(1024, 10,512, 2,48,300,1,false);
        //p2ps = new SanFerminOptimistic(512, 9,256, 2,48,300,1,false);

        p2ps = new SanFerminOptimistic(8, 3,4, 2,48,300,3,true);
        p2ps.StartAll();
        p2ps.verbose = true;
        p2ps.network.setNetworkLatency(distribProp, distribVal).setMsgDiscardTime(1000);
        //p2ps.network.removeNetworkLatency();
        p2ps.network.run(30);
        Collections.sort(p2ps.finishedNodes,
                (n1,n2) -> {  return Long.compare(n1.thresholdAt,n2.thresholdAt);});
        int max = p2ps.finishedNodes.size() < 10 ? p2ps.finishedNodes.size()
                : 10;
        for(SanFerminNode n: p2ps.finishedNodes.subList(0,max))
            System.out.println(n);

        p2ps.network.printNetworkLatency();
    }
}

/*
*
* ONE NODE WITHOUT SHUFFLING
SanFerminNode{nodeId=110, thresholdAt=311, doneAt=1085, sigs=8, msgReceived=6, msgSent=6, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=100, thresholdAt=345, doneAt=475, sigs=8, msgReceived=7, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=000, thresholdAt=346, doneAt=486, sigs=8, msgReceived=8, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=010, thresholdAt=400, doneAt=1021, sigs=8, msgReceived=6, msgSent=6, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=101, thresholdAt=514, doneAt=888, sigs=8, msgReceived=4, msgSent=5, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=011, thresholdAt=691, doneAt=1553, sigs=8, msgReceived=3, msgSent=6, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=001, thresholdAt=739, doneAt=905, sigs=8, msgReceived=4, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
 * ONE NODE WITH SHUFFLING
 SanFerminNode{nodeId=111, thresholdAt=283, doneAt=751, sigs=8, msgReceived=5, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=101, thresholdAt=286, doneAt=588, sigs=8, msgReceived=5, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=110, thresholdAt=329, doneAt=465, sigs=8, msgReceived=7, msgSent=3, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=100, thresholdAt=378, doneAt=805, sigs=8, msgReceived=4, msgSent=4, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=011, thresholdAt=685, doneAt=754, sigs=8, msgReceived=6, msgSent=7, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}
SanFerminNode{nodeId=001, thresholdAt=739, doneAt=847, sigs=8, msgReceived=6, msgSent=5, KBytesSent=0, KBytesReceived=0, outdatedSwaps=0}

 */