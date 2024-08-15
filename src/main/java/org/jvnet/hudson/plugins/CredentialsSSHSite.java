package org.jvnet.hudson.plugins;

import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.logging.Logger;

import org.jenkinsci.plugins.jsch.JSchConnector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.FileOnMasterPrivateKeySource;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.DomainSpecification;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortSpecification;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import hudson.Util;

public class CredentialsSSHSite {

	public static class LegacySSHSite extends CredentialsSSHSite {
		transient String password;
		transient String keyfile;
	}

	String name;

	String hostname;
	String username;
	int port;
	String credentialId;
	int serverAliveInterval = 0;
	int timeout = 0;
	Boolean pty = Boolean.FALSE;

	transient String resolvedHostname = null;

	public static final Logger LOGGER = Logger.getLogger(CredentialsSSHSite.class.getName());

	public static final List<DomainRequirement> NO_REQUIREMENTS = Collections.<DomainRequirement> emptyList();

	private CredentialsSSHSite() {
	}

	@DataBoundConstructor
	public CredentialsSSHSite(final String name, final String hostname, final String port, final String credentialId,
			final String serverAliveInterval, final String timeout) {
		final StandardUsernameCredentials credentials = lookupCredentialsById(credentialId);
		if (credentials != null) {
			this.username = credentials.getUsername();
		}

		this.name = Util.fixEmptyAndTrim(name);
		this.hostname = hostname;
		try {
			this.port = Integer.parseInt(port);
		} catch (final NumberFormatException e) {
			this.port = 22;
		}
		this.credentialId = credentialId;
		this.setServerAliveInterval(serverAliveInterval);
		this.setTimeout(timeout);
	}

	private void closeSession(final Session session, final ChannelExec channel) {
		if (channel != null) {
			channel.disconnect();
		}
		if (session != null) {
			session.disconnect();
		}
	}

	private ChannelExec createChannel(final PrintStream logger, final Session session) throws JSchException {
		final ChannelExec channel = (ChannelExec) session.openChannel("exec");
		channel.setOutputStream(logger, true);
		channel.setExtOutputStream(logger, true);
		channel.setInputStream(null);
		if (pty == null) {
			pty = Boolean.FALSE;
		}
		channel.setPty(pty);

		return channel;
	}

	private Session createSession(final PrintStream logger) throws JSchException, IOException, InterruptedException {
		final StandardUsernameCredentials user = lookupCredentialsById(credentialId);
		if (user == null) {
			String message = "Credentials with id '" + credentialId + "', no longer exist!";
			logger.println(message);
			throw new InterruptedException(message);
		}

		final JSchConnector connector = new JSchConnector(user.getUsername(), getResolvedHostname(), port);

		final SSHAuthenticator<JSchConnector, StandardUsernameCredentials> authenticator = SSHAuthenticator
				.newInstance(connector, user);
		authenticator.authenticate(new StreamTaskListener(logger, Charset.defaultCharset()));

		final Session session = connector.getSession();

		session.setServerAliveInterval(serverAliveInterval);

		final Properties config = new Properties();
		//TODO put this as configuration option instead of ignoring by default
		config.put("StrictHostKeyChecking", "no");
		session.setConfig(config);
		session.connect(timeout);

		return session;
	}

	/**
	 * Migrates LegacySSHSite (plaintext login and pass) to CredentialsSSHSite (credentials in credentials plugin)<br>
	 * Returns the same instance when supplied with CredentialsSSHSite
	 */
	public static CredentialsSSHSite migrateToCredentials(CredentialsSSHSite site) throws InterruptedException,
			IOException {
		if (!(site instanceof LegacySSHSite)) {
			return site;
		}

		final LegacySSHSite legacy = (LegacySSHSite) site;

		final List<StandardUsernameCredentials> credentialsForDomain = CredentialsProvider.lookupCredentials(
				StandardUsernameCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, new HostnamePortRequirement(site.hostname,
						site.port));
		final StandardUsernameCredentials existingCredentials = CredentialsMatchers.firstOrNull(credentialsForDomain,
				CredentialsMatchers.withUsername(legacy.username));

		final String credentialId;
		if (existingCredentials == null) {
			String createdCredentialId = UUID.randomUUID().toString();

			final StandardUsernameCredentials credentialsToCreate;
			if (!Strings.isNullOrEmpty(legacy.keyfile)) {
				credentialsToCreate = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, createdCredentialId,
						legacy.username, new FileOnMasterPrivateKeySource(legacy.keyfile), legacy.password,
						"migrated from previous ssh-plugin version");
			} else if (!Strings.isNullOrEmpty(legacy.password)) {
				credentialsToCreate = new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, createdCredentialId,
						"migrated from previous ssh-plugin version", legacy.username, legacy.password);
			} else {
				throw new InterruptedException(
						"Did not find password nor keyfile while migrating from non-credentials SSH configuration!");
			}

			final SystemCredentialsProvider credentialsProvider = SystemCredentialsProvider.getInstance();
			final Map<Domain, List<Credentials>> credentialsMap = credentialsProvider.getDomainCredentialsMap();

			DomainSpecification hostnameSpec = new HostnamePortSpecification(site.hostname + ":" + site.port, null);
			final Domain sshDomain = new Domain("ssh-plugin-" + site.hostname, "migrated ssh-plugin credentials-"
					+ site.hostname, Lists.newArrayList(hostnameSpec));

