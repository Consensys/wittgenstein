package net.consensys.wittgenstein.core.geoinfo;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import static net.consensys.wittgenstein.core.Node.MAX_X;
import static net.consensys.wittgenstein.core.Node.MAX_Y;

public class CityGeoInfoAllCities implements CityGeoInfo {
  private final Map<String, int[]> citiesPosition;
  private final double mapWidth;
  private final double mapHeight;
  private final static Path CITY_PATH = Paths.get("resources/cities.csv");


  public CityGeoInfoAllCities() {
    this.mapWidth = MAX_X;
    this.mapHeight = MAX_Y;
    this.citiesPosition = readCityInfo(CITY_PATH);
  }

  public Map<String, int[]> citiesPosition() {
    return new HashMap<>(citiesPosition);
  }

  private Map<String, int[]> readCityInfo(Path path) {
    Map<String, int[]> citiesPosition = new HashMap<>();

    try (Reader reader = Files.newBufferedReader(path);
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withHeader())) {

      for (CSVRecord csvRecord : csvParser) {
        String cityName = csvRecord.get(0).replace(' ', '+').toLowerCase();
        float latitude = Float.valueOf(csvRecord.get(1));
        float longitude = Float.valueOf(csvRecord.get(2));
        int mercX = convertToMercatorX(longitude);
        int mercY = convertToMercatorY(latitude);

        citiesPosition.put(cityName, new int[] {mercX, mercY});
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return citiesPosition;
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
    CityGeoInfo geoInfo = new CityGeoInfoAllCities();
    int[] p = geoInfo.citiesPosition().get("Colorado+Springs");
    System.out.println("London " + p[0] + ", " + p[1]);
  }
}
