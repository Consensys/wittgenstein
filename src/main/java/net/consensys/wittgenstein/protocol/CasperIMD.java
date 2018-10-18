package net.consensys.wittgenstein.protocol;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.tools.Graph;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Casper IMD - Beacon chain stage 1 (no justification, no dynasty changes)
 * https://ethresear.ch/t/beacon-chain-casper-ffg-rpj-mini-spec/2760
 */
@SuppressWarnings({"WeakerAccess", "SameParameterValue", "unused"})
public class CasperIMD {
  final int SLOT_DURATION = 8000;

  /**
   * Number of rounds per cycle. 64 in the spec
   */
  final int cycleLength;

  /**
   * On tie, the best strategy is randomness. But unit tests are simpler with determinist strategy.
   */
  final boolean randomOnTies;

  /**
   * Number of block producers. There is a single block producer per round.
   */
  final int blockProducersCount;

  /**
   * Number of attesters in a round. Spec says 892.
   */
  final int attestersPerRound;

  /**
   * Calculated attestersPerRound * cycleLength
   */
  final int attestersCount;

  /**
   * Time to build a block. Same for all blocks & all block producers.
   */
  final int blockConstructionTime;

  /**
   * Time to build an attestation. Same for all.
   */
  final int attestationConstructionTime;

  public CasperIMD() {
    this(5, true, 5, 80, 1000, 1);
  }

  public CasperIMD(int cycleLength, boolean randomOnTies, int blockProducersCount,
      int attestersPerRound, int blockConstructionTime, int attestationConstructionTime) {
    this.cycleLength = cycleLength;
    this.randomOnTies = randomOnTies;
    this.blockProducersCount = blockProducersCount;
    this.attestersPerRound = attestersPerRound;
    this.attestersCount = attestersPerRound * cycleLength;
    this.blockConstructionTime = blockConstructionTime;
    this.attestationConstructionTime = attestationConstructionTime;

    this.network.setNetworkLatency(new NetworkLatency.NetworkLatencyByDistance());
    this.network.addObserver(new CasperNode(false, genesis) {});
  }


  final BlockChainNetwork network = new BlockChainNetwork(0);
  final Node.NodeBuilder nb = new Node.NodeBuilderWithRandomPosition(network.rd);
  final CasperBlock genesis = new CasperBlock();

  final ArrayList<Attester> attesters = new ArrayList<>();
  final ArrayList<BlockProducer> bps = new ArrayList<>();


  // Spec: attestation, [current_slot,h1,h2....h64], where h1...h64 are the hashes
  //   of the ancestors of the head up to 64 slots and current_slot is the current slot number.
  //   (if a chain has missing slots between heights a and b, then use the hash of the block at
  //   height a for heights a+1....b−1), and current_slot is the current slot number.
  //
  // EF team repo:
  // https://github.com/ethereum/beacon_chain/blob/master/beacon_chain/state/attestation_record.py
  // It contains a field referencing the block hash: 'shard_block_hash': 'hash32'
  class Attestation extends Network.Message<CasperNode> {
    final Attester attester;
    final int height;
    final Set<Long> hs = new HashSet<>(); // technically, we put them in a set for efficiency
    final CasperBlock head;
    // It's not in the spec, but we need this to be sure we're not confusing the attestations from different branches

    public Attestation(Attester attester, int height) {
      this.attester = attester;
      this.height = height;
      this.head = attester.head;

      // Note that's not the head, but the head's parent. As a son, we're going to be selected
      //  from the attestations to our parents, so that makes sense. But it means that the attestation
      //  is valid for all sons of the same parent (hence the need to add 'head'.)
      for (Block cur = attester.head.parent; cur != null
          && cur.height >= attester.head.height - cycleLength; cur = cur.parent) {
        hs.add(cur.id);
      }
    }

    @Override
    public void action(CasperNode from, CasperNode to) {
      to.onAttestation(this);
    }

    boolean attests(CasperBlock cb) {
      return (hs.contains(cb.id));
    }

    @Override
    public String toString() {
      return "Attestation{" + "attester=" + attester.nodeId + ", height=" + height + ", ids="
          + hs.size() + '}';
    }
  }


