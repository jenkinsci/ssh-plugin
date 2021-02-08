# ssh-plugin

This Plugin was derived from the very cool [SCP Plugin](https://plugins.jenkins.io/scp/).
You can use the SSH Plugin to run shell commands on a remote machine via ssh.

You can use the SSH Plugin to run shell commands on a remote machine via ssh.

------------------------------------------------------------------------

### Usage

First go to the global configuration page and add a SSH site.

![ssh global config](docs/images/ssh-global-cfg.png)

For your job select a configured site and enter the shell commands that
should be executed before and after the build.

![ssh job config](docs/images/ssh-job-cfg.png)

Log will look like this.

![ssh job log](docs/images/ssh-job-log.png)

------------------------------------------------------------------------

### Contribute

Fork and send a pull request (or create an issue on GitHub or in JIRA)

##### TODO

-   i18n
-   we also need a post deploy script (ask some of the core team how to
    do that)
-   investigate stop behavior of Hudson jobs using the ssh Plugin
-   use same sites as scp plugin (plugin dependencies???)
-   ...

------------------------------------------------------------------------

### Changelog

For recent versions, see [GitHub Releases](https://github.com/jenkinsci/ssh-plugin/releases)

For versions 2.6.1 and older, see [CHANGELOG.md](CHANGELOG.md)
