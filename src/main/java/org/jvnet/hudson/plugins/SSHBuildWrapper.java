package org.jvnet.hudson.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.BuildListener;
import hudson.model.ItemGroup;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.security.ACL;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.XStream2;
import hudson.util.ListBoxModel;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.jsch.JSchConnector;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
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
	public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
			InterruptedException {
		Environment env = new Environment() {
			@Override
			public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException,
					InterruptedException {
				if (!executePostBuildScript(build, listener)) {
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

	private boolean executePreBuildScript(AbstractBuild<?, ?> build, BuildListener listener) throws IOException,
			InterruptedException {
		PrintStream logger = listener.getLogger();
		CredentialsSSHSite site = getSite();
		Map<String, String> vars = new HashMap<String, String>();
		vars.putAll(build.getEnvironment(listener));
		vars.putAll(build.getBuildVariables());
		String runtime_cmd = VariableReplacerUtil.replace(preScript, vars);
		log(logger, "executing pre build script:\n" + VariableReplacerUtil.scrub(runtime_cmd, vars, build.getSensitiveBuildVariables()));
		if (runtime_cmd != null && !runtime_cmd.trim().equals("")) {
			return site.executeCommand(logger, runtime_cmd) == 0;
		}
		return true;
	}

	private boolean executePostBuildScript(AbstractBuild<?, ?> build, BuildListener listener) throws IOException,
			InterruptedException {
		PrintStream logger = listener.getLogger();
		CredentialsSSHSite site = getSite();
		Map<String, String> vars = new HashMap<String, String>();
		vars.putAll(build.getEnvironment(listener));
		vars.putAll(build.getBuildVariables());
		String runtime_cmd = VariableReplacerUtil.replace(postScript, vars);
		log(logger, "executing post build script:\n" + VariableReplacerUtil.scrub(runtime_cmd, vars, build.getSensitiveBuildVariables()));
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

	public CredentialsSSHSite getSite() {
		CredentialsSSHSite[] sites = DESCRIPTOR.getSites();

		for (CredentialsSSHSite site : sites) {
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

		private final CopyOnWriteList<CredentialsSSHSite> sites = new CopyOnWriteList<CredentialsSSHSite>();

		@Override
		public String getDisplayName() {
			return Messages.SSH_DisplayName();
		}

		public ListBoxModel doFillSiteNameItems() {
			ListBoxModel m = new ListBoxModel();
			for (CredentialsSSHSite site : SSHBuildWrapper.DESCRIPTOR.getSites()) {
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

		public CredentialsSSHSite[] getSites() {
			return sites.toArray(new CredentialsSSHSite[0]);
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) {
			sites.replaceBy(req.bindJSONToList(CredentialsSSHSite.class, formData.get("sites")));

			save();
			return true;
		}

		public ListBoxModel doFillCredentialIdItems(final @AncestorInPath ItemGroup<?> context) {
			final List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
					StandardUsernameCredentials.class, context, ACL.SYSTEM, CredentialsSSHSite.NO_REQUIREMENTS);

			return new SSHUserListBoxModel().withEmptySelection().withMatching(
					SSHAuthenticator.matcher(JSchConnector.class), credentials);
		}

		/**
		 * Validates ssh connection - currently this is executed on master node
		 */
		public FormValidation doLoginCheck(@QueryParameter("hostname") String hostname,
				@QueryParameter("port") String port,
				@QueryParameter("credentialId") String credentialId,
				@QueryParameter("serverAliveInterval") String serverAliveInterval,
				@QueryParameter("timeout") String timeout) {
			hostname = Util.fixEmpty(hostname);
			port = Util.fixEmpty(port);
			credentialId = Util.fixEmpty(credentialId);

			if (hostname == null || port == null || credentialId == null) {// all fields not entered yet
				return FormValidation.warning("Please fill host, port and credentials.");
			}

			final CredentialsSSHSite site = new CredentialsSSHSite(hostname, port, credentialId,
					serverAliveInterval, timeout);
			try {
				try {
					site.testConnection(System.out);
				} catch (JSchException e) {
					LOGGER.log(Level.SEVERE, e.getMessage());
					throw new IOException("Can't connect to server");
				}
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage());
				return FormValidation.error(e.getMessage());
			} catch (InterruptedException e) {
				LOGGER.log(Level.SEVERE, e.getMessage());
				return FormValidation.error(e.getMessage());
			}
			return FormValidation.ok("Successfull connection");
		}

		@Override
		public boolean isApplicable(AbstractProject<?, ?> item) {
			return true;
		}

		@Override
		protected XmlFile getConfigFile() {
			final XStream2 customXstream = new XStream2();
			customXstream.addCompatibilityAlias("org.jvnet.hudson.plugins.SSHSite", CredentialsSSHSite.LegacySSHSite.class);

			return new XmlFile(customXstream, new File(Jenkins.getInstance().getRootDir(),getId()+".xml"));
		}

		/**
		 * Migration logic from plaintext login and pass sites to sites using credentials<br>
		 * Called by XStream each time just after unmarshaling fields from XML<br>
		 * Jenkins by default will persist migrated sites on shutdown or after global config save.
		 */
		private Object readResolve() {
			boolean madeChanges = false;
			final List<CredentialsSSHSite> migratedCredentials = new ArrayList<CredentialsSSHSite>(sites.size());
			for (CredentialsSSHSite site : sites) {
				try {
					final CredentialsSSHSite migrated = CredentialsSSHSite.migrateToCredentials(site);
					migratedCredentials.add(migrated);
					
					madeChanges = madeChanges || (migrated != site);
				} catch (InterruptedException e) {
					throw new IllegalStateException("Failed to migrate site: " + site, e);
				} catch (IOException e) {
					throw new IllegalStateException("Failed to migrate site: " + site, e);
				}
			}

			if (madeChanges) {
				sites.replaceBy(migratedCredentials);
			}

			return this;
		}
	}

	public String getSiteName() {
		return siteName;
	}

	public void setSiteName(String siteName) {
		this.siteName = siteName;
	}

	private void log(final PrintStream logger, final String message) {
		logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName()) + message);
	}
}