  static class CasperBlock extends Block<CasperBlock> {
    final Map<Integer, Set<Attestation>> attestationsByHeight;

    public CasperBlock(BlockProducer blockProducer, int height, CasperBlock father,
        Map<Integer, Set<Attestation>> attestationsByHeight, boolean valid, int time) {
      super(blockProducer, height, father, valid, time);
      this.attestationsByHeight = attestationsByHeight;
    }

    public CasperBlock() {
      this.attestationsByHeight = Collections.emptyMap();
    }

    @Override
    public String toString() {
      if (id == 0)
        return "genesis";
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


  abstract class CasperNode extends BlockChainNode<CasperBlock> {
    final Map<Long, Set<Attestation>> attestationsByHead = new HashMap<>();
    final Set<CasperBlock> blocksToReevaluate = new HashSet<>();

    CasperNode(boolean byzantine, CasperBlock genesis) {
      super(nb, byzantine, genesis);
    }

    @Override
    public CasperBlock best(CasperBlock o1, CasperBlock o2) {
      if (o1 == o2)
        return o1;

      if (!o2.valid)
        return o1;
      if (!o1.valid)
        return o2;

      if (o1.height == o2.height) {
        // Someone sent two blocks for the same height
        // Could be made slashable. For now we don't support this case
        throw new IllegalStateException();
      }

      if (o1.hasDirectLink(o2)) { // if 'o1' is an ancestor of 'o2', then 'o1' is NOT greater than 'o2'
        return (o1.height < o2.height ? o2 : o1);
      }

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
      if (b1Votes > b2Votes)
        return o1;
      if (b1Votes < b2Votes)
        return o2;

      if (randomOnTies) {
        // VB: I’d say break ties via client-side randomness. Seems safest in the existing cases where it’s been studied.
        return network.rd.nextBoolean() ? o1 : o2;
      } else {
        return b1.id >= b2.id ? o1 : o2;
      }
    }

    /**
     * Count the number of attestations we have for block 'h' for the branch ending on block 'start'
     */
    protected int countAttestations(CasperBlock start, CasperBlock h) {
      // We can have attestations we received directly and attestations contained in the blocks.
      //  We obviously need to count them only once
      // Also, we cannot reuse attestations that are for other branches.

      Set<Attestation> a1 = new HashSet<>();

      for (CasperBlock cur = start; cur != h; cur = cur.parent) {
        assert cur != null;

        // Then, for all the block, we take the attestation to 'h' they contain
        for (int i = cur.height - 1; i > h.height; i--) {
          for (Attestation a : cur.attestationsByHeight.getOrDefault(i, Collections.emptySet())) {
            // We may have attestation that will attest a parent of 'h' but not 'h'
            if (a.attests(h))
              a1.add(a);
          }
        }

        // Lastly, we look at the attestation we received
        //  We take only the attestation with an head on our branch.
        for (Attestation a : attestationsByHead.getOrDefault(cur.id, Collections.emptySet())) {
          if (a.attests(h))
            a1.add(a);
        }
      }


      return a1.size();
    }

    private Set<Long> attestsFor(int height) {
      Set<Long> as = new HashSet<>();
      for (Block c = head; c != genesis && height - cycleLength >= c.height; c = c.parent) {
        as.add(c.id);
      }
      return as;
    }


    @Override
    public boolean onBlock(final CasperBlock b) {
      // Spec: The node’s local clock time is greater than or equal to the minimum timestamp as
      // computed by GENESIS_TIME + slot_number * SLOT_DURATION
      final int delta = network.time - genesis.proposalTime + b.height * SLOT_DURATION;
      if (delta >= 0) {
        blocksToReevaluate.add(head); // if head loose the race it may win later.
        blocksToReevaluate.add(b);
        return super.onBlock(b);
      } else {
        // Spec: If these conditions are not met, the client should delay processing the block until the
        //  conditions are all satisfied.
        network.registerTask(() -> onBlock(b), delta * -1, CasperNode.this);
        return false;
      }
    }

    void onAttestation(Attestation a) {
      // A vote for a block is a vote for all its parents.
      // Spec: publish a (signed) attestation, [current_slot,h1,h2....h64], where h1...h64 are the hashes
      //   of the ancestors of the head up to 64 slots and current_slot is the current slot number.
      //   (if a chain has missing slots between heights a and b, then use the hash of the block at
      //   height a for heights a+1....b−1), and current_slot is the current slot number.
      //
      // We don't reuse the attestationsByBlock between branches
      //  if A1 voted for B2 --> B1, the vote for B1 should not be reused in another branch like (B3 --> B1) for fork rules
      //  here: https://docs.google.com/presentation/d/1aqU1gK8B_sozm6orNVqqyvBTC2u2_13Re0Fi99rFhjg/edit#slide=id.g413fdd60fc_1_250
      Set<Attestation> as = attestationsByHead.computeIfAbsent(a.head.id, k -> new HashSet<>());
      as.add(a);

      // A new attestation => a possible change in the fork-choice-rule... If we have received the block!
      if (blocksReceivedByBlockId.containsKey(a.head.id)) {
        blocksToReevaluate.add(a.head);
      }
    }

    /**
     * As we take into account the attestations we received directly and not only the attestations
     * received the head could change at each attestation received. But we don't really need to
     * reevaluate all the time we just need to check when we're going to emit a new block or a new
     * attestation.
     * <p>
     * The spec doesn't say anything about this case but it seems logic. In a p2p where we're
     * supposed to send the head, it may be less possible.
     */
    void reevaluateHead() {
      for (CasperBlock b : blocksToReevaluate) {
        head = best(head, b);
      }
      blocksToReevaluate.clear();
    }

    @Override
    public String toString() {
      return "CasperNode{" + "nodeId=" + nodeId + '}';
    }

    protected Runnable getPeriodicTask() {
      return null;
    }
  }


  class BlockProducer extends CasperNode {

    BlockProducer(CasperBlock genesis) {
      super(false, genesis);
    }

    protected BlockProducer(boolean byzantine, CasperBlock genesis) {
      super(byzantine, genesis);
    }

    @Override
    protected Runnable getPeriodicTask() {
      return () -> {
        reevaluateHead();
        createAndSendBlock(network.time / SLOT_DURATION);
      };
    }


    CasperBlock buildBlock(CasperBlock base, int height) {
      // Spec:
      // [BlockChainNetwork.Block proposer] is expected to create (“propose”) a block, which contains a pointer to some
      //  parent block that they perceive as the “head of the chain”, and includes all of the attestations
      //  that they know about that have not yet been included into that chain.
      //
      // So we merge all the previous blocks and the ones we received
      Map<Integer, Set<Attestation>> res = new HashMap<>();
      for (int i = height - 1; i >= 0 && i >= height - cycleLength; i--) {
        res.put(i, new HashSet<>());
      }

      // phase 1: take all attestations already included in our parent's blocks.
      // as each block includes only the new attestations, we need to go through all parents < cycleLength
      Set<Attestation> allFromBlocks = new HashSet<>();
      for (CasperBlock cur = base; cur != genesis && cur.height >= height - cycleLength; cur =
          cur.parent) {
        for (Set<Attestation> ats : cur.attestationsByHeight.values()) {
          allFromBlocks.addAll(ats);
        }
      }

      // phase 2: add all missing attestations
      for (CasperBlock cur = base; cur != null && cur.height >= height - cycleLength; cur =
          cur.parent) {

        Set<Attestation> as = attestationsByHead.getOrDefault(cur.id, Collections.emptySet());

        for (Attestation a : as) {
          if (a.height < height && !allFromBlocks.contains(a)) {
            Set<Attestation> sa = res.computeIfAbsent(a.height, k -> new HashSet<>());
            sa.add(a);
          }
        }
      }

      return new CasperBlock(this, height, base, res, true, network.time);
    }

    void createAndSendBlock(int height) {
      head = buildBlock(head, height);
      network.sendAll(new BlockChainNetwork.SendBlock<CasperBlock, CasperNode>(head),
          network.time + blockConstructionTime, this);
    }

    @Override
    public String toString() {
      return "BlockProducer{" + "nodeId=" + nodeId + '}';
    }
  }


  class Attester extends CasperNode {

    Attester(CasperBlock genesis) {
      super(false, genesis);
    }

    @Override
    protected Runnable getPeriodicTask() {
      return () -> vote(network.time / SLOT_DURATION);
    }

    void vote(int height) {
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
    public String toString() {
      return "Attester{" + "nodeId=" + nodeId + '}';
    }
  }


  void init(CasperIMD.ByzBlockProducer byzantineNode) {
    bps.add(byzantineNode);
    network.addNode(byzantineNode);
    network.registerPeriodicTask(byzantineNode.getPeriodicTask(),
        SLOT_DURATION + byzantineNode.delay, SLOT_DURATION * blockProducersCount, byzantineNode);

    for (int i = 1; i < blockProducersCount; i++) {
      BlockProducer n = new BlockProducer(genesis);
      bps.add(n);
      network.addNode(n);
      network.registerPeriodicTask(n.getPeriodicTask(), SLOT_DURATION * (i + 1),
          SLOT_DURATION * blockProducersCount, n);
    }

    for (int i = 0; i < attestersCount; i++) {
      Attester n = new Attester(genesis);
      attesters.add(n);
      network.addNode(n);
      network.registerPeriodicTask(n.getPeriodicTask(),
          SLOT_DURATION * (1 + i % cycleLength) + 4000, SLOT_DURATION * cycleLength, n);
    }

  }

  /**
   * Wait "a little" before sending its own block. Idea: it allows to include more transactions
   */
  class ByzBlockProducer extends BlockProducer {
    int toSend = 1;
    int h;
    final int delay;
    int onDirectFather = 0;
    int onOlderAncestor = 0;
    int incNotTheBestFather = 0;

    ByzBlockProducer(int delay, CasperBlock genesis) {
      super(true, genesis);
      this.delay = delay;
    }

    /**
     * As this node is byzantine, it needs to take into account the delay to know what the actual
     * height is. Moreover, the current head may be off, because of the latest attestations. So we
     * need to recalculate all this: actual head & actual heights
     */
    public void reevaluateH(int time) {
      reevaluateHead();

      // There is a delay, so may be our current head is 'newer' than us. It's not
      //   legal to have a parent younger than us...
      while (head.height >= toSend) {
        head = head.parent;
      }

      int slotTime = time - delay;
      h = slotTime / SLOT_DURATION;

      if (h != toSend)
        throw new IllegalStateException("h=" + h + ", toSend=" + toSend);
    }

    @Override
    protected Runnable getPeriodicTask() {
      return () -> {
        reevaluateH(network.time);

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
      };
    }

    @Override
    public String toString() {
      return this.getClass().getSimpleName() + "{" + "delay=" + delay + ", onDirectFather="
          + onDirectFather + ", onOlderAncestor=" + onOlderAncestor + ", incNotTheBestFather="
          + incNotTheBestFather + '}';
    }
  }


  /**
   * Skip its father's block. Idea: it can them include the transactions of its father.
   */
  class ByzBlockProducerSF extends ByzBlockProducer {
    ByzBlockProducerSF(int nodeId, int delay, CasperBlock genesis) {
      super(delay, genesis);
    }

    @Override
    protected Runnable getPeriodicTask() {
      return () -> {
        reevaluateH(network.time);
        if (head.id != 0 && head.height == h - 1) {
          head = head.parent;
          onDirectFather++;
        } else {
          onOlderAncestor++;
        }

        createAndSendBlock(toSend);
        int lastSent = toSend;
        toSend += blockProducersCount;
      };
    }
  }


  /**
   * Try to skip his father if his father skipped his grand father Idea: you will have the
   * attestation of your grand father with you, so you will win the fight with the grand father.
   */
  class ByzBlockProducerNS extends ByzBlockProducer {
    ByzBlockProducerNS(int nodeId, int delay, CasperBlock genesis) {
      super(delay, genesis);
    }

    int skipped = 0;

    @Override
    protected Runnable getPeriodicTask() {
      return () -> {
        reevaluateH(network.time);

        if (head.id != 0 && head.height == h - 1 && head.parent.height == h - 3) {
          CasperBlock b = blocksReceivedByHeight.get(h - 2);
          if (b != null) {
            head = b;
            skipped++;
          }
        }


        createAndSendBlock(toSend);
        int lastSent = toSend;
        toSend += blockProducersCount;
      };
    }

    @Override
    public String toString() {
      return "ByzantineBPNS{" + "delay=" + delay + ", skipped=" + skipped + '}';
    }
  }


  /**
   * Wait for the previous block (height - 1) before applying the delay Idea: by always including
   * your father, you will be more often on the "good" branch so you will increase your reward
   */
  @SuppressWarnings("unused")
  class ByzBlockProducerWF extends ByzBlockProducer {
    int late = 0;
    int onTime = 0;

    ByzBlockProducerWF(int delay, CasperBlock genesis) {
      super(delay, genesis);
    }

    @Override
    protected Runnable getPeriodicTask() {
      return () -> {
        if (head == genesis && toSend == 1) {
          // If we're the first producer we need to kick off the system.
          reevaluateH(network.time);
          createAndSendBlock(h);
          toSend += blockProducersCount;
        }
      };
    }

    @Override
    public boolean onBlock(final CasperBlock b) {
      if (super.onBlock(b)) {
        if (b.height == toSend - 1) {

          int perfectDate = SLOT_DURATION * toSend + delay;

          Runnable r = new Runnable() {
            final int th = toSend;

            @Override
            public void run() {
              head = buildBlock(b, th);
              network.sendAll(new BlockChainNetwork.SendBlock<CasperBlock, CasperNode>(head),
                  network.time + blockConstructionTime, ByzBlockProducerWF.this);
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
    public String toString() {
      return "ByzantineBPWF{" + "delay=" + delay + ", late=" + late + ", onTime=" + onTime + '}';
    }
  }

  private static boolean latencyPrinted = false;

  private static void runSet(int delay, boolean randomOnTies, Graph.Series report) {
    CasperIMD bc = new CasperIMD(5, randomOnTies, 5, 80, 1000, 1);

    ByzBlockProducer badNode = bc.new ByzBlockProducerWF(delay, bc.genesis);
    bc.init(badNode);

    if (!latencyPrinted) {
      NetworkLatency nl = NetworkLatency.estimateLatency(bc.network, 100000);
      System.out.println(nl);
      latencyPrinted = true;
    }

    bc.network.run(3600 * 5); // 5 hours is a minimum if you want something statistically reasonable

    bc.network.printStat(true);

    report
        .addLine(new Graph.ReportLine(delay, badNode.txsCreatedInChain(bc.network.observer.head)));
  }

  public static void main(String... args) {

    Graph graph = new Graph("ByzPP impact", "delay in ms", "ByzBP tx counts");
    Graph.Series txsR = new Graph.Series("tx count");
    graph.addSerie(txsR);
    Graph.Series txsNR = new Graph.Series("tx count - not random on ties");
    graph.addSerie(txsNR);

    for (int delay = -5000; delay < 16000; delay += 500) {
      runSet(delay, false, txsNR);
      runSet(delay, true, txsR);
    }

    try {
      graph.save(new File("/tmp/graph.png"));
    } catch (IOException e) {
      System.err.println("Can't generate the graph: " + e.getMessage());
    }
  }
}
/*

ByzBlockProducer{delay=-4000, onDirectFather=372, onOlderAncestor=80, incNotTheBestFather=32}; 408; 1920000; 183060; 166565
ByzBlockProducer{delay=-3000, onDirectFather=353, onOlderAncestor=99, incNotTheBestFather=45}; 401; 2389000; 183060; 166565
ByzBlockProducer{delay=-2000, onDirectFather=360, onOlderAncestor=92, incNotTheBestFather=45}; 408; 2832000; 183060; 166565
ByzBlockProducer{delay=-1000, onDirectFather=369, onOlderAncestor=83, incNotTheBestFather=46}; 417; 3303000; 183060; 166565
ByzBlockProducer{delay=0, onDirectFather=369, onOlderAncestor=83, incNotTheBestFather=46}; 417; 3720000; 183060; 166565 <========
ByzBlockProducer{delay=1000, onDirectFather=367, onOlderAncestor=85, incNotTheBestFather=31}; 405; 3949000; 183060; 166565
ByzBlockProducer{delay=2000, onDirectFather=383, onOlderAncestor=69, incNotTheBestFather=35}; 419; 4478000; 183060; 166565
ByzBlockProducer{delay=3000, onDirectFather=417, onOlderAncestor=35, incNotTheBestFather=35}; 426; 4926000; 183060; 166565
ByzBlockProducer{delay=4000, onDirectFather=418, onOlderAncestor=34, incNotTheBestFather=34}; 432; 5440000; 183060; 166565
ByzBlockProducer{delay=5000, onDirectFather=411, onOlderAncestor=41, incNotTheBestFather=41}; 407; 5587000; 183060; 166565 <========
ByzBlockProducer{delay=6000, onDirectFather=402, onOlderAncestor=50, incNotTheBestFather=50}; 350; 5196000; 183060; 166565
ByzBlockProducer{delay=7000, onDirectFather=406, onOlderAncestor=46, incNotTheBestFather=46}; 222; 3530000; 183060; 166565
ByzBlockProducer{delay=8000, onDirectFather=421, onOlderAncestor=31, incNotTheBestFather=31}; 224; 3688000; 183060; 166565
ByzBlockProducer{delay=9000, onDirectFather=406, onOlderAncestor=46, incNotTheBestFather=46}; 163; 2939000; 183060; 166565
ByzBlockProducer{delay=10000, onDirectFather=404, onOlderAncestor=48, incNotTheBestFather=48}; 2; 36000; 183060; 166565
ByzBlockProducer{delay=13000, onDirectFather=408, onOlderAncestor=43, incNotTheBestFather=43}; 1; 21000; 182655; 166565
ByzBlockProducer{delay=14000, onDirectFather=406, onOlderAncestor=45, incNotTheBestFather=45}; 1; 22000; 182655; 166565
ByzBlockProducer{delay=16000, onDirectFather=403, onOlderAncestor=48, incNotTheBestFather=48}; 1; 24000; 182655; 166565

ByzantineBPWF{delay=-4000, late=54, onTime=397}; 404; 1886844; 183060; 166565
ByzantineBPWF{delay=-3000, late=54, onTime=397}; 404; 2239844; 183060; 166565
ByzantineBPWF{delay=-2000, late=47, onTime=404}; 404; 2596344; 183060; 166565
ByzantineBPWF{delay=-1000, late=43, onTime=408}; 404; 2959344; 183060; 166565
ByzantineBPWF{delay=0, late=37, onTime=414}; 404; 3328344; 183060; 166565
ByzantineBPWF{delay=1000, late=37, onTime=414}; 404; 3697344; 183060; 166565
ByzantineBPWF{delay=2000, late=45, onTime=406}; 403; 4068047; 183060; 166565
ByzantineBPWF{delay=3000, late=0, onTime=451}; 398; 4378000; 183060; 166565
ByzantineBPWF{delay=4000, late=0, onTime=451}; 396; 4752000; 183060; 166565
ByzantineBPWF{delay=5000, late=0, onTime=451}; 378; 4914000; 183060; 166565
ByzantineBPWF{delay=6000, late=0, onTime=451}; 307; 4298000; 183060; 166565
ByzantineBPWF{delay=7000, late=0, onTime=451}; 205; 3075000; 183060; 166565


ByzantineBPNS{delay=-4000, skipped=34}; 374; 1784000; 183060; 166565
ByzantineBPNS{delay=-3000, skipped=37}; 364; 2204000; 183060; 166565
ByzantineBPNS{delay=-2000, skipped=38}; 370; 2604000; 183060; 166565
ByzantineBPNS{delay=-1000, skipped=39}; 378; 3030000; 183060; 166565
ByzantineBPNS{delay=0, skipped=39}; 378; 3408000; 183060; 166565
ByzantineBPNS{delay=1000, skipped=51}; 354; 3490000; 183060; 166565
ByzantineBPNS{delay=2000, skipped=49}; 370; 3988000; 183060; 166565
ByzantineBPNS{delay=3000, skipped=40}; 390; 4658000; 183060; 166565
ByzantineBPNS{delay=4000, skipped=42}; 387; 4972000; 183060; 166565
ByzantineBPNS{delay=5000, skipped=38}; 368; 5120000; 183060; 166565  <=== not as good as a simple delay
ByzantineBPNS{delay=6000, skipped=47}; 289; 4278000; 183060; 166565
ByzantineBPNS{delay=7000, skipped=45}; 219; 3485000; 183060; 166565


 */
