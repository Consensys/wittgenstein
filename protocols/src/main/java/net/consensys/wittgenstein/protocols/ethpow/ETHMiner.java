package net.consensys.wittgenstein.protocols.ethpow;

import java.util.*;
import java.util.stream.Collectors;
import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;

@SuppressWarnings("WeakerAccess")
public class ETHMiner extends ETHPoW.ETHPoWNode {
  protected int hashPowerGHs; // hash power in GH/s
  protected ETHPoW.POWBlock inMining;
  protected Set<ETHPoW.POWBlock> minedToSend = new HashSet<>();
  protected double threshold;
  UncleCmp uncleCmp = new UncleCmp();

  public ETHMiner(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPowerGHs,
      ETHPoW.POWBlock genesis) {
    super(network, nb, genesis);
    this.hashPowerGHs = hashPowerGHs;
  }

  /** Override this if you want to choose the uncle to include. */
  protected boolean includeUncle(ETHPoW.POWBlock uncle) {
    return true;
  }

  /** Override this if you want to choose if we send a block we've just mined. */
  protected boolean sendMinedBlock(ETHPoW.POWBlock mined) {
    return true;
  }

  /** Override this if you want to add a delay when we send a mined block. */
  protected int extraSendDelay(ETHPoW.POWBlock mined) {
    return 0;
  }

  protected boolean switchMining(ETHPoW.POWBlock rcv) {
    return true;
  }

  /** Called when the head changes. */
  protected void onNewHead(ETHPoW.POWBlock oldHead, ETHPoW.POWBlock newHead) {}

  /** Called when we find a new block. */
  protected void onMinedBlock(ETHPoW.POWBlock mined) {}

  /** Called when we receive a new valid block. */
  protected void onReceivedBlock(ETHPoW.POWBlock rcv) {}

  /** @return the number of blocks we mined in a row ourselves from the block 'b' */
  int depth(ETHPoW.POWBlock b) {
    int res = 0;

    while (b != null && b.producer == this) {
      res++;
      b = b.parent;
    }

    return res;
  }

  /** @return the list of the possible uncle if you create a block with this father */
  List<ETHPoW.POWBlock> possibleUncles(ETHPoW.POWBlock father) {
    List<ETHPoW.POWBlock> res = new ArrayList<>();

    Set<ETHPoW.POWBlock> included = new HashSet<>();
    ETHPoW.POWBlock b = father;
    for (int h = 0; b != null && h < 8; h++, b = b.parent) {
      included.add(b);
      included.addAll(b.uncles);
    }

    for (int h = father.height; h >= father.height - 6; h--) {
      Set<ETHPoW.POWBlock> rcv =
          this.blocksReceivedByHeight.getOrDefault(h, Collections.emptySet());
      for (ETHPoW.POWBlock u : rcv) {
        if (!included.contains(u)
            && (u.parent == father.parent || father.isPossibleUncle(u))
            && includeUncle(u)) {
          res.add(u);
        }
      }
    }

    res.sort(uncleCmp);
    return res;
  }

  /**
   * Sort the uncle. Between two uncles:</br> - if we produced one of them, we include it first</br>
   * - if we produced two of them, we take the one with the higher height (better reward)</br> - if
   * we produced none of them, we take the one with the smallest height (opportunity to include the
   * other later)</br>
   */
  class UncleCmp implements Comparator<ETHPoW.POWBlock> {
    @Override
    public int compare(ETHPoW.POWBlock o1, ETHPoW.POWBlock o2) {
      if (o1.producer == ETHMiner.this) {
        if (o2.producer != o1.producer) {
          return -1;
        } else {
          return Integer.compare(o2.height, o1.height);
        }
      }

      if (o2.producer == ETHMiner.this) {
        return 1;
      }

      return Integer.compare(o1.height, o2.height);
    }
  }

  /** Mine for 10 milliseconds. */
  public final boolean mine10ms() {
    if (inMining == null) {
      startNewMining(head);
    }
    assert inMining != null;
    if (network.rd.nextDouble() < threshold) {
      onFoundNewBlock(inMining);
      return true;
    } else {
      return false;
    }
  }

