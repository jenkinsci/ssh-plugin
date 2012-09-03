package org.jvnet.hudson.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.JSchException;

public final class SSHBuildWrapper extends BuildWrapper {

	public static final Logger LOGGER = Logger.getLogger(SSHBuildWrapper.class.getName());

	private String siteName;
	private String preScript;
	private String postScript;

	public SSHBuildWrapper() {
	}

	@DataBoundConstructor
	public SSHBuildWrapper(String siteName, String preScript, String postScript) {
		this.siteName = siteName;
		this.preScript = preScript;
		this.postScript = postScript;
	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
		Environment env = new Environment() {
			@Override
			public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
				if (!executePostBuildScript(build,listener)) {
					return false;
				}
				return super.tearDown(build, listener);
			}
		};
		if (executePreBuildScript(build, listener)) {
			return env;
		}
		// build will fail.
		return null;
	}

	private boolean executePreBuildScript(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
		PrintStream logger = listener.getLogger();
		SSHSite site = getSite();
		Map<String, String> vars = new HashMap<String, String>(); 
		vars.putAll(build.getEnvironment(listener));
		vars.putAll(build.getBuildVariables());
		String runtime_cmd = VariableReplacerUtil.replace(preScript, vars);
		log(logger, "executing pre build script:\n" + runtime_cmd);
		if (runtime_cmd != null && !runtime_cmd.trim().equals("")) {
			return site.executeCommand(logger, runtime_cmd) == 0;
		}
		return true;
	}

	private boolean executePostBuildScript(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
		PrintStream logger = listener.getLogger();
		SSHSite site = getSite();
		Map<String, String> vars = new HashMap<String, String>(); 
		vars.putAll(build.getEnvironment(listener));
		vars.putAll(build.getBuildVariables());
		String runtime_cmd = VariableReplacerUtil.replace(postScript, vars);
		log(logger, "executing post build script:\n" + runtime_cmd);
		if (runtime_cmd != null && !runtime_cmd.trim().equals("")) {
			return site.executeCommand(logger, runtime_cmd) == 0;
		}
		return true;
	}

	public String getPreScript() {
		return preScript;
	}

	public void setPreScript(String preScript) {
		this.preScript = preScript;
	}

	public String getPostScript() {
		return postScript;
	}

	public void setPostScript(String postScript) {
		this.postScript = postScript;
	}

	public SSHSite getSite() {
		SSHSite[] sites = DESCRIPTOR.getSites();

		for (SSHSite site : sites) {
			if (site.getSitename().equals(siteName))
				return site;
		}
		return null;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static final class DescriptorImpl extends BuildWrapperDescriptor {

		public DescriptorImpl() {
			super(SSHBuildWrapper.class);
			load();
		}

		protected DescriptorImpl(Class<? extends BuildWrapper> clazz) {
			super(clazz);
		}

		private final CopyOnWriteList<SSHSite> sites = new CopyOnWriteList<SSHSite>();

		@Override
		public String getDisplayName() {
			return Messages.SSH_DisplayName();
		}
		
		public ListBoxModel doFillSiteNameItems() {
			ListBoxModel m = new ListBoxModel();
			for (SSHSite site : SSHBuildWrapper.DESCRIPTOR.getSites()) {
				m.add(site.getSitename());
			}
			return m;
		}

		public String getShortName() {
			return "[SSH] ";
		}

		@Override
		public String getHelpFile() {
			return "/plugin/ssh/help.html";
		}

		@Override
		public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) {
			return req.bindJSON(clazz, formData);
		}

		public SSHSite[] getSites() {
			return sites.toArray(new SSHSite[0]);
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) {
			sites.replaceBy(req.bindParametersToList(SSHSite.class, "ssh."));
			save();
			return true;
		}

		public FormValidation doKeyfileCheck(@QueryParameter String keyfile) {
			keyfile = Util.fixEmpty(keyfile);
			if (keyfile != null) {
				File f = new File(keyfile);
				if (!f.isFile()) {
					return FormValidation.error("keyfile does not exist");
				}
			}

			return FormValidation.ok();
		}

		public FormValidation doLoginCheck(StaplerRequest request) {
			String hostname = Util.fixEmpty(request.getParameter("hostname"));
			if (hostname == null) {// hosts is not entered yet
				return FormValidation.ok();
			}
			SSHSite site = new SSHSite(hostname, request.getParameter("port"), request.getParameter("user"), request.getParameter("pass"), request.getParameter("keyfile"));
			try {
				try {
					site.testConnection(System.out);
				}
				catch (JSchException e) {
					LOGGER.log(Level.SEVERE, e.getMessage());
					throw new IOException("Can't connect to server");
				}
			}
			catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage());
				return FormValidation.error(e.getMessage());
			}
			return FormValidation.ok();
		}

		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}

	}

	public String getSiteName() {
		return siteName;
	}

	public void setSiteName(String siteName) {
		this.siteName = siteName;
	};

	private void log(final PrintStream logger, final String message) {
		logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName()) + message);
	}
}
