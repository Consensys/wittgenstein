package net.consensys.wittgenstein.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import net.consensys.wittgenstein.core.Node;
import net.consensys.wittgenstein.core.NodeBuilder;
import net.consensys.wittgenstein.protocols.PingPong;
import org.assertj.core.util.Files;
import org.junit.Assert;
import org.junit.Test;

public class NodeDrawerTest {

  @Test
  public void testDrawImage() {
    PingPong p1 = new PingPong(new PingPong.PingPongParameters());

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
    NodeBuilder nb = new NodeBuilder.NodeBuilderWithRandomPosition();
    Random rd = new Random();
    List<Node> nodes = new ArrayList<>();
    nodes.add(new Node(rd, nb));
    nodes.add(new Node(rd, nb));

    p1.init();
    p1.network().runMs(200);
    File destAnim = Files.newTemporaryFile();
    destAnim.delete();

    File destImg = Files.newTemporaryFile();
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
