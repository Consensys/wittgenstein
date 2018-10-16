package net.consensys.wittgenstein.tools;


import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.Styler;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generate a graph (an image file) from a list of report lines per period.
 */
public class Graph {
  private final List<Series> series = new ArrayList<>();

  private final String graphTitle;
  private final String xName;
  private final String yName;

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

    final XYChart chart = new XYChartBuilder()
        .height(getHeight())
        .title(graphTitle)
        .xAxisTitle(xName)
        .yAxisTitle(yName)
        .width(getLength())
        .build();

    chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
    chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Area);

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
    chart.getStyler().setYAxisMin(minY);
    chart.getStyler().setYAxisMax(maxY);

    return chart;
  }

  public void save(File dest) throws IOException {
    Chart chart = createChart();
    BitmapEncoder.saveBitmapWithDPI(chart, dest.getAbsolutePath(), BitmapEncoder.BitmapFormat.PNG,
        300);
  }

  public void addSerie(Series s) {
    series.add(s);
  }
}
