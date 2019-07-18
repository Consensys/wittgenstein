package net.consensys.wittgenstein.protocols.ethpow;

import java.math.BigInteger;
import java.util.*;
import net.consensys.wittgenstein.core.*;

@SuppressWarnings("WeakerAccess")
public class ETHPoW implements Protocol {
  final BlockChainNetwork<POWBlock, ETHMiner> network;
  final NodeBuilder nb;
  final POWBlock genesis = POWBlock.createGenesis();
  final ETHPoWParameters params;

  public static class ETHPoWParameters extends WParameters {
    public final String nodeBuilderName;
    public final String networkLatencyName;
    public final int numberOfMiners;
    public final String byzClassName;
    public final double byzMiningRatio;

    public ETHPoWParameters(
        String nodeBuilderName,
        String networkLatencyName,
        int numberOfMiners,
        String byzClassName,
        double byzMiningRatio) {
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
      this.numberOfMiners = numberOfMiners;
      this.byzClassName = byzClassName == null || byzClassName.isEmpty() ? null : byzClassName;
      this.byzMiningRatio = this.byzClassName == null ? 0 : byzMiningRatio;
    }

    // For json...
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
    this.network.setNetworkLatency(
        RegistryNetworkLatencies.singleton.getByName(params.networkLatencyName));
  }

  @Override
  public BlockChainNetwork<POWBlock, ETHMiner> network() {
    return network;
  }

  @Override
  public Protocol copy() {
    return new ETHPoW(params);
  }

