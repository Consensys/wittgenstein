package net.consensys.wittgenstein.protocol;


import net.consensys.wittgenstein.core.*;
import java.util.*;

@SuppressWarnings({"WeakerAccess", "SameParameterValue"})
public class Dfinity implements Protocol {
  final int roundTime = 3000; // the delay between the block generation and the start of the next random beacon generation

  final int blockProducersCount;
  final int blockProducersPerRound = 5;
  final int blockProducersRound;

  final int attestersCount;
  final int attestersPerRound;
  final int attestersRound;

  final int randomBeaconCount;

  final int blockConstructionTime;
  final int attestationConstructionTime;
  final int percentageDeadAttester;

  final int majority;

  final NodeBuilder nb;

  final BlockChainNetwork<DfinityBlock, DfinityNode> network = new BlockChainNetwork<>();
  final DfinityBlock genesis = DfinityBlock.createGenesis();
  final DfinityBlockComparator blockComparator = new DfinityBlockComparator();

  final List<AttesterNode> attesters = new ArrayList<>();
  final List<BlockProducerNode> bps = new ArrayList<>();
  final List<RandomBeaconNode> rds = new ArrayList<>();

  @Override
  public BlockChainNetwork<DfinityBlock, DfinityNode> network() {
    return network;
  }

  public Dfinity copy() {
    return new Dfinity(blockProducersCount, attestersCount, attestersPerRound,
        blockConstructionTime, attestationConstructionTime, percentageDeadAttester);
  }

  public Dfinity() {
    this(10, 300, 100, 1000, 1, 0);
  }

  public Dfinity(int blockProducersCount, int attestersCount, int attestersPerRound,
      int blockConstructionTime, int attestationConstructionTime, int percentageDeadAttester) {
    this.blockProducersCount = blockProducersCount;
    this.blockProducersRound = blockProducersCount / blockProducersPerRound;

    this.attestersRound = attestersCount / attestersPerRound;
    this.attestersCount = attestersCount;
    this.attestersPerRound = attestersPerRound;

    this.randomBeaconCount = attestersPerRound; // simplification: the committee doesn't change and has the same size of the attesters'.

    this.majority = (attestersPerRound / 2) + 1;
    this.blockConstructionTime = blockConstructionTime;
    this.attestationConstructionTime = attestationConstructionTime;
    this.percentageDeadAttester = percentageDeadAttester;

    this.nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    this.network.addObserver(new DfinityNode(network.rd, genesis) {});
  }

  static class DfinityBlock extends Block<DfinityBlock> {
    public DfinityBlock(BlockProducerNode blockProducerNode, int height, DfinityBlock head,
        boolean b, int time) {
      super(blockProducerNode, height, head, b, time);
    }

    DfinityBlock() {}


    static public DfinityBlock createGenesis() {
      return new DfinityBlock();
    }
  }

  static class DfinityBlockComparator implements Comparator<DfinityBlock> {

    /**
     * @return -1 if o1 is lower than o2,
     */
    @Override
    public int compare(DfinityBlock o1, DfinityBlock o2) {
      if (o1 == o2)
        return 0;

      if (!o2.valid)
        return 1;
      if (!o1.valid)
        return -1;

      if (o1.hasDirectLink(o2)) { // if 'o1' is an ancestor of 'o2', then 'o1' is NOT greater than 'o2'
        return (o1.height < o2.height ? -1 : 1);
      }

      if (o1.height != o2.height) {
        //
        return (o1.height < o2.height ? -1 : 1);
      }

      assert o1.producer != null;
      return Long.compare(o1.producer.nodeId, o1.producer.nodeId);
    }
  }

  static class BlockProposal extends Network.Message<DfinityNode> {
    final DfinityBlock block;

    public BlockProposal(DfinityBlock block) {
      this.block = block;
    }

    @Override
    public void action(DfinityNode from, DfinityNode to) {
      AttesterNode n = (AttesterNode) to;
      n.onProposal(block);
    }
  }

  static class Vote extends Network.Message<DfinityNode> {
    final DfinityBlock voteFor;

    public Vote(DfinityBlock voteFor) {
      this.voteFor = voteFor;
    }

    @Override
    public void action(DfinityNode fromNode, DfinityNode toNode) {
      toNode.onVote(fromNode, voteFor);
    }
  }


  static class RandomBeaconExchange extends Network.Message<RandomBeaconNode> {
    final int height;

    public RandomBeaconExchange(int height) {
      this.height = height;
    }

    @Override
    public void action(RandomBeaconNode from, RandomBeaconNode to) {
      to.onRandomBeaconExchange(from, height);
    }
  }

