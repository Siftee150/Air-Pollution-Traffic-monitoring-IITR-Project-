package com.map.app.containers;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.Instruction;
import com.graphhopper.util.InstructionList;
import com.graphhopper.util.Parameters;
import com.graphhopper.util.PointList;
import com.map.app.model.UrlContainer;
import com.map.app.graphhopperfuncs.EncoderCalculator;
import com.map.app.model.RoutePath;
import com.map.app.service.PathChoice;
import com.map.app.service.TrafficAndRoutingService;
import com.map.app.service.TransportMode;

/**
 * @author Siftee, Amit
 */
public class RoutePathContainer {
	private final GraphHopper gh;
	private final Lock readLock;
	int c = 0;
	public RoutePathContainer(GraphHopper hopper, Lock readLock) {
		this.gh = hopper;
		this.readLock = readLock;
		initializeResultsCSV();
	}
	
	public RoutePath finalPath(UrlContainer p,String profile,TransportMode mode)
	{
		Properties prop=new Properties();
		int getUTurnCosts, defaultSmoke, defaultTime; double getTimeFactor, getPollutionFactor; boolean curbside, getPassThrough;
		String Algorithm = Parameters.Algorithms.DIJKSTRA_BI;
		try (FileInputStream ip = new FileInputStream("config.properties")) {
			prop.load(ip);
			defaultSmoke = Integer.parseInt(prop.getProperty("default_smoke"));
			getPassThrough = Boolean.parseBoolean(prop.getProperty("pass_through"));
			getUTurnCosts = Integer.parseInt(prop.getProperty("u_turn_costs"));
			getTimeFactor = Double.parseDouble(prop.getProperty("balanced_time_factor"));
			getPollutionFactor = Double.parseDouble(prop.getProperty("balanced_pollution_factor"));
			defaultTime = Integer.parseInt(prop.getProperty("default_time"));
			curbside = Boolean.parseBoolean(prop.getProperty("curbside"));
			System.out.println(curbside);
		} catch (IOException e) {
			throw new RuntimeException("Config properties are not found. Aborting ...");
		}

		RoutePath indiv=new RoutePath();
		List<String> CURBSIDES = Stream.generate(() -> "left").limit(1000).collect(Collectors.toList());
		// set routing algorithm
		GHRequest request=new GHRequest(p.getStartlat(), p.getStartlon(), p.getEndlat(), p.getEndlon())
				.setProfile(profile)
				.putHint(Parameters.CH.DISABLE, false)
				.putHint(Parameters.Routing.U_TURN_COSTS, getUTurnCosts)
				.putHint(Parameters.Routing.PASS_THROUGH, getPassThrough)
				.setPathDetails(List.of(Parameters.Details.EDGE_ID));
		if (curbside)
			request.setCurbsides(CURBSIDES).putHint(Parameters.Routing.FORCE_CURBSIDE, false);
		request.setAlgorithm(Algorithm);
		PointList pl = new PointList();
		HashMap<String,Float> map=new HashMap<>();
		ArrayList<String> ins = new ArrayList<>();

		try 
		{
			GHResponse fullRes = gh.route(request);
			if (fullRes.hasErrors()) 
			{
				throw new RuntimeException(fullRes.getErrors().toString());
				}
			ResponsePath res = fullRes.getBest();
			double distanceScore = (double) (Math.round(res.getDistance() / 10)) / 100;
			double concScore = EncoderCalculator.calcConcentrationScore(gh, res.getPathDetails().get(Parameters.Details.EDGE_ID), mode);
			double exposureScore = EncoderCalculator.calcExposureScore(gh, res.getPathDetails().get(Parameters.Details.EDGE_ID), mode);
			exposureScore = (double) Math.round(exposureScore * 100) / 100;
			double timeScore = EncoderCalculator.calcTimeScore(gh, res.getPathDetails().get(Parameters.Details.EDGE_ID), mode);
			timeScore = (double) (Math.round(timeScore * 10 / 60)) / 100;
			if (profile.split("_")[0].equals("fastest") || profile.split("_")[0].equals("shortest"))
				timeScore =  (double) (Math.round((double) ((res.getTime() * 100 / 60) / 1000))) / 100;
			// in metres
			map.put("distance", (float) distanceScore);
			// in minutes
			map.put("time", (float) timeScore);
			// micro gm / m^3
			map.put("concentration", (float) concScore);
			// micro gm s / m^3
			map.put("exposure", (float) exposureScore);
			InstructionList list = res.getInstructions();
			for (Instruction ele: list) {
				if (ele.getSign() != 4) {
					String navIns = ele.getTurnDescription(list.getTr()) + ", covering about " + (double) (Math.round(ele.getDistance() * 100) / 100) + " meters";
					ins.add(navIns.toLowerCase());
					} 
				else {
					String navIns = ele.getTurnDescription(list.getTr());
					ins.add(navIns.toLowerCase());
					}
				}

			ins.add("DISTANCE [km]: " + distanceScore);
			ins.add("TIME [min]: " + timeScore);
			ins.add("CONCENTRATION [micro gm / m^3]: " + concScore);
			ins.add("EXPOSURE (10^3) [micro gm sec/ m^3 ]: " + exposureScore);
			String origin_lat = String.valueOf(request.getPoints().get(0).lat);
			String origin_lon = String.valueOf(request.getPoints().get(0).lon);
			String destination_lat = String.valueOf(request.getPoints().get(request.getPoints().size() -1).lat);
			String destination_lon = String.valueOf(request.getPoints().get(request.getPoints().size() -1).lon);

			writeResults(
					c,
					origin_lat,
					origin_lon,
					destination_lat,
					destination_lon,
					profile.split("_")[0],
					distanceScore,
					timeScore,
					concScore,
					exposureScore,
					defaultSmoke,
					defaultTime,
					getUTurnCosts,
					getTimeFactor,
					getPollutionFactor,
					Algorithm,
					curbside
					);
			pl = res.getPoints();
			} 
		finally {
			indiv.fillPath(pl, ins);
			indiv.setSummary(map);
			}
		c++;
		return indiv;
	}