  public ETHMiner getByzantineNode() {
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
      ETHMiner cur;
      if (i == 1 && params.byzClassName != null && params.byzClassName.length() > 0) {
        Class<?> clazz;
        try {
          clazz = Class.forName(params.byzClassName);
          cur =
              (ETHMiner)
                  clazz.getConstructors()[0].newInstance(network, nb, byzHashPower, this.genesis);
        } catch (Throwable e) {
          throw new IllegalArgumentException(e);
        }
      } else {
        cur = new ETHMiner(network, nb, honestHashPower, this.genesis);
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
    final ETHMiner who;
    final double amount;

    public Reward(BlockChainNode<POWBlock> who, double amount) {
      this.who = (ETHMiner) who;
      this.amount = amount;
    }

    public static void sumRewards(Map<ETHMiner, Double> sum, List<Reward> rewards) {
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

    POWBlock(ETHMiner ethMiner, POWBlock father, int time) {
      this(Collections.emptyList(), ethMiner, father, time);
    }

    POWBlock(List<Transactions> transactions, ETHMiner ethMiner, POWBlock father, int time) {
      super(ethMiner, father.height + 1, father, true, time);
      this.transactions = transactions;
      this.difficulty = calculateDifficulty(father, time);

      // Total difficulty should take uncles into account as well
      this.totalDifficulty = father.totalDifficulty.add(BigInteger.valueOf(this.difficulty));
    }

    POWBlock(ETHMiner ethMiner, POWBlock father, int time, Set<POWBlock> us) {
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

    /** Creates a genesis block. */
    protected POWBlock() {
      // We start at https://etherscan.io/block/7951081
      super(7951081);
      this.transactions = Collections.emptyList();
      this.difficulty = 1949482043446410L;
      this.totalDifficulty = new BigInteger("10591882213905570860929");
    }

    // For tests
    POWBlock(ETHMiner ethMiner, POWBlock father, int height, int diff, int time) {
      super(ethMiner, height, father, true, time);
      this.transactions = Collections.emptyList();
      this.difficulty = diff;
      this.totalDifficulty =
          father != null
              ? father.totalDifficulty.add(BigInteger.valueOf(this.difficulty))
              : BigInteger.valueOf(this.difficulty);
    }

    protected long onCalculateDifficulty(long all, POWBlock father, long diff, long bomb) {
      return all; // 2586243382184844L;
    }

    /** @return the list of rewards for this block. */
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

    public HashMap<ETHMiner, Double> allRewards() {
      return allRewards(0);
    }

    public HashMap<ETHMiner, Double> allRewards(int untilHeight) {
      HashMap<ETHMiner, Double> res = new HashMap<>();
      allRewards(res, untilHeight);
      return res;
    }

    public void allRewards(HashMap<ETHMiner, Double> res, int untilHeight) {
      POWBlock cur = this;
      while (cur.producer != null && cur.height >= untilHeight - 1) {
        Reward.sumRewards(res, cur.rewards());
        cur = cur.parent;
      }
    }

    public void allRewardsById(HashMap<Integer, Double> sum, int untilHeight) {
      POWBlock cur = this;
      while (cur.producer != null && cur.height > untilHeight) {
        for (Reward r : cur.rewards()) {
          double cR = sum.getOrDefault(r.who.nodeId, 0.0);
          cR += r.amount;
          sum.put(r.who.nodeId, cR);
        }

        cur = cur.parent;
      }
    }

    public long avgDifficulty(int untilHeight) {
      POWBlock cur = this;
      while (cur.producer != null && cur.height > untilHeight) {
        cur = cur.parent;
      }

      if (cur == this) {
        return cur.difficulty;
      }

      BigInteger diff =
          totalDifficulty.subtract(cur.totalDifficulty).add(BigInteger.valueOf(cur.difficulty));
      long blocks = 1 + height - cur.height;

      return diff.divide(BigInteger.valueOf(blocks)).longValue();
    }

    public double uncleRate(int untilHeight) {
      double uncles = 0;
      POWBlock cur = this;
      POWBlock first = null;
      while (cur.producer != null && cur.height > untilHeight) {
        uncles += cur.uncles.size();
        first = cur;
        cur = cur.parent;
      }
      return first == null ? 0.0 : uncles / (uncles + this.height - first.height);
    }

    /** @return true if 'b' can be an uncle */
    boolean isPossibleUncle(POWBlock b) {
      if (b.height >= height || height - b.height > 7) {
        return false;
      }

      POWBlock cur = this;
      while (cur != null && cur.height > b.height) {
        cur = cur.parent;
      }
      return cur != null && cur.parent == b.parent;
    }

    public static POWBlock createGenesis() {
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

      long all = father.difficulty + diff + bomb;

      return onCalculateDifficulty(all, father, diff, bomb);
    }
  }

  static class POWBlockComparator implements Comparator<POWBlock> {

    @Override
    public int compare(POWBlock o1, POWBlock o2) {
      if (o1 == o2) return 0;

      if (!o2.valid) return 1;
      if (!o1.valid) return -1;

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
    private final transient POWBlockComparator blockComparator = new POWBlockComparator();
    protected final transient BlockChainNetwork<POWBlock, ETHMiner> network;

    ETHPoWNode(BlockChainNetwork<POWBlock, ETHMiner> network, NodeBuilder nb, POWBlock genesis) {
      super(network.rd, nb, false, genesis);
      this.network = network;
    }

    void createTransaction() {
      // TODO: Create transactions with different types, gas cost and limit to be added to pool at
      // different rates
    }

    @Override
    public POWBlock best(POWBlock cur, POWBlock alt) {
      if (alt == null) {
        return cur;
      }
      if (cur == null) {
        return alt;
      }
      int res = blockComparator.compare(cur, alt);
      if (res == 0) return alt.producer == this ? alt : cur;
      return res > 0 ? cur : alt;
    }
  }

  /** Class to be extended to store decisions that will be used later to learn. */
  public abstract static class Decision {
    final int takenAtHeight;

    /** We will calculate the reward and store the decision when the head reach this point. */
    final int rewardAtHeight;

    public Decision(int takenAtHeight, int rewardAtHeight) {
      this.takenAtHeight = takenAtHeight;
      this.rewardAtHeight = rewardAtHeight;
    }

    /** Should return the fields to be store in a CSV like format. */
    public abstract String forCSV();

    public String toString() {
      return forCSV();
    }

    /** Overload this function to change the way the reward is calculated. */
    public double reward(POWBlock currentHead, ETHAgentMiner miner) {
      return currentHead.allRewards(takenAtHeight).getOrDefault(miner, 0.0);
    }
  }
}
