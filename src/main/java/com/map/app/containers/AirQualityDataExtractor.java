package com.map.app.containers;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.shapes.BBox;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import com.graphhopper.GraphHopper;
import com.graphhopper.storage.Graph;
import com.map.app.graphhopperfuncs.AirQualityBFS;
import com.map.app.graphhopperfuncs.GreenestWeighting;
import com.map.app.model.AirQuality;

/**
 * @author Siftee, Amit
 */

public class AirQualityDataExtractor {
	private final JSONParser jsonP;
	private final Lock writeLock;
	private  ArrayList<AirQuality> ap;
	private final GraphHopper hopper;
	private String aqiApiKey = System.getenv("waqi_api_key");
	private static final String url = "https://api.waqi.info/map/bounds/?latlng=";

	public AirQualityDataExtractor(GraphHopper ghopper, Lock lock) {
		hopper = ghopper;
		this.jsonP = new JSONParser();
		this.writeLock = lock;
		if (aqiApiKey ==null) {
			Properties prop=new Properties();
			try(FileInputStream ip = new FileInputStream("config.properties");) {
				prop.load(ip);
				aqiApiKey=prop.getProperty("waqi_api_key");
			} catch (IOException e) {
				throw new RuntimeException("Config.properties not found. Aborting ...");
			}
		}
	}
	
	public void readJSON(BBox boundingBox) {
		/*
		 Fetching the content from the api and parsing the json result
		 */
		if (aqiApiKey.equals("<WAQI_API_KEY>")){
			throw new RuntimeException("API Key for AQI URL is not found. Aborting...");
		}
		try {
			writeLock.lock();
			URL uri = new URL(url+boundingBox.minLat+","+boundingBox.minLon + ","
					+ boundingBox.maxLat + "," + boundingBox.maxLon +"&token=" + aqiApiKey );
			
			System.out.println(uri);
			HttpURLConnection con = (HttpURLConnection) uri.openConnection();
			int responseCode = con.getResponseCode();
			if (responseCode != 200) {
				throw new RuntimeException("HttpResponseCode: " + responseCode);
			}
			ap = new ArrayList<>();
			//avgConc=0;
			BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
			String inputLine;
			StringBuilder response = new StringBuilder();
			while ((inputLine = in .readLine()) != null) {
				response.append(inputLine);
			}
			in .close();

			JSONObject obj = (JSONObject) jsonP.parse(response.toString());
			JSONArray data = (JSONArray) obj.get("data");
			//System.out.println(data);
			for (Object datum : data) {
				JSONObject obj1 = (JSONObject) datum;
				double lat = (double) obj1.get("lat");
				double lon = (double) obj1.get("lon");
				String aq = (String) obj1.get("aqi");
				// Regex to check string
				// contains only digits
				String regex = "[0-9]+";

				// Compile the ReGex
				Pattern p = Pattern.compile(regex);

				// If the string is empty
				// return false
				if (aq == null) {
					continue;
				}

				// Find match between given string
				// and regular expression
				// using Pattern.matcher()
				Matcher m = p.matcher(aq);
				if (m.matches()) {
					double aqi = Double.parseDouble(aq);
					JSONObject obj2 = (JSONObject) obj1.get("station");
					String name = (String) obj2.get("name");
					ap.add(new AirQuality(lat, lon, aqi, name));
				}
			}
			//assign air quality metric to edge in graphhopper
			Graph gh = hopper.getGraphHopperStorage().getBaseGraph();
//			LocationIndex locationIndex = hopper.getLocationIndex();
			AirQualityBFS trav = new AirQualityBFS(hopper, gh, ap);
			trav.start(gh.createEdgeExplorer(), 0);
			} 
		catch (Exception e) {
			
			e.printStackTrace();
		}
		finally 
		{
			writeLock.unlock();
			System.out.println("WAQI API parsing done...");
		}

	}
	

}