	public ArrayList<RoutePath> find(UrlContainer p) {
		//routing result for given route information
		this.readLock.lock();
		ArrayList<RoutePath> result = new ArrayList<>();
		
		try
		{
		//fetching the profile to do routing with
		String profile="";
		TransportMode mode=TransportMode.valueOf("car");
		PathChoice pathChoice;
		switch (p.getVehicle()) {
			case "bus":
				profile = "bus";
				break;
			case "ipt":
				profile = "ipt";
				break;
			case "metro":
				profile = "metro";
				break;
			default:
				 mode= TransportMode.valueOf(p.getVehicle());
				 pathChoice= PathChoice.valueOf(p.getRouteType());
				 if(!pathChoice.toString().equals("all"))
					profile = TrafficAndRoutingService.getModeBasedPathChoice(pathChoice, mode);
				break;
		}

		if(profile.length() != 0)
		{
			result.add(finalPath(p, profile, mode));
		}
		else 
		{
			for(PathChoice pc:PathChoice.values())
			{
				if(!pc.toString().equals("all"))
				{
					
					profile = TrafficAndRoutingService.getModeBasedPathChoice(pc, mode);
					result.add(finalPath(p, profile, mode));
				}
			}
		}
		}
		finally
		{
		readLock.unlock();
		}
		return result; //result contains latitudes and longitudes of route and instructions for navigation
	}
	public static void initializeResultsCSV() {
		FileWriter csvwriter;	
		BufferedWriter bufferedWriter = null;
			try {
				Properties prop=new Properties();
				String outputDir;
				try (FileInputStream ip = new FileInputStream("config.properties")) {
					prop.load(ip);
					outputDir = prop.getProperty("output_results");
				} catch (IOException e) {
					throw new RuntimeException("Config properties are not found. Aborting ...");
				}

				csvwriter = new FileWriter(outputDir, false);
				bufferedWriter = new BufferedWriter(csvwriter);
				StringJoiner stringJoiner = new StringJoiner(",");
				stringJoiner
						.add("sno")
						.add("origin_lat")
						.add("origin_lon")
						.add("destination_lat")
						.add("destination_lon")
						.add("routing")
						.add("distance")
						.add("time")
						.add("concentration")
						.add("exposure")
						.add("default_smoke")
						.add("default_time")
						.add("u_turn_costs")
						.add("time_factor")
						.add("pollution_factor")
						.add("algorithm")
						.add("curbside");
//						.add("request");
				bufferedWriter.write(stringJoiner.toString());
				bufferedWriter.newLine();	}
			catch (IOException e) {
				e.printStackTrace();
			} finally {
				try {assert bufferedWriter != null;
				bufferedWriter.flush();
			} catch (IOException e) {
				e.printStackTrace();
				}
				try {
					bufferedWriter.close();
				} catch (IOException e)
				{
					e.printStackTrace();
				}
			}
	}
	public static void writeResults(int sno, String origin_lat,  String origin_lon, String destination_lat, String destination_lon, String routing, double dist, double time, double conc, double exposure, double defaultSmoke, double defaultTime, double uTurnCosts, double tFactor, double pFactor, String algorithm, Boolean curbside) {
		FileWriter csvwriter;
		BufferedWriter bufferedWriter = null;
		try {
			Properties prop=new Properties();
			String outputDir;
			try (FileInputStream ip = new FileInputStream("config.properties")) {
				prop.load(ip);
				outputDir = prop.getProperty("output_results");
			} catch (IOException e) {
				throw new RuntimeException("Config properties are not found. Aborting ...");
			}
			csvwriter = new FileWriter(outputDir, true);
			bufferedWriter = new BufferedWriter(csvwriter);
			StringJoiner stringJoiner = new StringJoiner(",");
			stringJoiner
					.add(String.valueOf(sno))
					.add(origin_lat)
					.add(origin_lon)
					.add(destination_lat)
					.add(destination_lon)
					.add(routing)
					.add(String.valueOf(dist))
					.add(String.valueOf(time))
					.add(String.valueOf(conc))
					.add(String.valueOf(exposure))
					.add(String.valueOf(defaultSmoke))
					.add(String.valueOf(defaultTime))
					.add(String.valueOf(uTurnCosts))
					.add(String.valueOf(tFactor))
					.add(String.valueOf(pFactor))
					.add(algorithm)
					.add(String.valueOf(curbside));
//					.add(request);
			bufferedWriter.write(stringJoiner.toString());
			bufferedWriter.newLine();	}
		catch (IOException e) {
			e.printStackTrace();
		} finally {
			try { assert bufferedWriter != null;
				bufferedWriter.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
			try {
				bufferedWriter.close();
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}

}