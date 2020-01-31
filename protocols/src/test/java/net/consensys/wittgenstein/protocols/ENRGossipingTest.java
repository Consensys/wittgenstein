package net.consensys.wittgenstein.protocols;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.consensys.wittgenstein.core.*;
import net.consensys.wittgenstein.core.utils.StatsHelper;
import org.junit.Assert;
import org.junit.Test;

public class ENRGossipingTest {

  // test: runs until all nodes have found at least 1 peer with a matching capability.

  // Test that copy method works
  @Test
  public void testCopy() {
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);
    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    ENRGossiping p1 =
        new ENRGossiping(
            new ENRGossiping.ENRParameters(100, 10, 25, 15000, 2, 20, 0.4f, 10, 5, 5, nb, nl));
    ENRGossiping p2 = p1.copy();
    p1.init();
    p1.network().run(10);
    p2.init();
    p2.network().run(10);

    for (ENRGossiping.ETHNode n1 : p1.network().allNodes) {
      ENRGossiping.ETHNode n2 = p2.network().getNodeById(n1.nodeId);
      Assert.assertNotNull(n2);
      Assert.assertEquals(n1.doneAt, n2.doneAt);
      Assert.assertEquals(n1.isDown(), n2.isDown());
      Assert.assertEquals(n1.getMsgReceived(-1).size(), n2.getMsgReceived(-1).size());
      Assert.assertEquals(n1.x, n2.x);
      Assert.assertEquals(n1.y, n2.y);
      Assert.assertEquals(n1.peers, n2.peers);
    }
  }

  @Test
  public void testPPT() {
    String nb = RegistryNodeBuilders.name(RegistryNodeBuilders.Location.RANDOM, true, 0);

    String nl = NetworkLatency.NetworkLatencyByDistanceWJitter.class.getSimpleName();
    ENRGossiping p1 =
        new ENRGossiping(
            new ENRGossiping.ENRParameters(100, 10, 25, 15000, 2, 20, 0.4f, 30, 10, 5, nb, nl));
    Predicate<Protocol> contIf = pp1 -> pp1.network().time <= 1000 * 100;
    StatsHelper.StatsGetter sg =
        new StatsHelper.StatsGetter() {
          final List<String> fields = new StatsHelper.SimpleStats(0, 0, 0).fields();

          @Override
          public List<String> fields() {
            return fields;
          }

          @Override
          public StatsHelper.Stat get(List<? extends Node> liveNodes) {
            return StatsHelper.getStatsOn(liveNodes, n -> ((ENRGossiping.ETHNode) n).doneAt);
          }
        };
    ProgressPerTime ppp =
        new ProgressPerTime(
            p1,
            "",
            "Nodes that have found capabilities",
            sg,
            1,
            null,
            10000,
            TimeUnit.MILLISECONDS);
    ppp.run(contIf);
  }
}
