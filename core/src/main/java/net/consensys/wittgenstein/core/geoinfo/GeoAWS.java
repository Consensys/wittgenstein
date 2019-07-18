package net.consensys.wittgenstein.core.geoinfo;

import java.util.HashMap;
import java.util.Map;

public class GeoAWS extends Geo {
  private Map<String, int[]> cities = new HashMap<>();

  public GeoAWS() {
    // We have only the positions for the cities
    // in the AWS network latency object
    cities.put("Oregon", new int[] {271, 261, 1});
    cities.put("Virginia", new int[] {513, 316, 1});
    cities.put("Mumbai", new int[] {1344, 426, 1});
    cities.put("Seoul", new int[] {1641, 312, 1});
    cities.put("Singapore", new int[] {1507, 532, 1});
    cities.put("Sydney", new int[] {1773, 777, 1});
    cities.put("Tokyo", new int[] {1708, 316, 1});
    cities.put("Canada central", new int[] {422, 256, 1});
    cities.put("Frankfurt", new int[] {985, 226, 1});
    cities.put("Ireland", new int[] {891, 200, 1});
    cities.put("London", new int[] {937, 205, 1});
  }

  public Map<String, CityInfo> citiesPosition() {
    return cityInfoMap(cities, cities.size());
  }

  public static void main(String[] args) {
    Geo g = new GeoAWS();
    CityInfo c = g.citiesPosition().get("tokyo");
    System.out.println("seoul" + " " + c.mercX + " " + c.mercY + " " + c.cumulativeProbability);
  }
}
