package org.dawnoftime.onceuponatown;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Constants {

	public static final String MOD_ID = "onceuponatown";
	public static final String MOD_NAME = "Once Upon a Town";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

	public static final String STREETS_POOL = MOD_ID + ":streets";

	public static boolean isStreetsPool(String poolName) {
		return poolName != null && poolName.endsWith(":streets");
	}
}