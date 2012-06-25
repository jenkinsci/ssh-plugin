package org.jvnet.hudson.plugins;

import java.util.Map;

public class VariableReplacerUtil {
	public static String replace(String originalCommand, Map<String, String> vars) {
		if(originalCommand == null){
			return null;
		}
		vars.remove("_"); //why _ as key for build tool?
		StringBuilder sb = new StringBuilder();
		for (String variable : vars.keySet()) {
			if (originalCommand.contains(variable) ) {
				sb.append("export " + variable + "=\"" + vars.get(variable)	+ "\"\n");
			}
		}
		sb.append("\n");
		sb.append(originalCommand);
		return sb.toString();
	}
}
