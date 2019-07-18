package net.consensys.wittgenstein.tools;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class CSVLatencyReaderTest {
  private CSVLatencyReader reader;
  private final String city1 = "city1";
  private final String city2 = "city2";
  private final String city3 = "city3";
  private final String city4 = "city4";

  @Before
  public void setup() {
    Map<String, Map<String, Float>> latencyMatrix = makeLatencyMatrix();
    reader = new CSVLatencyReader(latencyMatrix);
  }

  @Test
  public void testLoad() {
    reader = new CSVLatencyReader();
    Assert.assertTrue(reader.cities().size() > 0);
  }

  @Test
  public void testSupportedCities() {
    Assert.assertTrue(reader.cities().contains(city1));
    Assert.assertTrue(reader.cities().contains(city2));
    Assert.assertTrue(reader.cities().contains(city3));
    Assert.assertFalse(reader.cities().contains(city4));
  }

  private Map<String, Map<String, Float>> makeLatencyMatrix() {

    Map<String, Map<String, Float>> latencyMatrix = new HashMap<>();

    Map<String, Float> c1Map = new HashMap<>();
    c1Map.put(city1, 30f);
    c1Map.put(city2, 140f);
    c1Map.put(city3, 250f);

    latencyMatrix.put(city1, c1Map);

    Map<String, Float> c2Map = new HashMap<>();
    c2Map.put(city1, 141f);
    c2Map.put(city2, 30f);
    c2Map.put(city3, 180f);

    Map<String, Float> c3Map = new HashMap<>();
    c3Map.put(city1, 247f);
    c3Map.put(city2, 180f);
    c3Map.put(city3, 30f);

    latencyMatrix.put(city1, c1Map);
    latencyMatrix.put(city2, c2Map);
    latencyMatrix.put(city3, c3Map);
    latencyMatrix.put(city4, c3Map);

    return latencyMatrix;
  }
}