  static class RandomBeaconResult extends Network.Message<DfinityNode> {
    final int height;
    final long rd;

    public RandomBeaconResult(int height, long rd) {
      this.height = height;
      this.rd = rd;
    }

    @Override
    public void action(DfinityNode from, DfinityNode to) {
      to.onRandomBeacon(height, rd);
    }
  }


  abstract class DfinityNode extends BlockChainNode<DfinityBlock> {
    final Set<Long> committeeMajorityBlocks = new HashSet<>();
    final Set<Integer> committeeMajorityHeight = new HashSet<>();
    int lastRandomBeacon;


    @Override
    public DfinityBlock best(DfinityBlock o1, DfinityBlock o2) {
      return blockComparator.compare(o1, o2) >= 0 ? o1 : o2;
    }

    DfinityNode(Random rd, DfinityBlock genesis) {
      super(rd, nb, false, genesis);
    }

    public void onVote(Node voter, DfinityBlock voteFor) {}

    /**
     * Can be called multiple times for a single node
     */
    final void onRandomBeacon(int height, long rd) {
      if (lastRandomBeacon < height) {
        lastRandomBeacon = height;
        onRandomBeaconOnce(height, rd);
      }
    }

    void onRandomBeaconOnce(int height, long rd) {}
  }


  class BlockProducerNode extends DfinityNode {
    final int myRound;
    int waitForBlockHeight; // If we're supposed to create a block but we don"t have the parent yet

    BlockProducerNode(final int myRound, DfinityBlock genesis) {
      super(network.rd, genesis);
      this.myRound = myRound;
      this.waitForBlockHeight = -1;
    }

    void createProposal(int height) {
      if (head.height != height - 1) {
        throw new IllegalArgumentException();
      }

      DfinityBlock newBlock = new DfinityBlock(this, height, head, true, network.time);

      List<DfinityNode> attestersS = new ArrayList<>(attesters);
      Collections.shuffle(attestersS, network.rd);
      network.send(new BlockProposal(newBlock), network.time + blockConstructionTime, this,
          attestersS);
      waitForBlockHeight = -1;
    }

    @Override
    public boolean onBlock(DfinityBlock b) {
      if (!super.onBlock(b))
        return false;
      if (head.height == waitForBlockHeight) {
        createProposal(waitForBlockHeight + 1);
      }
      return true;
    }

    /**
     * If we're randomly selected we send a proposal if we can. If we can't we wait for the parent
     * block.
     */
    @Override
    void onRandomBeaconOnce(int h, long rd) {
      if (rd % blockProducersRound == myRound) {
        if (head.height == h - 1) {
          createProposal(h);
        }
      }
    }
  }

  class AttesterNode extends DfinityNode {
    final Map<Long, Set<Integer>> votes = new HashMap<>();
    final List<DfinityBlock> proposals = new ArrayList<>();
    final int myRound;
    int voteForHeight = -1;

    AttesterNode(int myRound, DfinityBlock genesis) {
      super(network.rd, genesis);
      this.myRound = myRound;
    }

    @Override
    public void onVote(Node voter, DfinityBlock voteFor) {
      Set<Integer> voters = votes.computeIfAbsent(voteFor.id, k -> new HashSet<>());
      if (voteForHeight == voteFor.height) {
        if (voters.add(voter.nodeId) && voters.size() >= majority) {
          sendBlock(voteFor);
        }
      }
    }

    private void sendBlock(DfinityBlock voteFor) {
      committeeMajorityBlocks.add(voteFor.id);
      committeeMajorityHeight.add(voteFor.height);
      voteForHeight = -1;
      network.sendAll(new BlockChainNetwork.SendBlock<>(voteFor), this);
    }

    /**
     * Proposals are sent by block producers. If we have not already reached a majority for this
     * height, we vote vote for this new proposal. If we reach the majority, we can send the
     * (signed) block on the network.
     */
    void onProposal(DfinityBlock b) {
      if (voteForHeight == b.height) {
        Set<Integer> voters = votes.computeIfAbsent(b.id, k -> new HashSet<>());

        if (voters.add(this.nodeId)) {
          if (voters.size() >= majority) {
            sendBlock(b);
          } else {
            Vote v = new Vote(b);
            List<DfinityNode> attestersS = new ArrayList<>(attesters);
            Collections.shuffle(attestersS, network.rd);
            network.send(v, network.time + attestationConstructionTime, this, attestersS);
          }
        }
      } else if (b.height > head.height) {
        // We may receive proposals in advance. We can't validate it yet, as we need the
        //  previous block. Also, we're not sure that we're an attester for the next
        //  cycle, we need the random beacon. So we buffer it.
        proposals.add(b);
      }
    }

