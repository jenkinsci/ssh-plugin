package org.jvnet.hudson.plugins;

import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import hudson.util.StreamTaskListener;
import hudson.util.ListBoxModel;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserListBoxModel;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.JSchConnector;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.jcraft.jsch.Session;

public class CredentialsUtil {

	private static class CredentialsUtilHolder {
		private static final CredentialsUtil INSTANCE = new CredentialsUtil();
	}

	public static Session createSession(final String hostName, final int port, final String credentialId,
			final PrintStream logger) throws IOException, InterruptedException {
		final List<StandardUsernameCredentials> all = CredentialsProvider.lookupCredentials(
				StandardUsernameCredentials.class, (Item) null, ACL.SYSTEM, NO_REQUIREMENTS);

		final StandardUsernameCredentials user = CredentialsMatchers.firstOrNull(all,
				CredentialsMatchers.withId(credentialId));
		final JSchConnector connector = new JSchConnector(user.getUsername(), hostName, port);

		final SSHAuthenticator<JSchConnector, StandardUsernameCredentials> authenticator = SSHAuthenticator
				.newInstance(connector, user);
		authenticator.authenticate(new StreamTaskListener(logger, Charset.defaultCharset()));

		return connector.getSession();
	}

	public static ListBoxModel credentialsFor(final ItemGroup<?> context) {
		if (!isAvailable()) {
			return EMPTY;
		}

		final List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
				StandardUsernameCredentials.class, context, ACL.SYSTEM, NO_REQUIREMENTS);

		return new SSHUserListBoxModel().withEmptySelection().withMatching(
				SSHAuthenticator.matcher(JSchConnector.class), credentials);

	}

	public static boolean isAvailable() {
		return CredentialsUtilHolder.INSTANCE.available;
	}

	private static final List<DomainRequirement> NO_REQUIREMENTS = Collections.<DomainRequirement> emptyList();

	private static final ListBoxModel EMPTY = new ListBoxModel();

	private final boolean available;

	public CredentialsUtil() {
		boolean tmpAvailable = false;
		try {
			final Class<?> authenticatorClass = Class
					.forName("com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator");

			final boolean firstStage = authenticatorClass != null;

			final boolean secondStage = authenticatorClass.getClassLoader().loadClass("com.jcraft.jsch.JSchException") != null;

			tmpAvailable = firstStage && secondStage;
		} catch (final ClassNotFoundException ignored) {
		}

		available = tmpAvailable;
	}
}
