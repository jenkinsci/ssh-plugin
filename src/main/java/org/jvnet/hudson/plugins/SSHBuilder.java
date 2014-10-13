package org.jvnet.hudson.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SSHBuilder extends Builder {

	private String siteName;
	private String command;

	@DataBoundConstructor
	public SSHBuilder(String siteName, String command) {
		this.siteName = siteName;
		this.command = command;
	}

	public String getSiteName() {
		return siteName;
	}

	public String getCommand() {
		return command;
	}

	public void setSiteName(String siteName) {
		this.siteName = siteName;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
		CredentialsSSHSite site = getSite();
		
		// Get the build variables and make sure we substitute the current SSH Server host name
		site.setResolvedHostname(build.getEnvironment(listener).expand(site.getHostname()));
		
		Map<String, String> vars = new HashMap<String, String>(); 
		vars.putAll(build.getEnvironment(listener));
		vars.putAll(build.getBuildVariables());
		String runtime_cmd = VariableReplacerUtil.replace(command, vars);
		
		if (site != null && runtime_cmd != null && runtime_cmd.trim().length() > 0) {
			listener.getLogger().printf("executing script:%n%s%n", runtime_cmd);
			return site.executeCommand(listener.getLogger(), runtime_cmd) == 0;
		}
		return true;
	}

	public CredentialsSSHSite getSite() {
		CredentialsSSHSite[] sites = SSHBuildWrapper.DESCRIPTOR.getSites();
		for (CredentialsSSHSite site : sites) {
			if (site.getSitename().equals(siteName))
				return site;
		}
		return null;
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return Messages.SSH_DisplayName();
		}

		@Override
		public Builder newInstance(StaplerRequest req, JSONObject formData) throws hudson.model.Descriptor.FormException {
			return req.bindJSON(clazz, formData);
		}

		public ListBoxModel doFillSiteNameItems() {
			ListBoxModel m = new ListBoxModel();
			for (CredentialsSSHSite site : SSHBuildWrapper.DESCRIPTOR.getSites()) {
				m.add(site.getSitename());
			}
			return m;
		}
	}
}
