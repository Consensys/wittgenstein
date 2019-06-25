package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.server.WParameters;
import java.io.*;
import java.math.BigInteger;
import java.util.*;



/**
 * Check the difficulty calculation (rounding error?) when mining add uncle add reward (depends on
 * uncle) add call to external strategy (eg. decision)
 */
@SuppressWarnings("WeakerAccess")
public class ETHPoW implements Protocol {
  final BlockChainNetwork<POWBlock, ETHMiningNode> network;
  final NodeBuilder nb;
  final POWBlock genesis = POWBlock.createGenesis();
  final ETHPoWParameters params;


  public static class ETHPoWParameters extends WParameters {
    private final String nodeBuilderName;
    private final String networkLatencyName;
    private final int numberOfMiners;
    private final String byzClassName;
    private final double byzMiningRatio;

    public ETHPoWParameters(String nodeBuilderName, String networkLatencyName, int numberOfMiners,
        String byzClassName, double byzMiningRatio) {
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
      this.numberOfMiners = numberOfMiners;
      this.byzClassName = byzClassName == null || byzClassName.isEmpty() ? null : byzClassName;
      this.byzMiningRatio = this.byzClassName == null ? 0 : byzMiningRatio;
    }

    public ETHPoWParameters() {
      nodeBuilderName = null;
      networkLatencyName = null;
      numberOfMiners = 1;
      byzClassName = null;
      byzMiningRatio = 0;
    }
  }

