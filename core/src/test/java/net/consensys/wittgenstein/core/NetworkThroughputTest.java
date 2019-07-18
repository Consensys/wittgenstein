package net.consensys.wittgenstein.core;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class NetworkThroughputTest {
  private final AtomicInteger ai = new AtomicInteger(1);
  private final NodeBuilder nb =
      new NodeBuilder() {
        @Override
        public int getX(int rdi) {
          return ai.getAndAdd(Node.MAX_X / 2);
        }
      };
  private final Node n1 = new Node(new Random(0), nb);
  private final Node n2 = new Node(new Random(0), nb);

  @Test
  public void testRateTCPLimit() {
    NetworkLatency nl = new NetworkLatency.NetworkFixedLatency(200 / 2);
    NetworkThroughput nt = new NetworkThroughput.MathisNetworkThroughput(nl, 64 * 1024);

    int delay = nt.delay(n1, n2, 0, 2048);
    Assert.assertEquals(117, delay);
  }

  @Test
  public void testRateBandwidthLimit() {
    NetworkLatency nl = new NetworkLatency.NetworkFixedLatency(1000);
    NetworkThroughput nt = new NetworkThroughput.MathisNetworkThroughput(nl, 5 * 1024 * 1024);

    int delay = nt.delay(n1, n2, 0, 2048);
    Assert.assertEquals(1177, delay);
  }
}
