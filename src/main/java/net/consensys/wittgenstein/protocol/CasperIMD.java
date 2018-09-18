package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.Block;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@SuppressWarnings({"WeakerAccess", "SameParameterValue", "unused"})
public class CasperIMD {
    final long SLOT_DURATION = 8000;
    final int CYCLE_LENGTH = 5;
    final boolean RANDOM_ON_TIES;


    final int blockProducersCount;
    final int attestersCount;
    final int attestersPerRound;
    final long blockConstructionTime;
    final long attestationConstructionTime;
    final int percentageDeadAttester;

    public CasperIMD() {
        this(true, 5, 80, 1000, 1, 0);
    }

    public CasperIMD(boolean RANDOM_ON_TIES, int blockProducersCount, int attestersPerRound,
                     long blockConstructionTime,
                     long attestationConstructionTime, int percentageDeadAttester) {
        this.RANDOM_ON_TIES = RANDOM_ON_TIES;
        this.blockProducersCount = blockProducersCount;
        this.attestersPerRound = attestersPerRound;
        this.attestersCount = attestersPerRound * CYCLE_LENGTH;
        this.blockConstructionTime = blockConstructionTime;
        this.attestationConstructionTime = attestationConstructionTime;
        this.percentageDeadAttester = percentageDeadAttester;

        this.network = new Network(new CasperNode(0, 0, genesis) {
        });
    }

    final Network network;
    final CasperBlock genesis = new CasperBlock();

    final ArrayList<Attester> attesters = new ArrayList<>();
    final ArrayList<BlockProducer> bps = new ArrayList<>();


    // Spec: attestation, [current_slot,h1,h2....h64], where h1...h64 are the hashes
    //   of the ancestors of the head up to 64 slots and current_slot is the current slot number.
    //   (if a chain has missing slots between heights a and b, then use the hash of the block at
    //   height a for heights a+1....b−1), and current_slot is the current slot number.
    class Attestation extends Network.MessageContent {
        final Attester attester;
        final int height;
        final Set<Long> hs = new HashSet<>(); // technically, we put them in a set for efficiency
        final CasperBlock head; // It's not in the spec, but we need this to be sure we're not confusing the attestations from different branches

        public Attestation(Attester attester, int height) {
            this.attester = attester;
            this.height = height;
            this.head = attester.head;

            // Note that's not the head, but the head's parent. As a son, we're going to be selected
            //  from the attestations to our parents, so that makes sense. But it means that the attestation
            //  is valid for all sons of the same parent (hence the need to add 'head'.
            for (Block cur = attester.head.parent; cur != null && cur.height >= attester.head.height - CYCLE_LENGTH; cur = cur.parent) {
                hs.add(cur.id);
            }
        }

        @Override
        public void action(Node from, Node to) {
            ((CasperNode) to).onAttestation(this);
        }

        boolean attests(@NotNull CasperBlock cb) {
            return (hs.contains(cb.id));
        }

        @Override
        public String toString() {
            return "Attestation{" +
                    "attester=" + attester.nodeId +
                    ", height=" + height +
                    ", ids=" + hs.size() +
                    '}';
        }
    }


    static class CasperBlock extends Block<CasperBlock> {
        final Map<Integer, Set<Attestation>> attestationsByHeight;

        public CasperBlock(BlockProducer blockProducer,
                           int height, CasperBlock head, Map<Integer, Set<Attestation>> attestationsByHeight, boolean b, long time) {
            super(blockProducer, height, head, b, time);
            this.attestationsByHeight = attestationsByHeight;
        }

        public CasperBlock() {
            this.attestationsByHeight = Collections.emptyMap();
        }

