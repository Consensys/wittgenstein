package net.consensys.wittgenstein.tools;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GraphTest {
  private Graph.Series series1 = new Graph.Series("Test Series 1");
  private Graph.Series series2 = new Graph.Series("Test Series 2");
  private Graph.Series series3 = new Graph.Series("Test Series 3");
  private Graph.Series series4 = new Graph.Series("Test Series 3");
  private Graph.Series series5 = new Graph.Series("Test Series 3");
  private List<Graph.Series> testSeries = new ArrayList<>();
  private List<Graph.Series> testSeries2 = new ArrayList<>();
  private List<Graph.Series> testSeries3 = new ArrayList<>();
  private List<Graph.Series> testSeries4 = new ArrayList<>();
  private List<Graph.Series> testSeries5 = new ArrayList<>();

  @Before
  public void setup() {
    generateSeries(series1, 10, 10, 3, 4, 4);
    generateSeries(series2, 10, 10, 4, 4, 4);
    generateSeries(series3, 10, 20, 3, 4, 4);
    generateSeries(series4, 10, 20, 3, 4, 3);
    testSeries.add(series1);
    testSeries.add(series2);
    testSeries2.add(series1);
    testSeries2.add(series3);
    testSeries3.add(series1);
    testSeries3.add(series4);
    testSeries5.add(series1);
  }

  private void generateSeries(
      Graph.Series s, int x, int y, int incrementX, int incrementY, int length) {
    for (int i = 0; i < length; i++) {
      s.addLine(new Graph.ReportLine(x * (1 + i * incrementX), y * (1 + i * incrementY)));
    }
  }

  /**
   * Verifying that Illegal Argument Exception is thrown when series have incomplete values i.e. x
   * values are not consistently equal
   */
  @Test(expected = IllegalArgumentException.class)
  public void testSeriesMissingValues() {
    Graph.statSeries("test", testSeries);
  }

  /**
   * Verifying function for averages returns and empty series return if an empty series is passed
   */
  @Test
  public void checkEmptySeries() {
    Assert.assertTrue(series5.vals.isEmpty());
    Assert.assertTrue(Graph.statSeries("test", testSeries4).avg.vals.isEmpty());
  }

  /** The average of 1 series is itself */
  @Test
  public void averageOfOneSeries() {
    Graph.Series average = new Graph.Series("Test Series average 1 and 3");
    average.addLine(new Graph.ReportLine(10, 10));
    average.addLine(new Graph.ReportLine(40, 50));
    average.addLine(new Graph.ReportLine(70, 90));
    average.addLine(new Graph.ReportLine(100, 130));
    Graph.Series calculatedAvg = Graph.statSeries("test2", testSeries5).avg;
    Assert.assertEquals(average.vals.get(0).x, calculatedAvg.vals.get(0).x, 0.00001);
    Assert.assertEquals(average.vals.get(1).x, calculatedAvg.vals.get(1).x, 0.00001);
    Assert.assertEquals(average.vals.get(2).x, calculatedAvg.vals.get(2).x, 0.00001);
    Assert.assertEquals(average.vals.get(3).x, calculatedAvg.vals.get(3).x, 0.00001);
    Assert.assertEquals(average.vals.get(0).y, calculatedAvg.vals.get(0).y, 0.00001);
    Assert.assertEquals(average.vals.get(1).y, calculatedAvg.vals.get(1).y, 0.00001);
    Assert.assertEquals(average.vals.get(2).y, calculatedAvg.vals.get(2).y, 0.00001);
    Assert.assertEquals(average.vals.get(3).y, calculatedAvg.vals.get(3).y, 0.00001);
  }

  /** Verify average values are calculated correctly when series are of equal length */
  @Test
  public void calculateAverageSameLength() {
    Graph.Series average = new Graph.Series("Test Series average 1 and 3");
    average.addLine(new Graph.ReportLine(10, 15));
    average.addLine(new Graph.ReportLine(40, 75));
    average.addLine(new Graph.ReportLine(70, 135));
    average.addLine(new Graph.ReportLine(100, 195));
    Graph.Series calculatedAvg = Graph.statSeries("test2", testSeries2).avg;
    Assert.assertEquals(average.vals.get(0).x, calculatedAvg.vals.get(0).x, 0.00001);
    Assert.assertEquals(average.vals.get(1).x, calculatedAvg.vals.get(1).x, 0.00001);
    Assert.assertEquals(average.vals.get(2).x, calculatedAvg.vals.get(2).x, 0.00001);
    Assert.assertEquals(average.vals.get(3).x, calculatedAvg.vals.get(3).x, 0.00001);
    Assert.assertEquals(average.vals.get(0).y, calculatedAvg.vals.get(0).y, 0.00001);
    Assert.assertEquals(average.vals.get(1).y, calculatedAvg.vals.get(1).y, 0.00001);
    Assert.assertEquals(average.vals.get(2).y, calculatedAvg.vals.get(2).y, 0.00001);
    Assert.assertEquals(average.vals.get(3).y, calculatedAvg.vals.get(3).y, 0.00001);
  }

  /**
   * Verify average values are calculated correctly when series are different size by taking the
   * previous (index i-1) value and adding that
   */
  @Test
  public void calculateAverageIrregularSeries() {
    Graph.Series average = new Graph.Series("Test Series average 1 and 4");
    average.addLine(new Graph.ReportLine(10, 15));
    average.addLine(new Graph.ReportLine(40, 75));
    average.addLine(new Graph.ReportLine(70, 135));
    Graph.Series calculatedAvg = Graph.statSeries("test2", testSeries3).avg;
    Assert.assertEquals(average.vals.get(0).x, calculatedAvg.vals.get(0).x, 0.00001);
    Assert.assertEquals(average.vals.get(1).x, calculatedAvg.vals.get(1).x, 0.00001);
    Assert.assertEquals(average.vals.get(2).x, calculatedAvg.vals.get(2).x, 0.00001);
    Assert.assertEquals(100, calculatedAvg.vals.get(3).x, 0.00001);
    Assert.assertEquals(average.vals.get(0).y, calculatedAvg.vals.get(0).y, 0.00001);
    Assert.assertEquals(average.vals.get(1).y, calculatedAvg.vals.get(1).y, 0.00001);
    Assert.assertEquals(average.vals.get(2).y, calculatedAvg.vals.get(2).y, 0.00001);
    Assert.assertEquals(155, calculatedAvg.vals.get(3).y, 0.00001);
  }
}
