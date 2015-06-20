package org.jvnet.hudson.plugins;

import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.StreamTaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.JSchConnector;
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
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.base.Strings;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

public class CredentialsSSHSite {

	public static class LegacySSHSite extends CredentialsSSHSite {
		String password;
		String keyfile;
	}

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
	public CredentialsSSHSite(final String hostname, final String port, final String credentialId,
			final String serverAliveInterval, final String timeout) {
		final StandardUsernameCredentials credentials = lookupCredentialsById(credentialId);
		this.username = credentials.getUsername();

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

		final JSchConnector connector = new JSchConnector(user.getUsername(), hostname, port);

		final SSHAuthenticator<JSchConnector, StandardUsernameCredentials> authenticator = SSHAuthenticator
				.newInstance(connector, user);
		authenticator.authenticate(new StreamTaskListener(logger, Charset.defaultCharset()));

		final Session session = connector.getSession();

		session.setServerAliveInterval(serverAliveInterval);

		final Properties config = new Properties();
		config.put("StrictHostKeyChecking", "no");
		session.setConfig(config);
		session.connect(timeout);

		return session;
	}

	public static CredentialsSSHSite migrateToCredentials(CredentialsSSHSite site) throws InterruptedException,
			IOException {
		if (!(site instanceof LegacySSHSite)) {
			return site;
		}

		final LegacySSHSite legacy = (LegacySSHSite) site;

		final List<StandardUsernameCredentials> credentialsForDomain = CredentialsProvider.lookupCredentials(
				StandardUsernameCredentials.class, (Item) null, ACL.SYSTEM, new HostnamePortRequirement(site.hostname,
						site.port));
		final StandardUsernameCredentials existingCredentials = CredentialsMatchers.firstOrNull(credentialsForDomain,
				CredentialsMatchers.withUsername(legacy.username));

		final String credentialId;
		if (existingCredentials == null) {
			String createdCredentialId = UUID.randomUUID().toString();

			final StandardUsernameCredentials credentialsToCreate;
			if (!Strings.isNullOrEmpty(legacy.password)) {
				credentialsToCreate = new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, createdCredentialId,
						"migrated from previous ssh-plugin version", legacy.username, legacy.password);
			} else if (!Strings.isNullOrEmpty(legacy.keyfile)) {
				credentialsToCreate = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, createdCredentialId,
						legacy.username, new FileOnMasterPrivateKeySource(legacy.keyfile), null,
						"migrated from previous ssh-plugin version");
			} else {
				throw new InterruptedException(
						"Did not find password nor keyfile while migrating from non-credentials SSH configuration!");
			}

			final SystemCredentialsProvider credentialsProvider = SystemCredentialsProvider.getInstance();
			final Map<Domain, List<Credentials>> credentialsMap = credentialsProvider.getDomainCredentialsMap();

			final Domain domain = Domain.global();
			credentialsMap.put(domain, Collections.<Credentials> singletonList(credentialsToCreate));

			credentialsProvider.setDomainCredentialsMap(credentialsMap);
			credentialsProvider.save();

			credentialId = createdCredentialId;
		} else {
			credentialId = existingCredentials.getId();
		}

		return new CredentialsSSHSite(legacy.hostname, String.valueOf(legacy.port), credentialId,
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
					int i = executeCommand(logger, commands.nextToken().trim());
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
				status = executeCommand(logger, command);
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

	public String getSitename() {
		return username + "@" + hostname + ":" + port;
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

	public void testConnection(final PrintStream logger) throws JSchException, IOException, InterruptedException {
		final Session session = createSession(logger);
		closeSession(session, null);
	}

	@Override
	public String toString() {
		return "SSHSite [username=" + username + ", hostname=" + hostname + ",port=" + port + ", credentialId="
				+ credentialId + ", pty=" + pty + "]";
	}

}
