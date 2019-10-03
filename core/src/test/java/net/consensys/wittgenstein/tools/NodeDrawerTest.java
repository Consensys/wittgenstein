package net.consensys.wittgenstein.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.consensys.wittgenstein.core.Network;
import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.core.Protocol;
import org.junit.Assert;
import org.junit.Test;

public class NodeDrawerTest {

  @Test
  public void testDrawImage() throws IOException {
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Random rd = new Random();

    Protocol p1 =
        new Protocol() {
          Network<Node> network = new Network<>();

          @Override
          public Network<?> network() {
            return network;
          }

          @Override
          public Protocol copy() {
            return null;
          }

          @Override
          public void init() {}
        };

    NodeDrawer.NodeStatus nds =
        new NodeDrawer.NodeStatus() {
          @Override
          public int getVal(Node n) {
            return 5;
          }

          @Override
          public boolean isSpecial(Node n) {
            return false;
          }

          @Override
          public int getMax() {
            return 5;
          }

          @Override
          public int getMin() {
            return 0;
          }
        };
    List<Node> nodes = new ArrayList<>();
    nodes.add(new Node(rd, nb));
    nodes.add(new Node(rd, nb));

    p1.init();
    p1.network().runMs(200);
    File destAnim = File.createTempFile(this.getClass().getSimpleName(), "1");
    destAnim.delete();

    File destImg = File.createTempFile(this.getClass().getSimpleName(), "2");
    destImg.delete();

    NodeDrawer nd = new NodeDrawer(nds, destAnim, 1);
    nd.drawNewState(100, TimeUnit.MILLISECONDS, nodes);
    nd.writeLastToGif(destImg);
    Assert.assertTrue(destImg.exists());
    destImg.delete();

    nd.close();
    Assert.assertTrue(destAnim.exists());
    destAnim.delete();
  }
}
