package org.jvnet.hudson.plugins;

import java.util.Map;
import java.util.Set;

public class VariableReplacerUtil {
	/**
	 * Sets/exports shell env vars before original command, if the command contains variable name
	 * @param originalCommand
	 * @param vars
	 * @return shell script preluded with env vars
	 */
	public static String preludeWithEnvVars(String originalCommand, Map<String, String> vars) {
		if(originalCommand == null){
			return null;
		}
		if (vars == null) {
			return originalCommand;
		}
		vars.remove("_"); //why _ as key for build tool?
		StringBuilder sb = new StringBuilder();
		for (String variable : vars.keySet()) {
			if (originalCommand.contains(variable) ) {
				sb.append(variable).append("=\"").append(vars.get(variable)).append("\"\n");
			}
		}
		sb.append("\n");
		sb.append(originalCommand);
		return sb.toString();
	}

	public static String scrub(String command, Map<String, String> vars, Set<String> eyesOnlyVars) {
		if(command == null || vars == null || eyesOnlyVars == null){
			return command;
		}
		vars.remove("_");
		for (String sensitive : eyesOnlyVars) {
			for (String variable : vars.keySet()) {
				if (variable.equals(sensitive)) {
					String value = vars.get(variable);
					if (command.contains(value)) {
						if (command.contains("\"" + value + "\"")) {
							command = command.replace(("\"" + value + "\"") , "**********");
						}
						command = command.replace(value , "**********");
					}
					break;
				}
			}
		}
		return command;
	}
}
