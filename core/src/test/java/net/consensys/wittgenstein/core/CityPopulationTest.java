package net.consensys.wittgenstein.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.consensys.wittgenstein.core.geoinfo.CityInfo;
import net.consensys.wittgenstein.core.geoinfo.Geo;
import net.consensys.wittgenstein.core.geoinfo.GeoAllCities;
import net.consensys.wittgenstein.tools.CSVLatencyReader;
import org.junit.Assert;
import org.junit.Test;

class GeoTest extends Geo {
  private final Map<String, CityInfo> citiesInfo;

  GeoTest(Map<String, int[]> map, int totalPopulation) {
    citiesInfo = cityInfoMap(map, totalPopulation);
  }

  public Map<String, CityInfo> citiesPosition() {
    return citiesInfo;
  }
}

public class CityPopulationTest {

  @Test
  public void testCumulativeProbability() {
    CSVLatencyReader lr = new CSVLatencyReader();
    Assert.assertTrue(lr.cities().size() > 0);
    NodeBuilder.NodeBuilderWithCity nb =
        new NodeBuilder.NodeBuilderWithCity(lr.cities(), new GeoAllCities());

    for (Map.Entry<String, CityInfo> cityInfo : nb.getCitiesInfo().entrySet()) {
      Assert.assertTrue(
          "wrong cumulative probability for "
              + cityInfo.getKey()
              + ":"
              + cityInfo.getValue().cumulativeProbability,
          cityInfo.getValue().cumulativeProbability < 1.00001f);
    }
  }

  @Test
  public void tetDistribution() {
    Map<String, int[]> cities = new HashMap<>();
    cities.put("SmallCity0", new int[] {0, 0, 1});
    cities.put("SmallCity1", new int[] {0, 0, 1});
    cities.put("SmallCity2", new int[] {0, 0, 1});
    cities.put("SmallCity3", new int[] {0, 0, 1});
    cities.put("BigCity", new int[] {0, 0, 6});

    int total = 10;
    Geo geo = new GeoTest(cities, total);

    NodeBuilder.NodeBuilderWithCity nb =
        new NodeBuilder.NodeBuilderWithCity(new ArrayList<>(cities.keySet()), geo);

    int bigCityCount = 0;
    for (int i = 0; i < total; i++) {
      String city = nb.getCityName(i);
      if (city.equals("BigCity")) {
        bigCityCount++;
      }
    }
    Assert.assertEquals(
        "Bigger cities should be selected more often than smaller", 6, bigCityCount);
  }
}