        @Override
        public String toString() {
            if (id == 0) return "genesis";
            StringBuilder sb = new StringBuilder();
            sb.append("{" + " height=").append(height).append(", id=").append(id);
            sb.append(", proposalTime=").append(proposalTime).append(", parent=").append(parent.id);

            List<Integer> keys = new ArrayList<>(attestationsByHeight.keySet());
            Collections.sort(keys);
            for (Integer h : keys) {
                List<Attestation> as = new ArrayList<>(attestationsByHeight.get(h));
                as.sort(Comparator.comparingInt(o -> o.attester.nodeId));

                if (!as.isEmpty()) {
                    sb.append(", (h ").append(h).append(":");

                    for (Attestation a : as) {
                        sb.append(" ").append(a.attester.nodeId);
                    }
                    sb.append(")");
                }
            }
            sb.append('}');
            return sb.toString();
        }
    }


    abstract class CasperNode extends Node<CasperBlock> {
        final Map<Long, Set<Attestation>> attestationsByHead = new HashMap<>();
        final Set<CasperBlock> lastReceivedBlocks = new HashSet<>();
        final long startAt;

        CasperNode(int nodeId, long pos, CasperBlock genesis) {
            super(nodeId, genesis);
            this.startAt = pos * SLOT_DURATION;
        }

        @Override
        public CasperBlock best(CasperBlock o1, CasperBlock o2) {
            if (o1 == o2) return o1;

            if (!o2.valid) return o1;
            if (!o1.valid) return o2;

            if (o1.height == o2.height) {
                // Someone sent two blocks for the same height
                // Could be made slashable. For now we don't support this case
                throw new IllegalStateException();
            }

            if (o1.hasDirectLink(o2)) // if 'o1' is an ancestor of 'o2', then 'o1' is NOT greater than 'o2'
                return (o1.height < o2.height ? o2 : o1);

            // Spec:
            // Choose the descendant of H such that the highest number of validators attests to H
            // (ie. published an attestation where H∈h1...h64).

            // Here we know we're on two different branches
            //  1) We need to find the first common block (i.e. eg. the 'H' of the spec)
            //  2) Then we will count the votes for 'H' on the two branches
            // We suppose here we already received the parents of the block b. It may not be true in reality

            // Phase 1: find 'H'
            CasperBlock b1 = o1;
            CasperBlock b2 = o2;
            while (b1.parent != b2.parent) {
                assert b1.parent.height != b2.parent.height;
                if (b1.parent.height > b2.parent.height) {
                    b1 = b1.parent;
                } else {
                    b2 = b2.parent;
                }
            }
            CasperBlock h = b1.parent;

            // Phase 2: count the votes
            int b1Votes = countAttestations(o1, h);
            int b2Votes = countAttestations(o2, h);

            // Decision time
            if (b1Votes > b2Votes) return o1;
            if (b1Votes < b2Votes) return o2;

            if (RANDOM_ON_TIES) {
                // VB: I’d say break ties via client-side randomness. Seems safest in the existing cases where it’s been studied.
                return network.rd.nextBoolean() ? o1 : o2;
            } else {
                return b1.id >= b2.id ? o1 : o2;
            }
        }

        /**
         * Count the number of attestations we have for block 'h' for the branch ending on block 'start'
         */
        protected int countAttestations(@NotNull CasperBlock start, @NotNull CasperBlock h) {
            // We can have attestations we received directly and attestations contained in the blocks.
            //  We obviously need to count them only once
            // Also, we cannot reuse attestations that are for other branches.

            HashSet<Attestation> a1 = new HashSet<>();

            for (CasperBlock cur = start; cur != h; cur = cur.parent) {
                assert cur != null;

                // Then, for all the block, we take the attestation to 'h' they contain
                for (int i = cur.height - 1; i > h.height; i--) {
                    for (Attestation a : cur.attestationsByHeight.getOrDefault(i, new HashSet<>())) {
                        // We may have attestation that will attest a parent of 'h' but not 'h'
                        if (a.attests(h)) a1.add(a);
                    }
                }

                // Lastly, we look at the attestation we received
                //  We take only the attestation with an head on our branch.
                for (Attestation a : attestationsByHead.getOrDefault(cur.id, new HashSet<>())) {
                    if (a.attests(h)) a1.add(a);
                }
            }


            return a1.size();
        }

