package net.consensys.wittgenstein.core.geoinfo;

import java.util.HashMap;
import java.util.Map;

public abstract class Geo {

  public abstract Map<String, CityInfo> citiesPosition();

  protected Map<String, CityInfo> cityInfoMap(Map<String, int[]> cities, int totalPopulation) {
    float cumulativeProbability = 0f;
    Map<String, CityInfo> citiesInfo = new HashMap<>();
    for (Map.Entry<String, int[]> e : cities.entrySet()) {
      cumulativeProbability = cumulativeProbability + e.getValue()[2] * 1.f / totalPopulation;
      CityInfo cityInfo = new CityInfo(e.getValue()[0], e.getValue()[1], cumulativeProbability);
      citiesInfo.put(e.getKey(), cityInfo);
    }
    return citiesInfo;
  }
}
