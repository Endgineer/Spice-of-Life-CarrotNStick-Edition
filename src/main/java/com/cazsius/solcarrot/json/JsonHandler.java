package com.cazsius.solcarrot.json;

import com.cazsius.solcarrot.SOLCarrot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.lang.reflect.Type;

public class JsonHandler {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	
	public static <T> T getOrCreateConfigFile(File configDir, String configName, T defaults, Type type) {
		File configFile = new File(configDir, configName);
		
		if (!configFile.exists()) {
			writeFile(configFile, defaults);
		}
		
		try {
			return (T) GSON.fromJson(FileUtils.readFileToString(configFile), type);
		} catch (Exception exception) {
			SOLCarrot.LOGGER.error("Error parsing config from json: " + configFile.toString(), exception);
		}
		
		return null;
	}
	
	private static boolean writeFile(File outputFile, Object object) {
		try {
			FileUtils.write(outputFile, GSON.toJson(object));
			return true;
		} catch (Exception exception) {
			SOLCarrot.LOGGER.error("Error writing config file " + outputFile.getAbsolutePath() + ": " + exception.getMessage());
			return false;
		}
	}
}
