package net.consensys.wittgenstein.tools;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CSVLatencyReader {
  private static final Path DATA_PATH = Paths.get("resources/Data");
  private static final String CSV_FILE_SUFFIX = "Ping.csv";
  private static final float SAME_CITY_LATENCY = 30f;
  private final Map<String, Map<String, Float>> latencyMatrix;
  private List<String> cities;

  public CSVLatencyReader() {
    this.cities = dirNames(DATA_PATH);
    this.latencyMatrix = makeLatencyMatrix();
    Set<String> citiesWitMissingMeasurements = citiesWithMissingMeasurements(latencyMatrix);
    latencyMatrix.keySet().removeAll(citiesWitMissingMeasurements);
  }

  public CSVLatencyReader(Map<String, Map<String, Float>> latencyMatrix) {
    this.latencyMatrix = latencyMatrix;
    Set<String> citiesWitMissingMeasurements = citiesWithMissingMeasurements(latencyMatrix);
    latencyMatrix.keySet().removeAll(citiesWitMissingMeasurements);
  }

  public Map<String, Map<String, Float>> getLatencyMatrix() {
    return latencyMatrix;
  }

  public List<String> cities() {
    return new ArrayList<>(latencyMatrix.keySet());
  }


  private Map<String, Map<String, Float>> makeLatencyMatrix() {
    Map<String, Map<String, Float>> latencies = new HashMap<>();

    for (String city : cities) {
      Map<String, Float> map = latenciesForCity(resolvePath(city));
      map.put(city, SAME_CITY_LATENCY);
      latencies.put(city, map);
    }
    return latencies;
  }

  private Map<String, Float> latenciesForCity(Path path) {
    Map<String, Float> map = new HashMap<>();

    try (Reader reader = Files.newBufferedReader(path);
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withHeader())) {
      for (CSVRecord csvRecord : csvParser) {
        String cityAndLocation = csvRecord.get(0);
        Optional<String> city = processCityName(cityAndLocation);
        Float latency = Float.valueOf(csvRecord.get(4));
        city.ifPresent(c -> map.put(c, latency));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return map;
  }

  private Set<String> citiesWithMissingMeasurements(Map<String, Map<String, Float>> latencyMatrix) {
    Set<String> allCities = latencyMatrix.keySet();
    Set<String> citiesWithMissingMeasurements = new HashSet<>();

    for (String cityFrom : allCities) {
      for (String cityTo : allCities) {
        Map<String, Float> latencyTo = latencyMatrix.get(cityFrom);
        if (!latencyTo.containsKey(cityTo)) {
          Map<String, Float> latencyFrom = latencyMatrix.get(cityTo);
          if (!latencyFrom.containsKey(cityFrom)) {
            citiesWithMissingMeasurements.add(cityFrom);
          }
        }
      }
    }
    return citiesWithMissingMeasurements;
  }

  private Optional<String> processCityName(String cityAndLocation) {

    //Directory names have format "Name1+Name2" for two word city names, but
    //the format in the csv file is "Name1 Name2 country, region"
    //this logic normalizes the naming convention
    //returns Empty if a city is not present in the csv file

    return cities.stream().filter(c -> cityAndLocation.contains(c.replace('+', ' '))).max(
        Comparator.comparing(String::length));
  }

  private Path resolvePath(String city) {
    return DATA_PATH.resolve(city).resolve(city + CSV_FILE_SUFFIX);
  }

  private static List<String> dirNames(Path path) {
    File file = path.toFile();
    if (file == null || file.list() == null) {
      throw new IllegalStateException("Can't get file for path=" + path);
    }
    List<String> res = Arrays.asList(file.list());
    Collections.sort(res);
    return res;
  }

  public static void main(String[] args) {
    CSVLatencyReader reader = new CSVLatencyReader();
    Map<String, Map<String, Float>> latencyMatrix = reader.latencyMatrix;
    Set<String> cities = latencyMatrix.keySet();

    for (String cityFrom : cities) {
      Map<String, Float> latenciesTo = latencyMatrix.get(cityFrom);

      for (String cityTo : cities) {
        if (latenciesTo.containsKey(cityTo)) {
          float v = latenciesTo.get(cityTo);
          // System.out.println(cityFrom + " ->  " + cityTo + " = " + v);
          if (v <= 1) {
            System.err.println(
                "Strange latency for " + cityTo + ", latency is " + v + ", from " + cityFrom);
          }
        } else {
          float v = latencyMatrix.get(cityTo).get(cityFrom);
          //System.out.println(cityFrom + " ->  " + cityTo + " = " + v);
          if (v <= 1) {
            System.err.println(
                "Strange latency for " + cityTo + ", latency is " + v + ", from " + cityFrom);
          }
        }
      }
    }
  }
}