        private Set<Long> attestsFor(int height) {
            Set<Long> as = new HashSet<>();
            for (Block c = head; c != genesis && height - CYCLE_LENGTH >= c.height; c = c.parent) {
                as.add(c.id);
            }
            return as;
        }


        @Override
        public boolean onBlock(@NotNull CasperBlock b) {
            // Spec: The node’s local clock time is greater than or equal to the minimum timestamp as
            // computed by GENESIS_TIME + slot_number * SLOT_DURATION
            if (network.time >= genesis.proposalTime + b.height * SLOT_DURATION) {
                lastReceivedBlocks.add(head); // if head loose the race it may win later.
                lastReceivedBlocks.add(b);
                return super.onBlock(b);
            }
            return false;
        }

        void onAttestation(@NotNull Attestation a) {
            // A vote for a block is a vote for all its parents.
            // Spec: publish a (signed) attestation, [current_slot,h1,h2....h64], where h1...h64 are the hashes
            //   of the ancestors of the head up to 64 slots and current_slot is the current slot number.
            //   (if a chain has missing slots between heights a and b, then use the hash of the block at
            //   heigh a for heights a+1....b−1), and current_slot is the current slot number.
            //
            // We don't reuse the attestationsByBlock between branches
            //  if A1 voted for B2 --> B1, the vote for B1 should not be reused in another branch like (B3 --> B1) for fork rules
            //  here: https://docs.google.com/presentation/d/1aqU1gK8B_sozm6orNVqqyvBTC2u2_13Re0Fi99rFhjg/edit#slide=id.g413fdd60fc_1_250
            Set<Attestation> as = attestationsByHead.computeIfAbsent(a.head.id, k -> new HashSet<>());
            as.add(a);
        }

        /**
         * As we take into account the attestations we received directly and not only the attestations received
         * the head could change at each attestation received. But we don't really need to reevaluate all the time
         * we just need to check when we're going to emit a new block or a new attestation.
         * <p>
         * The spec doesn't say anything about this case but it seems logic. In a p2p where we're supposed to
         * send the head, it may be less possible.
         */
        void reevaluateHead() {
            for (CasperBlock b : lastReceivedBlocks) {
                head = best(head, b);
            }
            lastReceivedBlocks.clear();
        }

        @Override
        public String toString() {
            return "CasperNode{" +
                    "nodeId=" + nodeId +
                    '}';
        }
    }


    class BlockProducer extends CasperNode {

        BlockProducer(int nodeId, long pos, @NotNull CasperBlock genesis) {
            super(nodeId, pos, genesis);
        }

        CasperBlock buildBlock(CasperBlock base, int height) {
            // Spec:
            // [Network.Block proposer] is expected to create (“propose”) a block, which contains a pointer to some
            //  parent block that they perceive as the “head of the chain”, and includes all of the attestations
            //  that they know about that have not yet been included into that chain.
            //
            // So we merge all the previous blocks and the ones we received
            Map<Integer, Set<Attestation>> res = new HashMap<>();
            for (int i = height - 1; i >= 0 && i >= height - CYCLE_LENGTH; i--) {
                res.put(i, new HashSet<>());
            }

            // phase 1: take all attestations already included in our parent's blocks.
            // as each block includes only the new attestation, we need to go through all parents < 64

            Set<Attestation> allFromBlocks = new HashSet<>();
            for (CasperBlock cur = base; cur != genesis && cur.height >= height - CYCLE_LENGTH; cur = cur.parent) {
                for (Set<Attestation> ats : cur.attestationsByHeight.values()) {
                    allFromBlocks.addAll(ats);
                }
            }

            // phase 2: add all missing attestations
            for (CasperBlock cur = base;
                 cur != null && cur.height >= height - CYCLE_LENGTH;
                 cur = cur.parent) {

                Set<Attestation> as = attestationsByHead.getOrDefault(cur.id, new HashSet<>());

                for (Attestation a : as) {
                    if (a.height < height && !allFromBlocks.contains(a)) {
                        Set<Attestation> sa = res.computeIfAbsent(a.height, k -> new HashSet<>());
                        sa.add(a);
                    }
                }
            }

            CasperBlock newBlock = new CasperBlock(this, height, base, res, true, network.time);
            return newBlock;
        }

