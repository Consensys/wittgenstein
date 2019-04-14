package net.consensys.wittgenstein.tools;

import net.consensys.wittgenstein.core.Node;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

public class NodeDrawer {
  private final static int SIZE = 5;
  private final static int MAX_X = Node.MAX_X;
  private final static int MAX_Y = Node.MAX_Y;

  private static class Pos {
    final int x;
    final int y;

    private Pos(int x, int y) {
      this.x = x;
      this.y = y;
    }
  }

  BufferedImage loadWM() {
    String imgName = "world-map-2000px.png";

    //ImageIO.read()
    return null;
  }

  /**
   * Keep tracks of the dots already used in the image, to be sure that the nodes are not
   * overlapping.
   */
  private boolean[][] dots = new boolean[MAX_X][MAX_Y];

  /**
   * Keep track of the positions already allocated, so the nodes don't change their position between
   * two frames.
   */
  private final Map<Node, Pos> nodePos = new HashMap<>();

  private final NodeStatus nodeStatus;
  private final double min;
  private final double max;
  private final List<BufferedImage> imgs = new ArrayList<>();

  public NodeDrawer(NodeStatus nodeStatus) {
    this.nodeStatus = nodeStatus;
    this.min = nodeStatus.getMin() - 1; // to avoid division by zero.
    this.max = nodeStatus.getMax();
    if (min >= max || min < -1) {
      throw new IllegalArgumentException(
          "bad values for min=" + nodeStatus.getMin() + "  or max=" + nodeStatus.getMax());
    }
  }

  public interface NodeStatus {

    /**
     * @return the value associated to this node
     */
    int getVal(Node n);

    /**
     * @return the maximum value that can be associated to a node. This value will be shown as
     *         green.
     */
    int getMax();

    /**
     * @return the minimum value that can be associated to a node. This value will be shown as red.
     */
    int getMin();

    /**
     * @return if the node is 'special'. This node will be marked with a dot;.
     */
    boolean isSpecial(Node n);
  }

  private boolean isFree(int x, int y) {
    if (x > MAX_X - 5) {
      return false;
    }
    if (y > MAX_Y - 5) {
      return false;
    }
    for (int ix = 0; ix < SIZE; ix++) {
      for (int iy = 0; iy < SIZE; iy++) {
        if (dots[x + ix][y + iy]) {
          return false;
        }
      }
    }
    return true;
  }

  private void fill(int x, int y) {
    for (int ix = 0; ix < SIZE; ix++) {
      for (int iy = 0; iy < SIZE; iy++) {
        if (dots[x + ix][y + iy]) {
          throw new IllegalStateException("already filled");
        }
        dots[x + ix][y + iy] = true;
      }
    }
  }

  private Pos findPos(Node n) {
    Pos res = nodePos.get(n);
    if (res != null) {
      return res;
    }

    for (int y = 0; y < MAX_Y - SIZE; y++) {
      for (int x = 0; x < MAX_X - SIZE; x++) {
        if (isFree(x, y)) {
          res = new Pos(x, y);
          fill(x, y);
          nodePos.put(n, res);
          return res;
        }
      }
    }

    throw new IllegalStateException("No free room!");

  }

  // see https://stackoverflow.com/questions/4161369/html-color-codes-red-to-yellow-to-green
  private Color makeColor(int value) {
    // value must be between [0, 510]
    value = Math.min(Math.max(0, value), 510);

    int redValue;
    int greenValue;
    if (value < 255) {
      redValue = 255;
      greenValue = (int) Math.sqrt(value) * 16;
      if (greenValue > 255) {
        greenValue = 255;
      }
    } else {
      greenValue = 255;
      value = value - 255;
      redValue = 256 - (value * value / 255);
      redValue = Math.round(redValue);
      if (redValue > 255) {
        redValue = 255;
      }
    }

    return new Color(redValue, greenValue, 0);
  }

  private Color findColor(Node n) {
    int val = nodeStatus.getVal(n);
    double ratio = (val - min) / (max - min);
    return makeColor((int) (510 * ratio));
  }

  private BufferedImage draw(List<? extends Node> nodes) {

    BufferedImage bi = new BufferedImage(MAX_X, MAX_Y, BufferedImage.TYPE_INT_RGB);
    Graphics g = bi.getGraphics();

    for (Node n : nodes) {
      g.setColor(findColor(n));
      Pos pos = findPos(n);
      g.fillRect(pos.x, pos.y, SIZE, SIZE);
      if (nodeStatus.isSpecial(n)) {
        g.setColor(Color.BLACK);
        g.fillRect(pos.x + 2, pos.y + 2, 2, 2);
      }
    }
    return bi;
  }

  public void drawNewState(List<? extends Node> nodes) {
    imgs.add(draw(nodes));
  }

  public void writeAnimatedGif(File dest) {
    try {
      ImageOutputStream output = new FileImageOutputStream(dest);
      GifSequenceWriter writer = new GifSequenceWriter(output, imgs.get(0).getType(), 10, true);

      for (BufferedImage bi : imgs) {
        writer.writeToSequence(bi);
      }

      writer.close();
      output.close();

    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public void writeLastToGif(File gifFile) {
    try {
      writeGif(imgs.get(imgs.size() - 1), gifFile);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void writeGif(BufferedImage bi, File gifFile) throws IOException {
    Iterator imageWriters = ImageIO.getImageWritersByFormatName("GIF");
    ImageWriter imageWriter = (ImageWriter) imageWriters.next();
    ImageOutputStream ios = ImageIO.createImageOutputStream(gifFile);
    imageWriter.setOutput(ios);
    imageWriter.write(bi);
  }

}