  /** Start to mine a new block as a son of 'father' */
  public void startNewMining(ETHPoW.POWBlock father) {
    List<ETHPoW.POWBlock> us = possibleUncles(father);
    Set<ETHPoW.POWBlock> uss =
        us.isEmpty()
            ? Collections.emptySet()
            : us.size() <= 2 ? new HashSet<>(us) : new HashSet<>(us.subList(0, 2));
    inMining = new ETHPoW.POWBlock(this, father, network.time, uss);
    threshold = solveIn10ms(inMining.difficulty);
  }

  /** For tests: we force a successful mining. */
  protected void luckyMine() {
    if (!mine10ms()) {
      threshold = 10;
      mine10ms();
    }
  }

  /** Helper function: send a mined block. */
  protected void sendBlock(ETHPoW.POWBlock mined) {
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

  /** Helper function: send all the blocks mined not yet sent. */
  public void sendAllMined() {
    List<ETHPoW.POWBlock> all = new ArrayList<>(minedToSend);
    minedToSend.clear();
    for (ETHPoW.POWBlock b : all) {
      sendMinedBlock(b);
    }
  }

  private void onFoundNewBlock(ETHPoW.POWBlock mined) {
    ETHPoW.POWBlock oldHead = head;
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

  public int getMinedToSend() {
    return minedToSend.size();
  }

  @Override
  public boolean onBlock(ETHPoW.POWBlock b) {
    ETHPoW.POWBlock oldHead = head;
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

  /** Calculate the probability for this node to find the right hash in 10ms. */
  double solveIn10ms(long difficulty) {
    double hpTMs = (hashPowerGHs * 1024.0 * 1024 * 1024) / 100.0;

    double singleHashSuccess = (1.0 / difficulty);
    double noSuccess = Math.pow(1.0 - singleHashSuccess, hpTMs);
    return 1 - noSuccess;
  }

  /** To try a strategy. */
  public static void tryMiner(
      String builderName, String nlName, Class<?> miner, double[] pows, int hours, int runs) {

    System.out.println(
        "miner, hashrate ratio, revenue ratio, revenue, uncle rate, total revenue, avg difficulty");

    for (double pow : pows) {
      ETHPoW.ETHPoWParameters params =
          new ETHPoW.ETHPoWParameters(builderName, nlName, 10, miner.getName(), pow);

      HashMap<Integer, Double> rewards = new HashMap<>();
      rewards.put(1, 0.0);
      double ur = 0;
      long avgDiff = 0;

      for (int i = 1; i <= runs; i++) {
        ETHPoW p = new ETHPoW(params);
        p.network.rd.setSeed(i);
        p.init();
        p.network.runH(hours);

        // For real tests, we skip the first blocks: the system is starting and is tuning
        //   the difficulty & so on. So not including them removes some noise.
        int limit = (hours > 30 ? 5000 : 0) + p.genesis.height;

        // We also skip the last blocks. Especially if there are long private chains we
        //  don't want the last block to decide as it may be very random.
        ETHPoW.POWBlock base = p.network.getNodeById(1).head;
        for (int j = 0; hours > 30 && j < 5000; j++) {
          base = base.parent;
        }

        base.allRewardsById(rewards, limit);
        ur += base.uncleRate(limit);
        avgDiff += base.avgDifficulty(limit);

        p.getByzantineNode().close();
      }

      ur /= runs;
      avgDiff /= runs;
      // take this pass the agent miner id and get reward by height get function to return value
      final double tot = rewards.values().stream().reduce(0.0, Double::sum);
      Map<Integer, Double> pc =
          rewards.entrySet().stream()
              .collect(Collectors.toMap(Map.Entry::getKey, k -> (k.getValue() / tot)));

      String powS = String.format("%.2f", pow);
      String urS = String.format("%.4f", ur);
      String rateS = String.format("%.4f", pc.get(1));
      String rewS = String.format("%.0f", rewards.get(1) / runs);
      String totS = String.format("%.0f", tot / runs);

      System.out.println(
          miner.getSimpleName()
              + "/"
              + nlName
              + "/"
              + hours
              + "/"
              + runs
              + ", "
              + powS
              + ", "
              + rateS
              + ", "
              + rewS
              + ", "
              + urS
              + ", "
              + totS
              + ", "
              + avgDiff);
    }
  }
}