        void createAndSendBlock(int height) {
            head = buildBlock(head, height);
            network.sendAll(new Network.SendBlock(head), network.time + blockConstructionTime, this);
        }

        @Override
        public Network.StartWork firstWork() {
            return network.new StartWork(startAt + SLOT_DURATION);
        }

        @Override
        public Network.StartWork work(long time) {
            reevaluateHead();
            createAndSendBlock((int) (time / SLOT_DURATION));
            return network.new StartWork(time + blockProducersCount * SLOT_DURATION);
        }

        @Override
        public String toString() {
            return "BlockProducer{" +
                    "nodeId=" + nodeId +
                    '}';
        }
    }


    class Attester extends CasperNode {

        Attester(int nodeId, long pos, @NotNull CasperBlock genesis) {
            super(nodeId, pos, genesis);
        }


        private void vote(int height) {
            // Spec:
            // After 4 seconds, [attesters] are expected to take the newly published block
            // (if it has actually been published) into account, determine what they think is the new
            // “head of the chain” (if all is well, this will generally be the newly published block),
            // and publish a (signed) attestation
            reevaluateHead();
            Attestation v = new Attestation(this, height);
            network.sendAll(v, network.time + attestationConstructionTime, this);
        }

        @Override
        public Network.StartWork firstWork() {
            return network.new StartWork(startAt + SLOT_DURATION / 2);
        }

        @Override
        public Network.StartWork work(long time) {
            vote((int) (time / SLOT_DURATION));
            return network.new StartWork(time + (CYCLE_LENGTH * SLOT_DURATION));
        }

        @Override
        public String toString() {
            return "Attester{" +
                    "nodeId=" + nodeId +
                    '}';
        }
    }


    void init(BlockProducer byzantineNode) {
        if (byzantineNode.nodeId != 1) throw new IllegalStateException();

        int nodeId = 2;

        bps.add(byzantineNode);
        network.addNode(byzantineNode);
        for (int i = 1; i < blockProducersCount; i++) {
            BlockProducer n = new BlockProducer(nodeId++, i, genesis);
            bps.add(n);
            network.addNode(n);
        }

        for (int i = 0; i < attestersCount; i++) {
            Attester n = new Attester(nodeId++, i % CYCLE_LENGTH, genesis);
            attesters.add(n);
            network.addNode(n);
        }

    }

    /**
     * Wait "a little" before sending its own block.
     * Idea: it allows to include more transactions
     */
    class ByzantineProd extends BlockProducer {
        int toSend = 1;
        int h;
        final long delay;
        int onDirectFather = 0;
        int onOlderAncestor = 0;
        int incNotTheBestFather = 0;

        ByzantineProd(int nodeId, long delay, @NotNull CasperBlock genesis) {
            super(nodeId, 0, genesis);
            this.delay = delay;
        }

        public void revaluateH(long time) {
            reevaluateHead();

            long slotTime = time > SLOT_DURATION ? time - delay : time; // for the first slot we're honest => no delay
            h = (int) (slotTime / SLOT_DURATION);

            if (h != toSend) throw new IllegalStateException();
        }

        @Override
        public Network.StartWork work(long time) {
            revaluateH(time);

            if (head.height == h - 1) {
                onDirectFather++;
            } else {
                onOlderAncestor++;
                // Sometimes we received our direct father but it wasn't the best head
                Block possibleFather = blocksReceivedByHeight.get(h - 1);
                if (possibleFather != null && possibleFather.parent.height != h - 1) {
                    incNotTheBestFather++;
                }
            }

            createAndSendBlock(toSend);
            int lastSent = toSend;
            toSend += blockProducersCount;

            return network.new StartWork((lastSent * SLOT_DURATION) + (blockProducersCount * SLOT_DURATION) + delay);
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "{" +
                    "delay=" + delay +
                    ", onDirectFather=" + onDirectFather +
                    ", onOlderAncestor=" + onOlderAncestor +
                    ", incNotTheBestFather=" + incNotTheBestFather +
                    '}';
        }
    }

