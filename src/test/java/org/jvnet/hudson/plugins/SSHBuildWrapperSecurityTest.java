package org.jvnet.hudson.plugins;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.security.csrf.DefaultCrumbIssuer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.htmlunit.util.NameValuePair;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

public class SSHBuildWrapperSecurityTest {
    private static final String BASE = "descriptorByName/org.jvnet.hudson.plugins.SSHBuildWrapper/";
    private static final String CREDENTIAL_ID = "security-test-credential";

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    @Before
    public void setUpSecurity() throws Exception {
        rule.jenkins.setSecurityRealm(rule.createDummySecurityRealm());
        rule.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .toEveryone()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin"));
        rule.jenkins.setCrumbIssuer(new DefaultCrumbIssuer(true));

        CredentialsStore store =
                CredentialsProvider.lookupStores(rule.jenkins).iterator().next();
        store.addCredentials(
                Domain.global(),
                new UsernamePasswordCredentialsImpl(
                        CredentialsScope.SYSTEM,
                        CREDENTIAL_ID,
                        "security test credential",
                        "security-test-user",
                        "dummy-local-password"));
    }

    @Test
    public void lowPrivilegePostCannotStartConnection() throws Exception {
        try (ServerSocket listener = listener()) {
            WebResponse response =
                    request(client("reader"), "loginCheck", HttpMethod.POST, true, connectionParameters(listener));

            assertNoConnection(listener);
            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    public void getCannotStartConnection() throws Exception {
        try (ServerSocket listener = listener()) {
            WebResponse response =
                    request(client("reader"), "loginCheck", HttpMethod.GET, false, connectionParameters(listener));

            assertNoConnection(listener);
            assertEquals(405, response.getStatusCode());
        }
    }

    @Test
    public void administratorGetCannotStartConnection() throws Exception {
        try (ServerSocket listener = listener()) {
            WebResponse response =
                    request(client("admin"), "loginCheck", HttpMethod.GET, false, connectionParameters(listener));

            assertNoConnection(listener);
            assertEquals(405, response.getStatusCode());
        }
    }

    @Test
    public void administratorPostStillStartsConnectionCheck() throws Exception {
        try (ServerSocket listener = listener()) {
            WebResponse response =
                    request(client("admin"), "loginCheck", HttpMethod.POST, true, connectionParameters(listener));

            try (Socket connection = listener.accept()) {
                assertTrue(connection.getInetAddress().isLoopbackAddress());
            }
            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    public void administratorPostWithoutCrumbCannotStartConnection() throws Exception {
        try (ServerSocket listener = listener()) {
            WebResponse response =
                    request(client("admin"), "loginCheck", HttpMethod.POST, false, connectionParameters(listener));

            assertNoConnection(listener);
            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    public void credentialChoicesRequireAdministrator() throws Exception {
        WebResponse readerResponse =
                request(client("reader"), "fillCredentialIdItems", HttpMethod.GET, false, List.of());
        assertEquals(403, readerResponse.getStatusCode());

        WebResponse adminResponse = request(client("admin"), "fillCredentialIdItems", HttpMethod.GET, false, List.of());
        assertEquals(200, adminResponse.getStatusCode());
        assertTrue(adminResponse.getContentAsString().contains(CREDENTIAL_ID));
    }

    private JenkinsRule.WebClient client(String user) {
        return rule.createWebClient().withBasicCredentials(user, user);
    }

    private WebResponse request(
            JenkinsRule.WebClient client,
            String endpoint,
            HttpMethod method,
            boolean addCrumb,
            List<NameValuePair> parameters)
            throws Exception {
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        client.getOptions().setRedirectEnabled(false);
        client.getOptions().setJavaScriptEnabled(false);

        WebRequest request = new WebRequest(new URL(rule.getURL(), BASE + endpoint), method);
        request.setRequestParameters(parameters);
        if (addCrumb) {
            Page crumbPage = client.getPage(new URL(rule.getURL(), "crumbIssuer/api/json"));
            JSONObject crumb = JSONObject.fromObject(crumbPage.getWebResponse().getContentAsString());
            request.setAdditionalHeader(crumb.getString("crumbRequestField"), crumb.getString("crumb"));
        }

        Page page = client.getPage(request);
        return page.getWebResponse();
    }

    private static ServerSocket listener() throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(250);
        return listener;
    }

    private static List<NameValuePair> connectionParameters(ServerSocket listener) {
        return List.of(
                new NameValuePair("hostname", InetAddress.getLoopbackAddress().getHostAddress()),
                new NameValuePair("port", Integer.toString(listener.getLocalPort())),
                new NameValuePair("credentialId", CREDENTIAL_ID),
                new NameValuePair("serverAliveInterval", "0"),
                new NameValuePair("timeout", "500"));
    }

    private static void assertNoConnection(ServerSocket listener) {
        assertThrows(SocketTimeoutException.class, listener::accept);
    }
}
