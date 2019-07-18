package net.consensys.wittgenstein.tools;

import java.io.*;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class CSVLatencyReader {
  private static final String DIR_NAME = "Data";
  private static final String CSV_FILE_SUFFIX = "Ping.csv";
  private static final float SAME_CITY_LATENCY = 30f;
  private final Map<String, Map<String, Float>> latencyMatrix;
  private final List<String> cities =
      Arrays.asList(
          "Adelaide",
          "Albany",
          "Alblasserdam",
          "Albuquerque",
          "Algiers",
          "Amsterdam",
          "Anchorage",
          "Ankara",
          "Antwerp",
          "Arezzo",
          "Asheville",
          "Athens",
          "Atlanta",
          "Auckland",
          "Austin",
          "Baltimore",
          "Bangalore",
          "Bangkok",
          "Barcelona",
          "Basel",
          "Belfast",
          "Bergen",
          "Berkeley+Springs",
          "Bern",
          "Bogota",
          "Boston",
          "Brasilia",
          "Bratislava",
          "Brisbane",
          "Bristol",
          "Brno",
          "Bruges",
          "Brunswick",
          "Brussels",
          "Bucharest",
          "Budapest",
          "Buenos+Aires",
          "Buffalo",
          "Bursa",
          "Cairo",
          "Calgary",
          "Canberra",
          "Cape+Town",
          "Caracas",
          "Cardiff",
          "Charlotte",
          "Cheltenham",
          "Chennai",
          "Chicago",
          "Chisinau",
          "Christchurch",
          "Cincinnati",
          "Cleveland",
          "Colorado+Springs",
          "Columbus",
          "Copenhagen",
          "Coventry",
          "Cromwell",
          "Dagupan",
          "Dallas",
          "Dar+es+Salaam",
          "Denver",
          "Des+Moines",
          "Detroit",
          "Dhaka",
          "Dronten",
          "Dubai",
          "Dublin",
          "Dusseldorf",
          "Edinburgh",
          "Eindhoven",
          "Falkenstein",
          "Fez",
          "Frankfurt",
          "Fremont",
          "Frosinone",
          "Gdansk",
          "Geneva",
          "Gothenburg",
          "Green+Bay",
          "Groningen",
          "Halifax",
          "Hamburg",
          "Hangzhou",
          "Hanoi",
          "Helsinki",
          "Heredia",
          "Ho+Chi+Minh+City",
          "Hong+Kong",
          "Honolulu",
          "Houston",
          "Hyderabad",
          "Indianapolis",
          "Indore",
          "Istanbul",
          "Izmir",
          "Jackson",
          "Jacksonville",
          "Jakarta",
          "Jerusalem",
          "Joao+Pessoa",
          "Johannesburg",
          "Kampala",
          "Kansas+City",
          "Karaganda",
          "Kiev",
          "Knoxville",
          "Koto",
          "Ktis",
          "La+Ceiba",
          "Lagos",
          "Lahore",
          "Las+Vegas",
          "Lausanne",
          "Liege",
          "Lima",
          "Limassol",
          "Lincoln",
          "Lisbon",
          "Ljubljana",
          "London",
          "Los+Angeles",
          "Louisville",
          "Lugano",
          "Luxembourg",
          "Lyon",
          "Madrid",
          "Maidstone",
          "Malaysia",
          "Manchester",
          "Manhattan",
          "Manila",
          "Marseille",
          "Medellin",
          "Melbourne",
          "Memphis",
          "Mexico",
          "Miami",
          "Milan",
          "Milwaukee",
          "Minneapolis",
          "Missoula",
          "Montevideo",
          "Monticello",
          "Montreal",
          "Moscow",
          "Munich",
          "Nairobi",
          "New+Delhi",
          "New+Orleans",
          "New+York",
          "Newcastle",
          "Nis",
          "Novosibirsk",
          "Nuremberg",
          "Oklahoma+City",
          "Orlando",
          "Osaka",
          "Oslo",
          "Ottawa",
          "Palermo",
          "Panama",
          "Paramaribo",
          "Paris",
          "Perth",
          "Philadelphia",
          "Phnom+Penh",
          "Phoenix",
          "Piscataway",
          "Pittsburgh",
          "Portland",
          "Prague",
          "Pune",
          "Quebec+City",
          "Quito",
          "Raleigh",
          "Redding",
          "Reykjavik",
          "Richmond",
          "Riga",
          "Riyadh",
          "Rome",
          "Roseburg",
          "Rotterdam",
          "Roubaix",
          "Sacramento",
          "Salem",
          "Salt+Lake+City",
          "San+Antonio",
          "San+Diego",
          "San+Francisco",
          "San+Jose",
          "San+Juan",
          "Santiago",
          "Sao+Paulo",
          "Sapporo",
          "Saskatoon",
          "Savannah",
          "Scranton",
          "Seattle",
          "Secaucus",
          "Seoul",
          "Shanghai",
          "Shenzhen",
          "Singapore",
          "Sofia",
          "South+Bend",
          "St+Louis",
          "St+Petersburg",
          "Stamford",
          "Stockholm",
          "Strasbourg",
          "Sydney",
          "Syracuse",
          "Taipei",
          "Tallinn",
          "Tampa",
          "Tbilisi",
          "Tel+Aviv",
          "Tempe",
          "The+Hague",
          "Thessaloniki",
          "Tirana",
          "Tokyo",
          "Toledo",
          "Toronto",
          "Tunis",
          "Valencia",
          "Valletta",
          "Vancouver",
          "Varna",
          "Venice",
          "Vienna",
          "Vilnius",
          "Vladivostok",
          "Warsaw",
          "Washington",
          "Wellington",
          "Westpoort",
          "Winnipeg",
          "Zhangjiakou",
          "Zurich");

  private List<String> getResourceFiles(String path) {
    List<String> dirnames = new ArrayList<>();

    try (InputStream in = CSVLatencyReader.class.getResourceAsStream(path);
        BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
      String resource;

      while ((resource = br.readLine()) != null) {
        dirnames.add(resource);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.out.print("Arrays.asList(");
    for (String s : dirnames) {
      System.out.print("\"" + s + "\", ");
    }
    System.out.println(");");

    return dirnames;
  }

  public CSVLatencyReader() {
    // this.cities = getResourceFiles("/" + DIR_NAME);
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

  private Map<String, Float> latenciesForCity(InputStream is) {
    Map<String, Float> map = new HashMap<>();

    try (Reader reader = new InputStreamReader(is);
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
    // Directory names have format "Name1+Name2" for two word city names, but
    // the format in the csv file is "Name1 Name2 country, region"
    // this logic normalizes the naming convention
    // returns Empty if a city is not present in the csv file

    return cities.stream()
        .filter(c -> cityAndLocation.contains(c.replace('+', ' ')))
        .max(Comparator.comparing(String::length));
  }

  private InputStream resolvePath(String city) {
    // return DATA_PATH.resolve(city).resolve(city + CSV_FILE_SUFFIX);
    String name =
        File.separator + DIR_NAME + File.separator + city + File.separator + city + CSV_FILE_SUFFIX;
    InputStream is = getClass().getResourceAsStream(name);
    if (is == null) {
      throw new IllegalArgumentException("Can't find the file for " + city + " in " + name);
    }
    return is;
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
          // System.out.println(cityFrom + " ->  " + cityTo + " = " + v);
          if (v <= 1) {
            System.err.println(
                "Strange latency for " + cityTo + ", latency is " + v + ", from " + cityFrom);
          }
        }
      }
    }
  }
}
