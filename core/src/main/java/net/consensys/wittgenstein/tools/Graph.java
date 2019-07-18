package net.consensys.wittgenstein.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.Styler;

/** Generate a graph (an image file) from a list of report lines per period. */
public class Graph {
  private static final double EPS = 0.00001;
  private final List<Series> series = new ArrayList<>();

  private final String graphTitle;
  private final String xName;
  private final String yName;

  private Double forcedMinY = null;

  public void setForcedMinY(Double forcedMinY) {
    this.forcedMinY = forcedMinY;
  }

  public static class Series {
    final String description;
    final List<ReportLine> vals = new ArrayList<>();

    private double minX = Double.MAX_VALUE;
    private double maxX = Double.MIN_VALUE;
    private double minY = Double.MAX_VALUE;
    private double maxY = Double.MIN_VALUE;

    private void addToChart(XYChart c) {
      double[] xs = new double[vals.size()];
      double[] ys = new double[vals.size()];

      int p = 0;
      for (ReportLine r : vals) {
        xs[p] = r.x;
        ys[p] = r.y;
        p++;
      }

      c.addSeries(description, xs, ys);
    }

    public Series(String description) {
      this.description = description;
    }

    public Series() {
      this.description = "no description";
    }

    public void addLine(ReportLine rl) {
      vals.add(rl);

      minX = Math.min(minX, rl.x);
      maxX = Math.max(maxX, rl.x);
      minY = Math.min(minY, rl.y);
      maxY = Math.max(maxY, rl.y);
    }

    double getMinX() {
      return minX;
    }

    double getMaxX() {
      return maxX;
    }

    double getMinY() {
      return minY;
    }

    double getMaxY() {
      return maxY;
    }
  }

  public static class ReportLine {
    final double x;
    final double y;

    public ReportLine(double x, double y) {
      this.x = x;
      this.y = y;
    }
  }

  public Graph(String graphTitle, String xName, String yName) {
    this.graphTitle = graphTitle;
    this.xName = xName;
    this.yName = yName;
  }

  private int getHeight() {
    return 800;
  }

  private int getLength() {
    return Math.min(200 + series.get(0).vals.size() * 200, 1600);
  }

  private Chart createChart() {
    if (series.isEmpty()) {
      throw new IllegalStateException("no series in this graph");
    }

    final XYChart chart =
        new XYChartBuilder()
            .height(getHeight())
            .title(graphTitle)
            .xAxisTitle(xName)
            .yAxisTitle(yName)
            .width(getLength())
            .build();

    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);

    double minX = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE;
    double minY = Double.MAX_VALUE;
    double maxY = Double.MIN_VALUE;

    for (Series s : series) {
      if (s.vals.isEmpty()) {
        throw new IllegalStateException("no data in this series: " + s.description);
      }

      s.addToChart(chart);

      minX = Math.min(minX, s.getMinX());
      maxX = Math.max(maxX, s.getMaxX());
      minY = Math.min(minY, s.getMinY());
      maxY = Math.max(maxY, s.getMaxY());
    }

    chart.getStyler().setXAxisMin(minX);
    chart.getStyler().setXAxisMax(maxX);
    chart.getStyler().setYAxisMin(forcedMinY == null ? minY : forcedMinY);
    chart.getStyler().setYAxisMax(maxY);

    return chart;
  }

  public void save(File dest) throws IOException {
    Chart chart = createChart();
    BitmapEncoder.saveBitmapWithDPI(
        chart, dest.getAbsolutePath(), BitmapEncoder.BitmapFormat.PNG, 300);
  }

  public void addSerie(Series s) {
    series.add(s);
  }

  /**
   * Remove the last entries from the series if they have all the same values. All the series in the
   * graph must have the same size.
   */
  public void cleanSeries() {
    final int uniqueSize = series.get(0).vals.size();
    double[] last = new double[series.size()];
    for (int i = 0; i < series.size(); i++) {
      Graph.Series s = series.get(i);
      if (s.vals.size() != uniqueSize) {
        throw new IllegalArgumentException(
            "different size uniqueSize=" + uniqueSize + ", size=" + s.vals.size());
      }
      last[i] = s.vals.get(uniqueSize - 1).y;
    }

    for (int i = uniqueSize - 2; i > 1; i--) {
      for (int ii = 0; ii < series.size(); ii++) {
        Graph.Series s = series.get(ii);
        double nv = s.vals.get(i).y;
        if (Math.abs(last[ii] - nv) > EPS) {
          return;
        }
      }
      for (Graph.Series s : series) {
        s.vals.remove(s.vals.size() - 1);
        s.maxX = s.vals.get(s.vals.size() - 1).x;
      }
    }
  }

  public static class StatSeries {
    public final Graph.Series min;
    public final Graph.Series max;
    public final Graph.Series avg;

    private StatSeries(Series min, Series max, Series avg) {
      this.min = min;
      this.max = max;
      this.avg = avg;
    }
  }

  /**
   * TODO
   *
   * @param title - the title of the series to be created
   * @param series - all the series must have the same value for 'x' at the same index. We allow
   *     missing values at the end
   * @return the aggregated series
   */
  public static StatSeries statSeries(String title, List<Graph.Series> series) {
    Graph.Series seriesMin = new Graph.Series(title + "(min)");
    Graph.Series seriesMax = new Graph.Series(title + "(max)");
    Graph.Series seriesAvg = new Graph.Series(title + "(avg)");

    Graph.Series largest = null;
    for (Graph.Series s : series) {
      if (largest == null || s.vals.size() > largest.vals.size()) {
        largest = s;
      }
    }

    for (int i = 0; largest != null && i < largest.vals.size(); i++) {
      double x = largest.vals.get(i).x;
      double sum = 0;
      double min = Double.MAX_VALUE;
      double max = Double.MIN_VALUE;
      for (Series s : series) {
        if (i < s.vals.size()) {
          double lx = s.vals.get(i).x;
          if (Math.abs(x - lx) > EPS) {
            throw new IllegalArgumentException(
                "We need the indexes to be the same, x=" + x + ", lx=" + lx);
          }
          min = Math.min(min, s.vals.get(i).y);
          max = Math.max(max, s.vals.get(i).y);
          sum += s.vals.get(i).y;
        } else {
          sum += s.vals.get(s.vals.size() - 1).y;
        }
      }
      seriesMin.addLine(new Graph.ReportLine(x, min));
      seriesMax.addLine(new Graph.ReportLine(x, max));
      seriesAvg.addLine(new Graph.ReportLine(x, sum / series.size()));
    }

    return new StatSeries(seriesMin, seriesMax, seriesAvg);
  }
}
