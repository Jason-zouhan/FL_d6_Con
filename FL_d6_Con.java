import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import com.trafficcast.base.dbutils.DBConnector;
import com.trafficcast.base.dbutils.DBUtils;
import com.trafficcast.base.enums.EventType;
import com.trafficcast.base.geocoding.MySqlGeocodingEngine;
import com.trafficcast.base.geocoding.MySqlGeocodingInitiater;
import com.trafficcast.base.inccon.IncConDBUtils;
import com.trafficcast.base.inccon.IncConRecord;
import com.trafficcast.base.tctime.TCTime;

/*******************************************************************************
 * FL_d6_Con.java --------------------- Copyright (C)2017 TrafficCast
 * International Inc.All right reserved
 * <p>
 * This reader get the Incident and Construction info from the FL_d6_Con web
 * site; The html
 * address:http://www.fdotmiamidade.com/lane-closure.html
 * Ticket number: #8126
 * <p>
 * ---------------------------
 * 
 * @author Harry Yang
 * @version 1.0 (05/18/2017)
 * @since 1.6
 * -----------------------------------------------------------------------------------------------------
 * Change Number:#1
 * Programmer:Jack Wang
 * Date: 01/31/2018
 * Description: RAMP RESTRICTIONS indication at the end of description
 * Ticket number: #SD-163
 * -----------------------------------------------------------------------------------------------------
 * Change Number:#2
 * Programmer:Jason
 * Date: 02/25/2019
 * Description:  SQLException for timestamp
 * Ticket number: #SD-796
 * -----------------------------------------------------------------------------------------------------
 * Change Number:#3
 * Programmer:Jason
 * Date: 12/03/2019
 * Description:  Verify Retrieved Records Whether Expired Already
 * Ticket number: #SD-1253
 *******************************************************************************/
public class FL_d6_Con {
	
	// Current version of this class.
	public static final double VERSION = 1.0;

	// log4j instance
	private static final Logger LOGGER = Logger.getLogger(FL_d6_Con.class);

	// int value 10 represents the con reader
	private final int READER_TYPE = 10;

	// Reader ID
	private final String READER_ID = FL_d6_Con.class.getName();

	// Property file location
	private final String PROPERTY_FILE = "prop/FL_d6_Con.properties";
	private final String PATTERN_FILE = "prop/FL_d6_Con_Pattern.txt";
	private final String STREET_ALIAS_FILE = "prop/FL_d6_Con_StreetAlias.txt";
	private final String LOCATION_FORMAT_FILE = "prop/FL_d6_Con_Format.txt";	
	
	// Property keys in FL_d6_Con.properties
	private final String PROP_KEY_DATA_URL_LOCATION = "DATA_URL_LOCATION";
	private final String PROP_KEY_URL_SUFFIX = "URL_SUFFIX";
	private final String PROP_KEY_CONNECT_TIME_OUT = "TIME_OUT";
	private final String PROP_KEY_SLEEP_TIME = "SLEEP_TIME";
	private final String PROP_KEY_RETRY_WAIT_TIME = "RETRY_WAIT_TIME";
	private final String PROP_KEY_TC_SEPARATE_SIGN = "TC_SEPARATE_SIGN";
	private final String PROP_KEY_SHOW_ALL_DESCRIPTION = "SHOW_ALL_DESCRIPTION";

	// Reverse geocoding flag
	private final String PROP_KEY_REVERSE_GEOCODING_FLAG = "REVERSE_GEOCODING_FLAG";

	// Reverse geocoding
	boolean isReverseGeocoding = true;

	// Reverse geocoding value
	private final int REVERSE_GEOCODING_VALUE = -2;

	// TrafficCast Separate Sign
	private String tcSeparateSign = "~TrafficCastSeparateSign~";
	
	// Remove detour info
	boolean showAllDescription = true;

	// sleep time, set default to 5 min, will load from property file
	private int loopSleepTime = 5 * 60 * 1000;

	// Retry wait time, set default to 2 minutes, will load from property file
	private int retryWaitTime = 2 * 60 * 1000;

	// Connection time, set default to 2 minutes, will load from property file
	private int connectOutTime = 2 * 60 * 1000;

	// Address to get json
	private String dataUrlLocation = "http://www.fdotmiamidade.com/front/laneClosureMap/region/";
	private String[] urlSuffix = null;

	// The Pattern to parse MainSt, CrossFrom, CrossTo
	private final String STREET_PATTERN = "STREET_PATTERN_WITH";
	private final String SPLIT_PATTERN = "SPLIT_PATTERN";
	private final String MAIN_ST_PATTERN = "MAIN_ST_PATTERN";
	private final String FROM_ST_PATTERN = "FROM_ST_PATTERN";
	private final String TO_ST_PATTERN = "TO_ST_PATTERN";
	private final String DATE_PATTERN = "DATE_PATTERN";
	private final String WEEKDAY_AND_TIME_PATTERN = "WEEKDAY_AND_TIME_PATTERN";
	private final String TIME_PATTERN = "TIME_PATTERN";

	// Arraylist to store patterns
	private LinkedHashMap<Pattern, String> streetPatternMap;
	private ArrayList<Pattern> mainStPatternArrayList;
	private ArrayList<Pattern> fromStPatternArrayList;
	private ArrayList<Pattern> toStPatternArrayList;
	private ArrayList<Pattern> datePatternArrayList;
	private ArrayList<Pattern> weekdayTimePatternArrayList;
	private ArrayList<Pattern> timePatternArrayList;
	private ArrayList<Pattern> splitPatternArrayList;

	// Hashmap to store street alias.
	private LinkedHashMap<String, String> streetAliasMap;
	
	// HashMap to store location format.
	private LinkedHashMap<String, String> locationFormatMap;
	
	// State code
	private final String STATE = "FL";

	// City, actually the market
	private final String MARKET = "MIA";

	// General FL Time Zone
	private TimeZone flTimeZone = null;

	// SimpleDateFormat to parse time
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat(
			"MM-dd-yyyy", Locale.US);

	private static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat(
			"MM-dd-yyyy hh:mm aa", Locale.US);
	
	// ArrayList to store Con
	private ArrayList<IncConRecord> fl_con_list = null;

	// This value is added to the wait time each time an exception is caught in
	// run()
	private final int SEED = 60000;

	public FL_d6_Con() {
		super();
	}

	/**
	 * Main will create a new FL_d6_Con, call run function
	 * 
	 * @param args
	 * @return None
	 * @exception
	 * @see
	 */
	public static void main(String[] args) {
		PropertyConfigurator.configureAndWatch("log4j.properties", 60000);
		try {
			FL_d6_Con fl_d6_Con = new FL_d6_Con();
			fl_d6_Con.run();
		} catch (Exception ex) {
			LOGGER.fatal("Unexpected problem, program will terminate now ("
					+ ex.getMessage() + ")");
		}
	}