    /**
     * Skip its son's block.
     * Idea: it can them include the transactions of its son.
     */
    class ByzantineProdSF extends ByzantineProd {
        ByzantineProdSF(int nodeId, long delay, @NotNull CasperBlock genesis) {
            super(nodeId, delay, genesis);
        }

        @Override
        public Network.StartWork work(long time) {
            revaluateH(time);
            if (head.id != 0 && head.height == h - 1) {
                head = head.parent;
                onDirectFather++;
            } else {
                onOlderAncestor++;
            }


            createAndSendBlock(toSend);
            int lastSent = toSend;
            toSend += blockProducersCount;
            return network.new StartWork((lastSent * SLOT_DURATION) + (blockProducersCount * SLOT_DURATION) + delay);
        }
    }

    class ByzantineProdNS extends ByzantineProd {
        ByzantineProdNS(int nodeId, long delay, @NotNull CasperBlock genesis) {
            super(nodeId, delay, genesis);
        }

        @Override
        public Network.StartWork work(long time) {
            revaluateH(time);

            if (head.id != 0 && head.height == h - 1 && head.parent.height == h - 3) {
                for (CasperBlock b : blocksReceivedByFatherId.get(head.parent.id)) {
                    if (b.height == h - 2) {
                        head = b;
                        break;
                    }
                }
            }

            createAndSendBlock(toSend);
            int lastSent = toSend;
            toSend += blockProducersCount;
            return network.new StartWork((lastSent * SLOT_DURATION) + (blockProducersCount * SLOT_DURATION) + delay);
        }
    }

    /**
     * Wait for the previous block (height - 1) before applying the delay
     */
    @SuppressWarnings("unused")
    class ByzantineProdWF extends ByzantineProd {
        int late = 0;
        int onTime = 0;

        ByzantineProdWF(int nodeId, long delay, @NotNull CasperBlock genesis) {
            super(nodeId, delay, genesis);
        }

        @Override
        public boolean onBlock(@NotNull final CasperBlock b) {
            if (super.onBlock(b)) {
                if (b.height == toSend - 1) {

                    long perfectDate = SLOT_DURATION * toSend + delay;

                    Runnable r = new Runnable() {
                        final int th = toSend;

                        @Override
                        public void run() {
                            CasperBlock nh = buildBlock(b, th);
                            network.sendAll(new Network.SendBlock(nh), network.time + blockConstructionTime, ByzantineProdWF.this);
                        }
                    };
                    toSend += blockProducersCount;

                    if (network.time >= perfectDate) {
                        r.run();
                        late++;
                    } else {
                        network.registerTask(r, perfectDate, this);
                        onTime++;
                    }

                }
                return true;
            } else {
                return false;
            }

        }

        @Override
        public Network.StartWork work(long time) {
            super.work(time); // we just
            return null;
        }

        @Override
        public String toString() {
            return "ByzantineProdWF{" +
                    "delay=" + delay +
                    ", late=" + late +
                    ", onTime=" + onTime +
                    '}';
        }
    }

    public static void main(String... args) {

        new CasperIMD().network.printNetworkLatency();

        for (int delay = -3000; delay < 15000; delay += 100000) {
            CasperIMD bc = new CasperIMD();
            bc.init(bc.new ByzantineProd(Network.BYZANTINE_NODE_ID, delay, bc.genesis));
            //bc.network.removeNetworkLatency();

            List<List<? extends Node>> lns = new ArrayList<>();
            lns.add(bc.bps);
            lns.add(bc.attesters);

            bc.network.run(30);

            //   bc.network.partition(.5f, lns);
            bc.network.run(3600 * 5); // 5 hours is a minimum if you want something statistically reasonable
            //   bc.network.endPartition();

            bc.network.run(30);

            // System.out.println("");
            bc.network.printStat(false);
        }
    }
}