			List<Credentials> domainCreds = credentialsMap.get(sshDomain);
			if (domainCreds == null) {
				domainCreds = Lists.newArrayList();
				credentialsMap.put(sshDomain, domainCreds);
			}

			domainCreds.add(credentialsToCreate);

			credentialsProvider.setDomainCredentialsMap(credentialsMap);
			credentialsProvider.save();

			credentialId = createdCredentialId;
		} else {
			credentialId = existingCredentials.getId();
		}

		return new CredentialsSSHSite(null, legacy.hostname, String.valueOf(legacy.port), credentialId,
				String.valueOf(legacy.serverAliveInterval), String.valueOf(legacy.timeout));
	}

	@Deprecated
	public int executeCommand(PrintStream logger, String command) throws InterruptedException {
		return executeCommand(logger, command, false);
	}

	public int executeCommand(PrintStream logger, String command, boolean execEachLine) throws InterruptedException {
		Session session = null;
		int status = -1;
		try {
			session = createSession(logger);
			if (execEachLine) {
				StringTokenizer commands = new StringTokenizer(command,"\n\r\f");
				while (commands.hasMoreTokens()) {
					int i = doExecCommand(session, logger, commands.nextToken().trim());
					if (i != 0) {
						status = i;
						break;
					}
					//if there are no more commands to execute return the status of the last command
					if (!commands.hasMoreTokens()) {
						status = i;
					}
				}
			}
			else {
				status = doExecCommand(session, logger, command);
			}
			logger.printf("%n[SSH] completed");
			logger.printf("%n[SSH] exit-status: " + status + "%n%n");
		} catch (JSchException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
		} catch (IOException e) {
			logger.println("[SSH] Exception:" + e.getMessage());
			e.printStackTrace(logger);
		} finally {
			if (session != null && session.isConnected()) {
				session.disconnect();
			}
		}

		return status;
	}

	private int doExecCommand(Session session, PrintStream logger, String command) throws InterruptedException, IOException, JSchException {
		ChannelExec channel = null;
		int status = -1;
		try {
			channel = createChannel(logger, session);
			channel.setCommand(command);
			InputStream in = channel.getInputStream();
			channel.connect();
			byte[] tmp = new byte[1024];
			while (true) {
				while (in.available() > 0) {
					int i = in.read(tmp, 0, 1024);
					if (i < 0)
						break;
					logger.write(tmp, 0, i);
				}
				if (channel.isClosed()) {
					status = channel.getExitStatus();
					break;
				}
				logger.flush();
				Thread.sleep(1000);
			}
		} catch (JSchException e) {
			throw e;
		} finally {
			if (channel != null && channel.isConnected()) {
				channel.disconnect();
			}
		}
		return status;
	}

	public String getCredentialId() {
		return credentialId;
	}

	public String getHostname() {
		return hostname;
	}

	public int getIntegerPort() {
		return port;
	}

	public String getPort() {
		return "" + port;
	}

	public Boolean getPty() {
		return pty;
	}

	public String getServerAliveInterval() {
		return "" + serverAliveInterval;
	}

	/** Returns &quot;identifier&quot; for ssh site: <strong>username@hostname:port</strong> */
	public String getSitename() {
		return (Util.fixEmptyAndTrim(name) == null) ? username + "@" + hostname + ":" + port : name + " - " + username + "@" + hostname + ":" + port;
	}

	public String getTimeout() {
		return "" + timeout;
	}

	private StandardUsernameCredentials lookupCredentialsById(final String credentialId) {
		final List<StandardUsernameCredentials> all = CredentialsProvider.lookupCredentials(
				StandardUsernameCredentials.class, (Item) null, ACL.SYSTEM, NO_REQUIREMENTS);

		return CredentialsMatchers.firstOrNull(all, CredentialsMatchers.withId(credentialId));
	}

	public void setCredentialId(final String credentialId) {
		this.credentialId = credentialId;
	}

	public void setHostname(final String hostname) {
		this.hostname = hostname;
	}

	public void setPort(final String port) {
		try {
			this.port = Integer.parseInt(port);
		} catch (final NumberFormatException e) {
			this.port = 22;
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = Util.fixEmptyAndTrim(name);
	}

	@DataBoundSetter
	public void setPty(final Boolean pty) {
		this.pty = pty;
	}

	public void setResolvedHostname(final String hostname) {
		this.resolvedHostname = hostname;
	}

	public void setServerAliveInterval(final String serverAliveInterval) {
		try {
			this.serverAliveInterval = Integer.parseInt(serverAliveInterval);
		} catch (final NumberFormatException e) {
			this.serverAliveInterval = 0;
		}
	}

	public void setTimeout(final String timeout) {
		try {
			this.timeout = Integer.parseInt(timeout);
		} catch (final NumberFormatException e) {
			this.timeout = 0;
		}
	}

	private String getResolvedHostname() {
		return resolvedHostname == null ? hostname : resolvedHostname;
	}

	public void testConnection(final PrintStream logger) throws JSchException, IOException, InterruptedException {
		final Session session = createSession(logger);
		closeSession(session, null);
	}

	@Override
	public String toString() {
		return "SSHSite [name=" + name + ", username=" + username + ", hostname=" + hostname + ",port=" + port + ", credentialId="
				+ credentialId + ", pty=" + pty + "]";
	}

}
