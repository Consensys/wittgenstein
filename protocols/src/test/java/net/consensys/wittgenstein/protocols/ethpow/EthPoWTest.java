package net.consensys.wittgenstein.protocols.ethpow;

import java.util.*;
import net.consensys.wittgenstein.core.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class EthPoWTest {
  private ETHPoW.POWBlock gen = ETHPoW.POWBlock.createGenesis();
  private String nlName = NetworkLatency.IC3NetworkLatency.class.getSimpleName();
  private String builderName =
      RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 1);
  private ETHPoW ep = new ETHPoW(new ETHPoW.ETHPoWParameters(builderName, nlName, 4, null, 0));
  private Random rd = new Random();
  private NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
  private ETHMiner m0;
  private ETHMiner m1;
  private ETHMiner m2;
  private ETHMiner m3;

  @Before
  public void before() {
    ep.init();
    m0 = ep.network.getNodeById(0);
    m1 = ep.network.getNodeById(1);
    m2 = ep.network.getNodeById(2);
    m3 = ep.network.getNodeById(3);
  }

  /** Test difficulty calculation against real data. */
  @Test
  public void testDifficulty() {
    ETHPoW.POWBlock b1 = gen;
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(null, b1, b1.proposalTime + 13000);
    Assert.assertEquals(1949482177664138L, b2.difficulty);
    Assert.assertEquals("10591884163387748525067", b2.totalDifficulty.toString());

    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(null, b2, b2.proposalTime + 7000);
    Assert.assertEquals(1950434207476428L, b3.difficulty);
    Assert.assertEquals("10591886113821956001495", b3.totalDifficulty.toString());

    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(null, b3, b3.proposalTime + 4000);
    Assert.assertEquals(1951386702147025L, b4.difficulty);
    Assert.assertEquals("10591888065208658148520", b4.totalDifficulty.toString());

    ETHPoW.POWBlock b5 = new ETHPoW.POWBlock(null, b4, b4.proposalTime + 39000);
    Assert.assertEquals(1948528359750282L, b5.difficulty);
    Assert.assertEquals("10591890013737017898802", b5.totalDifficulty.toString());

    ETHPoW.POWBlock b6 = new ETHPoW.POWBlock(null, b5, b5.proposalTime + 3000);
    Assert.assertEquals(1949479923831169L, b6.difficulty);
    Assert.assertEquals("10591891963216941729971", b6.totalDifficulty.toString());

    ETHPoW.POWBlock b7 = new ETHPoW.POWBlock(null, b6, b6.proposalTime + 15000);
    Assert.assertEquals(1949480058048897L, b7.difficulty);
    Assert.assertEquals("10591893912696999778868", b7.totalDifficulty.toString());

    ETHPoW.POWBlock u1 = new ETHPoW.POWBlock(null, b5, b5.proposalTime);
    ETHPoW.POWBlock b8 =
        new ETHPoW.POWBlock(null, b7, b7.proposalTime + 11000, Collections.singleton(u1));
    Assert.assertEquals(1949480192266625L, b8.difficulty);
    Assert.assertEquals("10591895862177192045493", b8.totalDifficulty.toString());

    ETHPoW.POWBlock b9 =
        new ETHPoW.POWBlock(null, b8, b8.proposalTime + 3000, Collections.singleton(u1));
    Assert.assertEquals(1951384115734613L, b9.difficulty);
    Assert.assertEquals("10591897813561307780106", b9.totalDifficulty.toString());
  }

  /** Test time to find a hash against real data. */
  @Test
  public void testInitialDifficulty() {
    // Difficulties & hashrate at block 7999565
    // We should get an avg block generation time of 13s
    ETHMiner m = new ETHMiner(ep.network, nb, 162 * 1024, gen);
    long avgD = 2031093808891300L + 2028116957207141L + 2032085740451229L;
    avgD += 2033078320257064L + 2032085956568356L + 2032085822350628L;
    avgD /= 6;
    double curProba = m.solveIn10ms(avgD);
    int found = 0;
    int time = 500_000_000;
    for (int t = 0; t < time; t += 10) {
      if (rd.nextDouble() < curProba) {
        found++;
      }
    }
    double avg = (time / (1000.0 * found));
    Assert.assertEquals("avg=" + avg, 13.0, avg, 0.5);
  }

  @Test
  public void testFindHash() {
    double p = m0.solveIn10ms(1);
    Assert.assertEquals(1, p, 0.00001);
  }

  @Test
  public void testBlockDurationConvergence() {
    ETHMiner m = new ETHMiner(ep.network, nb, 100 * 1024, gen);
    ETHPoW.POWBlock cur = gen;
    double curProba = m.solveIn10ms(cur.difficulty);
    int tot = 0;
    long target = 10000;
    double found = 0;
    for (int t = gen.proposalTime; cur.height - gen.height < target; t += 10) {
      if (rd.nextDouble() < curProba) {
        if (cur.height > gen.height + target * .8) {
          int foundIn = t - cur.proposalTime;
          tot += foundIn;
          found++;
        }
        cur = new ETHPoW.POWBlock(m, cur, t);
        curProba = m.solveIn10ms(cur.difficulty);
      }
    }
    tot /= 1000;
    Assert.assertEquals(13.0, (tot / found), 0.5);
  }

  @Test
  public void testMinersFairness() {
    ep.network().run(10_000);
    HashMap<ETHMiner, Double> rs = m0.head.allRewards();
    double c0 = rs.getOrDefault(m0, 0.0);
    double c1 = rs.getOrDefault(m1, 0.0);
    double diff = Math.abs(c0 - c1);
    double th = (c0 + c1) / 10;
    Assert.assertTrue(diff < th);
  }

  /**
   * Create a new block with same parent as another block and checks block is included in chain as
   * uncle
   */
  @Test
  public void testUncles() {
    ETHPoW.ETHPoWParameters params = new ETHPoW.ETHPoWParameters(builderName, nlName, 5, null, 0);
    ETHPoW p = new ETHPoW(params);
    p.init();
    p.network().run(10000);
    ETHMiner m = p.network.observer;
    int timestamp = p.network().time;
    Set<ETHPoW.POWBlock> main = p.network.observer.blocksReceivedByHeight.get(gen.height + 2);
    ETHPoW.POWBlock father = main.iterator().next().parent;
    // New block created with same father as existing block
    ETHPoW.POWBlock uncle = new ETHPoW.POWBlock(m, father, timestamp);

    p.network.sendAll(new BlockChainNetwork.SendBlock<>(uncle), m);
    p.network().run(1000);

    Assert.assertTrue(
        p.network.allNodes.get(1).blocksReceivedByHeight.get(uncle.height).contains(uncle));
  }

  @Test
  public void testAvgDifficulty() {
    ETHPoW.POWBlock b1 = new ETHPoW.POWBlock(null, null, 1, 100, 1);
    Assert.assertEquals(100, b1.avgDifficulty(0));

    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(m1, b1, 2, 100, 1);
    Assert.assertEquals(100, b2.avgDifficulty(0));

    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(m1, b2, 3, 400, 1);
    Assert.assertEquals(200, b3.avgDifficulty(0));

    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(m1, b3, 4, 400, 1);
    Assert.assertEquals(400, b4.avgDifficulty(b3.height));
  }

  @Test
  public void testReward() {
    ETHPoW.POWBlock b1 = gen;
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(m1, b1, b1.proposalTime + 13000);

    List<ETHPoW.Reward> r = b2.rewards();
    Assert.assertEquals(1, r.size());
    Assert.assertEquals(2.0, r.get(0).amount, 0.001);
    Assert.assertEquals(m1, r.get(0).who);

    ETHPoW.POWBlock u = new ETHPoW.POWBlock(m2, b1, b1.proposalTime + 13000);
    double[] ur = new double[] {1.75, 1.5, 1.25, 1.0, 0.75, 0.50, 0.25};
    ETHPoW.POWBlock cur = b2;
    for (int p = 0; p < 7; p++) {
      cur = new ETHPoW.POWBlock(m1, cur, cur.proposalTime + 13000, Collections.singleton(u));

      r = cur.rewards();
      Assert.assertEquals(2, r.size());

      HashMap<ETHMiner, Double> s = new HashMap<>();
      ETHPoW.Reward.sumRewards(s, r);
      Assert.assertEquals(2, s.size());
      Assert.assertEquals(2.0625, s.get(m1), 0.0000001);
      Assert.assertEquals(ur[p], s.get(m2), 0.0000001);
    }

    cur = new ETHPoW.POWBlock(m1, b2, b2.proposalTime + 13000);
    ETHPoW.POWBlock u2 = new ETHPoW.POWBlock(m3, cur, cur.proposalTime + 13000);
    cur = new ETHPoW.POWBlock(m1, cur, cur.proposalTime + 13000);
    cur = new ETHPoW.POWBlock(m1, cur, cur.proposalTime + 13000, Set.of(u, u2));

    r = cur.rewards();
    Assert.assertEquals(3, r.size());
    HashMap<ETHMiner, Double> s = new HashMap<>();
    ETHPoW.Reward.sumRewards(s, r);
    Assert.assertEquals(3, s.size());
    Assert.assertEquals(2.0 + .0625 * 2, s.get(m1), 0.0000001);
    Assert.assertEquals(1.25, s.get(m2), 0.0000001);
    Assert.assertEquals(1.75, s.get(m3), 0.0000001);
  }

  @Test
  public void testUncleSort() {
    ETHPoW.POWBlock b1 = new ETHPoW.POWBlock(m0, gen, gen.proposalTime + 1);
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(m1, gen, gen.proposalTime + 1);

    List<ETHPoW.POWBlock> us = new ArrayList<>();
    us.add(b1);
    us.add(b2);

    us.sort(m0.uncleCmp);
    Assert.assertEquals(us.get(0).producer, m0);

    us.sort(m1.uncleCmp);
    Assert.assertEquals(us.get(0).producer, m1);

    Assert.assertTrue(m0.uncleCmp.compare(b1, b2) < 0);
    Assert.assertTrue(m1.uncleCmp.compare(b1, b2) > 0);

    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(m0, gen, gen.proposalTime + 1);
    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(m0, b1, gen.proposalTime + 1);
    Assert.assertTrue(m0.uncleCmp.compare(b3, b4) > 0);
    Assert.assertTrue(m1.uncleCmp.compare(b3, b4) < 0);
  }

  @Test
  public void testUncleSelection() {
    ETHPoW.POWBlock b1 = new ETHPoW.POWBlock(m0, gen, gen.proposalTime + 1);
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(m0, b1, b1.proposalTime + 1);
    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(m0, b2, b2.proposalTime + 1);

    List<ETHPoW.POWBlock> bs = new ArrayList<>();
    for (ETHPoW.POWBlock b : List.of(b1, b2, b3)) {
      bs.add(b);
      bs.add(new ETHPoW.POWBlock(m1, b, b.proposalTime + 1));
      bs.add(new ETHPoW.POWBlock(m2, b, b.proposalTime + 1));
      bs.add(new ETHPoW.POWBlock(m3, b, b.proposalTime + 1));
    }

    for (ETHPoW.POWBlock b : bs) {
      for (ETHPoW.ETHPoWNode n : ep.network.allNodes) {
        n.onBlock(b);
      }
    }

    List<ETHPoW.POWBlock> us = m0.possibleUncles(b1);
    Assert.assertEquals(0, us.size());

    us = m1.possibleUncles(b1);
    Assert.assertEquals(0, us.size());

    us = m0.possibleUncles(b2);
    Assert.assertEquals(3, us.size());
    Assert.assertFalse(us.contains(b1));
    Assert.assertFalse(us.contains(b2));

    us = m1.possibleUncles(b2);
    Assert.assertEquals(3, us.size());
    Assert.assertFalse(us.contains(b1));
    Assert.assertFalse(us.contains(b2));

    us = m0.possibleUncles(b3);
    Assert.assertEquals(6, us.size());
    Assert.assertFalse(us.contains(b1));
    Assert.assertFalse(us.contains(b2));

    us = m1.possibleUncles(b3);
    Assert.assertEquals(6, us.size());
    Assert.assertFalse(us.contains(b1));
    Assert.assertFalse(us.contains(b2));
  }

  @Test
  public void testMiningWithUncle() {
    ETHPoW.POWBlock b1 = new ETHPoW.POWBlock(m0, gen, gen.proposalTime + 1);
    ETHPoW.POWBlock b2 = new ETHPoW.POWBlock(m0, b1, b1.proposalTime + 1);
    ETHPoW.POWBlock b3 = new ETHPoW.POWBlock(m0, b2, b2.proposalTime + 1);
    ETHPoW.POWBlock b4 = new ETHPoW.POWBlock(m0, b3, b3.proposalTime + 1);

    for (ETHPoW.POWBlock b : List.of(b1, b2, b3)) {
      m0.onBlock(b);
      m0.onBlock(new ETHPoW.POWBlock(m1, b, b.proposalTime + 1));
      m0.onBlock(new ETHPoW.POWBlock(m2, b, b.proposalTime + 1));
      m0.onBlock(new ETHPoW.POWBlock(m3, b, b.proposalTime + 1));
    }
    m0.onBlock(b4);

    ep.network.time = b4.proposalTime + 1;
    m0.luckyMine();
    Assert.assertEquals(2, m0.head.uncles.size()); // father is b1 for both
    Assert.assertEquals(b2.height, m0.head.uncles.get(0).height);

    ep.network.time++;
    m0.luckyMine();
    Assert.assertEquals(2, m0.head.uncles.size()); // fathers: b1 and b2

    ep.network.time++;
    m0.luckyMine();
    Assert.assertEquals(2, m0.head.uncles.size()); // father is b2 for both
    Assert.assertEquals(b3.height, m0.head.uncles.get(0).height);

    ep.network.time++;
    m0.luckyMine();
    Assert.assertEquals(2, m0.head.uncles.size()); // father is b3 for both
    Assert.assertEquals(b3.height + 1, m0.head.uncles.get(0).height);
    Assert.assertEquals(b3.height + 1, m0.head.uncles.get(1).height);

    ep.network.time++;
    m0.luckyMine();
    Assert.assertEquals(1, m0.head.uncles.size()); // father is b3
    Assert.assertEquals(b3.height + 1, m0.head.uncles.get(0).height);

    ep.network.time++;
    m0.luckyMine();
    Assert.assertEquals(0, m0.head.uncles.size());
  }

  private class EmptyDecision extends ETHPoW.Decision {
    final int p;

    EmptyDecision(int rewardAtHeight) {
      super(1, gen.height + 1 + rewardAtHeight);
      p = rewardAtHeight;
    }

    @Override
    public String forCSV() {
      return "" + p;
    }
  }

  @Test
  public void testDecisionSorting() {
    ETHAgentMiner n = new ETHAgentMiner(ep.network, nb, 1, gen);
    n.addDecision(new EmptyDecision(100));
    n.addDecision(new EmptyDecision(50));
    n.addDecision(new EmptyDecision(125));
    n.addDecision(new EmptyDecision(25));
    n.addDecision(new EmptyDecision(120));
    n.addDecision(new EmptyDecision(75));
    n.addDecision(new EmptyDecision(35));
    n.addDecision(new EmptyDecision(1));

    Assert.assertEquals(8, n.decisions.size());
    int cur = 0;
    for (ETHPoW.Decision f : n.decisions) {
      Assert.assertTrue("cur=" + cur + ", f=" + f, f.rewardAtHeight >= cur);
      cur = f.rewardAtHeight;
    }
  }

  static class DelayedMiner extends ETHAgentMiner {

    public DelayedMiner(
        BlockChainNetwork<ETHPoW.POWBlock, ETHMiner> network,
        NodeBuilder nb,
        int hashPower,
        ETHPoW.POWBlock genesis) {
      super(network, nb, hashPower, genesis);
    }

    @Override
    protected int extraSendDelay(ETHPoW.POWBlock mined) {
      int duration = network.time - mined.proposalTime;
      int depth = depth(mined);
      int delay = network.rd.nextInt(20) * 500;
      ExtraSendDelayDecision dec =
          new ExtraSendDelayDecision(mined.height, depth, mined.height + 10, duration, delay);
      addDecision(dec);
      return delay;
    }
  }

  static class ExtraSendDelayDecision extends ETHPoW.Decision {
    final int miningDurationMs;
    final int ownMiningDepth;
    final int delay;

    ExtraSendDelayDecision(
        int takenAtHeight,
        int ownMiningDepth,
        int rewardAtHeight,
        int miningDurationMs,
        int delay) {
      super(takenAtHeight, rewardAtHeight);
      this.miningDurationMs = miningDurationMs;
      this.ownMiningDepth = ownMiningDepth;
      this.delay = delay;
    }

    public String forCSV() {
      return miningDurationMs + "," + ownMiningDepth + "," + delay;
    }
  }

  private void testBadMiner(Class<?> miner) {
    final double[] pows = new double[] {0.01, 0.50};
    final int runs = 2;
    final int hours = 5;
    final String nlName =
        RegistryNetworkLatencies.name(RegistryNetworkLatencies.Type.UNIFORM, 2000);
    final String bdlName = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    ETHMiner.tryMiner(bdlName, nlName, miner, pows, hours, runs);
  }

  @Test
  public void testSelfishMiner() {
    testBadMiner(ETHSelfishMiner.class);
  }

  @Test
  public void testSelfishMiner2() {
    testBadMiner(ETHSelfishMiner2.class);
  }

  @Test
  public void testStandardMiner() {
    testBadMiner(ETHMiner.class);
  }

  @Test
  public void testDelayedMiner() {
    testBadMiner(DelayedMiner.class);
  }
}
