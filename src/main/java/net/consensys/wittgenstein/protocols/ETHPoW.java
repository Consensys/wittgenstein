package net.consensys.wittgenstein.protocols;

import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.messages.Message;
import net.consensys.wittgenstein.server.WParameters;
import java.math.BigInteger;
import java.util.*;



/**
 * Check the difficulty calculation (rounding error?) when mining add uncle add reward (depends on
 * uncle) add call to external strategy (eg. decision)
 */
public class ETHPoW implements Protocol {
  final BlockChainNetwork<POWBlock, ETHMiningNode> network;
  final NodeBuilder nb;
  final POWBlock genesis = POWBlock.createGenesis();
  final ETHPoWParameters params;
  final POWBlockComparator blockComparator = new POWBlockComparator();


  public static class ETHPoWParameters extends WParameters {
    private final int hashRate;
    private final int gasLimit;
    private final int blockReward;
    private final String nodeBuilderName;
    private final String networkLatencyName;
    private final int numberOfMiners;

    public ETHPoWParameters(int hashRate, int gasLimit, int blockReward, String nodeBuilderName,
        String networkLatencyName, int numberOfMiners) {
      this.hashRate = hashRate;
      this.gasLimit = gasLimit;
      this.blockReward = blockReward;
      this.nodeBuilderName = nodeBuilderName;
      this.networkLatencyName = networkLatencyName;
      this.numberOfMiners = numberOfMiners;
    }

    public ETHPoWParameters() {
      hashRate = 200;
      gasLimit = 0;
      blockReward = 0;
      nodeBuilderName = null;
      networkLatencyName = null;
      numberOfMiners = 1;
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

  @Override
  public void init() {
    for (int i = 0; i < params.numberOfMiners; i++) {
      final ETHMiningNode cur = new ETHMiningNode(network.rd, nb, 50, this.genesis);
      if (i == 0) {
        network.addObserver(cur);
      } else {
        network.addNode(cur);
      }
      network.registerPeriodicTask(cur::mine1ms, 1, 1, cur);
    }
  }

  @SuppressWarnings("JavadocReference")
  static class POWBlock extends Block<POWBlock> {
    final long difficulty;
    final BigInteger totalDifficulty;
    final List<Transactions> transInBlock;
    final List<Long> uncles = new ArrayList<>();

    POWBlock(ETHMiningNode ethMiner, POWBlock father, int time) {
      this(Collections.emptyList(), ethMiner, father, time);
    }

    POWBlock(List<Transactions> transInBlock, ETHMiningNode ethMiner, POWBlock father, int time) {
      super(ethMiner, father.height + 1, father, true, time);
      this.transInBlock = transInBlock;
      this.difficulty = calculateDifficulty(father, time);

      // Total difficulty should take uncles into account as well
      this.totalDifficulty = father.totalDifficulty.add(BigInteger.valueOf(this.difficulty));
    }

    POWBlock(ETHMiningNode ethMiner, POWBlock father, int time, List<POWBlock> u) {
      this(Collections.emptyList(), ethMiner, father, time);
      u.stream().forEach(b -> uncles.add(b.id));
    }

    POWBlock() {
      //super(null, 7951081, null, true, 1);
      super(7951081);
      this.transInBlock = Collections.emptyList();
      this.difficulty = 1949482043446410L;
      this.totalDifficulty = BigInteger.valueOf(this.difficulty);
    }

    static public POWBlock createGenesis() {
      return new POWBlock();
    }

    /**
     * Constantinople values.
     *
     * @param ts - timestamp in milliseconds of the new block
     * @see https://github.com/ethereum/go-ethereum/blob/master/consensus/ethash/ethash.go
     * @see https://eips.ethereum.org/EIPS/eip-1234
     * @see https://eips.ethereum.org/EIPS/eip-100
     */
    public long calculateDifficulty(POWBlock father, int ts) {
      long gap = (ts - father.proposalTime) / 9000;
      long y = 1;
      if (!uncles.isEmpty()) {
        y = 2;//  2 si le father a un uncle
      }
      long ugap = Math.max(-99, y - gap);
      long diff = (father.difficulty / 2048) * ugap;

      long periods = (father.height - 5000000L) / 100000L;
      long bomb = (long) Math.pow(2, periods - 2);

      return father.difficulty + diff + bomb;
    }
  }


  static class POWBlockComparator implements Comparator<POWBlock> {
    /**
     * @return -1 if o1 is lower than o2,
     */
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
    int gasLimit;
    String type;
    int gastCost = 0;

    Transactions(int gasLimit, String type) {
      this.gasLimit = gasLimit;
      this.type = type;
    }

    public void setGasCost(int gasSpent) {
      this.gastCost = gasSpent;
    }
  }


  abstract class ETHPoWNode extends BlockChainNode<POWBlock> {

    ETHPoWNode(Random rd, NodeBuilder nb, POWBlock genesis) {
      super(rd, nb, false, genesis);

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


  private void setHashPower() {
    List<Double> hashDistribution = new ArrayList<>();
    double[] miningPools =
        {0.26, 0.23, .12, .115, .0575, .024, .0185, .0161, 0.013, .013, .0129, .012};
    double remaining = 1.0;
    for (int i = 0; i < this.params.numberOfMiners; i++) {
      if (i < miningPools.length) {
        hashDistribution.add(miningPools[i]);
        remaining -= miningPools[i];
      } else {
        hashDistribution.add(remaining / (params.numberOfMiners - miningPools.length));
      }
    }
  }

  class ETHMiningNode extends ETHPoWNode {
    private int hashPower; // hash power in GH/s

    private POWBlock inMining;
    private double threshold;
    private List<POWBlock> uncles = new ArrayList<>();

    public ETHMiningNode(Random rd, NodeBuilder nb, int hashPower, POWBlock genesis) {
      super(rd, nb, genesis);
      this.hashPower = hashPower;
    }

    private void mine1ms() {
      if (inMining == null) {
        if (uncles.isEmpty()) {
          inMining = new POWBlock(this, this.head, network.time);
          threshold = solveByMs(inMining.difficulty);
        } else {
          inMining = new POWBlock(this, this.head, network.time, this.uncles);
          this.uncles.clear();
          threshold = solveByMs(inMining.difficulty);
        }
      }
      if (network.rd.nextDouble() < threshold) {
        network.sendAll(new BlockChainNetwork.SendBlock<>(inMining), this);
        onBlock(inMining);
        inMining = null;
      }
    }

    @Override
    public boolean onBlock(POWBlock b) {
      boolean res = super.onBlock(b);

      if (res) {
        // Someone sent us a new block, so we're going to switch
        //  our mining to this new head
        inMining = null;
      }
      if (this.blocksReceivedByBlockId.put(b.id, b) != null) {
        this.uncles.add(b);
      }
      return res;
    }

    double solveByMs(long difficulty) {
      // total hashpower  = ~200K GH/s
      double hpMs = (hashPower * 1024.0 * 1024 * 1024) / 1000.0; // hashPower is in GH/s

      double singleHashSuccess = (1.0 / difficulty);
      double noSuccess = Math.pow(1.0 - singleHashSuccess, hpMs);
      return 1 - noSuccess;
    }
  }

}