  public ETHPoW(ETHPoWParameters params) {
    this.params = params;
    this.network = new BlockChainNetwork<>();
    // Change Singleton to IC3
    this.nb = RegistryNodeBuilders.singleton.getByName(params.nodeBuilderName);
    this.network
        .setNetworkLatency(RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }


  @Override
  public BlockChainNetwork<POWBlock, ETHMiningNode> network() {
    return network;
  }

  @Override
  public Protocol copy() {
    return new ETHPoW(params);
  }

  public ETHMiningNode getByzantineNode() {
    if (params.byzClassName == null) {
      throw new IllegalArgumentException("no byzantine node in this network");
    }
    return network.getNodeById(1); // We decided that the bad node will always be at pos 1.
  }

  @Override
  public void init() {
    final int totalHashPower = 200 * 1024;
    final int byzHashPower = (int) (totalHashPower * params.byzMiningRatio);
    final int honestMiners = byzHashPower == 0 ? params.numberOfMiners : params.numberOfMiners - 1;
    final int honestHashPower = (totalHashPower - byzHashPower) / honestMiners;
    for (int i = 0; i < params.numberOfMiners; i++) {
      ETHMiningNode cur;
      if (i == 1 && params.byzClassName != null && params.byzClassName.length() > 0) {
        Class<?> clazz;
        try {
          clazz = Class.forName(params.byzClassName);
          cur = (ETHMiningNode) clazz.getConstructors()[0].newInstance(network, nb, byzHashPower,
              this.genesis);
        } catch (Throwable e) {
          throw new IllegalArgumentException(e);
        }
      } else {
        cur = new ETHMiningNode(network, nb, honestHashPower, this.genesis);
      }
      if (i == 0) {
        network.addObserver(cur);
      } else {
        network.addNode(cur);
      }
      network.registerPeriodicTask(cur::mine10ms, 1, 10, cur);
    }
  }

  static class Reward {
    final ETHMiningNode who;
    final double amount;

    public Reward(BlockChainNode<POWBlock> who, double amount) {
      this.who = (ETHMiningNode) who;
      this.amount = amount;
    }

    public static void sumRewards(Map<ETHMiningNode, Double> sum, List<Reward> rewards) {
      for (Reward r : rewards) {
        double cR = sum.getOrDefault(r.who, 0.0);
        cR += r.amount;
        sum.put(r.who, cR);
      }
    }
  }


  static class POWBlock extends Block<POWBlock> {
    final long difficulty;
    final BigInteger totalDifficulty;
    final List<Transactions> transactions;
    final List<POWBlock> uncles = new ArrayList<>();

    POWBlock(ETHMiningNode ethMiner, POWBlock father, int time) {
      this(Collections.emptyList(), ethMiner, father, time);
    }

    POWBlock(List<Transactions> transactions, ETHMiningNode ethMiner, POWBlock father, int time) {
      super(ethMiner, father.height + 1, father, true, time);
      this.transactions = transactions;
      this.difficulty = calculateDifficulty(father, time);

      // Total difficulty should take uncles into account as well
      this.totalDifficulty = father.totalDifficulty.add(BigInteger.valueOf(this.difficulty));
    }

    POWBlock(ETHMiningNode ethMiner, POWBlock father, int time, Set<POWBlock> us) {
      super(ethMiner, father.height + 1, father, true, time);
      this.transactions = Collections.emptyList();

      if (us.size() > 2) {
        throw new IllegalArgumentException(
            "Can't have more than 2 uncles: " + this + ", " + us.size());
      }

      for (POWBlock u : us) {
        if (!isPossibleUncle(u)) {
          throw new IllegalArgumentException(u + " can't be an uncle of " + this);
        }
        uncles.add(u);
      }

      this.difficulty = calculateDifficulty(father, time);
      this.totalDifficulty = father.totalDifficulty.add(BigInteger.valueOf(this.difficulty));
    }

    /**
     * Creates a genesis block.
     */
    protected POWBlock() {
      // We start at https://etherscan.io/block/7951081
      super(7951081);
      this.transactions = Collections.emptyList();
      this.difficulty = 1949482043446410L;
      this.totalDifficulty = new BigInteger("10591882213905570860929");
    }

    /**
     * @return the list of rewards for this block.
     */
    List<Reward> rewards() {
      final double rwd = 2.0;
      if (uncles.isEmpty()) {
        return Collections.singletonList(new Reward(producer, rwd));
      } else {
        List<Reward> res = new ArrayList<>();
        double pR = rwd;
        for (POWBlock u : uncles) {
          double uR = (rwd * (u.height + 8 - height)) / 8;
          pR += rwd / 32;
          res.add(new Reward(u.producer, uR));
        }
        res.add(new Reward(producer, pR));
        return res;
      }
    }

    public HashMap<ETHMiningNode, Double> allRewards() {
      return allRewards(0);
    }

    public HashMap<ETHMiningNode, Double> allRewards(int untilHeight) {
      HashMap<ETHMiningNode, Double> res = new HashMap<>();
      allRewards(res, untilHeight);
      return res;
    }

    public void allRewards(HashMap<ETHMiningNode, Double> res, int untilHeight) {
      POWBlock cur = this;
      while (cur.producer != null && cur.height >= untilHeight - 1) {
        Reward.sumRewards(res, cur.rewards());
        cur = cur.parent;
      }
    }

    public void allRewards(HashMap<Integer, Double> sum) {
      POWBlock cur = this;
      while (cur.producer != null) {

        for (Reward r : cur.rewards()) {
          double cR = sum.getOrDefault(r.who.nodeId, 0.0);
          cR += r.amount;
          sum.put(r.who.nodeId, cR);
        }

        cur = cur.parent;
      }
    }

    public double uncleRate() {
      double uncles = 0;
      POWBlock cur = this;
      POWBlock first = null;
      while (cur.producer != null) {
        uncles += cur.uncles.size();
        first = cur;
        cur = cur.parent;
      }
      return first == null ? 0.0 : uncles / (uncles + this.height - first.height);
    }

    /**
     * @return true if 'b' can be an uncle
     */
    private boolean isPossibleUncle(POWBlock b) {
      if (b.height >= height || height - b.height > 7) {
        return false;
      }

      POWBlock cur = this;
      while (cur != null && cur.height > b.height) {
        cur = cur.parent;
      }
      return cur != null && cur.parent == b.parent;
    }

    static public POWBlock createGenesis() {
      return new POWBlock();
    }

    /**
     * Constantinople values.
     *
     * @param ts - timestamp in milliseconds of the new block
     * @link {https://github.com/ethereum/go-ethereum/blob/master/consensus/ethash/consensus.go}
     * @link {https://eips.ethereum.org/EIPS/eip-1234}
     * @link {https://eips.ethereum.org/EIPS/eip-100}
     */
    public long calculateDifficulty(POWBlock father, int ts) {
      long gap = (ts - father.proposalTime) / 9000;
      long y = (father.uncles.isEmpty() ? 1 : 2);
      long ugap = Math.max(-99, y - gap);
      long diff = (father.difficulty / 2048) * ugap;

      long periods = (father.height - 4_999_999L) / 100_000L;
      long bomb = periods > 1 ? (long) Math.pow(2, periods - 2) : diff;

      return father.difficulty + diff + bomb;
    }
  }


  static class POWBlockComparator implements Comparator<POWBlock> {

    @Override
    public int compare(POWBlock o1, POWBlock o2) {
      if (o1 == o2)
        return 0;

      if (!o2.valid)
        return 1;
      if (!o1.valid)
        return -1;

      return o1.totalDifficulty.compareTo(o2.totalDifficulty);
    }
  }


  static class Transactions {
    final int gasLimit;
    final String type;
    final int gastCost = 0;

    Transactions(int gasLimit, String type) {
      this.gasLimit = gasLimit;
      this.type = type;
    }
  }


  abstract static class ETHPoWNode extends BlockChainNode<POWBlock> {
    final private transient POWBlockComparator blockComparator = new POWBlockComparator();
    final protected transient BlockChainNetwork<POWBlock, ETHMiningNode> network;

    ETHPoWNode(BlockChainNetwork<POWBlock, ETHMiningNode> network, NodeBuilder nb,
        POWBlock genesis) {
      super(network.rd, nb, false, genesis);
      this.network = network;
    }

    void createTransaction() {
      //TODO: Create transactions with different types, gas cost and limit to be added to pool at different rates
    }

    @Override
    public POWBlock best(POWBlock cur, POWBlock alt) {
      int res = blockComparator.compare(cur, alt);
      if (res == 0)
        return alt.producer == this ? alt : cur;
      return res > 0 ? cur : alt;
    }
  }

  public static class ETHMiningNode extends ETHPoWNode {
    protected int hashPowerGHs; // hash power in GH/s
    protected POWBlock inMining;
    protected Set<POWBlock> minedToSend = new HashSet<>();
    private double threshold;
    UncleCmp uncleCmp = new UncleCmp();


    public ETHMiningNode(BlockChainNetwork<POWBlock, ETHMiningNode> network, NodeBuilder nb,
        int hashPowerGHs, POWBlock genesis) {
      super(network, nb, genesis);
      this.hashPowerGHs = hashPowerGHs;
    }

    protected boolean includeUncle(POWBlock uncle) {
      return true;
    }

    protected boolean sendMinedBlock(POWBlock mined) {
      return true;
    }

    protected int extraSendDelay(POWBlock mined) {
      return 0;
    }

    protected boolean switchMining(POWBlock rcv) {
      return true;
    }

    protected void onNewHead(POWBlock oldHead, POWBlock newHead) {}

    protected void onMinedBlock(POWBlock mined) {}

    protected void onReceivedBlock(POWBlock rcv) {}


    /**
     * @return the number of blocks we mined in a row ourselves from the block 'b'
     */
    int depth(ETHPoW.POWBlock b) {
      int res = 0;

      while (b != null && b.producer == this) {
        res++;
        b = b.parent;
      }

      return res;
    }

    /**
     * @return the list of the possible uncle if you create a block with this father
     */
    List<POWBlock> possibleUncles(POWBlock father) {
      List<POWBlock> res = new ArrayList<>();

      Set<POWBlock> included = new HashSet<>();
      POWBlock b = father;
      for (int h = 0; b != null && h < 8; h++, b = b.parent) {
        included.add(b);
        included.addAll(b.uncles);
      }

      for (int h = father.height; h >= father.height - 6; h--) {
        Set<POWBlock> rcv = this.blocksReceivedByHeight.getOrDefault(h, Collections.emptySet());
        for (POWBlock u : rcv) {
          if (!included.contains(u) && (u.parent == father.parent || father.isPossibleUncle(u))
              && includeUncle(u)) {
            res.add(u);
          }
        }
      }

      res.sort(uncleCmp);
      return res;
    }

    /**
     * Sort the uncle. Between two uncles:</br>
     * - if we produced one of them, we include it first</br>
     * - if we produced two of them, we take the one with the higher height (better reward)</br>
     * - if we produced none of them, we take the one with the smallest height (opportunity to
     * include the other later)</br>
     */
    class UncleCmp implements Comparator<POWBlock> {
      @Override
      public int compare(POWBlock o1, POWBlock o2) {
        if (o1.producer == ETHMiningNode.this) {
          if (o2.producer != o1.producer) {
            return -1;
          } else {
            return Integer.compare(o2.height, o1.height);
          }
        }

        if (o2.producer == ETHMiningNode.this) {
          return 1;
        }

        return Integer.compare(o1.height, o2.height);
      }
    }

    /**
     * Mine for 10 milliseconds.
     */
    private boolean mine10ms() {
      if (inMining == null) {
        startNewMining(head);
      }
      if (network.rd.nextDouble() < threshold) {
        onFoundNewBlock(inMining);
        return true;
      } else {
        return false;
      }
    }

    protected void startNewMining(POWBlock father) {
      List<POWBlock> us = possibleUncles(father);
      Set<POWBlock> uss = us.isEmpty() ? Collections.emptySet()
          : us.size() <= 2 ? new HashSet<>(us) : new HashSet<>(us.subList(0, 2));
      inMining = new POWBlock(this, father, network.time, uss);
      threshold = solveIn10ms(inMining.difficulty);
    }

    /**
     * For tests: we force a successful mining.
     */
    protected void luckyMine() {
      if (!mine10ms()) {
        threshold = 10;
        mine10ms();
      }
    }

    protected void sendBlock(POWBlock mined) {
      if (mined.producer != this) {
        throw new IllegalArgumentException(
            "logic error: you're not the producer of this block" + mined);
      }
      int sendTime = network.time + 1 + extraSendDelay(mined);
      if (sendTime < 1) {
        throw new IllegalArgumentException("extraSendDelay(" + mined + ") sent a negative time");
      }
      network.sendAll(new BlockChainNetwork.SendBlock<>(mined), sendTime, this);
      minedToSend.remove(mined);
    }

    protected void sendAllMined() {
      List<POWBlock> all = new ArrayList<>(minedToSend);
      minedToSend.clear();
      for (POWBlock b : all) {
        sendMinedBlock(b);
      }
    }

    private void onFoundNewBlock(POWBlock mined) {
      POWBlock oldHead = head;
      inMining = null;

      if (sendMinedBlock(mined)) {
        sendBlock(mined);
      } else {
        minedToSend.add(mined);
      }
      if (!super.onBlock(mined)) {
        throw new IllegalStateException("invalid mined block:" + mined);
      }

      if (mined == head) {
        onNewHead(oldHead, mined);
      }
      onMinedBlock(mined);
    }


    @Override
    final public boolean onBlock(POWBlock b) {
      POWBlock oldHead = head;
      if (!super.onBlock(b)) {
        return false;
      }

      if (b == head) {
        onNewHead(oldHead, b);
        // Someone sent us a new head, so we're going to switch
        //  our mining to it
        if (switchMining(b)) {
          inMining = null;
        }
      } else if (inMining != null) {
        // May be 'b' is not better than our current head but we
        //  can still use it as an uncle for the block we're mining?
        if (inMining.isPossibleUncle(b)) {
          if (switchMining(b)) {
            inMining = null;
          }
        }
      }

      onReceivedBlock(b);
      return true;
    }

    /**
     * Calculate the probability for this node to find the right hash in 10ms.
     */
    double solveIn10ms(long difficulty) {
      double hpTMs = (hashPowerGHs * 1024.0 * 1024 * 1024) / 100.0;

      double singleHashSuccess = (1.0 / difficulty);
      double noSuccess = Math.pow(1.0 - singleHashSuccess, hpTMs);
      return 1 - noSuccess;
    }
  }

  /**
   * Class to be extended to store decisions that will be used later to learn.
   */
  public static abstract class Decision {
    final int takenAtHeight;

    /**
     * We will calculate the reward and store the decision when the head reach this point.
     */
    final int rewardAtHeight;

    public Decision(int takenAtHeight, int rewardAtHeight) {
      this.takenAtHeight = takenAtHeight;
      this.rewardAtHeight = rewardAtHeight;
    }

    /**
     * Should return the fields to be store in a CSV like format.
     */
    public abstract String forCSV();

    public String toString() {
      return forCSV();
    }

    /**
     * Overload this function to change the way the reward is calculated.
     */
    public double reward(POWBlock currentHead, ETHAgentMiningNode miner) {
      return currentHead.allRewards(takenAtHeight).getOrDefault(miner, 0.0);
    }
  }

  public static class ETHAgentMiningNode extends ETHMiningNode implements Closeable {
    /**
     * List of the decision taken that we need to evaluate. Sorted by evaluation height.
     */
    final LinkedList<Decision> decisions = new LinkedList<>();
    private final PrintWriter decisionOutput;

    public ETHAgentMiningNode(BlockChainNetwork<POWBlock, ETHMiningNode> network, NodeBuilder nb,
        int hashPower, POWBlock genesis) {
      super(network, nb, hashPower, genesis);
      try {
        FileWriter fw = new FileWriter("decisions.csv", true);
        BufferedWriter bw = new BufferedWriter(fw);
        decisionOutput = new PrintWriter(bw);
      } catch (Throwable e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * Add a decision tp the list of decisions to be evaluated.
     */
    final protected void addDecision(Decision d) {
      if (d.rewardAtHeight <= head.height) {
        throw new IllegalArgumentException("Can't calculate a reward for " + d + ", head=" + head);
      }

      if (decisions.isEmpty() || decisions.peekLast().rewardAtHeight <= d.rewardAtHeight) {
        decisions.addLast(d);
      } else {
        ListIterator<Decision> it = decisions.listIterator(decisions.size());
        while (it.hasPrevious()) {
          if (it.previous().rewardAtHeight <= d.rewardAtHeight) {
            it.next();
            break;
          }
        }
        it.add(d);
      }
    }

    @Override
    final protected void onNewHead(POWBlock oldHead, POWBlock newHead) {
      while (!decisions.isEmpty() && decisions.peekFirst().rewardAtHeight <= newHead.height) {
        Decision cur = decisions.pollFirst();
        assert cur != null;
        double reward = cur.reward(newHead, this);
        String toWrite = cur.forCSV() + "," + reward + "\n";
        decisionOutput.write(toWrite);
      }
    }

    @Override
    public void close() {
      decisionOutput.close();
    }
  }
}