	/**
	 * Read from website, parse json, analyze record, save to database,
	 * Geocoding, sleep and then start another loop.
	 * 
	 * @param None
	 * @return None
	 * @exception Exception
	 * @see
	 **/
	private void run() throws Exception {
		long startTime, sleepTime, waitTime = 0;
		if (loadProperties() && loadPatterns() && loadStreatAlias() && loadFormat()) {
			LOGGER.info("Load properties and initialize completed, next will enter while()");
		} else {
			LOGGER.fatal("Load properties failed ! Program will terminate.");
			throw new RuntimeException(); // main() will catch this exception.
		}
		
		initVariables(); 
		
		while (true) {
			try {
				startTime = System.currentTimeMillis();
				LOGGER.info("Starting to parse fl d6 website for construction information.");

				// Read the data source, stringBuffer for saveErrors, when normal running I disable it.
//				// Read data source
				readDataSource();
				//readDataSource2();

				// Update DB
			    LOGGER.info("Start to update db.");
				IncConDBUtils.updateDB(fl_con_list, STATE, EventType.CONSTRUCTION);
				// Update "last run" field in MySql table containing reader
				// program IDs.
				DBUtils.updateReaderLastRun(loopSleepTime, READER_TYPE);

				// Geocoding
				LOGGER.info("Starting GEOCoding process.");
				MySqlGeocodingEngine geo = null;
				geo = new MySqlGeocodingInitiater(MARKET, READER_ID);
				geo.initiateGeocoding();
				sleepTime = loopSleepTime
						- (System.currentTimeMillis() - startTime);
				if (sleepTime < 0) {
					sleepTime = 1000;
				}
				// Clear the ArrayList
				fl_con_list.clear();
				System.gc();
				LOGGER.info("Last built on 03/05/2020; Ticket Number: #SD-1379");
				LOGGER.info("Sleeping for " + (sleepTime / 1000) + " seconds.");
				System.out.println();
				DBConnector.getInstance().disconnect();
				Thread.sleep(sleepTime);
				waitTime = 0;
			} catch (NoRouteToHostException ex) {
				LOGGER.warn("This machine's internet connection is unavailable, retrying in  "
						+ retryWaitTime / 60000 + "  mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (ConnectException ex) {
				LOGGER.warn("Connection to the fl d6 website"
						+ " feed was refused, retyring in " + retryWaitTime
						/ 60000 + " mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (SocketException ex) {
				LOGGER.warn("Connection to the fl d6 website"
						+ " feed was refused, retyring in " + retryWaitTime
						/ 60000 + " mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (UnknownHostException ex) {
				LOGGER.warn("Unkown host. Could not establish contact with the fl d6 website, retrying in "
						+ retryWaitTime / 60000 + " mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (FileNotFoundException ex) {
				LOGGER.warn("Could not retrieve Inc data, retrying in "
						+ retryWaitTime / 60000 + " mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (IOException ex) {
				LOGGER.warn(ex.getMessage() + ", retrying in " + retryWaitTime
						/ 60000 + " mins...");
				try {
					Thread.sleep(retryWaitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread was interrupted.");
				}
			} catch (Exception ex) {
				waitTime += waitTime == 0 ? SEED : waitTime;
				LOGGER.log(Level.FATAL, "Unexpected exception (" + ex + "). "
						+ "Restarting parsing process in " + waitTime / 60000
						+ " minute(s).", ex);
				System.out.println();
				try {
					Thread.sleep(waitTime);
				} catch (InterruptedException ex1) {
					LOGGER.fatal("Thread interrupted!");
				}
			} finally {
				fl_con_list.clear();
			}
		}

		
	}
	
	private void readDataSource2() throws Exception {
		BufferedReader buffReader = null;
		InputStreamReader inReader = null;
		InputStream inStream = null;
		String lineRead = null;
		String latlon = null;
		String info = null;
		try {
			try {
				inReader = new InputStreamReader(new FileInputStream("5.txt"));
				buffReader = new BufferedReader(inReader);
				
				while((lineRead = buffReader.readLine()) != null) {
					lineRead = lineRead.trim();
					if (lineRead.matches("^var centerpoint\\s?=\\s?'(.*)';$")) {
						latlon = lineRead.replaceAll("^var centerpoint\\s?=\\s?'(.*)';$",
								"$1");
					} else if (lineRead.matches("^var content\\s?=\\s?'(.*)';$")) {
						info = lineRead.replaceAll("^var content\\s?=\\s?'(.*)';$", "$1");
						try {
							parseRecord(latlon, info, dataUrlLocation + "1");// parse record now
						} catch (Exception e) {
							e.printStackTrace();
							LOGGER.info("Parse record error, parse next: "	+ e.getMessage());
						} finally {
							latlon = null;
							info = null;
						}

					}
				}
			} catch (Exception e) {
				LOGGER.warn(e.getMessage());
			} finally {
				if (buffReader != null) {
		    		   buffReader.close();
		    		   buffReader = null;
		    	   }
		    	   if (inReader != null) {
		    		   inReader.close();
		    		   inReader = null;
		    	   }
		    	   if (inStream != null) {
		    		   inStream.close();
		    		   inStream = null;
		    	   }
			}		
		} finally {
			if (buffReader != null) {
	    		   buffReader.close();
	    		   buffReader = null;
	    	   }
	    	   if (inReader != null) {
	    		   inReader.close();
	    		   inReader = null;
	    	   }
	    	   if (inStream != null) {
	    		   inStream.close();
	    		   inStream = null;
	    	   }
		}
		
	}

	/**
	 * Read data source from html
	 * @throws Exception
	 */
	private void readDataSource() throws Exception {
		BufferedReader buffReader = null;
		InputStreamReader inReader = null;
		InputStream inStream = null;
		URL url = null;
		URLConnection conn = null;
		String lineRead = null;
		String latlon = null;
		String info = null;
		//System.setProperty("http.proxyHost", "13.78.116.29");
		//System.setProperty("http.proxyPort", "80");
		try {
			if(urlSuffix != null) {
				for (String suffix : urlSuffix) {
					if (suffix.trim().equals("")) {
						continue;
					}
					/*if (suffix.trim().equals("2")) {
						break;
					}*/
					try {
						LOGGER.info("URL: " + dataUrlLocation + suffix.trim());
						url = new URL(dataUrlLocation + suffix.trim());
						conn = url.openConnection();
						conn.setConnectTimeout(connectOutTime);
						conn.setReadTimeout(connectOutTime);
						inStream = conn.getInputStream();
						inReader = new InputStreamReader(inStream);
						buffReader = new BufferedReader(inReader);
						
						while((lineRead = buffReader.readLine()) != null) {
							lineRead = lineRead.trim();
							if (lineRead.matches("^var centerpoint\\s?=\\s?'(.*)';$")) {
								latlon = lineRead.replaceAll("^var centerpoint\\s?=\\s?'(.*)';$",
										"$1");
							} else if (lineRead.matches("^var content\\s?=\\s?'(.*)';$")) {
								info = lineRead.replaceAll("^var content\\s?=\\s?'(.*)';$", "$1");
								try {
									parseRecord(latlon, info, dataUrlLocation + suffix.trim());// parse record now
								} catch (Exception e) {
									e.printStackTrace();
									LOGGER.info("Parse record error, parse next: "	+ e.getMessage());
								} finally {
									latlon = null;
									info = null;
								}

							}
						}
					} catch (Exception e) {
						LOGGER.warn(e.getMessage());
						LOGGER.warn("Error occur when accessing the url: " + dataUrlLocation + suffix);
					} finally {
						if (buffReader != null) {
				    		   buffReader.close();
				    		   buffReader = null;
				    	   }
				    	   if (inReader != null) {
				    		   inReader.close();
				    		   inReader = null;
				    	   }
				    	   if (inStream != null) {
				    		   inStream.close();
				    		   inStream = null;
				    	   }
				    	   conn = null;
				    	   url = null;
					}
				} 
			} else {
				LOGGER.info("URL suffix is null, return now.");
				return;
			}			
		} finally {
			if (buffReader != null) {
	    		   buffReader.close();
	    		   buffReader = null;
	    	   }
	    	   if (inReader != null) {
	    		   inReader.close();
	    		   inReader = null;
	    	   }
	    	   if (inStream != null) {
	    		   inStream.close();
	    		   inStream = null;
	    	   }
	    	   conn = null;
	    	   url = null;
		}
	}

	/**
	 * Parse info from js
	 * @param latlon
	 * @param info
	 * @param url 
	 */
	private void parseRecord(String latlon, String info, String url) throws Exception {
        // Shared variables in one record
		String locationInfo = null;
		String[] locations = null;
		List<String> locationList = null;
		List<String> timeList = null;
		String timeInfo = null;
		IncConRecord incConRecord = null;
		
	    if (info == null || "".equals(info.trim())) {
	    	LOGGER.debug("Info is null, return now.");
	    	return;
	    }
	    
	    //Format the info
	    info = info.replaceAll("&(m|n)dash;", "-").trim();
	    info = info.replaceAll("&middot;", "·").trim();
	    info = info.replaceAll("&nbsp;", " ").trim();
	    info = info.replaceAll("&amp;", "&").trim();
	    info = info.replaceAll("&rsquo;", "'");
	    info = info.replaceAll("\\s\\s+", " ").trim();
	    
	    LOGGER.debug("Location full: " + info);
	    info = info.replaceAll("([ap])/?\\.m\\.", "$1m").trim();
	    info = info.replaceAll("([ap])\\.m:", "$1m:").trim();
	    //info = info.replaceAll("\\s(\\d+)\\s([ap]m)\\b", " $1:00 $2");
		
		// Get the locationInfo
	    locationInfo = info.replaceAll("^<b>(.+?)</b>.+$", "$1").trim();
		timeInfo = info.replaceAll(".+<div.*?>.*?<p.*?>(.+?)</p>.*?</div>", "$1").trim();
		timeInfo = timeInfo.replaceAll("</?b>", "").trim();
		timeInfo = timeInfo.trim().toUpperCase();
	    locationInfo = locationInfo.trim().toUpperCase();
	    locationInfo = locationInfo.replaceAll("\\bMIDNIGHT\\b", "12:00 AM").trim();
	    locationInfo = locationInfo.replaceAll("\\bSUNSET\\b", "6:00 PM").trim();
	    locationList = new ArrayList<String>();
	    	    
	    // Split locations
	    if (locationInfo.matches(".*\\bSTARTING THE WEEK OF\\b.*")) {
	    	locations = locationInfo.split("\\b(?=STARTING THE WEEK OF|FROM (?:\\w+DAY|MID),?\\s*\\w+\\s\\d+)");
	    	for (String loc : locations) {
	    		if (loc != null && !"".equals(loc.trim())) {
	    			locationList.add(loc);
	    			LOGGER.debug("Location splited: " + loc);
	    		}
	    	}
	    } else if (locationInfo.matches("(.*\\b\\w+?DAY,\\s*\\w+?\\s+\\d+(\\s+(?:THROUGH|AND)\\s+\\w+?DAY,\\s*\\w+?\\s+\\d+)?\\s*-.+?){2,}")) {
	    	timeList = new ArrayList<String>();// store all timeinfo
	    	Pattern timePattern = Pattern.compile("\\w+?DAY,\\s*\\w+?\\s+\\d+?(\\s+(?:THROUGH|AND)\\s+\\w+?DAY,\\s*\\w+?\\s+\\d+)?\\s*-");
	    	Matcher matcher = timePattern.matcher(locationInfo);
	    	while (matcher.find()) {
	    		String time = matcher.group();
	    		timeList.add(time);
	    	}
	    	locations = locationInfo.split("\\w+?DAY, \\w+? \\d+?(\\s+(?:THROUGH|AND)\\s+\\w+?DAY,\\s*\\w+?\\s+\\d+)?\\s*-");
	        if (locations.length - 1 == timeList.size()) {
	        	for (int index = 0; index < locations.length - 1; index++) {
	        		String tempLoc = locations[index + 1];
	        		String tempTime = timeList.get(index);
	        		if (tempLoc != null && !"".equals(tempLoc.trim()) 
	        				&& tempTime != null && !"".equals(tempTime.trim())) {
	        			locationList.add(tempTime + tempLoc);
	        			LOGGER.debug("Location splited: " + tempTime + tempLoc);
	        		}	        		
	        	}
	        } else {
	        	LOGGER.info("Split method need debug.");
	        	return;
	        }
	    } else if (locationInfo.matches("((?:SINGLE |MULTIPLE )?LANE CLOSURES .*? OCCUR FROM:.+?){2,}")) {
	    	Pattern timePattern = Pattern.compile("(?:SINGLE |MULTIPLE )?LANE CLOSURES .*? OCCUR FROM:.+? THROUGH \\w+?DAYS?");
	    	Matcher matcher = timePattern.matcher(locationInfo);
	    	while (matcher.find()) {
	    		String location = matcher.group();
	    		locationList.add(location);    
	    		LOGGER.debug("Location splited: " + location);
	    	}
	    } else if (locationInfo.matches(".*\\sLANES? AT \\d+ AND \\d+ (.+) WILL BE CLOSED FROM \\d+(?::\\d+)? [AP]M\\b.*")) {
	    	//ONE EASTBOUND LANE AT 300 AND 350 SUNNY ISLES BOULEVARD WILL BE CLOSED FROM 9 AM TO 3:30 PM, MONDAY THROUGH FRIDAY.
	        String location1 = locationInfo.replaceAll("(.*\\sLANES? AT) (\\d+) AND (\\d+) (.+) (WILL BE CLOSED FROM \\d+(?::\\d+)? [AP]M\\b.*)", "$1 $4 AND BLOCK $2 $5");
	        String location2 = locationInfo.replaceAll("(.*\\sLANES? AT) (\\d+) AND (\\d+) (.+) (WILL BE CLOSED FROM \\d+(?::\\d+)? [AP]M\\b.*)", "$1 $4 AND BLOCK $3 $5");
	    	locationList.add(location1);
	    	LOGGER.debug("Location1 : " + location1);
	    	locationList.add(location2);
	    	LOGGER.debug("Location2 : " + location2);
	    } else {
            locations = locationInfo.split("(?<!(?:TO|AND FROM|NIGHTLY FROM|AFTERNOON OF|AND|BEGINNING|THRU|ON|:|CLOSED|THROUGH|THORUGH|[AP]M|AS WELL AS)\\s*)\\b(?=\\w+DAY,\\s\\w+\\s\\d+)");
            for (String loc : locations) {
	    		if (loc != null && !"".equals(loc.trim())) {
	    			loc = loc.replaceAll("\\bFROM\\s*$", "");// remove the "from" coming from the split method
	    			locationList.add(loc);
	    			if (locations.length > 1) {
	    				LOGGER.debug("Location splited: " + loc);
	    			}			
	    		}
	    	}
	    }
	    
	    // Parse records
	    for (int index = 0; index < locationList.size(); index++) {
	    	String location = locationList.get(index);
	    	if (location == null || "".equals(location.trim())) {
	    		continue;
	    	}
	    	boolean splited = splitedLocation(location, locationList);
	    	if (splited) {
	    		continue;
	    	}
	    	String streetInfo = null;
			String mainSt = null;
			String mainDir = null;
			String fromSt = null;
			String fromDir = null;
			String toSt = null;
			String toDir = null;
			String latitude = null;
			String longitude = null;
			String description = null;
			String[] streets = null;
			
	    	incConRecord = new IncConRecord();
			// Set the default value
			incConRecord.setState(STATE);
			incConRecord.setCity(MARKET);
			incConRecord.setMapUrl(new URL(url));
			incConRecord.setTimeZone(flTimeZone);
			incConRecord.setType(EventType.CONSTRUCTION);// default type 2
			
			// Get the mainSt/fromSt/toSt
			//location = "BEGINNING MONDAY NIGHT, MARCH 9, THE OUTSIDE WESTBOUND LANE ON THE SR A1A/MACARTHUR CAUSEWAY EAST BRIDGE WILL BE CLOSED FROM 10 PM TO 4 PM THE LANE WILL REOPEN BETWEEN 4 PM AND 10 PM.";
			streetInfo = formatLocation(location);
			streetInfo = formatLocation(streetInfo);// format one more time
			streets = processStreetName(streetInfo);
			if (streets.length == 3) {
				mainSt = streets[0];
				mainDir = getDir(mainSt);
				mainSt = formatSt(mainSt);
				if (mainSt.matches("BLOCK \\d+")) {
					mainSt = "FL-" + mainSt.replaceAll("BLOCK (\\d+)", "$1");
				}
				fromSt = streets[1];
				fromDir = getDir(fromSt);
		    	fromSt = formatSt(fromSt);
		    	toSt = streets[2];
		    	toDir = getDir(toSt);
		    	toSt = formatSt(toSt);
		    	// assign the directions of mainDir for specific cases
		    	if(mainDir == null) {
		    		if (streetInfo != null && (streetInfo.matches(".* BETWEEN .* AND .* WILL BE CLOSED.*")
		    				|| streetInfo.matches(".* AT .* WILL BE CLOSED.*"))) {
		    			if (mainSt.matches(".* AVE$")) {
			    			mainDir = "NB_SB";
			    		} else if (mainSt.matches(".* ST$")) {
			    			mainDir = "WB_EB";
			    		}
		    		}
		    	}
			} else {
				LOGGER.debug("Parse street name error, street name array length is invalid.");
				continue;
			}
	    	
	    	if (mainSt != null && !"".equals(mainSt.trim())) {
	    		incConRecord.setMain_st(mainSt);
	    	} else {
	    		LOGGER.debug("MainSt is null, return now.");
	    		continue;
	    	}
	    	if (fromSt != null && !"".equals(fromSt.trim())) {
	    		incConRecord.setFrom_st(fromSt);
	    		if (fromDir != null && !"".equals(fromDir.trim())) {
	    			fromDir = fromDir.replaceAll("^([WESN]B).*", "$1");
	    			if (fromDir.matches("[WESN]B")){
	    				incConRecord.setFrom_dir(fromDir);
	    			}
	    		}
	    	} else {
	    		LOGGER.debug("FromSt is null, return now.");
	    		continue;
	    	}
	    	if (toSt != null && !"".equals(toSt.trim())) {
	    		incConRecord.setTo_st(toSt);
	    		if (toDir != null && !"".equals(toDir.trim())) {
	    			toDir = toDir.replaceAll("^([WESN]B).*", "$1");
	    			if (toDir.matches("[WESN]B")){
	    				incConRecord.setTo_dir(toDir);
	    			}
	    		}
	    	} else {
	    		LOGGER.debug("ToSt is null.");
	    	}
	    	
	    	// Get the lat/lon
	    	if (latlon != null && latlon.trim().matches("\\d{2}\\.\\d+,\\s*-\\d{2}\\.\\d+")) {
	    		latlon = latlon.trim();
	    		latitude = latlon.replaceAll("(\\d{2}\\.\\d+),\\s*(-\\d{2}\\.\\d+)", "$1");
	    		longitude = latlon.replaceAll("(\\d{2}\\.\\d+),\\s*(-\\d{2}\\.\\d+)", "$2");
	    		try {
		    		incConRecord.setS_lat(Double.parseDouble(latitude));
		    		incConRecord.setS_long(Double.parseDouble(longitude));
	    		} catch(Exception e) {
	    			LOGGER.debug("Parse latlon error: " + latlon);
	    		}
	    		if (incConRecord.getS_lat() != 0 && incConRecord.getS_long() != 0) {
	    			if(isReverseGeocoding) {
	    				incConRecord.setChecked(REVERSE_GEOCODING_VALUE);
	    			}
	    		}
	    	}
	    	
	    	// Get the description
	    	description = location.trim();
	    	description = description.replaceAll("\\.\\s+", ". ").trim();
	    	description = description.replaceAll("^\\d+\\s+(?=(?:ONE|THE|TWO) \\w+BOUND LANE)", "").trim();
	    	description = description.replaceAll("\\b(MAY|CAN):\\s+", "$1: ").trim();
	    	description = description.replaceAll("(?<!:|\\.)\\s+Â?·\\s+", ", ").trim();
	    	description = description.replaceAll("(?<=\\b(?:MAY|CAN):|\\.)\\s+Â?·\\s+", " ").trim(); 	
	    	description = description.replaceAll("\\b(MORNING|\\d+):\\s+(?=ON|THE)", "$1: ").trim();
	    	description = description.replaceAll("\\s{2,}", " ").trim();
	    	description = description.replaceAll("[\\p{P}\\s]+$", ".").trim();
	    	description = description.replaceAll("\\bJOHN F\\. KENNEDY\\b", "JOHN F KENNEDY").trim();
	    	if(!showAllDescription) {
	    		description = description.replaceAll("(.+?)(?<!\\d+|[AP])\\.(?!\\d+|M).*", "$1").trim();
	    		description = description.replaceAll("(.+?)(?<=\\d+)\\.\\s*(?:THE|DRIVER).*", "$1").trim();
	    		description = description.replaceAll("(.+?)(?<=\\d+)\\.\\s.*", "$1").trim();
	    		description = description.replaceAll("(?<=\\.)\\s*FLORIDA .* CREW.*", "").trim();
	    		description = description.replaceAll("(.+?)\\bFLORIDA POWER (?:AND|&) LIGHT ?(?:CREWS|FIBERNET) WILL BE\\b.*", "$1").trim();
	    		description = description.replaceAll("\\bDEPARTMENT CREWS WILL BE PERFORMING UTILITY WORK\\b.*", "").trim();
	    		description = description.replaceAll("(?<=\\d+ [AP]M) (\\w+\\s){1,5}CREWS? WILL BE PERFORMING.*", "").trim();
	    		description = description.replaceAll("[\\p{P}\\s]+$", ".").trim();
	    	}
	    	if (!description.endsWith(".")) {
	    		description  = description + ".";
	    	}
	    	// ramp close cases
	    	if(incConRecord.getTo_st() == null) {
	    		if (streetInfo.matches(".* EXIT RAMP TO .*")) {
	    			description = description + " OFF-RAMP CLOSED.";
	    		} else if (streetInfo.matches(".*\\bON I-\\d+( \\w+BOUND)? THE RAMP TO\\b.+")){
	    			description = description + " OFF-RAMP CLOSED.";
	    		}else if (streetInfo.matches(".* RAMP TO .*")) {
	    			description = description + " ON-RAMP CLOSED.";
	    		}
	    	}	    	
	    	incConRecord.setDescription(description);
	    	
	    	// #1
	    	if (incConRecord.getDescription() != null && 
	    			incConRecord.getDescription().matches(".*? LANE\\b.*? ON .*? RAMP TO .* (ON-RAMP|OFF-RAMP) CLOSED.")) {
				String descriptioncopy = incConRecord.getDescription();
				descriptioncopy = descriptioncopy.replaceAll("(ON-RAMP|OFF-RAMP) CLOSED.", "RAMP RESTRICTIONS.");
				incConRecord.setDescription(descriptioncopy);
			}
	    	// end of #1
	    	
	    	// processTime
	    	incConRecord = processTime(timeInfo, location, incConRecord);
	    	if (incConRecord == null) {
	    		LOGGER.debug("Time info is null so that the record is invalid.");
	    		continue;
	    	}
	    	
	    	// Clone incConRecord
	    	IncConRecord incConRecordClone = null;
	    	if (mainDir != null && !"".equals(mainDir.trim())) {
	    		if (mainDir.matches("[WESN]B")) {
		    		incConRecord.setMain_dir(mainDir);
		    	} else if (mainDir.matches("[WESN]B_[WESN]B")) {	    		
		    		String direction1 = mainDir.replaceAll("([WESN]B)_([WESN]B)", "$1");
		    		String direction2 = mainDir.replaceAll("([WESN]B)_([WESN]B)", "$2");
		    		if (direction1 != null && direction1.matches("[WESN]B")) {
		    			incConRecord.setMain_dir(direction1);
		    			if (direction2 != null && direction2.matches("[WESN]B")) {		    			
				    		if (direction1.matches("[WE]B")) {
				    			incConRecordClone = incConRecord.clone();
				    			incConRecordClone.setMain_dir(direction2);
				    			if (direction2.matches("[SN]B")) {
				    				mainSt = incConRecordClone.getMain_st();
				    				incConRecordClone.setMain_st(incConRecordClone.getFrom_st());
				    				incConRecordClone.setFrom_st(mainSt);
				    			}
				    		} else if (direction1.matches("[SN]B")) {
				    			incConRecordClone = incConRecord.clone();
				    			incConRecordClone.setMain_dir(direction2);
				    			if (direction2.matches("[WE]B")) {
				    				mainSt = incConRecordClone.getMain_st();
				    				incConRecordClone.setMain_st(incConRecordClone.getFrom_st());
				    				incConRecordClone.setFrom_st(mainSt);				    				
				    		    }
			    		    }
		    		    }
		    	    }
	    	    }	    	
	    	} else {
				if (mainSt.matches("(?:I|US|SR)-\\d+.*")) {
					mainDir = getDirByRoadNum(incConRecord.getMain_st());
				}
	    		if (mainDir != null && !mainDir.equals("")) {
	    			if (mainDir.matches("WB,EB")) {
	    				incConRecordClone = incConRecord.clone();
	    				incConRecordClone.setMain_dir("EB");
	    				incConRecord.setMain_dir("WB");
	    			} else if (mainDir.matches("SB,NB")) {
	    				incConRecordClone = incConRecord.clone();
	    				incConRecordClone.setMain_dir("NB");
	    				incConRecord.setMain_dir("SB");
	    			}
	    		}
	    	}
	    	
	    	if (incConRecord.getType().equals(EventType.CONSTRUCTION)) {
	    		fl_con_list.add(incConRecord);
	    		if (incConRecordClone != null) {
	    			fl_con_list.add(incConRecordClone);
	    		}
	    	}
	    	
	    }

	}

	/**
	 * Get dir when no specific direction
	 * @param roadName
	 * @return
	 */
	public String getDirByRoadNum(String roadName) {
		String roadNumber = "";

		if (roadName.matches(".*-(\\d+).*")) {
			roadNumber = roadName.replaceAll(".*-(\\d+).*", "$1");

			if (Integer.parseInt(roadNumber) % 2 == 0) {
				return "WB,EB";
			} else {
				return "SB,NB";
			}
		}

		return "";
	}
	
	/**
	 * Splited location according to locationinfo
	 * @param location
	 * @param locationList
	 * @return
	 */
	private boolean splitedLocation(String location, List<String> locationList) {
		boolean splited = false;
		if (locationList != null && location != null && !location.trim().equals("")) {
			Pattern pattern = Pattern.compile("^(\\w+DAY, \\w+ \\d+ - |STARTING THE WEEK OF.*?AROUND THE CLOCK:)?(.+?)( WILL BE CLOSED.*)");
			Matcher matcher = pattern.matcher(location);
			if (matcher.matches()) {
				String preffix = matcher.group(1);
				String suffix = matcher.group(3);
				String locInfo = matcher.group(2);
				String loc1 = null;
				String loc2 = null;
				boolean needSplited = false;
				// Splited cases
				if (locInfo.matches(".+ TO .+ AND .+ TO .+")) {
					loc1 = locInfo.replaceAll("(.+ TO .+) AND .+ TO .+", "$1");
					loc2 = locInfo.replaceAll("(.+ TO .+) AND (.+ TO .+)", "$2");
					needSplited = true;
				}
				if (needSplited) {
					if (loc1 != null && loc2 != null 
							&& !"".equals(loc1.trim()) && !"".equals(loc2.trim())) {
						if (preffix != null) {
							locationList.add(preffix + loc1 + suffix);
							locationList.add(preffix + loc2 + suffix);
							splited = true;
						} else {
							locationList.add(loc1 + suffix);
							locationList.add(loc2 + suffix);
							splited = true;
						}
					}					
				}	
			}
		}
		return splited;
	}

	/**
	 * Format location info
	 * @param location
	 * @return
	 */
	public String formatLocation(String location) {
		String streetInfo;
		if (location == null || "".equals(location.trim())) {
			LOGGER.debug("Location is null.");
			return null;
		}
		LOGGER.debug("Location: " + location);
		
		//Special cases
		streetInfo = location.replaceAll("(THE RAMP FROM (?:[-\\w.]+\\s){1,4}TO (?:[-\\w.]+\\s){1,4}AT (?:[-\\w.]+\\s){1,4})AND(?: (?:(?!WILL BE CLOSED).)+)? LANES? ON (?:[-\\w.]+\\s){1,4}(WILL BE CLOSED.*)", "$1$2").trim();
		streetInfo = streetInfo.replaceAll("THE RAMP FROM ((?:[-\\w.]+\\s){1,4})TO ((?:[-\\w.]+\\s){1,4})AT ((?:[-\\w.]+\\s){1,4})(WILL BE CLOSED.*)", "THE LANE ON $3BETWEEN $1AND $2$4");
		
		// Minor phrase cases		
		streetInfo = streetInfo.replaceAll("\\bAND/OR\\b", "AND").trim();
		streetInfo = streetInfo.replaceAll("\\b(THROUGH|THORUGH)\\b", "TO").trim();
		streetInfo = streetInfo.replaceAll("\\b(I-\\d+ EXPRESS) LANES?\\b", "$1 LN").trim();
		streetInfo = streetInfo.replaceAll("^THE CLOSURES? BELOW CAN OCCUR ON:", "").trim();
		streetInfo = streetInfo.replaceAll("^NIGHTTIME RAMP CLOSURE", "").trim();
		streetInfo = streetInfo.replaceAll("^VACA KEY - ", "").trim();
		streetInfo = streetInfo.replaceAll("^KEY LARGO - ", "").trim(); 
		streetInfo = streetInfo.replaceAll("^UP TO ", "").trim();
		streetInfo = streetInfo.replaceAll("\\s*\\(24 AROUND THE CLOCK\\)", "").trim();		
		
		// About lane and direction
		streetInfo = streetInfo.replaceAll("^ONE OR TWO LANES?\\b", "LANES").trim();
		streetInfo = streetInfo.replaceAll("^(?:ONE|THE|TWO|THREE) ((?:WEST|SOUTH|EAST|NORTH)BOUND) GENERAL PURPOSE LANES?\\b", "THE $1 LANE").trim();
		streetInfo = streetInfo.replaceAll("^(?:ONE |THE |TWO |THREE )?LANES?(?: IN)? EACH DIRECTIONS?\\b", "BOTH DIRECTION").trim();
		streetInfo = streetInfo.replaceAll("^THE (\\w+BOUND( AND \\w+BOUND)?)(?: ENTRANCE| EXIT)? RAMPS? FROM\\b", "THE $1 LANE ON").trim();
		streetInfo = streetInfo.replaceAll("^THE(?: ENTRANCE| EXIT)? RAMP FROM\\b", "THE LANES AT").trim();
		streetInfo = streetInfo.replaceAll("^((?:ONE|THE|TWO|THREE) ((?:WEST|SOUTH|EAST|NORTH)BOUND LANES?) FROM) \\d+\\b", "$1").trim();
		streetInfo = streetInfo.replaceAll("^(?:ONE|THE|TWO|THREE) ((?:WEST|SOUTH|EAST|NORTH)BOUND LANES?) FROM\\b", "THE $1 AT").trim();		
		streetInfo = streetInfo.replaceAll("^ONE OF TWO \\w+ TURN LANES?\\b", "LANES").trim();
		streetInfo = streetInfo.replaceAll("^NE (\\w+BOUND LANES?)\\b", "THE $1").trim();
		streetInfo = streetInfo.replaceAll("^LANES? FROM\\b", "THE").trim();
		streetInfo = streetInfo.replaceAll("^THE SHOULDER AND SIDEWALK ALONG\\b", "THE LANE ON").trim();
		streetInfo = streetInfo.replaceAll("^THE SHOULDER ALONG\\b", "THE LANE ON").trim();
		streetInfo = streetInfo.replaceAll("^THE SIDEWALK ALONG\\b", "THE LANE ON").trim();
		streetInfo = streetInfo.replaceAll("^(?:RAMP|TRAFFIC) FROM\\b", "").trim();
		streetInfo = streetInfo.replaceAll("^LANES? (?:FROM|ON) THE\\b", "").trim();
		streetInfo = streetInfo.replaceAll("^(?:THE|TWO|ONE|THREE) LANES? (?:FROM|ON) (THE \\w+BOUND) RAMP BETWEEN (([\\w']+\\s){1,6}AND ([\\w']+\\s){1,6}WILL BE CLOSED)\\b", "$1 LANE ON $2").trim();
		streetInfo = streetInfo.replaceAll("^UP TO \\w+ (\\w+BOUND LANES?)", "THE $1").trim();
		
		streetInfo = streetInfo.replaceAll("\\b(?:MAY|MIGHT|CAN) BE CLOSED\\b", "WILL BE CLOSED").trim();
		streetInfo = streetInfo.replaceAll("(?<=\\bLANE )OF TRAFFIC (?=WILL BE CLOSED\\b)", "").trim();
		streetInfo = streetInfo.replaceAll("\\bWILL BE CLOSED AS NEEDED,", "WILL BE CLOSED").trim();
		streetInfo = streetInfo.replaceAll(", (?:THE|TWO|ONE) (?:TO (?:[-\\w./]+\\s){1,3})?LANES? ?(?:\\((?:[-\\w./]+\\s){1,4}[-\\w./]+\\))?(?= WILL BE CLOSED)", "").trim();
		streetInfo = streetInfo.replaceAll("\\b(\\w+BOUND) \\w+ SHOULDER\\b", "$1 LANE").trim();
		streetInfo = streetInfo.replaceAll("\\b(\\w+BOUND) (?:OUTSIDE|INSIDE) LANE\\b", "$1 LANE").trim();
		//streetInfo = streetInfo.replaceAll("^\\w+ (\\w+BOUND) LANES? AND \\w+ (\\w+BOUND) LANES?\\b", "THE $1 AND $2 LANE").trim();
		
		// Remove time in the front
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+(?: (?:TO|AND) \\w+DAY, \\w+ \\d+)?\\s*--?\\s*(?:ONE|THE|TWO|THREE) ((?:WEST|SOUTH|EAST|NORTH)BOUND) GENERAL PURPOSE LANES?\\b", "THE $1 LANE").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAYS? FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M \\w+DAY TO \\w+DAY FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M \\w+DAYS?(?= WILL BE CLOSED)", "THE LANES").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAY,\\s*\\w+ \\d+\\s*--?", "").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAY,\\s*\\w+ \\d+,\\s*AT \\d+(?::\\d+)? [AP]M(?:,|\\b)", "").trim();
		streetInfo = streetInfo.replaceAll("^STARTING THE WEEK OF.*?AROUND THE CLOCK:", "").trim();
		streetInfo = streetInfo.replaceAll("^((?!WILL BE).)*?THE FOLLOWING MORNING:", "").trim();		
		streetInfo = streetInfo.replaceAll(".*?\\bOF THE FOLLOWING MORNING:(.+)", "$1").trim();
		streetInfo = streetInfo.replaceAll(".*?\\bFROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M THE FOLLOWING MORNING:(.+)", "$1").trim();
		streetInfo = streetInfo.replaceAll(".*?\\bFROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M:(?=\\s*ON)(.+)", "$1").trim();
		streetInfo = streetInfo.replaceAll(".*?\\bFROM \\d+(?::\\d+)? [AP]M TO \\w+DAY, \\w+ \\d+ AT \\d+(?::\\d+)? [AP]M:(?=\\s*ON)(.+)", "$1").trim();
		streetInfo = streetInfo.replaceAll(".*?:\\s*\\w+DAY, \\w+ \\d+,(?: AND FROM \\w+DAY, \\w+ \\d+ TO \\w+DAY, \\w+ \\d+,)?(?= WILL BE CLOSED)", "").trim();
			
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+,? \\d+ AT \\d+(?::\\d+)? [AP]M AND CONTINUING UNTIL APPROXIMATELY \\w+ \\d+, \\d+:", "").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+ UNTIL APPROXIMATELY.*?(?:FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M):", "").trim();
		streetInfo = streetInfo.replaceAll("^(?:FROM )?\\w+DAY, \\w+ \\d+ TO \\w+DAY, \\w+ \\d+ FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M(?: OF THE FOLLOWING MORNING)?:\\s*(?=ON)", "").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+,? (?:FROM|AT) \\d+(?::\\d+)? [AP]M TO \\w+DAY, \\w+ \\d+ AT \\d+(?::\\d+)? [AP]M:\\s*(?=ON)", "").trim();	
		streetInfo = streetInfo.replaceAll("^FROM \\d+(?::\\d+)? [AP]M ON \\w+DAY \\w+ \\d+ TO \\d+(?::\\d+)? [AP]M ON \\w+DAY \\w+ \\d+:", "").trim();
		streetInfo = streetInfo.replaceAll("^(?:FROM )?\\w+DAY, \\w+ \\d+ TO ?\\w+DAY, \\w+ \\d+ FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M(?: ON\\b|:)?", "").trim();
		
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+,? FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M OF THE FOLLOWING MORNING:?\\s*(?=ON)", "").trim();
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+ FROM \\d+(?::\\d+)? [AP]M TO \\d+(?::\\d+)? [AP]M ON\\b", "").trim();
		
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+ (?:TO|AND) \\w+DAY, \\w+ \\d+\\s*--?", "").trim();			
				
		// Remove useless info at back 
		streetInfo = streetInfo.replaceAll("\\bA DETOUR WILL BE IN EFFECT\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bDETOURS WILL BE CLEARLY MARKED\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bAND ONE ADJACENT NON-TOLLED GENERAL USE LANE (?=WILL BE)", "").trim();
		streetInfo = streetInfo.replaceAll("JOHN F. KENNEDY", "JOHN F KENNEDY").trim();
		streetInfo = streetInfo.replaceAll("ST. REGIS BAL HARBOUR", "ST REGIS BAL HARBOUR").trim();
		streetInfo = streetInfo.replaceAll("\\bDRIVERS.*?(?:MAY|CAN)\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bPLEASE USE CAUTION\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bTO ALLOW FOR THE\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bENTRANCE(?: AND EXIT)? RAMPS? WILL BE CLOSED IN THE WORK ZONE\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bAT RAMPS(?= BETWEEN)", "").trim();
		streetInfo = streetInfo.replaceAll("(.+?)(?<!\\d+)\\.(?!\\d+).*", "$1").trim();
		streetInfo = streetInfo.replaceAll("(.+?)(?<=\\d+)\\. THE.*", "$1").trim();
		
		streetInfo = streetInfo.replaceAll("(.+?)\\bFLORIDA POWER (?:AND|&) LIGHT ?(?:CREWS|FIBERNET) WILL BE\\b.*", "$1").trim();
		streetInfo = streetInfo.replaceAll("\\bDEPARTMENT CREWS WILL BE PERFORMING UTILITY WORK\\b.*", "").trim();
		streetInfo = streetInfo.replaceAll("(?<=\\.)\\s*FLORIDA .* CREW.*", "").trim();
		streetInfo = streetInfo.replaceAll("(?<=\\d+ [AP]M) (\\w+\\s){1,5}CREWS? WILL BE PERFORMING.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bTHE OUTSIDE SHOULDER WILL BE AVAILABLE.*", "").trim();
		streetInfo = streetInfo.replaceAll("\\bBETWEEN \\w+ \\d+ AND \\w+ \\d+ (?:IN ORDER )?TO INSTALL\\b.* ", "").trim();
		streetInfo = streetInfo.replaceAll("(.*WILL BE CLOSED.*),\\s*AND .* WILL BE CLOSED.* ", "$1").trim();		
		
		//Special cases
		streetInfo = streetInfo.replaceAll("^((?:THE|TWO|ONE) (?:(?:WEST|SOUTH|EAST|NORTH)BOUND )?LANES? (?:AND (?:THE|TWO|ONE) (?:(?:WEST|SOUTH|EAST|NORTH)BOUND )?LANES? )?AT) (\\d+) (.+?) (WILL BE CLOSED FROM \\d+(?::\\d+)? [AP]M)", "$1 $3 AND BLOCK $2 $4").trim();		
		streetInfo = streetInfo.replaceAll("\\b(ON .+?)(?:THE |ONE |TWO )?((?:WEST|SOUTH|EAST|NORTH)BOUND)(?: INSIDE)? LANES? AND (?:THE |ONE |TWO )?((?:WEST|SOUTH|EAST|NORTH)BOUND)(?: INSIDE)? LANES?(?= WILL BE CLOSED)", "$1$2/$3").trim();
		streetInfo = streetInfo.replaceAll("\\b(ON .+?)ALL ((?:WEST|SOUTH|EAST|NORTH)BOUND) AND ((?:WEST|SOUTH|EAST|NORTH)BOUND) LANES(?= WILL BE CLOSED AT)","$1$2/$3").trim();	
		streetInfo = streetInfo.replaceAll("(WILL BE CLOSED BETWEEN )THE RAMP TO (.+? AND)\\b","$1 $2").trim();
		streetInfo = streetInfo.replaceAll("(.+? WILL BE CLOSED .+? BETWEEN ((?!\b[AP]M\b).)+? AND ((?!\b[AP]M\b).)+?) THE EXIT RAMP FROM .+ WILL BE CLOSED.*","$1").trim();
		streetInfo = streetInfo.replaceAll("^(ON .* FROM (?:[-\\w.]+\\s){1,4}TO (?:[-\\w.]+\\s){1,4})THE (WEST|SOUTH|EAST|NORTH)BOUND LANES? WILL BE CLOSED$", "THE $2BOUND LANE $1WILL BE CLOSED.");
		//ON SUNDAY NIGHT, NOVEMBER 17
		streetInfo = streetInfo.replaceAll("ON \\w+DAY( NIGHT)?, \\w+ \\d+", "").trim();
		streetInfo = streetInfo.replaceAll("^((?:[-\\w.]+\\s){1,4})\\((FROM (?:[-\\w.]+\\s){1,4}TO (?:[-\\w.]+\\s){1,4}[-\\w.]+)\\) (WILL BE DETOURED (?:TO|ONTO) .+)", "$1$2 $3");
		streetInfo = streetInfo.replaceAll("^\\w+DAY, \\w+ \\d+ (?:TO|AND) \\w+DAY, \\w+ \\d+,? (WILL BE CLOSED\\b.*)", "LANES $1");
		LOGGER.debug("Formatted location: " + streetInfo);
		return streetInfo;
	}

	/**
	 * Process street info from location
	 * @param location
	 * @return
	 */
	private String[] processStreetName(String location) {
		String[] streets = {"","",""};
		Matcher matcher = null;
		String mainSt = null;
		String fromSt = null;
		String toSt = null;
		Set<Pattern> streetPatternList = null;
		String groupNum = null;
		//location = "BETWEEN GRASSY KEY AND DUCK KEY - THE NORTHBOUND LANES WILL BE SHIFTED AND THE SPEED LIMIT ON US 1 AT MILE MARKER 60.4 WILL BE REDUCED TO 45 MPH FROM 9 AM TO 4 PM, MONDAY THROUGH THURSDAY.";
		if (location != null && !"".equals(location.trim())) {
			location = location.trim().toUpperCase();
			streetPatternList = streetPatternMap.keySet();
			for (Pattern pattern : streetPatternList) {
				matcher = pattern.matcher(location);
				if (matcher.matches()) {
					try {
						groupNum = streetPatternMap.get(pattern);
						if (groupNum != null) {
							if (groupNum.equals("3")) {
								mainSt = matcher.group(1);
								fromSt = matcher.group(2);
								toSt = matcher.group(3);
								streets[0] = mainSt;
								streets[1] = fromSt;
								streets[2] = toSt;
								LOGGER.debug("MainSt: " + streets[0] + ", FromSt: "+ streets[1] + ", ToSt: "+ streets[2]);
								return streets;
							} else if (groupNum.equals("2")) {
								mainSt = matcher.group(1);
								fromSt = matcher.group(2);
								toSt = matcher.group(3);
								streets[0] = mainSt;
								streets[1] = fromSt;
								streets[2] = toSt;								streets[0] = mainSt;
								streets[1] = fromSt;
								LOGGER.debug("MainSt: " + streets[0] + ", FromSt: "+ streets[1] + ", ToSt: "+ streets[2]);
								return streets;
							} else if (groupNum.equals("1")) {
								mainSt = matcher.group(1);
								streets[0] = mainSt;
								streets[1] = mainSt;
								LOGGER.debug("MainSt: " + streets[0] + ", FromSt: "+ streets[1] + ", ToSt: "+ streets[2]);
								return streets;
							} else if (groupNum.equals("4")) {
								mainSt = matcher.group(2);
								fromSt = matcher.group(1);
								streets[0] = mainSt;
								streets[1] = fromSt;
								LOGGER.debug("MainSt: " + streets[0] + ", FromSt: "+ streets[1] + ", ToSt: "+ streets[2]);
								return streets;
							} else if (groupNum.equals("5")) {
								mainSt = matcher.group(4) + " " + matcher.group(3);
								fromSt = matcher.group(1);
								toSt = matcher.group(2);
								streets[0] = mainSt;
								streets[1] = fromSt;
								streets[2] = toSt;
								LOGGER.debug("MainSt: " + streets[0] + ", FromSt: "+ streets[1] + ", ToSt: "+ streets[2]);
								return streets;
							}
						}
					} catch (Exception e) {
						LOGGER.debug("Extract street name error.");
					}
					break;// only one pattern work
				}
			}
		}
		LOGGER.debug("Location is invalid: " + location);
		return streets;
	}

	/**
	 * Process time info accroding date/weekday/time
	 * @param timeInfo
	 * @param location
	 * @param incConRecord 
	 */
	private IncConRecord processTime(String timeInfo, String location, IncConRecord incConRecord) {
		TCTime startTCTime = null;
		TCTime endTCTime = null;
		
		if (timeInfo == null || "".equals(timeInfo.trim())) {
			LOGGER.debug("Time info is null, return.");
			return null;
		}
		if (location == null || "".equals(location.trim())) {
			LOGGER.debug("LocationInfo is null, return.");
			return null;
		}
		
		// Format the location
		location = formatLocationForTime(location, timeInfo);
		LOGGER.debug("Formatted location: " + location);
		Pattern pattern = null;
		Matcher matcher = null;
		boolean unSolved = true;
		boolean unMatched = true;
		String sDate = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
		String eDate = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
		if (sDate.matches("\\d+-\\d+-\\d+") && eDate.matches("\\d+-\\d+-\\d+")) {
			String currentDate = getCurrDate(sDate + "TO" + eDate, null);
			if (currentDate == null) {
				return null;
			}
		}
				
		// Process pattern section
			//DATEp+TIMEp:    FRIDAY, JUNE 17 TO SATURDAY, JUNE 18 FROM 9 P.M. TO 9 A.M. OF THE FOLLOWING MORNING
		if (location.matches(".*\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*")) {
				//get date,get time period
			unMatched = false;
			pattern = Pattern.compile(".*\\w+DAY,\\s(\\d+-\\d+-\\d+)\\sTO\\s\\w+DAY,\\s(\\d+-\\d+-\\d+)\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*");
		    matcher = pattern.matcher(location);
		    if (matcher.find()) {
		    	String date1 = matcher.group(1);
		    	String date2 = matcher.group(2);
		    	String timeStr1 = matcher.group(3);
		    	String timeStr2 = matcher.group(4);
		    	String currDate = getCurrDate(date1 + "TO" + date2, null);
		    	if (currDate != null) {
		    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    		try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
		    	}
		    }
		} else 
			//WDDATEP(TO) + TIMEP:     MONDAY, NOVEMBER 9 THROUGH SATURDAY, NOVEMBER 14, BETWEEN THE HOURS OF 9 P.M. AND 5:30 A.M. 
		if (location.matches(".*\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+,?\\s(?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*")) {
			unMatched = false;
			pattern = Pattern.compile(".*\\w+DAY,\\s(\\d+-\\d+-\\d+)\\sTO\\s\\w+DAY,\\s(\\d+-\\d+-\\d+),?\\s(?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*");
		    matcher = pattern.matcher(location);
		    if (matcher.find()) {
		    	String date1 = matcher.group(1);
		    	String date2 = matcher.group(2);
		    	String timeStr1 = matcher.group(3);
		    	String timeStr2 = matcher.group(4);
		    	String currDate = getCurrDate(date1 + "TO" + date2, null);
		    	if (currDate != null) {
		    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    		try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
		    	}
		    }
				
		} else  
			//WDDATEP(AND) + TIMEP:     THURSDAY, DECEMBER 11, AND SUNDAY, DECEMBER 14, 2014 FROM 10 P.M. TO 5 A.M. THE FOLLOWING MORNING
		if (location.matches(".*\\w+DAY,\\s\\d+-\\d+-\\d+,?\\sAND(?:\\sON)?\\s\\w+DAY,\\s\\d+-\\d+-\\d+,?\\s(?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M).*")) {
			unMatched = false;
			pattern = Pattern.compile(".*\\w+DAY,\\s(\\d+-\\d+-\\d+),?\\sAND(?:\\sON)?\\s\\w+DAY,\\s(\\d+-\\d+-\\d+),?\\s(?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M).*");
		    matcher = pattern.matcher(location);
		    if (matcher.find()) {
		    	String date1 = matcher.group(1);
		    	String date2 = matcher.group(2);
		    	String timeStr1 = matcher.group(3);
		    	String timeStr2 = matcher.group(4);
		    	String currDate = getCurrDate(date1 + "AND" + date2, null);
		    	if (currDate != null) {
		    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    		try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
		    	}
		    }
		} else 
			//TWO WDDATEP(TO)+TIMEP:  WEDNESDAY, AUGUST 24 TO SATURDAY, AUGUST 27, AND FROM MONDAY, AUGUST 29 TO THURSDAY, SEPTEMBER 1, FROM 8 A.M. TO 3:30 P.M.
		if (location.matches(".*?\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+,?\\sAND(?: FROM)?\\s\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+,\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M).*")) {
			unMatched = false;
			pattern = Pattern.compile(".*?\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+,?\\sAND(?: FROM)?\\s\\w+DAY,\\s\\d+-\\d+-\\d+\\sTO\\s\\w+DAY,\\s\\d+-\\d+-\\d+,\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M).*");
			matcher = pattern.matcher(location);
			String currDate = null;
			String date1 = null;
			String date2 = null;
			String dateTimeStr = matcher.group();
		 	String timeStr1 = matcher.group(1);
	    	String timeStr2 = matcher.group(2);
	    	Pattern datePattern = Pattern.compile("\\w+DAY,\\s(\\d+-\\d+-\\d+)\\sTO\\s\\w+DAY,\\s(\\d+-\\d+-\\d+)");
	    	Matcher dateMatcher = datePattern.matcher(dateTimeStr);
	    	while (dateMatcher.find()) {
	    		date1 = dateMatcher.group(1);
	    		date2 = dateMatcher.group(2);
	    		currDate = getCurrDate(date1 + "TO" + date2, null);
	    		if (currDate != null) {
	    			break;
	    		}
	    	}
	    	if (currDate != null) {
	    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
	    		try {
		    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
			    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
			    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
			    	}
			    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
		    	} catch(Exception e) {
		    		LOGGER.debug(e.getMessage());
		    	}
	    	}
		} else 
			//WD + TIMEP:  CLOSURES ON SUNDAYS THROUGH THURSDAYS WILL OCCUR FROM 9 P.M. TO 5:30 A.M.
		if (location.matches(".*\\bON (\\w+DAYS?) (AND|TO) (\\w+DAYS?(?: NIGHTS)?) WILL OCCUR (?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M).*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("\\bON (\\w+DAYS?) (AND|TO) (\\w+DAYS?)(?: NIGHTS)? WILL OCCUR (?:BETWEEN|FROM)(?: THE HOURS OF)?\\s(\\d+:\\d+ [AP]M)\\s(?:AND|TO)\\s(\\d+:\\d+ [AP]M)\\b");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
				String weekday1 = matcher.group(1);
				String weekday2 = matcher.group(3);
				String conjunction = matcher.group(2);
				String timeStr1 = matcher.group(4);
		    	String timeStr2 = matcher.group(5);
		    	String currDate = null;
		    	if (conjunction.equals("AND")) {
		    		currDate = getCurrDate(date1 + "TO" + date2, weekday1 + "," + weekday2);
		    	} else if (conjunction.equals("TO")) {
		    		currDate = getCurrDate(date1 + "TO" + date2, weekday1 + "-" + weekday2);
		    	}
				if (currDate != null) {
		    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    		try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
		    	} else {
		    		continue;
		    	}
			}
		} else 
			//MULTIPLE TIMEP+WDP:  MONDAY TO FRIDAY, 9:00 AM TO 4:00 PM IN ADDITION NIGHTLY LANE CLOSURES WILL TAKE PLACE FROM SUNDAY TO THURSDAY, 7:00 PM TO 6:00 AM
		if (location.matches(".*\\b(\\w+DAY TO \\w+DAY), (\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M)\\b.*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("\\b(\\w+DAY) TO (\\w+DAY), (\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M)\\b");
			matcher = pattern.matcher(location);
			String currDate = null;
			String weekday1 = null;
			String weekday2 = null;
			String timeStr1 = null;
			String timeStr2 = null;
			while (matcher.find()) {
				timeStr1 = matcher.group(3);
				timeStr2 = matcher.group(4);
		    	weekday1 = matcher.group(1);
		    	weekday2 = matcher.group(2);
		    	currDate = getCurrDate(date1 + "TO" + date2, weekday1 + "-" + weekday2);
		    	if (currDate != null) {
		    		break;
		    	}
			}
			if (currDate != null) {
		    	startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    	try {
			   		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
			    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
			    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
			    	}
			    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	unSolved = false;
			   	} catch(Exception e) {
			   		LOGGER.debug(e.getMessage());
			   	}
		    }
		} else 	
			// DATE+TIMEp: FRIDAY, JUNE 17 FROM 9 P.M. TO 9 A.M. OF THE FOLLOWING MORNING
		if (location.matches(".*(\\w+DAY,\\s\\d+-\\d+-\\d+),?\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*")) {
			//get date,get time period
			unMatched = false;
			pattern = Pattern.compile(".*\\w+DAY,\\s(\\d+-\\d+-\\d+),?\\sFROM\\s(\\d+:\\d+ [AP]M)\\sTO\\s(\\d+:\\d+ [AP]M)(?:\\sOF THE FOLLOWING MORNING)?.*");
			matcher = pattern.matcher(location);
			if (matcher.find()) {
				String date = matcher.group(1);
			    String timeStr1 = matcher.group(2);
			    String timeStr2 = matcher.group(3);
			    String currDate = getCurrDate(date, null);
				    if(currDate != null) {
			    	startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
			    	try {
				    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
				    	
			    }
			}
		} else 
			
			
			
			//MULTIPLE TIMEP+WDP:        CLOSED FROM 9 P.M. TO 5 A.M., SUNDAY THROUGH THURSDAY., OCCUR FROM: 9:30 P.M. TO 5 A.M. - SUNDAY THROUGH THURSDAY
		if (location.matches(".*(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M)(?:,|\\s*-)\\s*\\w+DAY\\sTO\\s\\w+DAY(, \\d+-\\d+-\\d+ TO \\d+-\\d+-\\d+)?.*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M)(?:,|\\s*-)\\s*(\\w+DAY)\\sTO\\s(\\w+DAY)(, \\d+-\\d+-\\d+ TO \\d+-\\d+-\\d+)?");
			matcher = pattern.matcher(location);
			String currDate = null;
			String weekday1 = null;
			String weekday2 = null;
			String timeStr1 = null;
			String timeStr2 = null;
			String dateStr = null;
			while (matcher.find()) {
				timeStr1 = matcher.group(1);
				timeStr2 = matcher.group(2);
	    		weekday1 = matcher.group(3);
	    		weekday2 = matcher.group(4);
	    		dateStr = matcher.group(5);
	    		if (dateStr != null && dateStr.matches(", \\d+-\\d+-\\d+ TO \\d+-\\d+-\\d+")) {
	    			date1 = dateStr.replaceAll(", (\\d+-\\d+-\\d+) TO (\\d+-\\d+-\\d+)", "$1");
	    			date2 = dateStr.replaceAll(", (\\d+-\\d+-\\d+) TO (\\d+-\\d+-\\d+)", "$2");
	    		}
	    		currDate = getCurrDate(date1 + "TO" + date2, weekday1 + "-" + weekday2);
	    		if (currDate != null) {
	    			break;
	    		}
			}
			if (currDate != null) {
	    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
	    		try {
		    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
			    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
			    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
			    	}
			    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	unSolved = false;
		    	} catch(Exception e) {
		    		LOGGER.debug(e.getMessage());
		    	}
	    	}
		} else
			// TIMEP+ DATE: CLOSED FROM 9 P.M. TO 5 A.M., JAN. 18, 2017 THROUGH JAN. 21, 2017
		if (location.matches(".*\\b(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M),\\s*(\\d+-\\d+-\\d+)\\s(?:TO|AND)\\s(\\d+-\\d+-\\d+)\\b.*")) {
			unMatched = false;
			pattern = Pattern.compile("\\b(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M),\\s*(\\d+-\\d+-\\d+)\\s(TO|AND)\\s(\\d+-\\d+-\\d+)\\b");
		    matcher = pattern.matcher(location);
		    if (matcher.find()) {
		    	String date1 = matcher.group(3);
		    	String date2 = matcher.group(5);
		    	String conjunction = matcher.group(4);
		    	String timeStr1 = matcher.group(1);
		    	String timeStr2 = matcher.group(2);
		    	String currDate = null;
		    	if (conjunction.matches("AND")) {
		    		currDate = getCurrDate(date1 + "AND" + date2, null);
		    	} else if (conjunction.matches("TO")) {
		    		currDate = getCurrDate(date1 + "TO" + date2, null);
		    	}
		    	
		    	if (currDate != null) {
		    		startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
		    		try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
				    	unSolved = false;
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
		    	}
		    }
		} else 
			//CONTINUOUS
			//24 HOURS AROUND THE CLOCK, FROM 01-9-2017 THRU APPROXIMATELY 05-2017 
			//STARTING THE WEEK OF 02-13-2017 AND CONTINUING UNTIL APPROXIMATELY 07-2017, 24 HOURS AROUND THE CLOCK
			//FROM MID MARCH 2017 UNTIL APPROXIMATELY SEPTEMBER 2017:  
		if (location.matches(".*\\b(?:FROM(?: \\w+DAY,?)?|STARTING THE WEEK OF) (\\d+-\\d+-\\d+) (?:TO|(?:AND CONTINUING )?UNTIL) APPROXIMATELY (\\d+-\\d{4})\\b.*")) {
			 unMatched = false;
			 pattern = Pattern.compile("\\b(?:FROM(?: \\w+DAY,?)?|STARTING THE WEEK OF) (\\d+-\\d+-\\d+) (?:TO|(?:AND CONTINUING )?UNTIL) APPROXIMATELY (\\d+-\\d{4})\\b");
			 matcher = pattern.matcher(location);
			 if (matcher.find()) {
				 String date1 = matcher.group(1);
				 String date2 = matcher.group(2);
				 date2 = date2.replaceAll("^(\\d+)-(\\d+)$", "$1-31-$2");
				 date2 = date2.replaceAll("^(04|06|09|11|4|6|9)-31-(\\d{4})$", "$1-30-$2");// end day of month 4/6/9/11
				 date2 = date2.replaceAll("^(02|2)-31-(\\d{4})$", "$1-28-$2");// February end day
				 startTCTime = new TCTime(DATE_FORMAT, date1, flTimeZone);
				 endTCTime = new TCTime(DATE_FORMAT, date2, flTimeZone);
				 unSolved = false;
			 }
		} else
			//CONTINUOUS
			//STARTING THE WEEK OF 11-21-2016 AND CONTINUING UNTIL APPROXIMATELY 04-30-2017
			//FROM MONDAY, 02-8-2016 AND CONTINUING UNTIL APPROXIMATELY 03-31-2017, 24 HOURS AROUND THE CLOCK
			//FROM MONDAY 09-26-2016 AT 6:00 AM AND CONTINUING UNTIL APPROXIMATELY 04-30-2017
			//FROM WEDNESDAY, 05-11-2016 AT 6:00 AM AND CONTINUING UNTIL APPROXIMATELY 04-30-2017
	     if (location.matches(".*\\b(?:STARTING THE WEEK OF|FROM(?: \\w+DAY,?)?) (\\d+-\\d+-\\d+(?: AT \\d+:\\d+ [AP]M)?)(?: AND CONTINUING)? UNTIL APPROXIMATELY (\\d+-\\d+-\\d+)\\b.*")) {
			unMatched = false;
			pattern = Pattern.compile("\\b(?:STARTING THE WEEK OF|FROM(?: \\w+DAY,?)?) (\\d+-\\d+-\\d+(?: AT \\d+:\\d+ [AP]M)?)(?: AND CONTINUING)? UNTIL APPROXIMATELY (\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			if(matcher.find()) {
				String date1 = matcher.group(1);
				String date2 = matcher.group(2);
				date1 = date1.replaceAll(" AT", "");
				if (date1.matches("\\d+-\\d+-\\d+ \\d+:\\d+ [AP]M")) {
					startTCTime = new TCTime(DATE_TIME_FORMAT, date1, flTimeZone);
				} else if (date1.matches("\\d+-\\d+-\\d+")){
					startTCTime = new TCTime(DATE_FORMAT, date1, flTimeZone);
				}
				endTCTime = new TCTime(DATE_FORMAT, date2, flTimeZone);
				unSolved = false;
			}	
		 } else
			//CONTINUOUS: FRIDAY, 08-19-2016 FROM 11:00 PM TO MONDAY, 08-22-2016 AT 5:00 AM
         if (location.matches(".*\\b\\w+DAY, (\\d+-\\d+-\\d+,? (?:FROM|AT) \\d+:\\d+ [AP]M) TO \\w+DAY, (\\d+-\\d+-\\d+ AT \\d+:\\d+ [AP]M)\\b.*")) {
        	 unMatched = false;
        	 pattern = Pattern.compile("\\b\\w+DAY, (\\d+-\\d+-\\d+,? (?:FROM|AT) \\d+:\\d+ [AP]M) TO \\w+DAY, (\\d+-\\d+-\\d+ AT \\d+:\\d+ [AP]M)\\b");
        	 matcher = pattern.matcher(location);
        	 if(matcher.find()) {
        		String date1 = matcher.group(1);
 				String date2 = matcher.group(2);
 				date1 = date1.replaceAll(",? (?:FROM|AT)", "");
 				date2 = date2.replaceAll(" AT", "");
 				startTCTime = new TCTime(DATE_TIME_FORMAT, date1, flTimeZone);
 				endTCTime = new TCTime(DATE_TIME_FORMAT, date2, flTimeZone);
 				unSolved = false;
        	 } 
         } else			
			//CONTINUOUS 
        	//TIME WDDATE: CLOSED FROM 10 P.M. SUNDAY, AUG. 7 TO 5 A.M., MONDAY, AUG. 8, CLOSED FROM 9 P.M., AUG. 24, 2016 THROUGH 5 A.M., AUG. 25, 2016., continuously
		if (location.matches(".*\\bFROM (\\d+:\\d+ [AP]M)(?:,| ON)? \\w+DAY,? (\\d+-\\d+-\\d+) (?:UNTIL|TO) (\\d+:\\d+ [AP]M)(?:,| ON)? \\w+DAY,? (\\d+-\\d+-\\d+).*")) {
			unMatched = false;
			pattern = Pattern.compile("\\bFROM (\\d+:\\d+ [AP]M)(?:,| ON)? \\w+DAY,? (\\d+-\\d+-\\d+) (?:UNTIL|TO) (\\d+:\\d+ [AP]M)(?:,| ON)? \\w+DAY,? (\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
				String date1 = matcher.group(2);
		    	String date2 = matcher.group(4);
		    	String timeStr1 = matcher.group(1);
		    	String timeStr2 = matcher.group(3);
		    	startTCTime = new TCTime(DATE_TIME_FORMAT, date1 + " " + timeStr1, flTimeZone);
		    	endTCTime = new TCTime(DATE_TIME_FORMAT, date2 + " " + timeStr2, flTimeZone);
		    	unSolved = false;
		    	break;
			}
			
		} else 
			//CONTINUOUS TIME DATE(NO WEEKDAY):   FROM 9 P.M., MARCH 14, 2017 TO 5 A.M. MARCH 15, 2017
		if (location.matches(".*(\\d+:\\d+ [AP]M),? (\\d+-\\d+-\\d+),? TO (\\d+:\\d+ [AP]M),? (\\d+-\\d+-\\d+).*")) {
			unMatched = false;
			pattern = Pattern.compile("(\\d+:\\d+ [AP]M),? (\\d+-\\d+-\\d+),? TO (\\d+:\\d+ [AP]M),? (\\d+-\\d+-\\d+)");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
				String date1 = matcher.group(2);
		    	String date2 = matcher.group(4);
		    	String timeStr1 = matcher.group(1);
		    	String timeStr2 = matcher.group(3);
		    	startTCTime = new TCTime(DATE_TIME_FORMAT, date1 + " " + timeStr1, flTimeZone);
		    	endTCTime = new TCTime(DATE_TIME_FORMAT, date2 + " " + timeStr2, flTimeZone);
		    	unSolved = false;
		    	break;
			}
		} else 
			//CONTINUOUS TIME WEEKDAY:       FROM 9 P.M., FRIDAYS TO 12 P.M., SUNDAYS
		if (location.matches(".*\\d+:\\d+ [AP]M,?\\s\\w+DAYS?\\sTO\\s\\d+:\\d+ [AP]M,?\\s\\w+DAYS?.*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("(\\d+:\\d+ [AP]M),?\\s(\\w+DAY)S?\\sTO\\s(\\d+:\\d+ [AP]M),?\\s(\\w+DAY)S?");
			matcher = pattern.matcher(location);
			while(matcher.find()) {
				String weekday1 = matcher.group(2);
		    	String weekday2 = matcher.group(4);
		    	String timeStr1 = matcher.group(1);
		    	String timeStr2 = matcher.group(3);
		    	String currDate = getCurrDate(date1 + "TO" + date2, weekday1 + "-" + weekday2);
		    	if (currDate != null) {
		    		int weekDayInt1 = getWeekdayInt(weekday1);
		    		int weekDayInt2 = getWeekdayInt(weekday2);
		    		// the weekday in one week
		    		if (weekDayInt1 <= weekDayInt2) {
		    			Calendar calNow = Calendar.getInstance(flTimeZone);
		    			int wkdayIntNow = calNow.get(Calendar.DAY_OF_WEEK);
		    			int startDate = calNow.get(Calendar.DAY_OF_MONTH) - (wkdayIntNow - weekDayInt1);
		    			int endDate = calNow.get(Calendar.DAY_OF_MONTH) + (weekDayInt2 - wkdayIntNow);
		    			String startDateStr = date1.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ startDate +"-$2");
		    			String endDateStr = date2.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ endDate +"-$2");
		    			startTCTime = new TCTime(DATE_TIME_FORMAT, startDateStr + " " + timeStr1, flTimeZone);
		    			endTCTime = new TCTime(DATE_TIME_FORMAT, endDateStr + " " + timeStr2, flTimeZone);
		    		} else if (weekDayInt1 > weekDayInt2) {// the weekday in two weeks, but the lasting days < 7 days
		    			Calendar calNow = Calendar.getInstance(flTimeZone);
		    			int wkDayIntNow = calNow.get(Calendar.DAY_OF_WEEK);
		    			if (wkDayIntNow > weekDayInt2) {
		    				int startDate = calNow.get(Calendar.DAY_OF_MONTH) - (wkDayIntNow - weekDayInt1);
			    			int endDate = calNow.get(Calendar.DAY_OF_MONTH) + (Calendar.SATURDAY - wkDayIntNow) + weekDayInt2;
			    			String startDateStr = date1.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ startDate +"-$2");
			    			String endDateStr = date2.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ endDate +"-$2");
			    			startTCTime = new TCTime(DATE_TIME_FORMAT, startDateStr + " " + timeStr1, flTimeZone);
			    			endTCTime = new TCTime(DATE_TIME_FORMAT, endDateStr + " " + timeStr2, flTimeZone);
		    			} else if (wkDayIntNow <= weekDayInt2) {
		    				int startDate = calNow.get(Calendar.DAY_OF_MONTH) - wkDayIntNow- (Calendar.SATURDAY - weekDayInt1);
			    			int endDate = calNow.get(Calendar.DAY_OF_MONTH) + (weekDayInt2 - wkDayIntNow);
			    			String startDateStr = date1.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ startDate +"-$2");
			    			String endDateStr = date2.replaceAll("(\\d+)-\\d+-(\\d+)", "$1-"+ endDate +"-$2");
			    			startTCTime = new TCTime(DATE_TIME_FORMAT, startDateStr + " " + timeStr1, flTimeZone);
			    			endTCTime = new TCTime(DATE_TIME_FORMAT, endDateStr + " " + timeStr2, flTimeZone);
		    			}
		    		}
		    		break;
		    	}
			}
		} else 
		   //ON SATURDAY BETWEEN 10-1-2016 AND 10-22-2016
		if (location.matches(".*\\bON \\w+DAY BETWEEN (\\d+-\\d+-\\d+) AND (\\d+-\\d+-\\d+)\\b.*")) {
			pattern = Pattern.compile("\\bON (\\w+DAY) BETWEEN (\\d+-\\d+-\\d+) AND (\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			if(matcher.find()) {
				String weekday = matcher.group(1);
		    	String dateStr1 = matcher.group(2);
		    	String dateStr2 = matcher.group(3);
		    	String currDate = getCurrDate(dateStr1 + "TO" + dateStr2, weekday);
		    	if (currDate != null) {
		    		startTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
			    	endTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
			    	endTCTime.add(24 * 60 * 60 * 1000);
			    	unSolved = false;
		    	}
			}
		} else
			//TIME + SINGLE DATE:      10 A.M. TO 3 P.M., DEC. 8, 2016	
		if (location.matches(".*(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M),\\s*(\\d+-\\d+-\\d+).*")) {
			unMatched = false;
			pattern = Pattern.compile("\\b(\\d+:\\d+ [AP]M) TO (\\d+:\\d+ [AP]M),\\s*(\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
				String date = matcher.group(3);
			    String timeStr1 = matcher.group(1);
			    String timeStr2 = matcher.group(2);
			    String currDate = getCurrDate(date, null);
			    if(currDate != null) {
			    	startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
			    	try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
			    	break;
			    }
			}
		} else 
			// MONDAY, 04-3-2017 AND TUESDAY, 04-4-2017 
			//MONDAY, 03-27-2017 TO FRIDAY, 03-31-2017
			// WEDNESDAY, 01-23-2018 TO 06-30-2018 - 24 HOURS
			//WEDNESDAY, 03-29-2017 TO THURSDAY, 03-30-2017, AND ON SUNDAY, 04-2-2017 TO FROM 11:00 PM TO 5:00 AM OF THE FOLLOWING MORNING
		if (location.matches(".*\\b\\w+DAY,\\s*(\\d+-\\d+-\\d+)\\s(AND|TO)\\s(?:\\w+DAY,)?\\s*(\\d+-\\d+-\\d+)\\b.*")) {
			unMatched = false;
			pattern = Pattern.compile("\\b\\w+DAY,\\s*(\\d+-\\d+-\\d+)\\s(AND|TO)\\s(?:\\w+DAY,)?\\s*(\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			if (matcher.find()) {
				String dateStr1 = matcher.group(1);
				String conjunction = matcher.group(2);
				String dateStr2 = matcher.group(3);
				String timeStr1 = null;
				String timeStr2 = null;
				String currDate = getCurrDate(dateStr1 + conjunction + dateStr2, null);
				if(currDate != null) {
				   pattern = Pattern.compile("\\b(?:BETWEEN|FROM)(?: THE HOURS OF)? (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b");
				   matcher = pattern.matcher(location);
				   if (matcher.find()) {
					   timeStr1 = matcher.group(1);
					   timeStr2 = matcher.group(2);
				   }
				   if (timeStr1 != null && timeStr2 != null) {
					   startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
				    	try {
					    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
					    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
					    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
					    	}
					    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
				    	} catch(Exception e) {
				    		LOGGER.debug(e.getMessage());
				    	}
				   } else {
					   startTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
					   endTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
					   endTCTime.add(24 * 60 * 60 * 1000);		   
				   }
				}
			}
			
		} else
			

			
			//  MIDNIGHT      EACH WEDNESDAY NIGHT BETWEEN MIDNIGHT AND 5 A.M
			//  WEEKDAY BETWEEN 8:00 AM AND SUNSET
		if (location.matches(".*\\bEACH \\w+DAY(?: NIGHT)? (?:BETWEEN|FROM) (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b.*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("\\bEACH (\\w+DAY)(?: NIGHT)? (?:BETWEEN|FROM) (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
				String weekDay = matcher.group(1);
			    String timeStr1 = matcher.group(2);
			    String timeStr2 = matcher.group(3);
			    String currDate = getCurrDate(date1 + "TO" + date2, weekDay);
			    if(currDate != null) {
			    	startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
			    	try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
			    	break;
			    }
			}
		} else 
			//THURSDAY, APRIL 27 - ONE NORTHBOUND GENERAL PURPOSE LANE FROM NW 27 STREET TO NW 79 STREET WILL BE CLOSED BETWEEN THE HOURS OF 9 PM AND 5:30 AM.
		if (location.matches(".*?\\w+DAY, (\\d+-\\d+-\\d+)(?: (?:TO|AND) \\w+DAY, (\\d+-\\d+-\\d+))?\\s*-.*\\b(?:BETWEEN|FROM)(?: THE HOURS OF)? (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b.*")) {
			pattern = Pattern.compile(".*?\\w+DAY, (\\d+-\\d+-\\d+)( (?:TO|AND) \\w+DAY, (?:\\d+-\\d+-\\d+))?\\s*-.*\\b(?:BETWEEN|FROM)(?: THE HOURS OF)? (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b.*");
			matcher = pattern.matcher(location);
			if (matcher.matches()) {
				String date1 = matcher.group(1);
				String date2 = matcher.group(2);
				String timeStr1 = matcher.group(3);
				String timeStr2 = matcher.group(4);
				String currDate = null;
				if (date2 != null && !"".equals(date2.trim())) {
					String dateStr2 = date2.replaceAll(".*(TO|AND) \\w+DAY, (\\d+-\\d+-\\d+).*", "$2");
					String conjunction = date2.replaceAll(".*(TO|AND) \\w+DAY, (\\d+-\\d+-\\d+).*", "$1");
					if (conjunction.equals("AND")) {
						currDate = getCurrDate(date1 + "AND" + dateStr2, null);
					} else if (conjunction.equals("TO")) {
						currDate = getCurrDate(date1 + "TO" + dateStr2, null);
					}					
				} else {
					currDate = getCurrDate(date1, null);
				}
				if (currDate != null) {
					if (timeStr1 != null && timeStr2 != null) {
						startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
						try {
							if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
								String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
								currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
							}
							endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
						} catch (Exception e) {
							LOGGER.debug(e.getMessage());
						}
					} else {
						startTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
						endTCTime = new TCTime(DATE_FORMAT, currDate, flTimeZone);
						endTCTime.add(24 * 60 * 60 * 1000);
					}
				}
			}
		} else	
			
			//ONLY TIME:     CLOSED BETWEEN 11 P.M. AND 5 A.M.
		if (location.matches(".*\\b(?:BETWEEN|FROM)(?: THE HOURS OF)? (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b.*")) {
			unMatched = false;
			String date1 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			String date2 = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$2").trim();
			pattern = Pattern.compile("\\b(?:BETWEEN|FROM)(?: THE HOURS OF)? (\\d+:\\d+ [AP]M) (?:AND|TO) (\\d+:\\d+ [AP]M)\\b");
			matcher = pattern.matcher(location);
			while (matcher.find()) {
			    String timeStr1 = matcher.group(1);
			    String timeStr2 = matcher.group(2);
			    String currDate = getCurrDate(date1 + "TO" + date2, null);
			    if(currDate != null) {
			    	startTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr1, flTimeZone);
			    	try {
			    		if (timeStr1.endsWith("PM") && timeStr2.endsWith("AM")) {
				    		String day = currDate.replaceAll("\\d+-(\\d+)-\\d+", "$1");
				    		currDate = currDate.replaceAll("(\\d+-)\\d+(-\\d+)", "$1" + (Integer.parseInt(day) + 1) + "$2");
				    	}
				    	endTCTime = new TCTime(DATE_TIME_FORMAT, currDate + " " + timeStr2, flTimeZone);
			    	} catch(Exception e) {
			    		LOGGER.debug(e.getMessage());
			    	}
			    	break;
			    }
			}
		} else 
			//UNTIL 06-20-2012
		if (location.matches(".*\\bUNTIL\\s\\d+-\\d+-\\d+\\b.*")) {
			unMatched = false;
			String dateTimeStr1 = location.replaceAll(".*\\bUNTIL\\s(\\d+-\\d+-\\d+)\\b.*", "$1");
			endTCTime = new TCTime(DATE_FORMAT, dateTimeStr1, flTimeZone);
			unSolved = false;
		} else 
			//
		if (location.matches(".*\\bUNTIL\\s\\d+-\\d+.*")) {
			 pattern = Pattern.compile("\\bUNTIL\\s(\\d+-\\d+)");
			 matcher = pattern.matcher(location);
			 if (matcher.find()) {
				 String date = matcher.group(1);
				 date = date.replaceAll("^(\\d+)-(\\d+)$", "$1-31-$2");
				 date = date.replaceAll("^(04|06|09|11|4|6|9)-31-(\\d{4})$", "$1-30-$2");// end day of month 4/6/9/11
				 date = date.replaceAll("^(02|2)-31-(\\d{4})$", "$1-28-$2");// February end day
				 endTCTime = new TCTime(DATE_FORMAT, date, flTimeZone);
				 unSolved = false;
			}
		}else 
			// STARTING
			//STARTING THE WEEK OF 04-3-2017
			//MONDAY, 08-22-2016, AT 5:00 AM
			//07-29-2013
			//STARTING THURSDAY, 05-19-2016 AT 6:00 AM AND CONTINUING UNTIL APPROXIMATELY 12-31-2017
			//STARTING THE WEEK OF 02-13-2017 AND CONTINUING UNTIL APPROXIMATELY 12, 2017
		if (location.matches(".*\\b(?:STARTING THE WEEK OF|\\w+DAY,) (\\d+-\\d+-\\d+(?:, AT \\d+:\\d+ [AP]M)?)\\b.*")) { 
			unMatched = false;
			pattern = Pattern.compile("\\b(?:STARTING THE WEEK OF|\\w+DAY,) (\\d+-\\d+-\\d+(?:, AT \\d+:\\d+ [AP]M)?)\\b");
			matcher = pattern.matcher(location);
			if (matcher.find()) {
				String dateStr = matcher.group(1);
				dateStr = dateStr.replaceAll(", AT", "");
				if (dateStr.matches("\\d+-\\d+-\\d+")) {
					startTCTime = new TCTime(DATE_FORMAT, dateStr, flTimeZone);
					unSolved = false;
				} else if (dateStr.matches("\\d+-\\d+-\\d+ \\d+:\\d+ [AP]M")) {
					startTCTime = new TCTime(DATE_TIME_FORMAT, dateStr, flTimeZone);
					unSolved = false;
				}
				pattern = Pattern.compile("UNTIL (?:APPROXIMATELY )?(\\d+-\\d+-\\d+(?:, AT \\d+:\\d+ [AP]M)?)\\b");
				matcher = pattern.matcher(location);
				if (matcher.find()) {
					String dateStr1 = matcher.group(1);
					dateStr1 = dateStr1.replaceAll(", AT", "");
					if (dateStr1.matches("\\d+-\\d+-\\d+")) {
						endTCTime = new TCTime(DATE_FORMAT, dateStr1, flTimeZone);
						unSolved = false;
					} else if (dateStr1.matches("\\d+-\\d+-\\d+ \\d+:\\d+ [AP]M")) {
						endTCTime = new TCTime(DATE_TIME_FORMAT, dateStr1, flTimeZone);
					}
				}
				pattern = Pattern.compile("UNTIL (?:APPROXIMATELY )?(\\d+),? (\\d{4})\\b");
				matcher = pattern.matcher(location);
				if (matcher.find()) {
					String dateStr1 = getLastDayOfMonth(Integer.valueOf(matcher.group(2)), Integer.valueOf(matcher.group(1)));
					if (dateStr1.matches("\\d+-\\d+-\\d+")) {
						endTCTime = new TCTime(DATE_FORMAT, dateStr1, flTimeZone);
						unSolved = false;
					} else if (dateStr1.matches("\\d+-\\d+-\\d+ \\d+:\\d+ [AP]M")) {
						endTCTime = new TCTime(DATE_TIME_FORMAT, dateStr1, flTimeZone);
						unSolved = false;
					}
				}
				//DURING DAYTIME WORK HOURS
				if (location.matches(".*DURING DAYTIME WORK HOURS.*")) {
					unMatched = false;
					Calendar calNow = Calendar.getInstance(flTimeZone);
					String dateStr1 = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR) + " 07:00 AM";
					String dateStr2 = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR) + " 05:00 PM";
					if (dateStr1.matches("\\d+-\\d+-\\d+ \\d+:\\d+ [AP]M")) {
						startTCTime = new TCTime(DATE_TIME_FORMAT, dateStr1, flTimeZone);
						endTCTime = new TCTime(DATE_TIME_FORMAT, dateStr2, flTimeZone);
					}
				}
				
			}
		} else 
		   // SR 5/US 1/OVERSEAS HIGHWAY IN KEY LARGO - WEEK OF JULY 29, 2013	
		if (location.matches(".*\\bWEEK OF (\\d+-\\d+-\\d+)\\b.*")) {
			unMatched = false;
			pattern = Pattern.compile("\\bWEEK OF (\\d+-\\d+-\\d+)\\b");
			matcher = pattern.matcher(location);
			if (matcher.find()) {
				String dateStr1 = matcher.group(1);
				startTCTime = new TCTime(DATE_FORMAT, dateStr1, flTimeZone);
				endTCTime = new TCTime(DATE_FORMAT, dateStr1, flTimeZone);
				endTCTime.add(7 * 24 * 60 * 60 * 1000);
				unSolved = false;
			}
		} 
		
		else {
			LOGGER.debug("Time pattern not matched: " + location);
		}

		//THE CLOSURE WILL BEGIN AT 7:00 PM AND ALL LANES WILL REOPEN BY 5:30 AM ON WEDNESDAY, 05-23-2012
		//FROM 12 A.M. TO 3:30 P.M., JAN. 14, 2017 THROUGH JAN. 16, 2017 AND ON JAN. 19, 2017 AND JANUARY 20, 2016.
		
		if (startTCTime == null && endTCTime == null) {
			LOGGER.debug("StartTCTime and endTCTime is both null.");
			return null;
		}
		if (startTCTime != null) {
			if (startTCTime.getLocalTime(incConRecord.getTimeZone(),new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")) != "0000-00-00 00:00:00" &&
					startTCTime.getLocalTime(incConRecord.getTimeZone(),new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")) != "0000-00-00") {
				incConRecord.setStartTime(startTCTime);
			}
		}
		
		if (endTCTime != null) {
			if (endTCTime.getLocalTime(incConRecord.getTimeZone(),new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")) != "0000-00-00 00:00:00" &&
					endTCTime.getLocalTime(incConRecord.getTimeZone(),new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")) != "0000-00-00") {
				incConRecord.setEndTime(endTCTime);
			}
		}
		
		return incConRecord;
	}

	/**
	 * Determine whether today is in date range
	 * @param date
	 * @param object
	 * @return
	 */
	private String getCurrDate(String dateStr, String weekDay) {
		SimpleDateFormat dateSdf = new SimpleDateFormat("MM-dd-yyyy", Locale.US);
		dateSdf.setTimeZone(flTimeZone);
		String currDate = null;
		boolean isInRange = false;
		String dateStr1 = null;
		String dateStr2 = null;
		Calendar calNow = Calendar.getInstance(flTimeZone);
		if (dateStr == null) {
			LOGGER.debug("No date info to determine.");
			return null;
		}
		LOGGER.debug("GetCurrDate: dateStr[" + dateStr + "], " + "weekDay[" + weekDay + "]");
		
		if (dateStr.matches("\\d+-\\d+-\\d+")) {
			dateStr1 = dateStr.trim();
			if (dateStr1.matches("\\d+-\\d+-\\d+")) {
				try {
					Date date = dateSdf.parse(dateStr1);
					Calendar cal = Calendar.getInstance(flTimeZone);
					cal.setTime(date);
					Calendar calNext = (Calendar) cal.clone();
					calNext.add(Calendar.HOUR_OF_DAY, 24);
					if (calNow.before(calNext) && calNow.after(cal)) {
						LOGGER.debug("DateStr is today.");
						isInRange = true;
					} else {
						LOGGER.debug("DateStr isn't today.");
					}
				} catch (ParseException e) {
					LOGGER.debug("Parse dateStr error: " + dateStr);
				}
			}
		} else if (dateStr.matches("\\d+-\\d+-\\d+TO\\d+-\\d+-\\d+")){
			dateStr1 = dateStr.replaceAll("(\\d+-\\d+-\\d+)TO(\\d+-\\d+-\\d+)", "$1").trim();
			dateStr2 = dateStr.replaceAll("(\\d+-\\d+-\\d+)TO(\\d+-\\d+-\\d+)", "$2").trim();
			if (dateStr1.matches("\\d+-\\d+-\\d+") && dateStr2.matches("\\d+-\\d+-\\d+")) {
				try {
					Date date1 = dateSdf.parse(dateStr1);
					Calendar cal1 = Calendar.getInstance(flTimeZone);
					cal1.setTime(date1);
					Date date2 = dateSdf.parse(dateStr2);					
					Calendar cal2 = Calendar.getInstance(flTimeZone);
					cal2.setTime(date2);
					cal2.add(Calendar.HOUR_OF_DAY, 24);
					if (calNow.before(cal2) && calNow.after(cal1)) {
						LOGGER.debug("Today is in range.");
						isInRange = true;
					} else {
						LOGGER.debug("Today is out of range.");
					}
				} catch (ParseException e) {
					LOGGER.debug("Parse date1 or date2 error: " + dateStr);
				}
			}
		} else if (dateStr.matches("\\d+-\\d+-\\d+AND\\d+-\\d+-\\d+")) {
			dateStr1 = dateStr.replaceAll("(\\d+-\\d+-\\d+)AND(\\d+-\\d+-\\d+)", "$1").trim();
			dateStr2 = dateStr.replaceAll("(\\d+-\\d+-\\d+)AND(\\d+-\\d+-\\d+)", "$2").trim();
			if (dateStr1.matches("\\d+-\\d+-\\d+") && dateStr2.matches("\\d+-\\d+-\\d+")) {
				try {
					Date date = dateSdf.parse(dateStr1);
					Calendar cal = Calendar.getInstance(flTimeZone);
					cal.setTime(date);
					Calendar calNext = (Calendar) cal.clone();
					calNext.add(Calendar.HOUR_OF_DAY, 24);
					if (calNow.before(calNext) && calNow.after(cal)) {
						LOGGER.debug("DateStr is today.");
						isInRange = true;
					} else {
						LOGGER.debug("DateStr isn't today.");
					}
				} catch (ParseException e) {
					LOGGER.debug("Parse dateStr error: " + dateStr);
				}
				try {
					Date date = dateSdf.parse(dateStr2);
					Calendar cal = Calendar.getInstance(flTimeZone);
					cal.setTime(date);
					Calendar calNext = (Calendar) cal.clone();
					calNext.add(Calendar.HOUR_OF_DAY, 24);
					if (calNow.before(calNext) && calNow.after(cal)) {
						LOGGER.debug("DateStr is today.");
						isInRange = true;
					} else {
						LOGGER.debug("DateStr isn't today.");
					}
				} catch (ParseException e) {
					LOGGER.debug("Parse dateStr error: " + dateStr);
				}
			}
		}
		
		if (isInRange) {
			if (weekDay == null) {
				currDate = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR); 
			} else {
				weekDay = weekDay.trim();
				String weekday1 = null;
				String weekday2 = null;
				if (weekDay.matches("\\w+DAY-\\w+DAY")) {//weekday to weekday
					weekday1 = weekDay.replaceAll("(\\w+DAY)-(\\w+DAY)", "$1");
					weekday2 = weekDay.replaceAll("(\\w+DAY)-(\\w+DAY)", "$2");
                    int dayInt = calNow.get(Calendar.DAY_OF_WEEK);
                    int startInt = getWeekdayInt(weekday1);
                    int endInt = getWeekdayInt(weekday2);
                    if (startInt <= endInt) {
                    	if (dayInt <= endInt && dayInt >= startInt) {
                    		currDate = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR);
                    	    LOGGER.debug("Current date: " + currDate);
                    	} else {
                    		LOGGER.debug("Today is not in the weekday range: " + weekDay);
                    	}
                    } else {
                    	if (dayInt > startInt && dayInt < Calendar.SATURDAY 
                    			|| dayInt > Calendar.SUNDAY && dayInt < endInt) {
                    		currDate = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR);
                    		LOGGER.debug("Current date: " + currDate);
                    	} else {
                    		LOGGER.debug("Today is not in the weekday range: " + weekDay);
                    	}
                    }
				} else if (weekDay.matches("\\w+DAY(,\\w+DAY)*")) {// weekday and weekday
					String[] weekdays = weekDay.split(",");
					int dayInt = calNow.get(Calendar.DAY_OF_WEEK);
					for (String weekday : weekdays) {
						if (dayInt == getWeekdayInt(weekday)) {
							currDate = "" + (calNow.get(Calendar.MONTH) + 1) + "-" + calNow.get(Calendar.DAY_OF_MONTH) + "-" + calNow.get(Calendar.YEAR);
							LOGGER.debug("Current date: " + currDate);
							break;
						}
					}
					if (currDate == null) {
						LOGGER.debug("Today is not in the weekday range: " + weekDay);
					}
				} else {
					LOGGER.debug("WeekDay is invalid.");
				}
			}
 		} else {
 			LOGGER.debug("DateStr is invalid or out of range: " + dateStr);
 		}
		return currDate;
	}

	/**
	 * Format location string
	 * @param location
	 * @return
	 */
	public String formatLocationForTime(String location, String timeInfo) {
		Iterator<String> formatIterator = null;
		if (location == null || "".equals(location.trim())) {
			return location;
		}
		Set<String> keySet = locationFormatMap.keySet();
		formatIterator = keySet.iterator();
		while(formatIterator.hasNext()) {
			String key = formatIterator.next();
			location = location.replaceAll(key, locationFormatMap.get(key)).trim();
		}
		// unify the timeFormat， append the year
		if (!location.matches(".*\\b\\d+-\\d+-\\d+\\b.*") && location.matches(".*\\b(?<!\\d+-?)\\d{1,2}-\\d{1,2}(?!-?\\d+).*") 
				&& timeInfo != null && timeInfo.matches("START DATE:(.*)END DATE:(.*)")) {
			String year = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			year = year.replaceAll("\\d+-\\d+-(\\d+)", "$1");
			location = location.replaceAll("\\b(\\d{1,2}-\\d{1,2}(?!-\\d+|\\d+))", "$1-" + year);
		}
		/*if (!location.matches(".*\\b\\d+-\\d+-\\d+\\b.*") && location.matches(".*\\b(?<!\\d+-?)\\d{1,2}-\\d{1,2}(?!-?\\d+).*") 
				&& timeInfo != null && timeInfo.matches("START DATE:(.*)END DATE:(.*)")) {
			String year = timeInfo.replaceAll("START DATE:(.*)END DATE:(.*)", "$1").trim();
			year = year.replaceAll("\\d+-\\d+-(\\d+)", "$1");
			location = location.replaceAll("\\b(\\d{1,2}-\\d{1,2}(?!-\\d+))", "$1-" + year);
		}*/
		return location;
	}

	// Get weekday Integer
	private int getWeekdayInt(String weekday) {
		if (weekday == null) {
			return -1;
		} 
		weekday = weekday.trim().toUpperCase();
		
		if (weekday.matches("SUNDAY")){
			return 1;
		} else if (weekday.matches("MONDAY")){
			return 2;
		} else if (weekday.matches("TUESDAY")){
			return 3;
		} else if (weekday.matches("WEDNESDAY")){
			return 4;
		} else if (weekday.matches("THURSDAY")){
			return 5;
		} else if (weekday.matches("FRIDAY")){
			return 6;
		} else if (weekday.matches("SATURDAY")){
			return 7;
		} else {
			return -1;
		}
	}

	/**
	 * Initialize instance level variables
	 * 
	 * @param None
	 * @return None
	 */
	private void initVariables() throws Exception {
		fl_con_list = new ArrayList<IncConRecord>();
		DBConnector.getInstance().setReaderID(READER_ID);
		flTimeZone = DBUtils.getTimeZone(MARKET, STATE);
		LOGGER.info("InitVariable successfully!");
	}
	
	/**
	 * Format the street name.
	 * 
	 * @param street
	 * @return Formated street name
	 */
	private String formatSt(String street) {
		String key = "", value = "";
		String initName = "";
		if (street == null) {
			LOGGER.debug("Street is null.");
			return null;
		} else if (street.equals("")) {
			LOGGER.debug("Street name is an empty string!");
			return "";
		}
		initName = street;
		LOGGER.debug("Begin of formatStreet:" + street);

		street = street.trim().toUpperCase();
		
		Iterator<String> iterator = streetAliasMap.keySet().iterator();
		while (iterator.hasNext()) {
			key = iterator.next();
			value = streetAliasMap.get(key);
			street = street.replaceAll(key, value).trim();
		}
		LOGGER.debug("End of formatStreet:" + street);
		if (street.equals("")) {
			LOGGER.debug("Street is formatted to a no-letter line:" + initName);
		}
		return street;
	}
	
	/**
	 * Get the Dir from mainSt or fromSt.
	 * @param location
	 * @return
	 */
	private String getDir(String location) {
		String dir = null;
		if (location != null && location.matches(".*\\b((N|S|E|W)B|(NORTH|SOUTH|WEST|EAST)\\s?BOUND)\\b.*")) {
			dir = "";
			Pattern pattern = Pattern.compile("\\b((N|S|E|W)B|(NORTH|SOUTH|WEST|EAST)\\s?BOUND)\\b");
			Matcher matcher = pattern.matcher(location);
			while (matcher.find()) {
				String tempDir = matcher.group();
				tempDir = tempDir.substring(0,1) + "B";
				dir = dir + "_" + tempDir;
			}
			dir = dir.replaceAll("^_", "");
			// MORE THAN 3 DIRECTIONS
			if (dir.matches("[WESN]B_[WESN]B(_[WESN]B)+")) {
				dir = dir.replaceAll("^([WESN]B_[WESN]B)(_[WESN]B)+", "$1");
			}
		} else if (location != null && location.matches(".*\\b(N|S|E|W) (?:SR|I|US|IN|HWY)(?:\\s|-)?\\d+\\b.*")) {
			dir = location.replaceAll(".*\\b(N|S|E|W) (?:SR|I|US|IN|HWY)(?:\\s|-)?\\d+\\b.*", "$1");
			dir = dir + "B";
		} else if (location != null && location.matches(".*\b(?:BOTH|ALL) DIRECTION.*")) {
			if (location.matches(".*\\b(?:I|US|SR|CR|RT)(?:\\s|-)?(\\d+)(?:\\w)?\\b.*")) {
				String streetNum = location.replaceAll(".*\\b(?:I|US|SR|CR|RT)(?:\\s|-)?(\\d+)(?:\\w)?\\b.*", "$1");
				if (streetNum.matches("\\d+")) {
					if (Integer.parseInt(streetNum) % 2 == 0) {
						dir = "WB_EB";
					} else {
						dir = "NB_SB";
					}
				}
			} else if (location.matches(".*\\b\\d+(?:TH|RD|ST|ND)?\\s(?:AVE|AVENUE|ST|STREET|RD|ROAD|LANE|LN|PLACE|PL|TER|TERRACE|COURT)\\b.*")) {
				String streetNum = location
						.replaceAll(".*\\b(\\d+)(?:TH|RD|ST|ND)?\\s(?:AVE|AVENUE|ST|STREET|RD|ROAD|LANE|LN|PLACE|PL|TER|TERRACE|COURT)\\b.*", "$1");
				if (streetNum.matches("\\d+")) {
					if (Integer.parseInt(streetNum) % 2 == 0) {
						dir = "WB_EB";
					} else {
						dir = "NB_SB";
					}
				}
			}
		}
		return dir;
	}
	
	/**
	 * Load the properties file
	 * 
	 * @return true if successfully, otherwise false
	 */
	public boolean loadProperties() {
		FileInputStream is = null;
		String propValue = null;
		Properties prop = new Properties();
		LOGGER.info("Start to load properties");
		try {
			is = new FileInputStream(PROPERTY_FILE);
			prop.load(is);

			// Get the loop sleep time
			propValue = prop.getProperty(PROP_KEY_SLEEP_TIME);
			if (propValue != null && propValue.trim().length() > 0) {
				loopSleepTime = Integer.parseInt(propValue.trim());
				LOGGER.info("Get the loop_sleep_time is :  " + loopSleepTime);
			} else {
				LOGGER.info("Get the loop_sleep_time failed!");
				return false;
			}

			// Get the retry wait time
			propValue = prop.getProperty(PROP_KEY_RETRY_WAIT_TIME);
			if (propValue != null && propValue.trim().length() > 0) {
				retryWaitTime = Integer.parseInt(propValue.trim());
				LOGGER.info("Get the retry_wait_time is : " + retryWaitTime);
			} else {
				LOGGER.info("Get the retry_wait_time failed!");
				return false;
			}

			// Get connect out time
			propValue = prop.getProperty(PROP_KEY_CONNECT_TIME_OUT);
			if (propValue != null && propValue.trim().length() > 0) {
				connectOutTime = Integer.parseInt(propValue.trim());
				LOGGER.info("Get the connect out time is : " + connectOutTime);
			} else {
				LOGGER.info("Get the connect out time failed!");
				return false;
			}

			// Get the URL of location
			propValue = prop.getProperty(PROP_KEY_DATA_URL_LOCATION);
			if (propValue != null && propValue.trim().length() > 0) {
				dataUrlLocation = propValue.trim();
				LOGGER.info("Get the URL of location is:  " + dataUrlLocation);
			} else {
				LOGGER.info("Get the URL of location failed!");
				return false;
			}

			// Get the URL for save
			propValue = prop.getProperty(PROP_KEY_URL_SUFFIX);
			if (propValue != null && propValue.trim().length() > 0) {
				urlSuffix = propValue.trim().split(",");
				if (urlSuffix != null && urlSuffix.length > 0) {
					for (String suffix : urlSuffix) {
						LOGGER.info("URL suffix: " + suffix);
					}
					LOGGER.info("Get the url suffix success.");
				} else {
					LOGGER.info("URL suffix is null.");
					return false;
				}
			} else {
				LOGGER.info("Get the URL suffix failed!");
				return false;
			}

			// Get the separate sign
			propValue = prop.getProperty(PROP_KEY_TC_SEPARATE_SIGN);
			if (propValue != null && propValue.trim().length() >= 5) {
				tcSeparateSign = propValue.trim();
				LOGGER.info("tcSeparateSign: " + tcSeparateSign);
			} else {
				LOGGER.info("Get tcSeparateSign failed!");
				return false;
			}

			// Get the reverse geocoding flag
			propValue = prop.getProperty(PROP_KEY_REVERSE_GEOCODING_FLAG);
			if (propValue != null && propValue.trim().length() > 0) {
				isReverseGeocoding = Boolean.parseBoolean(propValue.trim());
				LOGGER.info("ReverseGeocoding flag: " + isReverseGeocoding);
			} else {
				LOGGER.info("Get reverseGeocoding flag failed!");
				return false;
			}
			
			// Get the show all description flag
			propValue = prop.getProperty(PROP_KEY_SHOW_ALL_DESCRIPTION);
			if (propValue != null && propValue.trim().length() > 0) {
				showAllDescription = Boolean.parseBoolean(propValue.trim());
				LOGGER.info("Show all description flag: " + showAllDescription);
			} else {
				LOGGER.info("Get the show all description flag failed!");
				return false;
			}
			
//			// Get the filter key word
//			propValue = prop.getProperty(PROP_KEY_FILTER_KEYWORD);
//			if(propValue != null && !"".equals(propValue.trim())) {
//				filterKeyWordList = new ArrayList<String>();
//				propValue = propValue.trim().toUpperCase();
//				String[] keywords = propValue.split(",");
//				if (keywords != null && keywords.length > 0) {
//					for (String keyword : keywords) {
//						if (!keyword.trim().equals("")) {
//							filterKeyWordList.add(keyword.trim().toUpperCase());
//							LOGGER.info("Filter key word: " + keyword.toUpperCase());
//						}						
//					}
//				}
//				LOGGER.info("Get the filter key word success.");
//			} else {
//				LOGGER.info("Get the filter key word failure.");
//				return false;
//			}

			LOGGER.info("Load properties and init successfully!");
			return true;
		} catch (FileNotFoundException ex) {
			ex.printStackTrace();
			LOGGER.fatal("Properties file does not exist, program will terminate now ("
					+ ex.getMessage() + ")");
		} catch (Exception ex) {
			LOGGER.fatal("Load/Parse properties error, program will terminate now ("
					+ ex.getMessage() + ")");
		} finally {
			try {
				if (is != null) {
					is.close();
				}
			} catch (Exception e) {
				LOGGER.warn("Error while closing FileInputStream  "
						+ e.getMessage());
			}
			is = null;
		}
		return false;
	}

	/**
	 * Load the patterns file
	 * 
	 * @return true if successfully, otherwise false
	 */
	private boolean loadPatterns() {
		BufferedReader reader = null;
		String lineRead = null;
		String[] keyValue = null;
		Pattern pattern = null;
		streetPatternMap = new LinkedHashMap<Pattern, String>();
		mainStPatternArrayList = new ArrayList<Pattern>();
		fromStPatternArrayList = new ArrayList<Pattern>();
		toStPatternArrayList = new ArrayList<Pattern>();
		datePatternArrayList = new ArrayList<Pattern>();
		weekdayTimePatternArrayList = new ArrayList<Pattern>();
		timePatternArrayList = new ArrayList<Pattern>();
		splitPatternArrayList = new ArrayList<Pattern>();
		LOGGER.info("Start to load patterns.");

		try {
			reader = new BufferedReader(new FileReader(PATTERN_FILE));
			while ((lineRead = reader.readLine()) != null) {
				if (lineRead.length() < tcSeparateSign.length() + 2
						|| lineRead.startsWith("#")) {
					LOGGER.info("Empty or comment line, skipped:" + lineRead);
					continue;
				}
				keyValue = lineRead.split(tcSeparateSign);
				if (keyValue.length < 2) {
					LOGGER.info("Invalid line: " + lineRead);
					continue;
				}
				if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(STREET_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					String groupNum = keyValue[0].replaceAll(STREET_PATTERN, "");
					if (groupNum.equals("2") || groupNum.equals("3") || groupNum.equals("1") || groupNum.equals("4") || groupNum.equals("5")) {
						streetPatternMap.put(pattern, groupNum);
					}
					LOGGER.info("Add pattern to streetPatternMap:"
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(MAIN_ST_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					mainStPatternArrayList.add(pattern);
					LOGGER.info("Add pattern to mainStPatternArrayList:"
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(FROM_ST_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					fromStPatternArrayList.add(pattern);
					LOGGER.info("Add pattern to fromStPatternArrayList:"
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(TO_ST_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					toStPatternArrayList.add(pattern);
					LOGGER.info("Add pattern to toStPatternArrayList: "
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(SPLIT_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					splitPatternArrayList.add(pattern);
					LOGGER.info("Add pattern to splitPatternArrayList: "
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(DATE_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					datePatternArrayList.add(pattern);
					LOGGER.info("Add pattern to datePatternArrayList: "
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(WEEKDAY_AND_TIME_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					weekdayTimePatternArrayList.add(pattern);
					LOGGER.info("Add pattern to weekdayTimePatternArrayList: "
							+ pattern.toString());
				} else if (keyValue != null && keyValue.length > 1
						&& keyValue[0].contains(TIME_PATTERN)) {
					pattern = Pattern.compile(keyValue[1]);
					timePatternArrayList.add(pattern);
					LOGGER.info("Add pattern to timePatternArrayList: "
							+ pattern.toString());
				} else {
					LOGGER.info("Unknown pattern: " + keyValue[1]);
				}
			}

			LOGGER.info("Load patterns successfully!");
			return true;
		} catch (FileNotFoundException ex) {
			LOGGER.fatal("Patterns file:" + PATTERN_FILE
					+ " does not exist, program will terminate now ("
					+ ex.getMessage() + ")");
		} catch (Exception ex) {
			LOGGER.fatal("Parse patterns error, program will terminate now ("
					+ ex.getMessage() + ")");
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				reader = null;
			}
		}
		return false;
	}

	/**
	 * Load street alias
	 * 
	 * @return true if successfully, otherwise false
	 */
	private boolean loadStreatAlias() {
		BufferedReader reader = null;
		streetAliasMap = new LinkedHashMap<String, String>();
		String lineRead = null;
		String[] keyValue = null;
		LOGGER.info("Start to load street alias");
		try {
			reader = new BufferedReader(new FileReader(STREET_ALIAS_FILE));
			while ((lineRead = reader.readLine()) != null) {
				if (lineRead.length() < tcSeparateSign.length() + 2
						|| lineRead.startsWith("#")) {
					LOGGER.info("Empty or comment line, skipped:" + lineRead);
					continue;
				}
				keyValue = lineRead.split(tcSeparateSign);
				if (keyValue != null && keyValue.length > 1) {
					streetAliasMap.put(keyValue[0], keyValue[1]);
					LOGGER.info("STREET ALIAS: " + keyValue[0] + " = "
							+ keyValue[1]);
				}
			}
			LOGGER.info("Load street alias successfully!");
			return true;
		} catch (FileNotFoundException ex) {
			LOGGER.fatal("StreetAlias file:" + STREET_ALIAS_FILE
					+ " does not exist, program will terminate now ("
					+ ex.getMessage() + ")");
		} catch (Exception ex) {
			LOGGER.fatal("Load street alias error, program will terminate now ("
					+ ex.getMessage() + ")");
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				reader = null;
			}
		}
		return false;
	}
	
	public boolean loadFormat() {
		BufferedReader reader = null;
		locationFormatMap = new LinkedHashMap<String,String>();
		String lineRead = null;
		String[] keyValue = null;
		
		try {
			reader = new BufferedReader(new FileReader(LOCATION_FORMAT_FILE));
			while ((lineRead = reader.readLine()) != null) {
				if (lineRead.length() < tcSeparateSign.length() + 2
						|| lineRead.startsWith("#")) {
					LOGGER.info("Empty or comment line, skipped:" + lineRead);
					continue;
				}
				keyValue = lineRead.split(tcSeparateSign);
				if (keyValue.length < 2) {
					LOGGER.info("Invalid line: " + lineRead);
					continue;
				}
				if (keyValue != null && keyValue.length > 1
						&& keyValue[0].startsWith("LOCATION_FORMAT")) {
					locationFormatMap.put(keyValue[0].replaceAll("LOCATION_FORMAT", ""), keyValue[1]);
					LOGGER.info("LOCATION FORMAT: " + keyValue[0].replaceAll("LOCATION_FORMAT", "") + " = "
							+ keyValue[1]);
				}
			}
			LOGGER.info("Load location format successfully!");
			return true;
		} catch (FileNotFoundException ex) {
			LOGGER.fatal("Load locationFormat error, program will terminate now ("
					+ ex.getMessage() + ")");
		} catch (Exception ex) {
			LOGGER.fatal("Load locationFormat error, program will terminate now ("
					+ ex.getMessage() + ")");
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				reader = null;
			}
		}
		return false;
		
	}
	
	public String getLastDayOfMonth(int year, int month) {
		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.YEAR, year);
		cal.set(Calendar.MONTH, month - 1);
		int lastDay = cal.getActualMaximum(Calendar.DATE);
		cal.set(Calendar.DAY_OF_MONTH, lastDay);
		SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy");
		return sdf.format(cal.getTime());
	}
	
}
