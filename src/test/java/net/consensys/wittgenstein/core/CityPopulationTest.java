package net.consensys.wittgenstein.core;

import net.consensys.wittgenstein.core.geoinfo.CityInfo;
import net.consensys.wittgenstein.core.geoinfo.GeoAllCities;
import net.consensys.wittgenstein.tools.CSVLatencyReader;
import org.junit.Assert;
import org.junit.Test;
import java.util.Map;


public class CityPopulationTest {

  @Test
  public void testPopulation() {
    CSVLatencyReader lr = new CSVLatencyReader();
    Assert.assertTrue(lr.cities().size() > 0);
    NodeBuilder.NodeBuilderWithCity nb =
        new NodeBuilder.NodeBuilderWithCity(lr.cities(), new GeoAllCities());

    for (Map.Entry<String, CityInfo> citiyInfo : nb.getCitiesInfo().entrySet()) {
      Assert.assertTrue("wrong cumulative probability for " + citiyInfo.getKey(),
          citiyInfo.getValue().cumulativeProbability < 1.0f);
    }
  }
}
