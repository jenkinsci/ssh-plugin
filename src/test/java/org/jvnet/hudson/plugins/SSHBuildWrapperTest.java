package org.jvnet.hudson.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;

import hudson.security.ACL;

public class SSHBuildWrapperTest {
	@Rule
	public JenkinsRule rule = new JenkinsRule();

	@LocalData
	@Test
	public void loadConfigWorksFromOlder23Version() throws Exception {
		SSHBuildWrapper.DescriptorImpl desc = new SSHBuildWrapper.DescriptorImpl();
		assertEquals(1, desc.getSites().length);

		CredentialsSSHSite site = desc.getSites()[0];
		assertNotNull(site.getCredentialId());
		assertEquals("hostname.pl", site.getHostname());
	}

	@LocalData
	@Test
	public void credentialsForSameSiteAreMerged() throws Exception {
		SSHBuildWrapper.DescriptorImpl desc = new SSHBuildWrapper.DescriptorImpl();
		assertEquals(2, desc.getSites().length);

		List<StandardCredentials> storedCreds = CredentialsProvider.lookupCredentials(StandardCredentials.class, rule.jenkins, ACL.SYSTEM, CredentialsSSHSite.NO_REQUIREMENTS);
		assertEquals(1, storedCreds.size());

		for (CredentialsSSHSite site : desc.getSites()) {
			assertNotNull(site.getCredentialId());
			assertEquals("22", site.getPort());

			assertTrue(site.getCredentialId().equals(storedCreds.get(0).getId()));
		}
	}

	@LocalData
	@Test
	public void loadAllThreePossibleSiteConfigs() throws Exception {
		SSHBuildWrapper.DescriptorImpl desc = new SSHBuildWrapper.DescriptorImpl();
		assertEquals(3, desc.getSites().length);

		List<StandardCredentials> storedCreds = CredentialsProvider.lookupCredentials(StandardCredentials.class, rule.jenkins, ACL.SYSTEM, CredentialsSSHSite.NO_REQUIREMENTS);
		assertEquals(3, storedCreds.size());

		Map<String, StandardCredentials> storedCredsById = new HashMap<String, StandardCredentials>();
		for (StandardCredentials cred : storedCreds) {
			storedCredsById.put(cred.getId(), cred);
		}

		for (CredentialsSSHSite site : desc.getSites()) {
			assertNotNull(site.getCredentialId());
			assertEquals("22", site.getPort());
			assertTrue(site.getHostname().endsWith("example.com"));

			assertTrue(storedCredsById.containsKey(site.getCredentialId()));

			Credentials credentials = storedCredsById.get(site.getCredentialId());
			assertNotNull(credentials);

			if (credentials instanceof UsernamePasswordCredentials) {
				UsernamePasswordCredentials userPassCreds = (UsernamePasswordCredentials) credentials;
				assertEquals("dog8code", userPassCreds.getPassword().getPlainText());
			}
		}
	}
}
