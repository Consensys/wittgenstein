package net.consensys.wittgenstein.core.geoinfo;

import java.util.HashMap;
import java.util.Map;

public class CityGeoInfoAWS implements CityGeoInfo {

  private final Map<String, int[]> citiesPosition = new HashMap<>();

  public CityGeoInfoAWS() {
    // We have only the positions for the cities
    // in the AWS network latency object
    citiesPosition.put("oregon", new int[] {271, 261});
    citiesPosition.put("virginia", new int[] {513, 316});
    citiesPosition.put("mumbai", new int[] {1344, 426});
    citiesPosition.put("seoul", new int[] {1641, 312});
    citiesPosition.put("singapore", new int[] {1507, 532});
    citiesPosition.put("sydney", new int[] {1773, 777});
    citiesPosition.put("tokyo", new int[] {1708, 316});
    citiesPosition.put("canada central", new int[] {422, 256});
    citiesPosition.put("frankfurt", new int[] {985, 226});
    citiesPosition.put("ireland", new int[] {891, 200});
    citiesPosition.put("london", new int[] {937, 205});
  }

  public Map<String, int[]> citiesPosition() {
    return new HashMap<>(citiesPosition);
  }
}
