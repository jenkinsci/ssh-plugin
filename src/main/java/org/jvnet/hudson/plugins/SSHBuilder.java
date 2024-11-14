package org.jvnet.hudson.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class SSHBuilder extends Builder {

    private String siteName;
    private String command;
    private boolean execEachLine;
    private boolean hideCommand;

    @Deprecated
    public SSHBuilder(String siteName, String command) {
        this.siteName = siteName;
        this.command = command;
    }

    @DataBoundConstructor
    public SSHBuilder(String siteName, String command, boolean execEachLine) {
        this.siteName = siteName;
        this.command = command;
        this.execEachLine = execEachLine;
    }

    public String getSiteName() {
        return siteName;
    }

    public String getCommand() {
        return command;
    }

    public boolean getExecEachLine() {
        return execEachLine;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public void setExecEachLine(boolean execEachLine) {
        this.execEachLine = execEachLine;
    }

    @DataBoundSetter
    public void setHideCommand(boolean hideCommand) {
        this.hideCommand = hideCommand;
    }

    public boolean isHideCommand() {
        return hideCommand;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, IOException {
        CredentialsSSHSite site = getSite();
        if (site == null) {
            listener.getLogger().println("[SSH] No SSH site found - this is likely a configuration problem!");
            build.setResult(Result.UNSTABLE);
            return true;
        }

        // Get the build variables and make sure we substitute the current SSH Server host name
        site.setResolvedHostname(build.getEnvironment(listener).expand(site.getHostname()));

        Map<String, String> vars = new HashMap<String, String>();
        vars.putAll(build.getEnvironment(listener));
        vars.putAll(build.getBuildVariables());
        String runtime_cmd = VariableReplacerUtil.preludeWithEnvVars(command, vars);
        String scrubbed_cmd = VariableReplacerUtil.scrub(runtime_cmd, vars, build.getSensitiveBuildVariables());

        if (runtime_cmd != null && runtime_cmd.trim().length() > 0) {
            if (!hideCommand) {
                if (execEachLine) {
                    listener.getLogger().printf("[SSH] commands:%n%s%n", scrubbed_cmd);
                } else {
                    listener.getLogger().printf("[SSH] script:%n%s%n", scrubbed_cmd);
                }
            }
            listener.getLogger().printf("%n[SSH] executing...%n");
            return site.executeCommand(listener.getLogger(), runtime_cmd, execEachLine) == 0;
        }
        return true;
    }

    public CredentialsSSHSite getSite() {
        CredentialsSSHSite[] sites = SSHBuildWrapper.DESCRIPTOR.getSites();
        for (CredentialsSSHSite site : sites) {
            if (site.getSitename().equals(siteName)) {
                return site;
            }
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
        public Builder newInstance(StaplerRequest req, JSONObject formData)
                throws hudson.model.Descriptor.FormException {
            return req.bindJSON(clazz, formData);
        }

        public ListBoxModel doFillSiteNameItems() {
            ListBoxModel m = new ListBoxModel();
            for (CredentialsSSHSite site : SSHBuildWrapper.DESCRIPTOR.getSites()) {
                m.add(site.getSitename());
            }
            return m;
        }

        public FormValidation doCheckSiteName(@QueryParameter final String value) {
            if ((value == null) || (value.trim().isEmpty())) {
                return FormValidation.error("SSH Site not specified");
            }
            return FormValidation.ok();
        }
    }
}
