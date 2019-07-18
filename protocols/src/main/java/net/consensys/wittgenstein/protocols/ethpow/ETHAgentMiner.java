package net.consensys.wittgenstein.protocols.ethpow;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.ListIterator;
import net.consensys.wittgenstein.core.BlockChainNetwork;
import net.consensys.wittgenstein.core.NodeBuilder;

@SuppressWarnings("WeakerAccess")
public class ETHAgentMiner extends ETHMiner {
  private static final String DATA_FILE = "decisions.csv";

  /** List of the decision taken that we need to evaluate. Sorted by evaluation height. */
  final LinkedList<ETHPoW.Decision> decisions = new LinkedList<>();

  private final PrintWriter decisionOutput;

  public ETHAgentMiner(
      BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
      NodeBuilder nb,
      int hashPower,
      ETHPoW.POWBlock genesis) {
    super(network, nb, hashPower, genesis);
    try {
      FileWriter fw = new FileWriter(DATA_FILE, true);
      BufferedWriter bw = new BufferedWriter(fw);
      decisionOutput = new PrintWriter(bw);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  /** Add a decision tp the list of decisions to be evaluated. */
  protected final void addDecision(ETHPoW.Decision d) {
    if (d.rewardAtHeight <= head.height) {
      throw new IllegalArgumentException("Can't calculate a reward for " + d + ", head=" + head);
    }

    if (decisions.isEmpty() || decisions.peekLast().rewardAtHeight <= d.rewardAtHeight) {
      decisions.addLast(d);
    } else {
      ListIterator<ETHPoW.Decision> it = decisions.listIterator(decisions.size());
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
  protected final void onNewHead(ETHPoW.POWBlock oldHead, ETHPoW.POWBlock newHead) {
    while (!decisions.isEmpty() && decisions.peekFirst().rewardAtHeight <= newHead.height) {
      ETHPoW.Decision cur = decisions.pollFirst();
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
