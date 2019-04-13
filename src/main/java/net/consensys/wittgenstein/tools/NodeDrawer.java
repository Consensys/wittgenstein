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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class NodeDrawer {
  final static int SIZE = 15;
  final static int MAX_X = Node.MAX_X;
  final static int MAX_Y = Node.MAX_Y;
  static boolean[][] dots;
  final Getter getter;
  final double min;
  final double max;
  final List<BufferedImage> imgs = new ArrayList<>();

  public NodeDrawer(Getter getter) {
    this.getter = getter;
    this.min = getter.getMin() - 1; // to avoid division by zero.
    this.max = getter.getMax();
    if (min >= max || min < -1) {
      throw new IllegalArgumentException(
          "bad values for min=" + getter.getMin() + "  or max=" + getter.getMax());
    }
  }

  public interface Getter {

    int getMax();

    int getMin();

    int getVal(Node n);

    boolean isSpecial(Node n);
  }

  boolean freeY(int x, int y) {
    if (x > MAX_X - 5)
      return false;
    if (y > MAX_Y - 5)
      return false;
    for (int ix = 0; ix < SIZE; ix++) {
      for (int iy = 0; iy < SIZE; iy++) {
        if (dots[x + ix][y + iy]) {
          return false;
        }
      }
    }
    return true;
  }

  void fill(int x, int y) {
    for (int ix = 0; ix < SIZE; ix++) {
      for (int iy = 0; iy < SIZE; iy++) {
        if (dots[x + ix][y + iy]) {
          throw new IllegalStateException("already filled");
        }
        dots[x + ix][y + iy] = true;
      }
    }
  }

  int[] findPos(Node n) {
    for (int y = 0; y < MAX_Y - SIZE; y++) {
      for (int x = 0; x < MAX_X - SIZE; x++) {
        if (freeY(x, y)) {
          return new int[] {x, y};
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
    int val = getter.getVal(n);
    double ratio = (val - min) / (max - min);
    return makeColor((int) (510 * ratio));
  }

  private BufferedImage draw(List<? extends Node> nodes) {
    dots = new boolean[MAX_X][MAX_Y];

    BufferedImage bi = new BufferedImage(MAX_X, MAX_Y, BufferedImage.TYPE_INT_RGB);
    Graphics g = bi.getGraphics();

    for (Node n : nodes) {
      g.setColor(findColor(n));
      int[] pos = findPos(n);
      fill(pos[0], pos[1]);
      g.fillRect(pos[0], pos[1], SIZE, SIZE);
      if (getter.isSpecial(n)) {
        g.setColor(Color.BLACK);
        g.fillRect(pos[0] + 2, pos[1] + 2, 2, 2);
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