    @Override
    public boolean onBlock(DfinityBlock b) {
      if (!super.onBlock(b)) {
        return false;
      }
      committeeMajorityBlocks.add(b.id);
      committeeMajorityHeight.add(b.height);
      if (voteForHeight == b.height) { // We have a full block, not need to continue voting for this height
        voteForHeight = -1;
      }
      return true;
    }

    @Override
    void onRandomBeaconOnce(int h, long rd) {
      if (rd % attestersRound == myRound && !committeeMajorityHeight.contains(h)) {
        voteForHeight = h;
        HashSet<DfinityBlock> sent = new HashSet<>();
        for (DfinityBlock b : proposals) {
          if (b.height == h && !sent.contains(b)) {
            sent.add(b);
            Vote v = new Vote(b);
            List<DfinityNode> attestersS = new ArrayList<>(attesters);
            Collections.shuffle(attestersS, network.rd);
            network.send(v, network.time + attestationConstructionTime, this, attestersS);
          }
        }
        proposals.clear();
      }
    }
  }

  class RandomBeaconNode extends DfinityNode {
    long rd = 0;
    int height = 1;
    int lastRDSent = 0;
    Map<Integer, Set<Integer>> exchanged = new HashMap<>();

    RandomBeaconNode(DfinityBlock genesis) {
      super(network.rd, genesis);
    }

    /**
     * Exchange the signatures with other nodes. When the threshold is reached we have our random
     * beacon for this height
     */
    public void onRandomBeaconExchange(RandomBeaconNode from, int height) {
      if (height >= this.height && height > lastRDSent) {
        Set<Integer> voters = exchanged.computeIfAbsent(height, k -> new HashSet<>());
        if (voters.add(from.nodeId) && height == this.height && voters.size() >= majority) {
          sendRB();
        }
      }
    }

    void sendRB() {
      rd = height; // height to share a unique value w/o threshold sigs
      lastRDSent = height;
      RandomBeaconResult rb = new RandomBeaconResult(height, rd);
      network.sendAll(rb, network.time + attestationConstructionTime, this);
    }

    /**
     * When we receive a block, it's time to start the random beacon generation for the next height
     */
    @Override
    public boolean onBlock(DfinityBlock b) {
      if (!super.onBlock(b)) {
        return true;
      }

      if (head.height == height) {
        height++;

        Set<Integer> voters = exchanged.computeIfAbsent(height, k -> new HashSet<>());
        if (voters.add(this.nodeId) && voters.size() >= majority) {
          sendRB();
        } else {
          assert head.parent != null;
          int wt = head.parent.proposalTime + roundTime * 2;
          if (wt <= network.time)
            wt = network.time + attestationConstructionTime;
          RandomBeaconExchange rbe = new RandomBeaconExchange(height);

          List<DfinityNode> rdsSends = new ArrayList<>(rds);
          Collections.shuffle(rdsSends, network.rd);
          network.send(rbe, wt, this, rdsSends);
        }
      }
      return false;
    }

    /**
     * The random beacon can be generated by the others before we have finished. In this case we
     * just accept the value.
     */
    @Override
    void onRandomBeaconOnce(int h, long rd) {
      if (h > this.height) {
        this.lastRDSent = height;
        this.height = h;
        this.rd = rd;
      }
    }
  }

  public void init() {
    for (int i = 0; i < attestersCount; i++) {
      AttesterNode n = new AttesterNode(i % attestersRound, genesis);
      attesters.add(n);
      network.addNode(n);
    }

    for (int i = 0; i < blockProducersCount; i++) {
      BlockProducerNode n = new BlockProducerNode(i % blockProducersRound, genesis);
      bps.add(n);
      network.addNode(n);
    }

    for (int i = 0; i < randomBeaconCount; i++) {
      RandomBeaconNode n = new RandomBeaconNode(genesis);
      rds.add(n);
      network.addNode(n);
    }

    Collections.shuffle(bps, network.rd);

    for (RandomBeaconNode n : rds) {
      n.sendRB();
    }
  }


  public static void main(String... args) {
    Dfinity bc = new Dfinity();
    bc.init();

    bc.network.run(50);

    bc.network.partition(.20f);
    bc.network.run(2_000);
    bc.network.endPartition();

    bc.network.run(50);

    bc.network.printStat(false);
  }
}
// ~20K seconds
// bad network, no partition
// block count:5685, all tx: 20204249

// bad network, 20% partition
// block count:4665, all tx: 20205547


////////////////////

// perfect network, no partition
// block count:6733, all tx: 20207106

// perfect network, 20% partition
// block count:6733, all tx: 20207106
