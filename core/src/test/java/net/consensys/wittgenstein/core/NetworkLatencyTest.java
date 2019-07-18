package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.consensys.wittgenstein.core.geoinfo.Geo;
import net.consensys.wittgenstein.core.geoinfo.GeoAWS;
import net.consensys.wittgenstein.core.geoinfo.GeoAllCities;
import net.consensys.wittgenstein.tools.CSVLatencyReader;
import org.junit.Assert;
import org.junit.Test;

public class NetworkLatencyTest {
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
  public void testZeroDist() {
    Assert.assertEquals(0, n1.dist(n1));
    Assert.assertEquals(0, n2.dist(n2));
  }

  @Test
  public void testAwsLatency() {
    NetworkLatency nl = new NetworkLatency.AwsRegionNetworkLatency();
    Geo geoInfo = new GeoAWS();
    Random rd = new Random();

    for (String r1 : NetworkLatency.AwsRegionNetworkLatency.cities()) {
      NodeBuilder b1 = new NodeBuilder.NodeBuilderWithCity(Collections.singletonList(r1), geoInfo);
      Node n1 = new Node(rd, b1);
      for (String r2 : NetworkLatency.AwsRegionNetworkLatency.cities()) {
        NodeBuilder b2 =
            new NodeBuilder.NodeBuilderWithCity(Collections.singletonList(r2), geoInfo);
        Node n2 = new Node(rd, b2);
        int l = nl.getLatency(n1, n2, 0);
        if (r1.equals(r2)) {
          Assert.assertEquals(1, l);
        } else {
          Assert.assertTrue(r1 + " -> " + r2 + ": " + l, l > 1);
        }
      }
    }
  }

  @Test
  public void testIC3NetworkLatency() {
    NetworkLatency nl = new NetworkLatency.IC3NetworkLatency();

    Node a0 = new Node(new Random(0), new NodeBuilder());
    Node a00 = new Node(new Random(0), new NodeBuilder());
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.S10 / 2, nl.getLatency(a0, a00, 0));

    NodeBuilder nb =
        new NodeBuilder() {
          @Override
          public int getX(int rdi) {
            return Node.MAX_X / 2;
          }

          @Override
          public int getY(int rdi) {
            return Node.MAX_Y / 2;
          }
        };
    Node a1 = new Node(new Random(0), nb);
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.SW / 2, nl.getLatency(a0, a1, 0));
    Assert.assertEquals(NetworkLatency.IC3NetworkLatency.SW / 2, nl.getLatency(a1, a0, 0));
  }

  @Test
  public void testCitiesLatency() {
    CSVLatencyReader lr = new CSVLatencyReader();
    Assert.assertTrue(lr.cities().size() > 0);

    NodeBuilder nb = new NodeBuilder.NodeBuilderWithCity(lr.cities(), new GeoAllCities());
    NetworkLatency nl = new NetworkLatency.NetworkLatencyByCity();

    Random rd = new Random();
    List<Node> ln = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      ln.add(new Node(rd, nb));
    }

    for (Node f : ln) {
      for (Node t : ln) {
        int l = nl.getLatency(f, t, 1);
        if (f == t) {
          Assert.assertEquals(1, l);
        } else if (f.cityName.equals(t.cityName)) {
          Assert.assertTrue(l > 0);
        } else {
          Assert.assertTrue(
              "l=" + l + ", from=" + f.fullToString() + ", to=" + t.fullToString(), l > 0);
        }
      }
    }
  }

  @Test
  public void testEstimateLatency() {
    NetworkLatency nl = new NetworkLatency.EthScanNetworkLatency();
    Network<Node> network = new Network<>();
    network.setNetworkLatency(nl);
    NodeBuilder nb = new NodeBuilder();
    for (int i = 0; i < 1000; i++) {
      network.addNode(new Node(new Random(0), nb));
    }

    NetworkLatency.MeasuredNetworkLatency m1 = NetworkLatency.estimateLatency(network, 100000);
    network.setNetworkLatency(m1);
    NetworkLatency.MeasuredNetworkLatency m2 = NetworkLatency.estimateLatency(network, 100000);
    for (int i = 0; i < m1.longDistrib.length; i++) {
      Assert.assertEquals(m1.longDistrib[i], m2.longDistrib[i]);
    }
  }
}
