package net.consensys.wittgenstein.core.geoinfo;

import static net.consensys.wittgenstein.core.Node.MAX_X;
import static net.consensys.wittgenstein.core.Node.MAX_Y;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class GeoAllCities extends Geo {
  private final Map<String, CityInfo> citiesPosition;
  private final double mapWidth;
  private final double mapHeight;
  private static final String CITY_PATH = "cities.csv";

  public GeoAllCities() {
    this.mapWidth = MAX_X;
    this.mapHeight = MAX_Y;
    this.citiesPosition = readCityInfo(CITY_PATH);
  }

  public Map<String, CityInfo> citiesPosition() {
    return new HashMap<>(citiesPosition);
  }

  private Map<String, CityInfo> readCityInfo(String path) {
    Map<String, int[]> cities = new HashMap<>();

    int totalPopulation = 0;
    try (Reader reader =
            new InputStreamReader(getClass().getClassLoader().getResourceAsStream(path));
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withHeader())) {

      for (CSVRecord csvRecord : csvParser) {
        String cityName = csvRecord.get(0).replace(' ', '+');
        float latitude = Float.valueOf(csvRecord.get(1));
        float longitude = Float.valueOf(csvRecord.get(2));
        int mercX = convertToMercatorX(longitude);
        int mercY = convertToMercatorY(latitude);
        int population = Integer.valueOf(csvRecord.get(3));

        population += 200000;

        totalPopulation += population;
        cities.put(cityName, new int[] {mercX, mercY, population});
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return cityInfoMap(cities, totalPopulation);
  }

  private int convertToMercatorX(double longitude) {
    int posX = (int) ((longitude + 180) * (mapWidth / 360));
    if (posX < mapWidth / 2) {
      posX = posX - 45;
    } else {
      posX = posX - 70;
    }
    return posX;
  }

  private int convertToMercatorY(float latitude) {
    double latRad = latitude * Math.PI / 180;
    double mercN = Math.log(Math.tan((Math.PI / 4) + (latRad / 2)));
    int posY = (int) Math.round((mapHeight / 2) - (latitude * mapHeight / 180));
    if (posY < 0.2 * mapHeight) {
      posY = posY - 35;
    }
    return posY;
  }

  // main method for  testing
  public static void main(String[] args) {
    Geo geoInfo = new GeoAllCities();
    CityInfo p = geoInfo.citiesPosition().get("New+York");

    System.out.println("ny " + p.mercX + ", " + p.mercY + " " + p.cumulativeProbability);
  }
}
